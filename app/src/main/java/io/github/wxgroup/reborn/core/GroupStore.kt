package io.github.wxgroup.reborn.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分组数据与签名的客户端封装。
 *
 * 跨进程通道：
 *   - 模块 App 写自己的 SharedPreferences，并把分组/偏好镜像到 /data/local/tmp（666）；
 *   - 微信进程读微信私有目录 files/chatgroup_groups.json 与该镜像，**整份取更新的那个**；
 *   - ContentProvider 只做兜底（多数 ROM 被 AppsFilter 拦截）；
 *   - 静态签名直接用模块 ClassLoader 读内置 res/raw/wechat_signatures.json，不经过 IPC。
 *
 * 每个写入方都写**完整的分组表**，所以合并只能「整份取新」，不能按 id 取并集 ——
 * 并集会让任意一侧删掉的分组被另一侧的旧文件带回来。
 *
 * 写文件一律走 [atomicWriteText]（能 rename 就原子写，目录不可写时退回覆盖写）。
 */
object GroupStore {

    const val MODULE_PKG = "io.github.wxgroup.reborn"
    private const val TAG = "WxGroupReborn"
    const val PREFS = "wxgroup"
    const val KEY_GROUPS = "groups"
    const val KEY_SIGNATURE = "signature"
    const val KEY_SETTINGS = "settings"
    /** 上次与镜像同步（App 写出或导入）时镜像的 stamp；没变就说明没有新的外部改动 */
    private const val KEY_MIRROR_STAMP = "mirror_stamp"

    /**
     * 跨进程可读缓存文件：/data/local/tmp/ 是 1777（world-writable）且 SELinux 对 untrusted_app 可读，
     * 微信进程能直接读这里。模块 App 把分组镜像到此文件，微信即可读取（绕过 AppsFilter 对 Provider /
     * createPackageContext 的封锁，以及 app_data_file 跨应用 SELinux 限制）。
     */
    private const val TMP_GROUPS = "/data/local/tmp/wxgroup_reborn_groups.json"

    /** 偏好设置的跨进程镜像（同 TMP_GROUPS 的通道，见 WxSettings 注释） */
    private const val TMP_SETTINGS = "/data/local/tmp/wxgroup_reborn_settings.json"

    /** 微信私有目录里的偏好落点（微信侧一般不写它，仅作兼容读取） */
    private val CHATGROUP_SETTINGS_FILES = listOf(
        "/data/user/0/com.tencent.mm/files/chatgroup_settings.json",
        "/data/data/com.tencent.mm/files/chatgroup_settings.json"
    )

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

    /** Provider 通道（模块 App → 微信进程的回退通道，也是「分组已变更」通知的 URI） */
    const val PROVIDER_URI = "content://io.github.wxgroup.reborn.groups"

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

    // ===== 显示偏好（模块 App 侧写，微信 Hook 侧读） =====

    fun getSettings(ctx: Context): WxSettings = try {
        val s = prefs(ctx).getString(KEY_SETTINGS, null)
        if (s.isNullOrBlank()) WxSettings.DEFAULT else WxSettings.fromJson(JSONObject(s))
    } catch (t: Throwable) {
        WxSettings.DEFAULT
    }

    /** 写偏好：SharedPreferences + 镜像 + 通知微信（微信侧缓存 2s TTL，很快生效） */
    fun putSettings(ctx: Context, s: WxSettings) {
        val text = s.toJson().toString()
        writePref(ctx, KEY_SETTINGS, text)
        try {
            if (atomicWriteText(java.io.File(TMP_SETTINGS), text)) makeWorldReadable(TMP_SETTINGS)
        } catch (t: Throwable) {
            Log.e(TAG, "镜像偏好失败: ${t.message}")
        }
        notifyGroupsChanged(ctx)
    }

    // ===== 微信 Hook 侧读偏好 =====

    private var settingsCacheText: String? = null
    private var settingsCacheAt = 0L

