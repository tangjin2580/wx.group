package io.github.wxgroup.reborn.ui.state

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import io.github.wxgroup.reborn.core.GroupNameRules
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.RunLog
import io.github.wxgroup.reborn.core.SignatureAnalyzer
import io.github.wxgroup.reborn.core.WxGroup
import io.github.wxgroup.reborn.core.WxSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/* ══════════════════════════════════════════════════════════════════════════════
 * UI 状态层 —— 真实数据源。
 *
 * 全部读写走 GroupStore（SharedPreferences + /data/local/tmp 镜像 + 通知微信），
 * 界面里看到的、改到的就是微信 Hook 端正在用的那份配置，不再是假数据。
 *
 * 数据量小（几条分组 / 几个开关），与旧版 XML 界面一致直接主线程读写，
 * 不引入异步复杂度。
 * ══════════════════════════════════════════════════════════════════════════════ */

/** 分组的 UI 模型（与 core.WxGroup 一一对应，只是把成员数量也带上方便展示） */
data class GroupUi(
    val id: String,
    val name: String,
    val icon: String,          // 单字图标（空则按 id 从主题默认符号表取）
    val iconTint: Long,        // 图标底块颜色（ARGB）
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val memberCount: Int = 0,
    val unread: Int = 0        // App 侧拿不到微信进程的未读数，恒为 0（列表里由 hook 填）
)

/** 删除动作的快照：仅为了让「撤销」能把分组原样放回（含位置与自定义色） */
data class RemovedGroup(
    val group: WxGroup,
    val index: Int,
    val color: Int?
)

/** 签名配置状态：已匹配 / 未配置 / 错误 */
enum class SignatureStatus { MATCHED, MISSING, ERROR }

/**
 * 首页：模块运行状态的**事实来源**（不含任何展示决定）。
 *
 * ── 三件事必须分开说 ──
 * 用户需要的不是一个「能不能用」的大绿勾，而是知道卡在哪一环：
 *   [appInstalled] / [appVersion]  模块 App 自己装好了没（几乎恒为 true，但它是链路第 0 环）
 *   [channelReady]                 通信通道可用吗（/data/local/tmp 跨进程镜像可读可写）
 *   [hookActive] / [hookLastRunAt] LSPosed 里真的勾了作用域、并且微信进程跑过 Hook 吗
 * 前两项成立而第三项不成立时，App 看起来「一切正常」但微信里毫无变化 ——
 * 这是最高频的误会，所以必须拆开展示（以前三者合并成一个 moduleActive 布尔值）。
 *
 * 至于「当前算不算好」，由界面按这几个字段自己判定展示（见 HomeScreen 的 overallLevel）。
 */
data class HomeUiState(
    val appInstalled: Boolean = true,
    val appVersion: String = "",
    /** 跨进程镜像（/data/local/tmp）是否可读写 —— App 的改动能否到达微信 */
    val channelReady: Boolean = false,
    /** 微信进程最近一次跑过 Hook 的时间戳（0 = 从未跑过，即 LSPosed 未生效） */
    val hookLastRunAt: Long = 0L,
    val wechatInstalled: Boolean = false,
    val wechatVersion: String = "",
    val signatureStatus: SignatureStatus = SignatureStatus.MISSING,
    val signatureSummary: String = "尚未获得有效方法签名",
    val guideExpanded: Boolean = false,
    val guideText: String = ""
) {
    /** 微信进程是否真的加载过模块（近 30 天内跑过算生效） */
    val hookActive: Boolean
        get() = hookLastRunAt > 0 &&
            System.currentTimeMillis() - hookLastRunAt < HOOK_ACTIVE_WINDOW_MS
}

/** 「Hook 最近跑过」的时间窗：超过这个时长认为用户已经不再用它（或模块被停用） */
private const val HOOK_ACTIVE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

/**
 * 分组列表的整屏状态。
 *
 * 读一次磁盘理论上也可能失败（JSON 损坏 / 权限异常），以前这种情况会静默显示空白列表，
 * 用户完全不知道是「真没有分组」还是「读不出来」。现在把加载中 / 异常 / 空列表分开表达。
 */
data class GroupsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val groups: List<GroupUi> = emptyList()
)

/** 设置页整屏需要的状态（显示偏好开关 + 外观 + 关于） */
data class SettingsUiState(
    val themeId: String = GroupTheme.Id.DEFAULT,
    val darkMode: String = GroupTheme.Mode.SYSTEM,
    /** 单组自定义色（groupId → ARGB）；没有记录的分组跟随主题轮转 */
    val groupColors: Map<String, Int> = emptyMap(),
    val badgeDot: Boolean = false,
    val hideEmptyGroups: Boolean = false,
    val compactRows: Boolean = false,
    val showSenders: Boolean = false
)

/** 三个底部 Tab */
enum class Tab(val label: String) { HOME("首页"), GROUPS("分组"), SETTINGS("设置") }

