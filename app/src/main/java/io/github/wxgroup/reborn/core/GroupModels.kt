package io.github.wxgroup.reborn.core

import org.json.JSONArray
import org.json.JSONObject

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
