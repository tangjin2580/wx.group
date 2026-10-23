package io.github.wxgroup.reborn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.ui.components.SettingsGroup
import io.github.wxgroup.reborn.ui.components.SettingsItem
import io.github.wxgroup.reborn.ui.components.SettingsSwitchItem
import io.github.wxgroup.reborn.ui.state.SettingsUiState
import io.github.wxgroup.reborn.ui.theme.IconMint
import io.github.wxgroup.reborn.ui.theme.IconOrange
import io.github.wxgroup.reborn.ui.theme.IconPurple
import io.github.wxgroup.reborn.ui.theme.IconTeal

/**
 * 设置页：iOS 风格的分组列表（Grouped List）。
 *
 * 分成 4 组，每组一张白卡片：
 *   外观 —— 主题与深浅色
 *   显示偏好 —— 4 个 Switch（改动即时生效到微信进程）
 *   微信签名配置 —— 决定功能是否生效的关键配置
 *   关于 —— 版本 / 开源地址 / 反馈
 *
 * 所有条目都由 [SettingsGroup] + [SettingsItem] / [SettingsSwitchItem] 拼出来，
 * 新增设置项时只需要加一行，不用碰布局。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onTheme: () -> Unit,
    onSignature: () -> Unit,
    onAbout: () -> Unit,
    onBadgeDot: (Boolean) -> Unit,
    onHideEmpty: (Boolean) -> Unit,
    onCompact: (Boolean) -> Unit,
    onSenders: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── 外观 ──
        item {
            SettingsGroup(title = "外观") {
                SettingsItem(
                    icon = Icons.Rounded.Palette,
                    iconTint = IconMint,
                    title = "外观与主题",
                    subtitle = "8 套内置主题，支持深浅色",
                    valueText = GroupTheme.labelOf(state.themeId),
                    onClick = onTheme
                )
            }
        }

        // ── 显示偏好（Switch）──
        item {
            SettingsGroup(title = "显示偏好") {
                SettingsSwitchItem(
                    icon = Icons.Rounded.Visibility,
                    iconTint = IconPurple,
                    title = "未读角标用纯红点",
                    subtitle = "只显示小红点，不显示具体数字",
                    checked = state.badgeDot,
                    onCheckedChange = onBadgeDot
                )
                SettingsSwitchItem(
                    icon = Icons.Rounded.VisibilityOff,
                    iconTint = IconOrange,
                    title = "隐藏空分组",
                    subtitle = "会话列表里没有成员的分组自动隐藏",
                    checked = state.hideEmptyGroups,
                    onCheckedChange = onHideEmpty
                )
                SettingsSwitchItem(
                    icon = Icons.Rounded.Info,
                    iconTint = IconMint,
                    title = "收纳组行紧凑模式",
                    subtitle = "行更矮，一屏显示更多会话",
                    checked = state.compactRows,
                    onCheckedChange = onCompact
                )
                SettingsSwitchItem(
                    icon = Icons.Rounded.VolumeOff,
                    iconTint = IconTeal,
                    title = "副标题显示发送者",
                    subtitle = "组行副标题显示最近未读消息的发送者",
                    checked = state.showSenders,
                    onCheckedChange = onSenders
                )
            }
        }

        // ── 微信签名配置 ──
        item {
            SettingsGroup(title = "微信签名配置") {
                SettingsItem(
                    icon = Icons.Rounded.Code,
                    iconTint = IconPurple,
                    title = "微信签名配置",
                    subtitle = "按你的微信版本补全 Hook 方法签名",
                    onClick = onSignature
                )
            }
        }

        // ── 关于 ──
        item {
            SettingsGroup(title = "关于") {
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    iconTint = IconTeal,
                    title = "版本",
                    valueText = "v0.9.9"
                )
                SettingsItem(
                    icon = Icons.Rounded.Code,
                    iconTint = IconMint,
                    title = "开源地址",
                    subtitle = "github.com/tangjin2580/wx.group"
                )
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    iconTint = IconPurple,
                    title = "反馈"
                )
            }
        }
    }
}
