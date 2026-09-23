package io.github.wxgroup.reborn.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分组数据与签名的客户端封装。
 *
 * 跨进程读取方案（LSPosed 模块读取「模块 App」自身数据给「微信」进程用的标准做法）：
 *   - 模块 App（设置界面）把数据与签名写入自己的 SharedPreferences（文件位于
 *     /data/data/io.github.wxgroup.reborn/shared_prefs/wxgroup.xml）；
 *   - 微信进程里的 Hook 通过 LSPosed 提供的 XSharedPreferences(MODULE_PKG, "wxgroup")
 *     读取——LSPosed 的 lspd 守护进程以提升的 SELinux 上下文直接读该文件，
 *     不受 Android 11+ 的包可见性（AppsFilter）与 app_data_file 跨应用 SELinux 限制影响。
 *   - 静态签名另有「内置 raw 资源」兜底：模块类与资源会被 LSPosed 载入微信进程，
 *     直接用模块 ClassLoader 读 res/raw/wechat_signatures.json，完全不经过任何 IPC，
 *     保证 Hook 一定能在微信里挂上（即便跨进程读取临时异常）。
 */
object GroupStore {

    const val MODULE_PKG = "io.github.wxgroup.reborn"
    private const val TAG = "WxGroupReborn"
    const val PREFS = "wxgroup"
    const val KEY_GROUPS = "groups"
    const val KEY_SIGNATURE = "signature"

    /**
     * 跨进程可读缓存文件：/data/local/tmp/ 是 1777（world-writable）且 SELinux 对 untrusted_app 可读，
     * 微信进程能直接读这里。模块 App 把分组镜像到此文件，微信即可读取（绕过 AppsFilter 对 Provider /
     * createPackageContext 的封锁，以及 app_data_file 跨应用 SELinux 限制）。
     */
    private const val TMP_GROUPS = "/data/local/tmp/wxgroup_reborn_groups.json"

    /**
     * 微信私有目录里的分组落点。
     *
     * 模块的 Hook 跑在微信进程里，和微信同一个 uid，所以能直接读写微信私有目录下的
     * 这个文件：
     *     /data/user/0/com.tencent.mm/files/chatgroup_groups.json
     * 内容是一个裸 JSON 数组：[{"id":..,"name":..,"members":[..]}]
     *
     * 而模块 App 是另一个 uid，读不到微信私有目录，只能靠 /data/local/tmp 镜像同步。
     * 两个入口都保留：微信进程优先读这个文件，读不到再退回镜像。
     */
    private val CHATGROUP_FILES = listOf(
        "/data/user/0/com.tencent.mm/files/chatgroup_groups.json",
        "/data/data/com.tencent.mm/files/chatgroup_groups.json"
    )

