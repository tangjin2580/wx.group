package io.github.wxgroup.reborn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wxgroup.reborn.ui.components.CollapsibleCard
import io.github.wxgroup.reborn.ui.components.IconBox
import io.github.wxgroup.reborn.ui.components.SettingsItem
import io.github.wxgroup.reborn.ui.state.HomeUiState
import io.github.wxgroup.reborn.ui.state.SignatureStatus
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.Mint40

/* ══════════════════════════════════════════════════════════════════════════
 * 首页：只做「状态总览」
 *
 * 设计要点（对标 iOS 设置 / Overcast 那套克制风格）：
 *  - 顶部一张状态卡，一次说清「能不能用」：模块激活 / 微信版本 / 签名配置
 *  - 签名未配置或出错时，整张卡可点 → 直达签名配置页（把最关键一步路径最短化）
 *  - 快捷入口只放高频动作，不做功能堆砌
 *  - 「使用说明」默认折叠：说明类文字不占版面，首次启动自动展开一次
 * ════════════════════════════════════════════════════════════════════════════ */

/** 快捷入口的动作（由 AppRoot 接线跳转 / 触发） */
enum class QuickAction { NEW_GROUP, THEME, SIGNATURE, UPDATE }

/**
 * 首页。
 *
 * @param state 首页状态（假数据）
 * @param contentPadding 脚手架内边距（避开顶栏 / 底栏）
 * @param onGuideToggle 折叠说明的展开 / 收起
 * @param onQuick 快捷入口点击
 * @param onOpenSignature 状态卡点击（仅签名未配置 / 出错时可点）
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    onGuideToggle: () -> Unit,
    onQuick: (QuickAction) -> Unit,
    onOpenSignature: () -> Unit
) {
    // 三块内容的左右边距统一 16dp：在这里统一注入，
    // 保证状态卡 / 快捷操作 / 使用说明的卡片宽度完全一致
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
        // ── 状态总览卡 ──
        item {
            StatusCard(state = state, onOpenSignature = onOpenSignature)
        }

        // ── 快捷操作 ──
        item {
            io.github.wxgroup.reborn.ui.components.SettingsGroup(title = "快捷操作") {
                SettingsItem(
                    icon = Icons.Rounded.Add,
                    iconTint = io.github.wxgroup.reborn.ui.theme.IconBlue,
                    title = "新建分组",
                    subtitle = "创建一个收纳组并添加成员",
                    onClick = { onQuick(QuickAction.NEW_GROUP) }
                )
                SettingsItem(
                    icon = Icons.Rounded.Code,
                    iconTint = io.github.wxgroup.reborn.ui.theme.IconPurple,
                    title = "微信签名配置",
                    subtitle = "按你的微信版本补全 Hook 方法签名",
                    onClick = { onQuick(QuickAction.SIGNATURE) }
                )
                SettingsItem(
                    icon = Icons.Rounded.Palette,
                    iconTint = io.github.wxgroup.reborn.ui.theme.IconMint,
                    title = "外观与主题",
                    subtitle = "8 套内置主题 · 支持单组独立配色",
                    onClick = { onQuick(QuickAction.THEME) }
                )
                SettingsItem(
                    icon = Icons.Rounded.Refresh,
                    iconTint = io.github.wxgroup.reborn.ui.theme.IconTeal,
                    title = "检查更新",
                    subtitle = "在 GitHub 上查找最新发布版本",
                    onClick = { onQuick(QuickAction.UPDATE) }
                )
            }
        }

        // ── 使用说明（默认折叠；首次启动由 ViewModel 置为展开）──
        item {
            CollapsibleCard(
                title = "使用说明",
                expanded = state.guideExpanded,
                onToggle = onGuideToggle,
                text = state.guideText
            )
        }
    }
}

/**
 * 顶部状态卡：模块激活 / 微信版本 / 签名配置。
 *
 * 整卡恒可点 —— 签名「未配置/错误」时是「一键去修」，正常时就是签名配置入口。
 * 这是唯一会让功能完全失效的原因，必须给用户一个直达入口，而不是只在角落放个灰字提示。
 */
@Composable
fun StatusCard(
    state: HomeUiState,
    onOpenSignature: () -> Unit
) {
    val colors = LocalAppColors.current
    val needsAction = state.signatureStatus != SignatureStatus.MATCHED
    val statusColor = when (state.signatureStatus) {
        SignatureStatus.MATCHED -> colors.success
        SignatureStatus.MISSING -> colors.warning
        SignatureStatus.ERROR -> colors.error
    }
    val statusText = when (state.signatureStatus) {
        SignatureStatus.MATCHED -> "已匹配"
        SignatureStatus.MISSING -> "未配置"
        SignatureStatus.ERROR -> "错误"
    }

    // 恒可点：签名有问题时是「一键去修」，正常时就是签名配置入口
    // （左右边距由 HomeScreen 的 LazyColumn contentPadding 统一给 16dp）
    val cardModifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpenSignature)

    Card(
        modifier = cardModifier,
        shape = io.github.wxgroup.reborn.ui.theme.CardShape,
        colors = CardDefaults.cardColors(containerColor = colors.cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            statusColor.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            StatusRow(
                label = "模块状态",
                value = if (state.moduleActive) "已激活" else "未激活",
                valueColor = if (state.moduleActive) colors.success else colors.warning
            )
            DividerCompat()
            StatusRow(label = "微信版本", value = state.wechatVersion)
            DividerCompat()
            StatusRow(label = "签名配置", value = "$statusText · ${state.signatureSummary}",
                valueColor = statusColor)

            // 需要行动时给一句明确的指引（点击入口由整卡 clickable 提供）
            if (needsAction) {
                Text(
                    text = "点击本卡片前往「微信签名配置」",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    modifier = Modifier.padding(
                        start = 16.dp, end = 16.dp, bottom = 12.dp
                )
                )
            }
        }
    }
}

/** 状态卡里的一行：左侧标签 + 右侧值 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = LocalAppColors.current.textPrimary
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor
        )
    }
}

/** 极淡分隔线：左侧缩进到文字起点，右侧留边（iOS 习惯） */
@Composable
private fun DividerCompat() {
    val colors = LocalAppColors.current
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = colors.cardStroke
    )
}

/** 分组行里的小图标按钮（免打扰 / 置顶等） */
@Composable
private fun MiniIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}