/**
 * 界面状态容器：GroupStore 的 UI 门面。
 *
 * 所有写操作「先落盘、后刷新 StateFlow」，保证界面显示的永远与存储一致。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app

    private val _home = MutableStateFlow(HomeUiState(guideText = GUIDE_TEXT))
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _groups = MutableStateFlow(GroupsUiState())
    val groups: StateFlow<GroupsUiState> = _groups.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    /** 最近一次删除的快照（用于「撤销」）；撤销后清空，避免重复恢复同一条 */
    private var lastRemoved: RemovedGroup? = null

    init {
        refreshAll()
    }

    /** 全量刷新：进页面 / 每次写操作后调用 */
    fun refreshAll() {
        refreshHome()
        refreshGroups()
        refreshSettings()
    }

    // ══ 首页状态：通道 + 微信版本 + 签名 ══

    private fun refreshHome() {
        // 看「当前真正生效的那份」而不是本地手改的副本：分析器产物按版本缓存，
        // 只要版本匹配就优先于内置签名（微信进程里也是同一份，见 GroupStore）。
        val sig = GroupStore.effectiveSignatures(ctx)
        val validCount = sig?.methods?.values?.count { it.isValid } ?: 0
        val status = when {
            validCount > 0 -> SignatureStatus.MATCHED
            sig != null -> SignatureStatus.MISSING
            else -> SignatureStatus.ERROR
        }
        val summary = if (validCount > 0) {
            val source = when {
                // 分析器产物的 revision 打在百万位上，一眼可辨
                sig?.revision ?: 0 >= SignatureAnalyzer.ANALYZED_REVISION_BASE -> "分析器"
                GroupStore.bundledSignatureVersion() == sig?.wechatVersion -> "内置"
                else -> "自定义"
            }
            "$source · ${sig?.wechatVersion} · $validCount 项有效"
        } else {
            "尚未获得有效方法签名"
        }
        val wxVersion = GroupStore.installedWechatVersion(ctx)
        _home.value = HomeUiState(
            appVersion = appVersion(),
            channelReady = GroupStore.mirrorsReady(),
            // Hook 侧每 800ms 去抖落盘一次运行日志；能读到 savedAt 就说明
            // 微信进程确实加载过本模块（LSPosed 已激活且作用域勾了微信）
            hookLastRunAt = RunLog.readMirror()?.savedAt ?: 0L,
            wechatInstalled = wxVersion != null,
            wechatVersion = wxVersion ?: "未安装",
            signatureStatus = status,
            signatureSummary = summary,
            guideExpanded = _home.value.guideExpanded,
            guideText = GUIDE_TEXT
        )
    }

    /** 模块 App 自己的版本号（状态卡上要明确「装的是哪一版」） */
    private fun appVersion(): String = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${p.versionName} (${p.versionCode})"
    } catch (t: Throwable) {
        "未知"
    }

    // ══ 分组 ══

    private fun refreshGroups() {
        val list = try {
            val s = GroupStore.getSettings(ctx)
            GroupStore.getGroups(ctx).mapIndexed { i, g ->
                GroupUi(
                    id = g.id,
                    name = g.name,
                    icon = g.icon.ifBlank { GroupTheme.iconOf(g.id) },
                    iconTint = (s.groupColors[g.id] ?: GroupTheme.colorForGroup(g.id, i, s))
                        .toLong() and 0xFFFFFFFFL,
                    pinned = g.pinned,
                    muted = g.muted,
                    memberCount = g.members.size
                )
            }
        } catch (t: Throwable) {
            // 读不出来必须说出来：JSON 损坏 / 权限异常时给出可见的错误态，
            // 而不是让界面显示一个空列表假装「没有分组」
            Log.e(TAG, "读取分组失败", t)
            _groups.value = GroupsUiState(loading = false, error = t.message ?: "读取分组失败")
            return
        }
        _groups.value = GroupsUiState(loading = false, error = null, groups = list)
    }

    /**
     * 新建分组。
     *
     * @param rawName 用户在输入框里填的真实名称（未 trim）。
     * @return 落盘后的分组名；不合法（空 / 过长）时返回 null，**不写任何数据**。
     */
    fun addGroup(rawName: String): String? {
        if (!GroupNameRules.isValid(rawName)) return null
        val name = GroupNameRules.normalize(rawName)
        val id = "grp_${System.currentTimeMillis()}"
        GroupStore.createGroup(ctx, WxGroup(id = id, name = name, icon = GroupTheme.iconOf(id)), null)
        refreshGroups()
        return name
    }

    /**
     * 重命名。
     *
     * @return 新名字；名称不合法时返回 null，**原分组不动**（以前是静默 return，
     *         调用方以为改成功了，实际列表纹丝不动）。
     */
    fun renameGroup(id: String, rawName: String): String? {
        if (!GroupNameRules.isValid(rawName)) return null
        val name = GroupNameRules.normalize(rawName)
        GroupStore.updateGroup(ctx, id) { it.name = name }
        refreshGroups()
        return name
    }

    fun togglePin(id: String) {
        GroupStore.updateGroup(ctx, id) { it.pinned = !it.pinned }
        refreshGroups()
    }

    fun toggleMute(id: String) {
        GroupStore.updateGroup(ctx, id) { it.muted = !it.muted }
        refreshGroups()
    }

    /**
     * 删除分组。
     *
     * 数据影响（已核实 [GroupStore.removeGroup] 的实现）：只把这条 [WxGroup] 从列表里摘掉，
     * 丢的是**成员归属关系**（members 里记的是会话 username，会话本身和聊天记录不受影响，
     * 只是从收纳组回到微信主列表）。
     *
     * 另外顺手清掉 `WxSettings.groupColors` 里该分组的自定义色 —— 否则分组删了、
     * 配色映射还留着，键越攒越多。
     *
     * @return 被删掉的分组快照；调用方据此提供「撤销」，出错或分组不存在时返回 null。
     */
    fun removeGroup(id: String): RemovedGroup? {
        val before = GroupStore.getGroups(ctx)
        val index = before.indexOfFirst { it.id == id }
        if (index < 0) return null
        val snapshot = before[index]
        val color = GroupStore.getSettings(ctx).groupColors[id]

        GroupStore.removeGroup(ctx, id)
        val s = GroupStore.getSettings(ctx)
        if (s.groupColors.containsKey(id)) {
            GroupStore.putSettings(ctx, s.copy(groupColors = s.groupColors - id))
        }
        refreshGroups()
        lastRemoved = RemovedGroup(group = snapshot, index = index, color = color)
        return lastRemoved
    }

    /**
     * 撤销上一次删除：把快照原样插回原位置，自定义色也还原。
     *
     * @return 是否真的恢复了（快照存在且列表里确认没有同 id 分组）
     */
    fun undoRemove(): Boolean {
        val snap = lastRemoved ?: return false
        val list = GroupStore.getGroups(ctx).toMutableList()
        if (list.any { it.id == snap.group.id }) return false
        list.add(snap.index.coerceIn(0, list.size), snap.group)
        // 显式写回 order，保证顺序和用户删掉之前一致
        list.forEachIndexed { i, g -> g.order = i + 1 }
        GroupStore.putGroups(ctx, list)
        if (snap.color != null) {
            val s = GroupStore.getSettings(ctx)
            GroupStore.putSettings(ctx, s.copy(groupColors = s.groupColors + (snap.group.id to snap.color)))
        }
        lastRemoved = null
        refreshGroups()
        return true
    }

    /**
     * 单组配色：给某个分组指定强调色。
     *
     * @param color null = 跟随主题（从 groupColors 里摘掉该键）
     */
    fun setGroupColor(id: String, color: Int?) {
        val s = GroupStore.getSettings(ctx)
        val next = if (color == null) s.groupColors - id else s.groupColors + (id to color)
        if (next == s.groupColors) return
        GroupStore.putSettings(ctx, s.copy(groupColors = next))
        refreshSettings()
        refreshGroups()
    }

    /** 拖动排序：按 UI 顺序给全部分组写显式 order（从 1 递增）。
     * 微信端 [GroupStore.sortForDisplay] 会按 pinned → order → 创建时间排序。
     */
    fun moveGroup(from: Int, to: Int) {
        val list = GroupStore.getGroups(ctx).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        list.forEachIndexed { i, g -> g.order = i + 1 }
        GroupStore.putGroups(ctx, list)
        refreshGroups()
    }

    // ══ 显示偏好 / 外观 ══

    fun setBadgeDot(v: Boolean) = putSetting { it.copy(badgeDot = v) }
    fun setHideEmptyGroups(v: Boolean) = putSetting { it.copy(hideEmptyGroups = v) }
    fun setCompactRows(v: Boolean) = putSetting { it.copy(compactRows = v) }
    fun setShowSenders(v: Boolean) = putSetting { it.copy(showSenders = v) }
    fun setTheme(id: String) = putSetting { it.copy(themeId = id) }
    fun setDarkMode(mode: String) = putSetting { it.copy(darkMode = mode) }

    /** 修改设置的统一入口：落盘 → 刷新（putSettings 会镜像 + 通知微信，无需重启生效） */
    private fun putSetting(block: (WxSettings) -> WxSettings) {
        val cur = GroupStore.getSettings(ctx)
        GroupStore.putSettings(ctx, block(cur))
        refreshSettings()
        // 主题色变了会影响分组行的图标底块颜色
        refreshGroups()
    }

    private fun refreshSettings() {
        val s = GroupStore.getSettings(ctx)
        _settings.value = SettingsUiState(
            themeId = s.themeId,
            darkMode = s.darkMode,
            groupColors = s.groupColors,
            badgeDot = s.badgeDot,
            hideEmptyGroups = s.hideEmptyGroups,
            compactRows = s.compactRows,
            showSenders = s.showSenders
        )
    }

    /** 折叠说明的展开 / 收起 */
    fun toggleGuide() {
        _home.update { it.copy(guideExpanded = !it.guideExpanded) }
    }

    companion object {
        private const val TAG = "WxGroupApp"

        private val GUIDE_TEXT = buildString {
            appendLine("1. 在 LSPosed 里激活本模块，并勾选作用域「微信」。")
            appendLine("2. 签名通常已内置；如版本不匹配，到「微信签名配置」在线获取或手动填写。")
            appendLine("3. 强停微信后重新打开，会话列表顶部就会出现收纳组。")
        }
    }
}
