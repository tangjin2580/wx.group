package io.github.wxgroup.reborn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块级显示偏好（设置页里那些开关）+ 主题配置。
 *
 * 存储：模块 App 写 SharedPreferences + /data/local/tmp 镜像（和分组同一套通道），
 * 微信进程里的 Hook 读镜像 —— 所以微信侧**不用重启**也能看到偏好变化（轮询/通知兜底）。
 *
 * ── 向后兼容 ──
 * 老版本配置里没有主题字段，[fromJson] 一律用 optXxx 带默认值读取；
 * 解析出错（用户手改坏了 / 半截 JSON）时 [GROUP_COLORS] 里非法的色值会被丢弃，
 * 整体回退默认主题，**不会**把整个设置清空。
 */
data class WxSettings(
    /** 未读角标用纯红点（true）还是数字（false） */
    val badgeDot: Boolean = false,
    /** 主列表隐藏「没有成员在会话列表里」的空分组行 */
    val hideEmptyGroups: Boolean = false,
    /** 收纳组行紧凑模式（更小的内边距/字号） */
    val compactRows: Boolean = false,
    /** 组行副标题显示未读发送者（「A、B 和 C 发来消息」） */
    val showSenders: Boolean = false,

    // ── 外观与主题 ─────────────────────────────────────────────────────
    /** 全局主题 id（见 GroupTheme.Id）；空/未知值 → 默认「微信经典绿」 */
    val themeId: String = GroupTheme.Id.DEFAULT,
    /** 深浅色：system / light / dark（见 GroupTheme.Mode） */
    val darkMode: String = GroupTheme.Mode.DEFAULT,
    /**
     * 单组配色覆盖：groupId → "#AARRGGBB"。
     * ⚠️ 刻意**不放在 WxGroup 里** —— 分组数据（成员/顺序）要保持纯净，
     * 主题升级、导入导出、微信侧落盘都不应该动到它。
     */
    val groupColors: Map<String, Int> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("badgeDot", badgeDot)
        .put("hideEmptyGroups", hideEmptyGroups)
        .put("compactRows", compactRows)
        .put("showSenders", showSenders)
        .put("themeId", themeId)
        .put("darkMode", darkMode)
        .put("groupColors", JSONObject().also { o ->
            groupColors.forEach { (k, v) -> o.put(k, GroupTheme.colorHex(v)) }
        })

    companion object {
        fun fromJson(o: JSONObject): WxSettings = WxSettings(
            badgeDot = o.optBoolean("badgeDot", false),
            hideEmptyGroups = o.optBoolean("hideEmptyGroups", false),
            compactRows = o.optBoolean("compactRows", false),
            showSenders = o.optBoolean("showSenders", false),
            themeId = o.optString("themeId", GroupTheme.Id.DEFAULT)
                .takeIf { it.isNotBlank() } ?: GroupTheme.Id.DEFAULT,
            darkMode = normalizeMode(o.optString("darkMode", GroupTheme.Mode.DEFAULT)),
            groupColors = parseGroupColors(o.optJSONObject("groupColors"))
        )

        /** 未知/空值一律按「跟随系统」处理，避免写错值时主题彻底失效 */
        private fun normalizeMode(v: String): String = when (v) {
            GroupTheme.Mode.LIGHT, GroupTheme.Mode.DARK -> v
            else -> GroupTheme.Mode.SYSTEM
        }

        private fun parseGroupColors(o: JSONObject?): Map<String, Int> {
            if (o == null) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val raw = o.optString(k, "").takeIf { it.isNotBlank() } ?: continue
                val c = runCatching { android.graphics.Color.parseColor(raw) }.getOrNull() ?: continue
                out[k] = c
            }
            return out
        }

        val DEFAULT = WxSettings()
    }
}

/**
 * 一个分组：包含名称、置顶、免打扰、成员 username 集合。
 *
 * 存储有两处：
 *   - 模块 App 私有 SharedPreferences（本应用自己用）
 *   - 微信进程内的 /data/local/tmp 镜像 + 微信私有目录的
 *     files/chatgroup_groups.json（裸 JSON 数组）
 * 两种格式读取时都要认，写入时也保留裸数组格式，让两处数据保持一致。
 */
data class WxGroup(
    val id: String,
    var name: String,
    var pinned: Boolean = false,
    var muted: Boolean = false,
    val members: MutableSet<String> = mutableSetOf(),
    /**
     * 用户自定义的显示顺序（越小越靠前）。
     * 0 表示「还没手动排过」——那时回退到按创建时间排，见 ConversationGroupHook.sortedGroups()。
     * 用户拖动排序后，所有分组都会被写上显式的 order。
     */
    var order: Int = 0,
    /**
     * 分组图标（emoji）。空字符串 = 未显式指定，显示时按 id 哈希从 GroupTheme.ICONS 取默认。
     */
    var icon: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("pinned", pinned)
        put("muted", muted)
        put("order", order)
        put("icon", icon)
        put("members", JSONArray(members.toList()))
    }

    companion object {
        /**
         * 宽松解析：手写/历史数据里字段名可能不一样，缺字段也不能整条丢掉。
         *   id      ← id / groupId / gid
         *   name    ← name / title / groupName
         *   members ← members / usernames / list / conversations
         *   pinned  ← pinned / top / stick
         *   muted   ← muted / silent / mute
         */
        fun fromJson(o: JSONObject): WxGroup {
            val name = firstString(o, "name", "title", "groupName", "label").orEmpty()
            val rawId = firstString(o, "id", "groupId", "gid").orEmpty()
            val id = rawId.ifBlank { "grp_legacy_" + Integer.toHexString(name.hashCode()) }
            val members = mutableSetOf<String>()
            val arr = firstArray(o, "members", "usernames", "list", "conversations")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotBlank()) members.add(s)
                }
            }
            return WxGroup(
                id = id,
                name = name.ifBlank { "未命名分组" },
                pinned = firstBoolean(o, "pinned", "top", "stick"),
                muted = firstBoolean(o, "muted", "silent", "mute"),
                members = members,
                order = o.optInt("order", 0),
                icon = o.optString("icon", "")
            )
        }

        private fun firstString(o: JSONObject, vararg keys: String): String? {
            for (k in keys) {
                val v = o.optString(k, "")
                if (v.isNotBlank()) return v
            }
            return null
        }

        private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? {
            for (k in keys) o.optJSONArray(k)?.let { return it }
            return null
        }

        private fun firstBoolean(o: JSONObject, vararg keys: String): Boolean {
            for (k in keys) if (o.has(k)) return o.optBoolean(k, false)
            return false
        }
    }
}