    /**
     * 微信进程读取偏好。带 2s TTL 缓存 —— 这个函数会被组行绑定高频调用，
     * 每次都 stat 两个文件太浪费；偏好本来也只在模块 App 里改，2s 延迟无感。
     */
    @Synchronized
    fun settingsXposed(): WxSettings {
        val now = android.os.SystemClock.uptimeMillis()
        if (settingsCacheText != null && now - settingsCacheAt < 2000L) {
            return parseSettings(settingsCacheText)
        }
        // 模块 App 只写镜像；微信私有目录那份一般不存在，存在就以更晚的为准（兼容手工放置）
        val tmp = java.io.File(TMP_SETTINGS).takeIf { it.exists() }
        val priv = firstExisting(CHATGROUP_SETTINGS_FILES)
        val text = when {
            tmp == null && priv == null -> null
            tmp == null -> priv?.readText()
            priv == null -> tmp.readText()
            tmp.lastModified() >= priv.lastModified() -> tmp.readText()
            else -> priv.readText()
        }
        settingsCacheText = text
        settingsCacheAt = now
        return parseSettings(text)
    }

    private fun parseSettings(text: String?): WxSettings = try {
        if (text.isNullOrBlank()) WxSettings.DEFAULT else WxSettings.fromJson(JSONObject(text))
    } catch (t: Throwable) {
        WxSettings.DEFAULT
    }

    // ===== 导入 / 导出（设置页的备份入口） =====

    /**
     * 导出分组为可移植 JSON（完整 WxGroup 字段，{@link #parseGroups} 能直接吃回来）。
     * 先并一次镜像，保证微信里刚做的改动不会漏。
     */
    fun exportGroupsJson(ctx: Context): String {
        importFromTmp(ctx)
        val groups = sortForDisplay(getGroups(ctx))
        val root = JSONObject()
            .put("app", MODULE_PKG)
            .put("exportedAt", System.currentTimeMillis())
            .put("groups", JSONArray().also { a -> groups.forEach { a.put(it.toJson()) } })
        return root.toString(2)
    }

