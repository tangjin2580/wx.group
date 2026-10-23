package io.github.wxgroup.reborn.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.core.Updater
import io.github.wxgroup.reborn.core.WeChatKiller
import io.github.wxgroup.reborn.ui.components.SettingsGroup
import io.github.wxgroup.reborn.ui.components.SettingsItem
import io.github.wxgroup.reborn.ui.components.SettingsSwitchItem
import io.github.wxgroup.reborn.ui.state.SettingsUiState
import io.github.wxgroup.reborn.ui.theme.IconMint
import io.github.wxgroup.reborn.ui.theme.IconOrange
import io.github.wxgroup.reborn.ui.theme.IconPurple
import io.github.wxgroup.reborn.ui.theme.IconTeal
import io.github.wxgroup.reborn.ui.theme.StatusError

/* ══════════════════════════════════════════════════════════════════════════
 * 设置页：iOS 风格的分组列表（Grouped List）。
 *
 * 分成 4 组，每组一张白卡片：
 *   外观 —— 主题与深浅色
 *   显示偏好 —— 4 个 Switch（改动即时生效到微信进程）
 *   微信签名配置 —— 决定功能是否生效的关键配置 + 强杀微信
 *   关于 —— 版本 / 开源地址 / 反馈
 *
 * 页面跳转由 AppRoot 接线；「强杀微信」「开源地址」「反馈」这类
 * 只需要 Context 的动作直接在本页处理。
 * ══════════════════════════════════════════════════════════════════════════ */

private const val REPO_URL = "https://github.com/tangjin2580/wx.group"

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onTheme: () -> Unit,
    onSignature: () -> Unit,
    onBadgeDot: (Boolean) -> Unit,
    onHideEmpty: (Boolean) -> Unit,
    onCompact: (Boolean) -> Unit,
    onSenders: (Boolean) -> Unit
) {
    val context = LocalContext.current
    // 「强杀微信」确认弹窗
    var showKillDialog by remember { mutableStateOf(false) }

    // 三块内容的左右边距统一 16dp（与首页 / 分组页一致）
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding()
        ),
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
                SettingsItem(
                    icon = Icons.Rounded.RestartAlt,
                    iconTint = StatusError,
                    title = context.getString(R.string.settings_kill_wechat),
                    subtitle = context.getString(R.string.settings_kill_wechat_desc),
                    onClick = { showKillDialog = true }
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
                    // 动态读取真实版本号，不再硬编码
                    valueText = "v" + Updater.currentVersion(context),
                    showChevron = false
                )
                SettingsItem(
                    icon = Icons.Rounded.Code,
                    iconTint = IconMint,
                    title = "开源地址",
                    subtitle = "github.com/tangjin2580/wx.group",
                    onClick = { openUrl(context, REPO_URL) }
                )
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    iconTint = IconPurple,
                    title = "反馈",
                    subtitle = "到 GitHub 提 Issue",
                    onClick = { openUrl(context, "$REPO_URL/issues") }
                )
            }
        }
    }

    // ── 强杀微信确认弹窗（iOS 风格的 Compose AlertDialog）──
    if (showKillDialog) {
        AlertDialog(
            onDismissRequest = { showKillDialog = false },
            title = { Text(context.getString(R.string.settings_kill_wechat)) },
            text = { Text(context.getString(R.string.settings_kill_wechat_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showKillDialog = false
                    killWeChat(context)
                }) { Text(context.getString(R.string.settings_kill_wechat_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showKillDialog = false }) {
                    Text(context.getString(android.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 执行强杀：su 提权要等 root 管理器授权，放 UI 线程会直接 ANR，
 * 所以丢后台线程，结果回主线程 Toast。
 */
private fun killWeChat(context: android.content.Context) {
    Thread {
        val killed = WeChatKiller.forceStop(context)
        Handler(Looper.getMainLooper()).post {
            val msg = if (killed) R.string.settings_kill_wechat_done else R.string.settings_kill_wechat_fail
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }.start()
}

/** 打开外部链接（没有浏览器时静默失败） */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
    }
}
