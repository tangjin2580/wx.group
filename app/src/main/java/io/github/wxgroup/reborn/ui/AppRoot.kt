package io.github.wxgroup.reborn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wxgroup.reborn.ui.screens.GroupListScreen
import io.github.wxgroup.reborn.ui.screens.HomeScreen
import io.github.wxgroup.reborn.ui.screens.QuickAction
import io.github.wxgroup.reborn.ui.screens.SettingsScreen
import io.github.wxgroup.reborn.ui.state.AppViewModel
import io.github.wxgroup.reborn.ui.state.GroupUi
import io.github.wxgroup.reborn.ui.state.HomeUiState
import io.github.wxgroup.reborn.ui.state.SettingsUiState
import io.github.wxgroup.reborn.ui.state.Tab
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.Mint40
import io.github.wxgroup.reborn.ui.theme.WxRebornTheme

/**
 * 应用根界面：包主题 + 脚手架（顶栏 / 内容 / 底部导航）。
 *
 * @param vm 状态容器（假数据；接真实逻辑时替换其方法内部即可，界面层不用动）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()

    WxRebornTheme(mode = settings.darkMode) {
        val colors = LocalAppColors.current

        // 当前选中的 Tab；用 rememberSaveable 保证旋转 / 折叠屏切换后不丢
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

        // Large Title 折叠行为：向上滚动时大标题收成小标题
        val scrollBehavior: TopAppBarScrollBehavior =
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

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
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = tab == Tab.HOME,
                            onClick = { tab = Tab.HOME },
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Home,
                                    contentDescription = "首页",
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text("首页") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mint40,
                                selectedTextColor = Mint40,
                                indicatorColor = Mint40.copy(alpha = 0.14f)
                            )
                        )
                        NavigationBarItem(
                            selected = tab == Tab.GROUPS,
                            onClick = { tab = Tab.GROUPS },
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = "分组",
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text("分组") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mint40,
                                selectedTextColor = Mint40,
                                indicatorColor = Mint40.copy(alpha = 0.14f)
                            )
                        )
                        NavigationBarItem(
                            selected = tab == Tab.SETTINGS,
                            onClick = { tab = Tab.SETTINGS },
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "设置",
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text("设置") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mint40,
                                selectedTextColor = Mint40,
                                indicatorColor = Mint40.copy(alpha = 0.14f)
                            )
                        )
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
                            onQuick = {},
                            onOpenSignature = {}
                        )

                        Tab.GROUPS -> GroupListScreen(
                            groups = groups,
                            onCreate = { vm.addGroup("新分组") },
                            onRename = { vm.renameGroup(it, "已重命名") },
                            onTogglePin = vm::togglePin,
                            onToggleMute = vm::toggleMute,
                            onMembers = {},
                            onDelete = vm::removeGroup,
                            onMove = vm::moveGroup,
                            contentPadding = padding
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            state = settings,
                            contentPadding = padding,
                            onTheme = {},
                            onSignature = {},
                            onAbout = {},
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

/** 底部导航栏：三个 Tab（首页 / 分组 / 设置） */
@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(
        containerColor = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            Tab.HOME -> Icons.Rounded.Home
                            Tab.GROUPS -> Icons.Rounded.Folder
                            Tab.SETTINGS -> Icons.Rounded.Settings
                        },
                        contentDescription = tab.label,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Mint40,
                    selectedTextColor = Mint40,
                    indicatorColor = Mint40.copy(alpha = 0.14f)
                )
            )
        }
    }
}
