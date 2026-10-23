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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wxgroup.reborn.core.Updater
import io.github.wxgroup.reborn.ui.GroupManageActivity
import io.github.wxgroup.reborn.ui.LogActivity
import io.github.wxgroup.reborn.ui.SignatureActivity
import io.github.wxgroup.reborn.ui.SortGroupsActivity
import io.github.wxgroup.reborn.ui.ThemeActivity
import io.github.wxgroup.reborn.ui.screens.GroupListScreen
import io.github.wxgroup.reborn.ui.screens.HomeScreen
import io.github.wxgroup.reborn.ui.screens.QuickAction
import io.github.wxgroup.reborn.ui.screens.SettingsScreen
import io.github.wxgroup.reborn.ui.state.AppViewModel
import io.github.wxgroup.reborn.ui.state.Tab
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.Mint40
import io.github.wxgroup.reborn.ui.theme.WxRebornTheme

/**
 * 应用根界面：包主题 + 脚手架（顶栏 / 内容 / 底部导航）。
 *
 * 页面跳转 / 检查更新在这里接线 —— 旧版 XML 页面（分组管理 / 主题 / 签名 /
 * 排序）保留复用，新版 Compose 壳只负责导航与展示。
 *
 * @param vm 状态容器（假数据；接真实逻辑时替换其方法内部即可，界面层不用动）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()

    WxRebornTheme(mode = settings.darkMode) {
        val colors = LocalAppColors.current
        val context = LocalContext.current

        // 当前选中的 Tab；用 rememberSaveable 保证旋转 / 折叠屏切换后不丢
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

        // Large Title 折叠行为：向上滚动时大标题收成小标题
        val scrollBehavior: TopAppBarScrollBehavior =
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

        // ── 通用跳转 ──
        val openGroups: () -> Unit = { context.startActivitySafe<GroupManageActivity>() }
        val openTheme: () -> Unit = { context.startActivitySafe<ThemeActivity>() }
        val openSignature: () -> Unit = { context.startActivitySafe<SignatureActivity>() }
        val openLog: () -> Unit = { context.startActivitySafe<LogActivity>() }
        val openSort: () -> Unit = { context.startActivitySafe<SortGroupsActivity>() }
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
                topBar = {
                    LargeTopAppBar(
                        title = { Text(tab.label) },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = colors.pageBg,
                            scrolledContainerColor = colors.pageBg,
                            titleContentColor = colors.textPrimary
                        )
                    )
                },
                bottomBar = {
                    // iOS 风格：底栏铺卡片底色 + 顶部 0.5dp 分隔线（不再是透明悬浮）
                    Column {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = colors.cardStroke
                        )
                        NavigationBar(
                            containerColor = colors.cardBg,
                            tonalElevation = 0.dp
                        ) {
                            NavTabItem(
                                selected = tab == Tab.HOME,
                                onClick = { tab = Tab.HOME },
                                icon = Icons.Rounded.Home,
                                label = "首页"
                            )
                            NavTabItem(
                                selected = tab == Tab.GROUPS,
                                onClick = { tab = Tab.GROUPS },
                                icon = Icons.Rounded.Folder,
                                label = "分组"
                            )
                            NavTabItem(
                                selected = tab == Tab.SETTINGS,
                                onClick = { tab = Tab.SETTINGS },
                                icon = Icons.Rounded.Settings,
                                label = "设置"
                            )
                        }
                    }
                }
            ) { padding ->
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
                            onQuick = { action ->
                                when (action) {
                                    QuickAction.NEW_GROUP -> openGroups()
                                    QuickAction.SIGNATURE -> openSignature()
                                    QuickAction.THEME -> openTheme()
                                    QuickAction.UPDATE -> checkUpdate()
                                }
                            },
                            onOpenSignature = openSignature
                        )

                        Tab.GROUPS -> GroupListScreen(
                            groups = groups,
                            // 新建不跳二级页：直接在列表顶部追加一个分组，
                            // 命名用右滑重命名完成（用户偏好的一步式流程）
                            onCreate = { vm.addGroup("新分组") },
                            onRename = { vm.renameGroup(it, "已重命名") },
                            onTogglePin = vm::togglePin,
                            onToggleMute = vm::toggleMute,
                            // 管理成员 / 排序直接复用旧版成熟页面
                            onMembers = { openGroups() },
                            onDelete = vm::removeGroup,
                            onMove = vm::moveGroup,
                            contentPadding = padding
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            state = settings,
                            contentPadding = padding,
                            onTheme = openTheme,
                            onSignature = openSignature,
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

/** 底部导航的单个 Tab 项（统一样式：薄荷绿选中色 + 14% 选中底块） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.NavTabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Mint40,
            selectedTextColor = Mint40,
            indicatorColor = Mint40.copy(alpha = 0.14f)
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