    // ===== 模块 App 侧（运行在模块进程） =====

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writePref(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    /** 模块 App 首次启动确保默认签名与空分组已存在 */
    fun ensureDefaults(ctx: Context) {
        val p = prefs(ctx)
        // 内置签名带 revision，比本地保存的更新时自动覆盖。
        // 否则升级 res/raw/wechat_signatures.json（例如新增 RecyclerView 分支的签名）
        // 后，本地旧副本会一直生效，Hook 会静默失效。
        val embedded = embeddedSignatureJson()
        val stored = p.getString(KEY_SIGNATURE, null)
        if (embedded != null && (stored == null || signatureRevision(embedded) > signatureRevision(stored))) {
            writePref(ctx, KEY_SIGNATURE, embedded)
            Log.i(TAG, "内置签名 revision=${signatureRevision(embedded)} 覆盖本地副本")
        }
        if (!p.contains(KEY_GROUPS)) {
            writePref(ctx, KEY_GROUPS, JSONObject().put("groups", JSONArray()).toString())
        }
        // 微信进程写进镜像的分组（例如在微信界面里新建的）并回本地，两边列表保持一致。
        // ⚠️ 这里**不要**再 mirrorGroupsToTmp：镜像才是权威数据（微信进程在真机上随时改它），
        //    启动时用陈旧的本地副本回写镜像会把用户在微信里刚做的改动冲掉（实测丢过成员）。
        importFromTmp(ctx)
    }

    private fun signatureRevision(json: String): Int = try {
        JSONObject(json).optInt("revision", 0)
    } catch (t: Throwable) {
        0
    }

    // ===== 分组（模块 App 侧读写，全部以 SharedPreferences 为存储） =====

    fun getGroups(ctx: Context): List<WxGroup> = parseGroups(prefs(ctx).getString(KEY_GROUPS, null))

    /**
     * 分组显示顺序的唯一实现（模块 App 与微信 Hook 共用，避免两边排得不一样）。
     * 置顶优先 → 用户自定义 order → 创建时间（从 id 里取） → 名字。
     * order 为 0 表示「没手动排过」，此时用 id 里编码的毫秒时间戳兜底，
     * 所以没排过也是稳定的创建顺序；排过之后完全听用户的。
     */
    fun sortForDisplay(groups: List<WxGroup>): List<WxGroup> = groups.sortedWith(
        compareByDescending<WxGroup> { it.pinned }
            .thenBy { it.order }
            .thenBy { orderKey(it) }
            .thenBy { it.name }
    )

    /** 没手动排过顺序时的回退依据：id 里编码的创建时间，取不到给 MAX（排最后） */
    fun orderKey(g: WxGroup): Long {
        val id = g.id
        if (!id.startsWith("grp_")) return Long.MAX_VALUE
        val tail = id.substring(4)
        if (tail.startsWith("legacy")) return Long.MAX_VALUE
        return tail.toLongOrNull() ?: Long.MAX_VALUE
    }

    /** 整体落盘（按传入顺序，配 sortForDisplay 用），写 SharedPreferences + 镜像各一次 */
    fun putGroups(ctx: Context, groups: List<WxGroup>) {
        val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
        val arr = JSONArray()
        groups.forEach { arr.put(it.toJson()) }
        root.put("groups", arr)
        writePref(ctx, KEY_GROUPS, root.toString())
        mirrorGroupsToTmp(ctx)
    }

    fun putGroup(ctx: Context, group: WxGroup) {
        val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
        val arr = root.optJSONArray("groups") ?: JSONArray()
        val gj = group.toJson()
        var replaced = false
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id", "") == group.id) {
                arr.put(i, gj); replaced = true; break
            }
        }
        if (!replaced) arr.put(gj)
        root.put("groups", arr)
        writePref(ctx, KEY_GROUPS, root.toString())
        mirrorGroupsToTmp(ctx)
    }

    fun removeGroup(ctx: Context, id: String) {
        val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
        val arr = root.optJSONArray("groups") ?: return
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id", "") != id) out.put(arr.getJSONObject(i))
        }
        root.put("groups", out)
        writePref(ctx, KEY_GROUPS, root.toString())
        mirrorGroupsToTmp(ctx)
    }

    fun addMember(ctx: Context, groupId: String, username: String) {
        val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
        val arr = root.optJSONArray("groups") ?: return
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            if (g.optString("id", "") == groupId) {
                val members = g.optJSONArray("members") ?: JSONArray()
                var exists = false
                for (j in 0 until members.length()) {
                    if (members.getString(j) == username) { exists = true; break }
                }
                if (!exists) members.put(username)
                g.put("members", members)
                break
            }
        }
        root.put("groups", arr)
        writePref(ctx, KEY_GROUPS, root.toString())
        mirrorGroupsToTmp(ctx)
    }

    fun removeMember(ctx: Context, groupId: String, username: String) {
        val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
        val arr = root.optJSONArray("groups") ?: return
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            if (g.optString("id", "") == groupId) {
                val members = g.optJSONArray("members") ?: return
                val out = JSONArray()
                for (j in 0 until members.length()) {
                    if (members.getString(j) != username) out.put(members.getString(j))
                }
                g.put("members", out)
                break
            }
        }
        root.put("groups", arr)
        writePref(ctx, KEY_GROUPS, root.toString())
        mirrorGroupsToTmp(ctx)
    }

    /** 返回 username 所属的所有分组（一个会话可同时属于多个分组） */
    fun groupsOf(ctx: Context, username: String): List<WxGroup> =
        getGroups(ctx).filter { username in it.members }

    /** 分组列表（已按 置顶 > 名称 排序），供界面展示 */
    fun sortedGroups(ctx: Context): List<WxGroup> =
        getGroups(ctx).sortedWith(compareBy({ !it.pinned }, { it.name }))

    // ===== 签名（模块 App 侧） =====

    fun getSignatures(ctx: Context): WeChatSignatures? {
        val str = prefs(ctx).getString(KEY_SIGNATURE, null) ?: embeddedSignatureJson()
        return str?.let { try { WeChatSignatures.fromJson(JSONObject(it)) } catch (t: Throwable) { null } }
    }

    fun saveSignature(ctx: Context, json: String): Boolean {
        return try { JSONObject(json); writePref(ctx, KEY_SIGNATURE, json); true }
        catch (t: Throwable) { false }
    }

    fun resetSignature(ctx: Context): Boolean {
        val def = embeddedSignatureJson() ?: return false
        return try { JSONObject(def); writePref(ctx, KEY_SIGNATURE, def); true }
        catch (t: Throwable) { false }
    }

    /** 读取默认签名 JSON 文本（来自模块 APK 的 raw 资源），供 UI 展示 */
    fun defaultSignatureJson(ctx: Context? = null): String? = embeddedSignatureJson()

    // ===== Hook 侧（运行在微信进程） =====

    private const val PROVIDER_URI = "content://io.github.wxgroup.reborn.groups"

    /**
     * 微信进程读取签名：优先走 ContentProvider（可反映用户在 App 里的修改），
     * 失败则回退到模块内置 raw 资源（一定可用，不依赖任何跨进程通道）。
     */
    fun getSignaturesXposed(context: Context): WeChatSignatures? {
        // 内置签名（res/raw）跟随 APK 版本走，永远是最新的；provider 里可能还是用户
        // 本地保存/上一次装的旧副本。谁 revision 高用谁。
        val embedded = embeddedSignatureJson()
        val fromProvider = providerQuery(context, "signature")
        val str = when {
            fromProvider.isNullOrBlank() -> embedded
            embedded == null -> fromProvider
            signatureRevision(embedded) > signatureRevision(fromProvider) -> embedded
            else -> fromProvider
        }
        return str?.let { try { WeChatSignatures.fromJson(JSONObject(it)) } catch (t: Throwable) { null } }
    }

    /**
     * 微信进程读取分组（动态数据）。**两个来源取并集**，同 id 以模块镜像为准：
     *   1. 微信私有目录 /data/user/0/com.tencent.mm/files/chatgroup_groups.json（同 uid，可直接读）
     *   2. 模块 App 镜像 /data/local/tmp/wxgroup_reborn_groups.json
     *   3. ContentProvider（AppsFilter 通常已封，仅兜底）
     * 取并集而不是「谁新用谁」：微信私有目录里已有的分组不能丢，
     * 而用户在模块 App 里新建的分组同样要生效。
     */
    fun getGroupsXposed(context: Context?): List<WxGroup> {
        val out = LinkedHashMap<String, WxGroup>()
        readGroupsFile(firstExisting(CHATGROUP_FILES))?.forEach { out[it.id] = it }
        readGroupsFile(java.io.File(TMP_GROUPS).takeIf { it.exists() })?.forEach { out[it.id] = it }
        if (out.isEmpty()) {
            providerQuery(context, "groups")?.let { parseGroups(it) }?.forEach { out[it.id] = it }
        }
        val merged = out.values.toList()
        // 把合并结果写回镜像：微信私有目录里的分组，模块 App 也能看到（App 读不了微信私有目录）
        syncBackToTmp(merged)
        // 再把结果按裸数组格式回写到微信私有目录那份文件，保持两边一致
        syncBackToOldFile(merged)
        return merged
    }

    /**
     * 按裸数组格式回写微信私有目录的 files/chatgroup_groups.json（只写 id/name/members）。
     * 只做同步、不抢主人：文件不存在就不创建。写前比内容，避免「我写→文件变了→我刷新→我再写」自激。
     */
    private fun syncBackToOldFile(groups: List<WxGroup>) {
        val f = firstExisting(CHATGROUP_FILES) ?: return
        try {
            val arr = JSONArray()
            for (g in groups) {
                arr.put(
                    JSONObject()
                        .put("id", g.id)
                        .put("name", g.name)
                        .put("members", JSONArray(g.members.toList()))
                )
            }
            val text = arr.toString()
            if (f.readText() == text) return
            f.writeText(text)
            Log.i(TAG, "已按裸数组格式回写分组: ${f.path} (${groups.size} 个)")
        } catch (t: Throwable) {
            Log.e(TAG, "回写分组文件失败: ${t.message}")
        }
    }

    /** 把分组列表写进 /data/local/tmp 镜像（模块 App 与微信进程的共同可见区） */
    private fun syncBackToTmp(groups: List<WxGroup>) {
        try {
            val f = java.io.File(TMP_GROUPS)
            val arr = JSONArray()
            groups.forEach { arr.put(it.toJson()) }
            val text = JSONObject().put("groups", arr).toString()
            if (f.exists() && f.readText() == text) return
            f.writeText(text)
        } catch (t: Throwable) {
            Log.e(TAG, "同步镜像失败: ${t.message}")
        }
    }

    /**
     * 模块 App 侧：把镜像里（微信进程写入的）分组并回本地存储。
     * 模块 App 读不到微信私有目录，所以微信进程会把微信侧建的分组同步进
     * /data/local/tmp 镜像，App 启动时在这里并回来 —— 两边的分组列表就一致了。
     *
     * 同 id 时**以镜像为准**（覆盖本地）：镜像由微信进程写，是会话列表真正在用的那份数据；
     * 只按 id 判存在、保留本地旧值的话，App 一启动就会用旧成员覆盖镜像 → 丢数据。
     */
    fun importFromTmp(ctx: Context) {
        try {
            val f = java.io.File(TMP_GROUPS)
            if (!f.exists()) return
            val fromTmp = parseGroups(f.readText())
            if (fromTmp.isEmpty()) return
            val byId = LinkedHashMap<String, WxGroup>()
            val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
            val arr = root.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val g = parseGroups(JSONArray().put(arr.getJSONObject(i)).toString()).firstOrNull() ?: continue
                byId[g.id] = g
            }
            var changed = false
            for (g in fromTmp) {
                val old = byId[g.id]
                if (old == null || old.members.toSet() != g.members.toSet() || old.name != g.name) changed = true
                byId[g.id] = g // 镜像优先
            }
            if (!changed) return
            val out = JSONArray()
            byId.values.forEach { out.put(it.toJson()) }
            root.put("groups", out)
            writePref(ctx, KEY_GROUPS, root.toString())
            Log.i(TAG, "从镜像并回 ${fromTmp.size} 个分组")
        } catch (t: Throwable) {
            Log.e(TAG, "从镜像导入分组失败: ${t.message}")
        }
    }

    private fun readGroupsFile(f: java.io.File?): List<WxGroup>? {
        if (f == null) return null
        return try {
            parseGroups(f.readText())
        } catch (t: Throwable) {
            Log.e(TAG, "读分组文件失败 ${f.path}: ${t.message}")
            null
        }
    }

    private fun firstExisting(paths: List<String>): java.io.File? {
        for (p in paths) {
            val f = java.io.File(p)
            if (f.exists()) return f
        }
        return null
    }

    /** 微信进程查询某 username 所属分组（带调用方短缓存由 Hook 负责） */
    fun groupsOfXposed(context: Context?, username: String): List<WxGroup> =
        getGroupsXposed(context).filter { username in it.members }

    /**
     * 镜像文件的「变更戳」（最后修改时间 + 长度）。微信进程用它检测模块 App 是否改过分组，
     * 变了就主动 notifyDataSetChanged 刷新会话列表——否则用户改完分组要重启微信才看得到效果。
     */
    fun groupsStamp(): Long = try {
        var s = -1L
        for (p in listOf(TMP_GROUPS) + CHATGROUP_FILES) {
            val f = java.io.File(p)
            if (f.exists()) s = maxOf(s, f.lastModified() * 1_000_000L + f.length())
        }
        s
    } catch (t: Throwable) {
        -1L
    }

    /**
     * 在微信进程里直接落盘分组（微信私有目录那份）。
     * Hook 侧改分组（例如在微信界面里新建/收纳）时调用，写裸数组格式 + 刷新镜像。
     */
    fun saveGroupsXposed(groups: List<WxGroup>): Boolean {
        val arr = JSONArray()
        groups.forEach { arr.put(it.toJson()) }
        val text = arr.toString()
        var ok = false
        for (p in CHATGROUP_FILES) {
            try {
                val f = java.io.File(p)
                f.parentFile?.takeIf { it.exists() }?.let { f.writeText(text); ok = true }
                if (ok) break
            } catch (t: Throwable) {
                Log.e(TAG, "写分组文件失败 $p: ${t.message}")
            }
        }
        try {
            java.io.File(TMP_GROUPS).writeText(JSONObject().put("groups", arr).toString())
        } catch (t: Throwable) {
            Log.e(TAG, "写 /data/local/tmp 镜像失败: ${t.message}")
        }
        return ok
    }

    /** 通过 ContentProvider（query 通道）向模块 App 进程索取数据（作为 /data/local/tmp 的回退） */
    private fun providerQuery(context: Context?, segment: String): String? {
        if (context == null) return null
        return try {
            val uri = android.net.Uri.parse("$PROVIDER_URI/$segment")
            context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }.also {
                Log.i(TAG, "Provider $segment -> ${if (it.isNullOrBlank()) "空" else "成功(${it.length}字符)"}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Provider $segment 失败: ${t.message}")
            null
        }
    }

    /** 把当前分组镜像到 /data/local/tmp，供微信进程读取（微信已验证可读该目录） */
    private fun mirrorGroupsToTmp(ctx: Context) {
        try {
            val json = prefs(ctx).getString(KEY_GROUPS, null)
                ?: JSONObject().put("groups", JSONArray()).toString()
            val f = java.io.File(TMP_GROUPS)
            f.writeText(json)
            Runtime.getRuntime().exec("chmod 666 $TMP_GROUPS").waitFor()
            Log.i(TAG, "镜像分组到 /data/local/tmp 成功 (${json.length}字符)")
        } catch (t: Throwable) {
            Log.e(TAG, "镜像分组到 /data/local/tmp 失败: ${t.message}")
        }
    }

    // ===== 内置资源（任何进程均可直接读，无需 IPC） =====

    fun embeddedSignatureJson(): String? = try {
        GroupStore::class.java.classLoader
            ?.getResourceAsStream("res/raw/wechat_signatures.json")
            ?.bufferedReader()
            ?.use { it.readText() }
    } catch (t: Throwable) {
        Log.e(TAG, "读内置签名资源失败: ${t.message}")
        null
    }

    // ===== 解析辅助 =====

    /**
     * 兼容三种格式：
     *   1. 本模块：{"groups":[{id,name,pinned,muted,members:[...]}]}
     *   2. 微信私有目录（chatgroup_groups.json）：裸数组 [{id,name,members:[...]}]  ← 主要读写目标
     *   3. 更早/手写：{"医保":["xxx@chatroom", ...], "客户":[...]}（名称 → 成员数组）
     */
    private fun parseGroups(str: String?): List<WxGroup> {
        if (str.isNullOrBlank()) return emptyList()
        val trimmed = str.trim()
        return try {
            if (trimmed.startsWith("[")) {
                arrayToGroups(JSONArray(trimmed))
            } else {
                val root = JSONObject(trimmed)
                val arr = root.optJSONArray("groups")
                if (arr != null) arrayToGroups(arr) else nameMapToGroups(root)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "分组 JSON 解析失败: ${t.message}")
            emptyList()
        }
    }

    private fun arrayToGroups(arr: JSONArray): List<WxGroup> =
        (0 until arr.length()).mapNotNull {
            try {
                arr.getJSONObject(it).let { o -> WxGroup.fromJson(o) }
            } catch (t: Throwable) {
                try {
                    // 极端情况：数组里直接放的是 username 字符串
                    val s = arr.optString(it, "")
                    if (s.isNotBlank()) legacyNameMap("默认分组", JSONArray().put(s)) else null
                } catch (t2: Throwable) {
                    null
                }
            }
        }

    private fun nameMapToGroups(root: JSONObject): List<WxGroup> {
        val out = ArrayList<WxGroup>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val arr = root.optJSONArray(name) ?: continue
            legacyNameMap(name, arr)?.let { out.add(it) }
        }
        return out
    }

    private fun legacyNameMap(name: String, arr: JSONArray): WxGroup {
        val members = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) members.add(s)
        }
        return WxGroup(
            id = "grp_legacy_" + Integer.toHexString(name.hashCode()),
            name = name,
            members = members
        )
    }
}
