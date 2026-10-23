package io.github.wxgroup.reborn.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.Updater
import io.github.wxgroup.reborn.ui.GroupManageActivity
import io.github.wxgroup.reborn.ui.LogActivity
import io.github.wxgroup.reborn.ui.SignatureActivity
import io.github.wxgroup.reborn.ui.screens.AnalyzerScreen
import io.github.wxgroup.reborn.ui.screens.GroupListScreen
import io.github.wxgroup.reborn.ui.screens.HomeScreen
import io.github.wxgroup.reborn.ui.screens.QuickAction
import io.github.wxgroup.reborn.ui.screens.SettingsScreen
import io.github.wxgroup.reborn.ui.screens.ThemeScreen
import io.github.wxgroup.reborn.ui.state.AppViewModel
import io.github.wxgroup.reborn.ui.state.Tab
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.WxSize
import io.github.wxgroup.reborn.ui.theme.WxSpace
import io.github.wxgroup.reborn.ui.theme.resolvedDarkTheme
import io.github.wxgroup.reborn.ui.theme.WxRebornTheme

/**
 * 应用根界面：包主题 + 脚手架（顶栏 / 内容 / 底部导航 / Snackbar）。
 *
 * ── 职责边界 ──
 * 这里只做三件事：**收集状态、接线跳转、提供公共容器**（Snackbar）。
 * 具体决策（某个开关长什么样、列表怎么过滤、状态算不算「正常」）都在各自的
 * Screen / ViewModel 里 —— Controller 不掺和业务，业务也不认识 Activity/Intent。
 *
 * @param vm 状态容器（GroupStore 的 UI 门面）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val groupsState by vm.groups.collectAsStateWithLifecycle()

    // 主题 id 与深浅色一起下发：页面配色取自 core.GroupTheme 那一份色板，
    // 与主题页预览、微信进程里真实渲染的行完全同源（以前只传 mode，
    // 结果无论选哪套主题，模块 App 永远是「海盐薄荷」）
    WxRebornTheme(mode = settings.darkMode, themeId = settings.themeId) {
        val colors = LocalAppColors.current
        val context = LocalContext.current

        // 当前选中的 Tab；用 rememberSaveable 保证旋转 / 折叠屏切换后不丢
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        // 主题页：从设置进，作为一层覆盖展示（返回即回到底部 Tab）
        var themeOpen by rememberSaveable { mutableStateOf(false) }
        // 签名分析器：同主题页，作为一层覆盖展示
        var analyzerOpen by rememberSaveable { mutableStateOf(false) }
        // 统一的 Snackbar 通道：删除撤销、创建/重命名反馈都走这里，
        // 不各自弹 Toast（Toast 容易被系统的多条排队淹没，也没有操作按钮）
        val snackbarHost = remember { SnackbarHostState() }

        // Large Title 折叠行为：向上滚动时大标题收成小标题
        val scrollBehavior: TopAppBarScrollBehavior =
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

        // ── 通用跳转 ──
        val openMembers: () -> Unit = { context.startActivitySafe<GroupManageActivity>() }
        // 外观与主题走 Compose 页（本文件内覆盖），旧的 ThemeActivity 保留作为兼容入口
        val openTheme: () -> Unit = { themeOpen = true }
        val openSignature: () -> Unit = { context.startActivitySafe<SignatureActivity>() }
        val openAnalyzer: () -> Unit = { analyzerOpen = true }
        val openLog: () -> Unit = { context.startActivitySafe<LogActivity>() }
        // 检查更新：手动触发，无论结果都给反馈
        val checkUpdate: () -> Unit = {
            (context as? Activity)?.let { Updater.check(it, manual = true) }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.pageBg
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    // 关键：把滚动事件传给顶栏，标题才会跟着折叠
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                snackbarHost = { SnackbarHost(snackbarHost) },
                topBar = {
                    // 覆盖层（主题页 / 签名分析器）：顶栏换成「标题 + 返回」
                    if (themeOpen || analyzerOpen) {
                        TopAppBar(
                            title = {
                                Text(
                                    if (themeOpen) stringResource(R.string.settings_theme)
                                    else stringResource(R.string.analyzer_title)
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { themeOpen = false; analyzerOpen = false }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = stringResource(R.string.nav_back)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = colors.pageBg,
                                titleContentColor = colors.textPrimary
                            )
                        )
                    } else {
                        LargeTopAppBar(
                            title = { Text(tab.label) },
                            scrollBehavior = scrollBehavior,
                            colors = TopAppBarDefaults.largeTopAppBarColors(
                                containerColor = colors.pageBg,
                                scrolledContainerColor = colors.pageBg,
                                titleContentColor = colors.textPrimary
                            )
                        )
                    }
                },
                bottomBar = {
                    if (themeOpen || analyzerOpen) return@Scaffold
                    // iOS 风格：底栏铺卡片底色 + 顶部细分隔线（不再是透明悬浮）
                    Column {
                        HorizontalDivider(
                            thickness = WxSize.strokeCard,
                            color = colors.outlineVariant
                        )
                        NavigationBar(
                            containerColor = colors.cardBg,
                            tonalElevation = WxSize.elevationNone
                        ) {
                            NavTabItem(
                                selected = tab == Tab.HOME,
                                onClick = { tab = Tab.HOME },
                                icon = Icons.Rounded.Home,
                                label = stringResource(R.string.tab_home)
                            )
                            NavTabItem(
                                selected = tab == Tab.GROUPS,
                                onClick = { tab = Tab.GROUPS },
                                icon = Icons.Rounded.Folder,
                                label = stringResource(R.string.tab_groups)
                            )
                            NavTabItem(
                                selected = tab == Tab.SETTINGS,
                                onClick = { tab = Tab.SETTINGS },
                                icon = Icons.Rounded.Settings,
                                label = stringResource(R.string.tab_settings)
                            )
                        }
                    }
                }
            ) { padding ->
                if (themeOpen) {
                    ThemeScreen(
                        themeId = settings.themeId,
                        darkMode = settings.darkMode,
                        previewDark = resolvedDarkTheme(settings.darkMode),
                        groups = groupsState.groups,
                        groupColors = settings.groupColors,
                        onThemeSelected = vm::setTheme,
                        onDarkModeChange = vm::setDarkMode,
                        onGroupColor = vm::setGroupColor,
                        onResetTheme = {
                            vm.setTheme(io.github.wxgroup.reborn.core.GroupTheme.Id.DEFAULT)
                            vm.setDarkMode(io.github.wxgroup.reborn.core.GroupTheme.Mode.SYSTEM)
                        },
                        contentPadding = padding
                    )
                    return@Scaffold
                }
                if (analyzerOpen) {
                    AnalyzerScreen(
                        snackbarHost = snackbarHost,
                        contentPadding = padding
                    )
                    return@Scaffold
                }

                // 页面切换：淡入淡出 + 轻微横向位移
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { it / 8 }) togetherWith
                                (fadeOut() + slideOutHorizontally { -it / 8 })
                    },
                    label = "tabSwitch"
                ) { current ->
                    when (current) {
                        Tab.HOME -> HomeScreen(
                            state = home,
                            contentPadding = padding,
                            onGuideToggle = vm::toggleGuide,
                            onOpenSignature = openSignature,
                            onQuick = { action ->
                                when (action) {
                                    QuickAction.NEW_GROUP -> openMembers()
                                    QuickAction.MEMBERS -> openMembers()
                                    QuickAction.THEME -> openTheme()
                                    QuickAction.SIGNATURE -> openSignature()
                                    QuickAction.UPDATE -> checkUpdate()
                                    QuickAction.LOG -> openLog()
                                    QuickAction.REFRESH -> vm.refreshAll()
                                }
                            }
                        )

                        Tab.GROUPS -> GroupListScreen(
                            state = groupsState,
                            // 新建 / 重命名都在列表页内弹输入框：名称来自用户真实输入，
                            // 不再用「新分组」「已重命名」这类占位串顶替
                            onCreate = vm::addGroup,
                            onRename = vm::renameGroup,
                            onTogglePin = vm::togglePin,
                            onToggleMute = vm::toggleMute,
                            // 成员管理复用旧版成熟页面
                            onMembers = { openMembers() },
                            // 删除返回被删掉的名字（用于反馈 + 撤销）
                            onDelete = { id -> vm.removeGroup(id)?.group?.name },
                            onUndoDelete = vm::undoRemove,
                            onMove = vm::moveGroup,
                            onRefresh = vm::refreshAll,
                            snackbarHost = snackbarHost,
                            contentPadding = padding
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            state = settings,
                            contentPadding = padding,
                            onTheme = openTheme,
                            onSignature = openSignature,
                            onAnalyzer = openAnalyzer,
                            onLog = openLog,
                            onBadgeDot = vm::setBadgeDot,
                            onHideEmpty = vm::setHideEmptyGroups,
                            onCompact = vm::setCompactRows,
                            onSenders = vm::setShowSenders
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部导航的单个 Tab 项。
 *
 * 选中色取当前主题的 [AppColors.primary]（以前写死薄荷绿，换主题后底栏不跟着变）；
 * 选中底块用主色低透明度，比「铺一整块主色」克制，也不会压过页面内容。
 */
@Composable
private fun RowScope.NavTabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    val colors = LocalAppColors.current
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(WxSize.iconNav)
            )
        },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = colors.primary,
            selectedTextColor = colors.primary,
            indicatorColor = colors.primary.copy(alpha = 0.14f)
        )
    )
}

/** 稳妥跳转：目标 Activity 不存在（如被裁剪）时静默失败，不崩 */
private inline fun <reified T : Activity> Context.startActivitySafe() {
    try {
        startActivity(Intent(this, T::class.java))
    } catch (_: Throwable) {
    }
}