    /** 从导出的 JSON 导入分组（整表替换）。返回导入数量；JSON 非法返回 -1 */
    fun importGroupsJson(ctx: Context, text: String): Int {
        val groups = try {
            val trimmed = text.trim()
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed).optJSONArray("groups") ?: return -1
            }
            (0 until arr.length()).mapNotNull { i ->
                try { WxGroup.fromJson(arr.getJSONObject(i)) } catch (t: Throwable) { null }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "导入分组 JSON 解析失败: ${t.message}")
            return -1
        }
        putGroups(ctx, groups)
        Log.i(TAG, "已导入 ${groups.size} 个分组")
        return groups.size
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

    /**
     * 所有「改分组」的统一入口：读当前列表 → 改 → 落盘（prefs + 镜像 + 通知微信）。
     * 以前五个写方法各自「读全文 → 改 JSON → 写回」，重复且容易漏掉镜像/通知。
     */
    private fun mutateGroups(ctx: Context, block: (MutableList<WxGroup>) -> Unit) {
        // 先并入微信侧的最新改动：App 页面开着期间微信里可能刚加过成员/建过分组
        importFromTmp(ctx)
        val list = parseGroups(prefs(ctx).getString(KEY_GROUPS, null)).toMutableList()
        block(list)
        val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        root.put("groups", arr)
        writePref(ctx, KEY_GROUPS, root.toString())
        mirrorGroupsToTmp(ctx)
    }

    /** 整体落盘（按传入顺序，配 sortForDisplay 用），写 SharedPreferences + 镜像各一次 */
    fun putGroups(ctx: Context, groups: List<WxGroup>) = mutateGroups(ctx) { list ->
        list.clear()
        list.addAll(groups)
    }

    fun removeGroup(ctx: Context, id: String) = mutateGroups(ctx) { list ->
        list.removeAll { it.id == id }
    }

    /** 按 id 原地修改一个分组（改名 / 置顶 / 免打扰），只动传入的字段，不会用旧对象覆盖成员 */
    fun updateGroup(ctx: Context, id: String, block: (WxGroup) -> Unit) = mutateGroups(ctx) { list ->
        list.firstOrNull { it.id == id }?.let(block)
    }

    /** 收纳组是文件夹语义：加入一个分组时从其它分组摘掉（和微信侧 addTalkerToGroup 一致） */
    fun addMember(ctx: Context, groupId: String, username: String) = mutateGroups(ctx) { list ->
        val target = list.firstOrNull { it.id == groupId } ?: return@mutateGroups
        list.forEach { if (it.id != groupId) it.members.remove(username) }
        target.members.add(username)
    }

    /** 新建分组（可选直接收入一个会话），一次落盘 */
    fun createGroup(ctx: Context, group: WxGroup, username: String?) = mutateGroups(ctx) { list ->
        if (!username.isNullOrBlank()) {
            list.forEach { it.members.remove(username) }
            group.members.add(username)
        }
        list.add(group)
    }

    /** 只按 id 写回顺序（拖动排序页用），不替换整表，避免把排序期间别处新建的分组冲掉 */
    fun applyOrder(ctx: Context, orderedIds: List<String>) = mutateGroups(ctx) { list ->
        val rank = orderedIds.withIndex().associate { (i, id) -> id to i + 1 }
        var next = orderedIds.size
        list.forEach { g -> g.order = rank[g.id] ?: ++next }
    }

    fun removeMember(ctx: Context, groupId: String, username: String) = mutateGroups(ctx) { list ->
        list.firstOrNull { it.id == groupId }?.members?.remove(username)
    }

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
    fun defaultSignatureJson(): String? = embeddedSignatureJson()

    // ===== Hook 侧（运行在微信进程） =====

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
     * 微信进程读取分组（动态数据）。**纯读**，不写任何文件 —— 这个函数在
     * `sortedGroups()` 里被调用，写文件会导致「读一次就改一次 stamp」，进而自激刷新。
     *
     * 来源：
     *   1. 微信私有目录 /data/user/0/com.tencent.mm/files/chatgroup_groups.json（同 uid，可直接读）
     *   2. 模块 App 镜像 /data/local/tmp/wxgroup_reborn_groups.json
     *   3. ContentProvider（AppsFilter 通常已封，仅兜底）
     *
     * 两份文件**整份取更晚修改的那个**（每个写入方都写完整表）。早期按 id 取并集，
     * 结果是任一侧删掉的分组都会被另一侧的旧文件带回来。
     */
    fun getGroupsXposed(context: Context?): List<WxGroup> =
        readGroupsXposed()
            ?: providerQuery(context, "groups")?.let { parseGroupsOrNull(it) }
            ?: emptyList()

    /** 两份文件里取最后修改的那份（整份），都没有可用内容时返回 null */
    private fun readGroupsXposed(): List<WxGroup>? {
        val privateFile = firstExisting(CHATGROUP_FILES)
        val tmpFile = java.io.File(TMP_GROUPS).takeIf { it.exists() }
        val priv = readGroupsFile(privateFile)
        val tmp = readGroupsFile(tmpFile)
        if (priv == null || privateFile == null) return tmp
        if (tmp == null || tmpFile == null) return priv
        return if (tmpFile.lastModified() >= privateFile.lastModified()) tmp else priv
    }

    /**
     * 把两边文件对齐（微信私有目录 ↔ /data/local/tmp 镜像）。
     * 只在明确需要时调用（启动一次、微信侧保存后），**不要**放进读路径。
     */
    fun reconcileXposed(): Int {
        val groups = readGroupsXposed() ?: return 0
        var changed = 0
        // ⚠️ 必须写完整字段（order/icon/pinned/muted）。早期版本只写 id/name/members，
        // 微信侧每次对齐都会把用户排好的顺序/图标剥掉 —— 一旦私有文件比镜像新，
        // 排序就神不知鬼不觉地回到默认（实测踩过）。
        val text = JSONArray().also { a -> groups.forEach { a.put(it.toJson()) } }.toString()
        val f = firstExisting(CHATGROUP_FILES)
        if (f != null && runCatching { f.readText() }.getOrNull() != text) {
            if (atomicWriteText(f, text)) changed++
        }
        val tmpText = JSONObject().put(
            "groups", JSONArray().also { a -> groups.forEach { a.put(it.toJson()) } }
        ).toString()
        val tmp = java.io.File(TMP_GROUPS)
        if (runCatching { tmp.readText() }.getOrNull() != tmpText) {
            if (atomicWriteText(tmp, tmpText)) {
                makeWorldReadable(TMP_GROUPS)
                changed++
            }
        }
        if (changed > 0) Log.i(TAG, "分组文件已对齐: $changed 处（${groups.size} 个分组）")
        return changed
    }

    /**
     * 模块 App 侧：用镜像整表替换本地分组。
     *
     * 镜像是两边共同可见、且每次都写完整表的那份，所以它就是当前真相：
     * 微信里删掉的分组必须在这里也消失，按 id 合并会把它留在本地，下次 App 一保存又写回去。
     * 镜像为空文件 / 非法 JSON（例如刚 touch 出来）时不导入，保留本地。
     */
    fun importFromTmp(ctx: Context) {
        try {
            val f = java.io.File(TMP_GROUPS)
            if (!f.exists()) return
            // 镜像自上次同步后没动过 → 本地才是最新的（例如上次写镜像失败），不能被旧镜像回滚
            val stamp = fileStamp(f)
            if (stamp == prefs(ctx).getLong(KEY_MIRROR_STAMP, Long.MIN_VALUE)) return
            val fromTmp = parseGroupsOrNull(f.readText()) ?: return
            prefs(ctx).edit().putLong(KEY_MIRROR_STAMP, stamp).apply()
            val local = getGroups(ctx)
            if (sameGroups(local, fromTmp)) return
            val root = JSONObject(prefs(ctx).getString(KEY_GROUPS, null) ?: "{}")
            root.put("groups", JSONArray().also { a -> fromTmp.forEach { a.put(it.toJson()) } })
            writePref(ctx, KEY_GROUPS, root.toString())
            Log.i(TAG, "已从镜像同步 ${fromTmp.size} 个分组")
        } catch (t: Throwable) {
            Log.e(TAG, "从镜像导入分组失败: ${t.message}")
        }
    }

    private fun sameGroups(a: List<WxGroup>, b: List<WxGroup>): Boolean {
        if (a.size != b.size) return false
        val byId = b.associateBy { it.id }
        return a.all { g -> byId[g.id]?.let { sameGroup(g, it) } == true }
    }

    /** 两个分组除 id 外是否完全一致（成员按集合比，忽略顺序） */
    private fun sameGroup(a: WxGroup, b: WxGroup): Boolean =
        a.name == b.name && a.icon == b.icon && a.pinned == b.pinned &&
            a.muted == b.muted && a.order == b.order && a.members == b.members

    private fun readGroupsFile(f: java.io.File?): List<WxGroup>? {
        if (f == null) return null
        return try {
            parseGroupsOrNull(f.readText())
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

    /**
     * 镜像文件的「变更戳」（最后修改时间 + 长度）。微信进程用它检测模块 App 是否改过分组，
     * 变了就主动 notifyDataSetChanged 刷新会话列表——否则用户改完分组要重启微信才看得到效果。
     */
    fun groupsStamp(): Long = try {
        var s = -1L
        for (p in listOf(TMP_GROUPS) + CHATGROUP_FILES) {
            val f = java.io.File(p)
            if (f.exists()) s = maxOf(s, fileStamp(f))
        }
        s
    } catch (t: Throwable) {
        -1L
    }

    /**
     * 在微信进程里直接落盘分组（微信私有目录那份 + 镜像）。
     * Hook 侧改分组（例如在微信界面里新建/收纳）时调用，写裸数组格式。
     */
    fun saveGroupsXposed(groups: List<WxGroup>): Boolean {
        val arr = JSONArray()
        groups.forEach { arr.put(it.toJson()) }
        val text = arr.toString()
        var ok = false
        for (p in CHATGROUP_FILES) {
            val f = java.io.File(p)
            if (f.parentFile?.exists() != true) continue
            if (atomicWriteText(f, text)) {
                ok = true
                break
            }
        }
        val tmp = java.io.File(TMP_GROUPS)
        if (atomicWriteText(tmp, JSONObject().put("groups", arr).toString())) {
            makeWorldReadable(TMP_GROUPS)
        } else {
            Log.e(TAG, "写 /data/local/tmp 镜像失败")
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

    private fun fileStamp(f: java.io.File): Long = f.lastModified() * 1_000_000L + f.length()

    /**
     * 微信侧：镜像刚被 root 创建出来还是空文件时，用微信私有目录那份填上。
     * 否则模块 App 首次保存会用它自己（可能很旧）的整表成为「最新」，盖掉微信里建的分组。
     */
    fun seedMirrorIfEmptyXposed() {
        val tmp = java.io.File(TMP_GROUPS)
        if (!tmp.exists() || readGroupsFile(tmp) != null) return
        if (readGroupsFile(firstExisting(CHATGROUP_FILES)) == null) return
        reconcileXposed()
    }

    /**
     * 跨进程镜像是否可写。模块 App 在 SELinux 下建不了 /data/local/tmp 里的新文件，
     * 只能覆盖已存在的 666 文件，所以首次需要 root 预创建（见 [provisionMirrorsWithRoot]）。
     */
    fun mirrorsReady(): Boolean =
        listOf(TMP_GROUPS, TMP_SETTINGS).all { java.io.File(it).let { f -> f.exists() && f.canWrite() } }

    /** 用 su 预创建镜像文件并设为 666。会阻塞（等待 root 授权），必须在后台线程调用 */
    fun provisionMirrorsWithRoot(): Boolean = try {
        val cmd = listOf(TMP_GROUPS, TMP_SETTINGS).joinToString(" && ") { "touch $it && chmod 666 $it" }
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.outputStream.close()
        p.waitFor() == 0
    } catch (t: Throwable) {
        Log.e(TAG, "root 创建镜像文件失败: ${t.message}")
        false
    }

    /** 把当前分组镜像到 /data/local/tmp，供微信进程读取（微信已验证可读该目录） */
    private fun mirrorGroupsToTmp(ctx: Context) {
        try {
            val json = prefs(ctx).getString(KEY_GROUPS, null)
                ?: JSONObject().put("groups", JSONArray()).toString()
            val f = java.io.File(TMP_GROUPS)
            if (atomicWriteText(f, json)) {
                makeWorldReadable(TMP_GROUPS)
                prefs(ctx).edit().putLong(KEY_MIRROR_STAMP, fileStamp(f)).apply()
                Log.i(TAG, "镜像分组到 /data/local/tmp 成功 (${json.length}字符)")
            } else {
                Log.e(TAG, "镜像分组到 /data/local/tmp 失败")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "镜像分组到 /data/local/tmp 失败: ${t.message}")
        }
        notifyGroupsChanged(ctx)
    }

    /**
     * 通知微信进程「分组变了」。
     * 微信侧注册了 ContentObserver（见 ConversationGroupHook.installProviderObserver），
     * 收到就立刻重建列表 —— 比 1.2s 轮询快，也省电。观察者没注册上也无害（轮询兜底）。
     */
    private fun notifyGroupsChanged(ctx: Context) {
        try {
            ctx.contentResolver.notifyChange(android.net.Uri.parse(PROVIDER_URI), null)
        } catch (t: Throwable) {
            Log.e(TAG, "通知分组变更失败: ${t.message}")
        }
    }

    /**
     * 原子写：先写同目录临时文件再 rename；失败退回直接覆盖写。
     *
     * ⚠️ 直接 writeText 时若进程被杀，会留下半截 JSON（真机踩过，分组整个丢）。
     * 但 tmp+rename 需要**目录**写权限 —— /data/local/tmp 是 drwxrwx--x shell shell，
     * 普通应用 uid（模块 App 和微信都是）建不了 .tmp（EACCES）。
     * 而镜像文件本身是 666，**覆盖写已有文件**完全可行 —— 所以 rename 失败时
     * 必须退回直写，否则模块 App 改分组/改设置根本到不了微信（实测踩过：排序保存后
     * 微信纹丝不动，日志里 "EACCES: .tmp open failed"）。
     */
    private fun atomicWriteText(f: java.io.File, text: String): Boolean {
        val parent = f.parentFile
        if (parent != null && parent.exists()) {
            val tmp = java.io.File(parent, f.name + ".tmp")
            try {
                tmp.writeText(text)
                if (tmp.renameTo(f)) return true
            } catch (_: Throwable) {
                // 目录不可写：走下面的直写兜底
            }
            try { tmp.delete() } catch (_: Throwable) {}
        }
        return try {
            f.writeText(text)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "写文件失败 ${f.path}: ${t.message}")
            false
        }
    }

    /**
     * 让 /data/local/tmp 镜像对其他 uid 可读（微信进程要读它）。
     *
     * 以前用 `Runtime.getRuntime().exec("chmod 666 …").waitFor()`：每次保存分组都 fork
     * 一个进程、还在 UI 线程 join —— 又慢又容易触发 ANR 的味道。改成直接 syscall。
     */
    private fun makeWorldReadable(path: String) {
        try {
            android.system.Os.chmod(path, 438 /* 0666 */)
            return
        } catch (t: Throwable) {
            // fallthrough
        }
        try {
            val f = java.io.File(path)
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (t: Throwable) {
            Log.e(TAG, "设置镜像权限失败: ${t.message}")
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
    private fun parseGroups(str: String?): List<WxGroup> = parseGroupsOrNull(str) ?: emptyList()

    /** 空串 / 非法 JSON 返回 null，用来区分「没有数据」和「合法的空分组表」 */
    private fun parseGroupsOrNull(str: String?): List<WxGroup>? {
        if (str.isNullOrBlank()) return null
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
            null
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
            out.add(legacyNameMap(name, arr))
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
