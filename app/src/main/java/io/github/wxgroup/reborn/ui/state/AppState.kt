package io.github.wxgroup.reborn.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.wxgroup.reborn.core.GroupStore
import io.github.wxgroup.reborn.core.GroupTheme
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

/** 签名配置状态：已匹配 / 未配置 / 错误 */
enum class SignatureStatus { MATCHED, MISSING, ERROR }

/** 首页整屏需要的状态 */
data class HomeUiState(
    /** 同步通道（/data/local/tmp 镜像）是否就绪 —— App 的改动能否到达微信 */
    val moduleActive: Boolean = true,
    val wechatVersion: String = "",
    val signatureStatus: SignatureStatus = SignatureStatus.MISSING,
    val signatureSummary: String = "尚未获得有效方法签名",
    val guideExpanded: Boolean = false,
    val guideText: String = ""
)

/** 设置页整屏需要的状态（显示偏好开关 + 外观 + 关于） */
data class SettingsUiState(
    val themeId: String = GroupTheme.Id.DEFAULT,
    val darkMode: String = GroupTheme.Mode.SYSTEM,
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

    private val _groups = MutableStateFlow<List<GroupUi>>(emptyList())
    val groups: StateFlow<List<GroupUi>> = _groups.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

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
        val s = GroupStore.getSettings(ctx)
        val sig = GroupStore.getSignatures(ctx)
        val validCount = sig?.methods?.values?.count { it.isValid } ?: 0
        val status = when {
            validCount > 0 -> SignatureStatus.MATCHED
            sig != null -> SignatureStatus.MISSING
            else -> SignatureStatus.ERROR
        }
        val summary = if (validCount > 0) {
            val source = if (GroupStore.bundledSignatureVersion() == sig?.wechatVersion) "内置" else "自定义"
            "$source · ${sig?.wechatVersion} · $validCount 项有效"
        } else {
            "尚未获得有效方法签名"
        }
        _home.value = HomeUiState(
            moduleActive = GroupStore.mirrorsReady(),
            wechatVersion = GroupStore.installedWechatVersion(ctx) ?: "未安装",
            signatureStatus = status,
            signatureSummary = summary,
            guideExpanded = _home.value.guideExpanded,
            guideText = GUIDE_TEXT
        )
    }

    // ══ 分组 ══

    private fun refreshGroups() {
        val s = GroupStore.getSettings(ctx)
        val wx = GroupStore.getGroups(ctx)
        _groups.value = wx.mapIndexed { i, g ->
            GroupUi(
                id = g.id,
                name = g.name,
                icon = g.icon.ifBlank { GroupTheme.iconOf(g.id) },
                iconTint = (s.groupColors[g.id] ?: GroupTheme.colorForGroup(g.id, i, s)).toLong() and 0xFFFFFFFFL,
                pinned = g.pinned,
                muted = g.muted,
                memberCount = g.members.size
            )
        }
    }

    /** 新建分组：直接追加（命名靠右滑重命名完成） */
    fun addGroup(name: String) {
        val id = "grp_${System.currentTimeMillis()}"
        GroupStore.createGroup(ctx, WxGroup(id = id, name = name, icon = GroupTheme.iconOf(id)), null)
        refreshGroups()
    }

    fun renameGroup(id: String, name: String) {
        if (name.isBlank()) return
        GroupStore.updateGroup(ctx, id) { it.name = name }
        refreshGroups()
    }

    fun togglePin(id: String) {
        GroupStore.updateGroup(ctx, id) { it.pinned = !it.pinned }
        refreshGroups()
    }

    fun toggleMute(id: String) {
        GroupStore.updateGroup(ctx, id) { it.muted = !it.muted }
        refreshGroups()
    }

    fun removeGroup(id: String) {
        GroupStore.removeGroup(ctx, id)
        refreshGroups()
    }

    /**
     * 拖动排序：按 UI 顺序给全部分组写显式 order（从 1 递增）。
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
        private val GUIDE_TEXT = buildString {
            appendLine("1. 在 LSPosed 里激活本模块，并勾选作用域「微信」。")
            appendLine("2. 签名通常已内置；如版本不匹配，到「微信签名配置」在线获取或手动填写。")
            appendLine("3. 强停微信后重新打开，会话列表顶部就会出现收纳组。")
        }
    }
}
