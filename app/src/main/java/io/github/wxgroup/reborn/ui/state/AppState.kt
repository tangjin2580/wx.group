package io.github.wxgroup.reborn.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.WxSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/* ══════════════════════════════════════════════════════════════════════════════
 * UI 状态层 —— 全部是假数据，目的是让界面先跑起来。
 *
 * 后续接真实逻辑时，只需要把下面几个方法的内部替换掉即可：
 *   读取   → GroupStore.getGroups(ctx) / GroupStore.getSettings(ctx)
 *   写入   → GroupStore.putGroups(ctx, list) / GroupStore.putSettings(ctx, s)
 * 分组排序 → SortGroupsActivity 里已有的排序逻辑
 * ══════════════════════════════════════════════════════════════════════════════ */

/** 分组的 UI 模型（与 core.WxGroup 一一对应，只是把成员数量也带上方便展示） */
data class GroupUi(
    val id: String,
    val name: String,
    val icon: String,          // 单字图标（沿用现有「色/组/签」这类单字方案）
    val iconTint: Long,        // 图标底块颜色（ARGB）
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val memberCount: Int = 0,
    val unread: Int = 0
)

/** 模块激活状态（LSPosed 里是否勾选了微信作用域） */
enum class ModuleStatus { ACTIVE, INACTIVE, UNKNOWN }

/** 签名配置状态：已匹配 / 未配置 / 错误 */
enum class SignatureStatus { MATCHED, MISSING, ERROR }

/** 首页整屏需要的状态 */
data class HomeUiState(
    val moduleActive: Boolean = true,
    val moduleVersion: String = "0.9.9",
    val wechatVersion: String = "8.0.79",
    val signatureStatus: SignatureStatus = SignatureStatus.MISSING,
    val signatureSummary: String = "尚未填入当前微信版本的方法签名",
    val guideExpanded: Boolean = false,
    val guideText: String = ""
)

/** 设置页整屏需要的状态（显示偏好开关 + 外观 + 关于） */
data class SettingsUiState(
    val themeId: String = GroupTheme.Id.DEFAULT,
    val darkMode: String = GroupTheme.Mode.SYSTEM,
    val badgeDot: Boolean = false,
    val hideEmptyGroups: Boolean = true,
    val compactRows: Boolean = false,
    val showSenders: Boolean = true
)

/** 三个底部 Tab */
enum class Tab(val label: String) { HOME("首页"), GROUPS("分组"), SETTINGS("设置") }

/**
 * 界面状态容器。
 *
 * 真机逻辑接入后，这里的 StateFlow 可以直接换成 GroupStore 的数据源，
 * 界面层（Composable）不用改。
 */
class AppViewModel : ViewModel() {

    private val _home = MutableStateFlow(
        HomeUiState(
            guideText = buildString {
                appendLine("1. 在 LSPosed 里激活本模块，并勾选作用域「微信」。")
                appendLine("2. 打开「微信签名配置」，填入你手机微信版本对应的类/方法签名。")
                appendLine("3. 强停微信后重新打开，会话列表顶部就会出现收纳组。")
            }
        )
    )
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _groups = MutableStateFlow(
        listOf(
            GroupUi("g_life", "生活", "★", 0xFF2BB38A, memberCount = 2, unread = 0),
            GroupUi("g_med", "医保", "⚑", 0xFF3C86EF, memberCount = 27, unread = 5),
            GroupUi("g_intl", "国际", "✈", 0xFFF2922F, memberCount = 30, unread = 0),
            GroupUi("g_work", "Work", "☰", 0xFF8C7BEE, memberCount = 4, unread = 1),
            GroupUi("g_zn", "中诺", "◈", 0xFFEF5F79, memberCount = 3, unread = 0)
        )
    )
    val groups: StateFlow<List<GroupUi>> = _groups.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 远端最新版本号（null = 还没查过）；当前版本由 [HomeUiState.moduleVersion] 提供 */
    private val _latest = MutableStateFlow<String?>(null)
    val latest: StateFlow<String?> = _latest.asStateFlow()

    // ══ 显示偏好：改完立刻回写（真实实现走 GroupStore.putSettings）══
    fun setBadgeDot(v: Boolean) = _settings.update { it.copy(badgeDot = v) }
    fun setHideEmptyGroups(v: Boolean) = _settings.update { it.copy(hideEmptyGroups = v) }
    fun setCompactRows(v: Boolean) = _settings.update { it.copy(compactRows = v) }
    fun setShowSenders(v: Boolean) = _settings.update { it.copy(showSenders = v) }

    // ══ 外观 ══
    fun setTheme(id: String) = _settings.update { it.copy(themeId = id) }
    fun setDarkMode(mode: String) = _settings.update { it.copy(darkMode = mode) }

    // ══ 分组管理 ══
    fun addGroup(name: String) {
        val id = "g_${System.currentTimeMillis()}"
        // 新分组默认给单色符号 + 轮转色相
        val icon = GroupTheme.iconOf(id)
        val tint = GroupTheme.colorForGroup(id, _groups.value.size, WxSettings.DEFAULT)
        _groups.update { it + GroupUi(id, name, icon, tint.toLong() and 0xFFFFFFFFL or 0xFF000000L) }
    }

    fun renameGroup(id: String, name: String) {
        _groups.update { list -> list.map { if (it.id == id) it.copy(name = name) else it } }
    }

    fun togglePin(id: String) {
        _groups.update { list -> list.map { if (it.id == id) it.copy(pinned = !it.pinned) else it } }
    }

    fun toggleMute(id: String) {
        _groups.update { list -> list.map { if (it.id == id) it.copy(muted = !it.muted) else it } }
    }

    fun removeGroup(id: String) {
        _groups.update { list -> list.filterNot { it.id == id } }
    }

    /** 拖动排序：把 from 位置的条目移动到 to 位置 */
    fun moveGroup(from: Int, to: Int) {
        _groups.update { list ->
            if (from !in list.indices || to !in list.indices || from == to) return@update list
            val mutable = list.toMutableList()
            val item = mutable.removeAt(from)
            mutable.add(to, item)
            mutable
        }
    }

    /**
     * 检查更新（模拟网络请求）。
     *
     * 真实实现直接调 core.Updater.fetchLatest()：
     * ```kotlin
     * viewModelScope.launch {
     *     _checking.value = true
     *     runCatching { withContext(Dispatchers.IO) { Updater.fetchLatest() } }
     *         .onSuccess { _latest.value = it?.version }
     *     _checking.value = false
     * }
     * ```
     */
    fun checkUpdate() {
        viewModelScope.launch {
            _checking.value = true
            runCatching {
                // 假装网络请求 1.2 秒
                delay(1200)
                "1.0.0"
            }.onSuccess { ver ->
                _latest.value = ver
            }
            _checking.value = false
        }
    }

    /** 折叠说明的展开 / 收起（首次启动由 [AppViewModel] 初始化为展开） */
    fun toggleGuide() {
        _home.update { it.copy(guideExpanded = !it.guideExpanded) }
    }
}
