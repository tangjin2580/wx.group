package io.github.wxgroup.reborn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wxgroup.reborn.ui.components.CollapsibleCard
import io.github.wxgroup.reborn.ui.components.HairlineDivider
import io.github.wxgroup.reborn.ui.components.IconBox
import io.github.wxgroup.reborn.ui.components.MetaRow
import io.github.wxgroup.reborn.ui.components.StatusLevel
import io.github.wxgroup.reborn.ui.components.StatusPill
import io.github.wxgroup.reborn.ui.components.WxCard
import io.github.wxgroup.reborn.ui.components.clickableOnce
import io.github.wxgroup.reborn.ui.state.HomeUiState
import io.github.wxgroup.reborn.ui.state.SignatureStatus
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.WxShape
import io.github.wxgroup.reborn.ui.theme.WxSize
import io.github.wxgroup.reborn.ui.theme.WxSpace

/* ══════════════════════════════════════════════════════════════════════════════
 * 首页：运行状态仪表盘
 *
 * ── 从「功能堆叠」改成「先看状态」──
 * 老首页一上来就是四行功能入口（新建/签名/主题/更新），用户第一眼不知道模块到底
 * 有没有在工作，遇到「微信里没出现收纳组」只能靠猜。
 * 现在首屏自上而下只有三件事：
 *   1) 状态卡：一句话结论 + 链路逐环明细（App 已装 → 通道可用 → Hook 已生效 → 签名已匹配）
 *   2) 常用操作：新建分组 / 成员管理 / 外观主题 / 签名配置（四个够了，多了就是堆叠）
 *   3) 次级入口：使用说明（折叠）+ 诊断类低频操作
 *
 * ── 状态不只靠颜色 ──
 * 每个状态都同时给出：形状明确的图标 + 文字结论 + 语义色底色三重冗余，
 * 绿/红色弱用户也能分辨，截图/灰度打印时也不丢信息。
 * ══════════════════════════════════════════════════════════════════════════════ */

/** 首页可触发的动作（AppRoot 负责接线） */
enum class QuickAction {
    NEW_GROUP, MEMBERS, THEME, SIGNATURE, UPDATE, LOG, REFRESH
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    onQuick: (QuickAction) -> Unit,
    onGuideToggle: () -> Unit,
    onOpenSignature: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = WxSpace.lg,
            end = WxSpace.lg,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(WxSpace.betweenCards)
    ) {
        item { StatusDashboard(state = state, onOpenSignature = onOpenSignature) }

        item {
            Column {
                Text(
                    text = "常用操作",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppColors.current.textSecondary,
                    modifier = Modifier.padding(start = WxSpace.xs, bottom = WxSpace.sm)
                )
                QuickActions(onQuick = onQuick)
            }
        }

        item {
            CollapsibleCard(
                title = "使用说明",
                expanded = state.guideExpanded,
                onToggle = onGuideToggle,
                text = state.guideText
            )
        }

        item { SecondaryActions(onQuick = onQuick) }
    }
}

/* ── 状态总览 ─────────────────────────────────────────────────────────────── */

/** 一句话结论（由界面层根据 facts 推导，VM 只提供事实） */
private data class Overview(
    val level: StatusLevel,
    val title: String,
    val summary: String,
    val icon: ImageVector
)

/**
 * 链路判定：逐环检查，第一处不通就停在那里告诉用户。
 *
 * 顺序很重要 —— 必须是「App 装了 → 通道通了 → 签名匹配了 → Hook 生效了」这条依赖链，
 * 否则会出现「前面没通却提示后面有问题」的误导。
 */
private fun overviewOf(state: HomeUiState): Overview = when {
    !state.channelReady -> Overview(
        StatusLevel.ERROR,
        "通信通道未就绪",
        "App 的改动还传不到微信，需要授权创建跨进程镜像",
        Icons.Rounded.ErrorOutline
    )

    state.signatureStatus != SignatureStatus.MATCHED -> Overview(
        StatusLevel.WARN,
        "签名待配置",
        "存在有效方法签名后 Hook 才能挂上",
        Icons.Rounded.Code
    )

    !state.wechatInstalled -> Overview(
        StatusLevel.WARN,
        "未检测到微信",
        "安装微信后可自动识别版本与内置签名",
        Icons.Rounded.Folder
    )

    !state.hookActive -> Overview(
        StatusLevel.WARN,
        "尚未在微信中生效",
        "请在 LSPosed 勾选本模块作用域「微信」，并强停微信后重开",
        Icons.Rounded.Refresh
    )

    else -> Overview(
        StatusLevel.OK,
        "运行正常",
        "收纳组已按当前分组与主题渲染",
        Icons.Rounded.CheckCircle
    )
}

@Composable
private fun StatusDashboard(state: HomeUiState, onOpenSignature: () -> Unit) {
    val colors = LocalAppColors.current
    val ov = overviewOf(state)
    val (fg, bg) = when (ov.level) {
        StatusLevel.OK -> colors.success to colors.success
        StatusLevel.WARN -> colors.warning to colors.warning
        StatusLevel.ERROR -> colors.error to colors.error
        StatusLevel.NEUTRAL -> colors.textSecondary to colors.textSecondary
    }

    WxCard(borderColor = bg.copy(alpha = 0.35f)) {
        Column {
            // ── 结论区：图标 + 结论 + 一句解释 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(WxSpace.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(WxShape.control)
                        .background(bg.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = ov.icon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(WxSpace.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ov.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = ov.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            HairlineDivider()

            // ── 链路明细：一环一行，都能自己看懂 ──
            MetaRow(
                label = "模块版本",
                value = state.appVersion,
                level = StatusLevel.OK
            )
            HairlineDivider()
            MetaRow(
                label = "微信版本",
                value = state.wechatVersion,
                level = if (state.wechatInstalled) StatusLevel.OK else StatusLevel.WARN
            )
            HairlineDivider()
            MetaRow(
                label = "通信通道",
                value = if (state.channelReady) "可用" else "不可用",
                level = if (state.channelReady) StatusLevel.OK else StatusLevel.ERROR
            )
            HairlineDivider()
            MetaRow(
                label = "已在微信运行",
                value = hookLabel(state),
                level = if (state.hookActive) StatusLevel.OK else StatusLevel.WARN
            )
            HairlineDivider()
            MetaRow(
                label = "签名配置",
                value = when (state.signatureStatus) {
                    SignatureStatus.MATCHED -> "已匹配"
                    SignatureStatus.MISSING -> "未配置"
                    SignatureStatus.ERROR -> "错误"
                },
                shortText = state.signatureSummary,
                level = when (state.signatureStatus) {
                    SignatureStatus.MATCHED -> StatusLevel.OK
                    SignatureStatus.MISSING -> StatusLevel.WARN
                    SignatureStatus.ERROR -> StatusLevel.ERROR
                }
            )

            // 需要行动时，给一个明确入口（比藏在角落的灰字有用得多）
            if (state.signatureStatus != SignatureStatus.MATCHED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableOnce(onClick = onOpenSignature)
                        .padding(horizontal = WxSpace.lg, vertical = WxSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(WxSize.iconRow)
                    )
                    Spacer(Modifier.width(WxSpace.sm))
                    Text(
                        text = "前往「微信签名配置」",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** Hook 最近一次运行时间的人话表达 */
private fun hookLabel(state: HomeUiState): String {
    if (state.hookLastRunAt <= 0L) return "从未"
    val diff = System.currentTimeMillis() - state.hookLastRunAt
    val min = diff / 60_000L
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        else -> "${min / (60 * 24)} 天前"
    }
}

/* ── 常用操作 ─────────────────────────────────────────────────────────────── */

/**
 * 四个高频入口，两列排布。
 *
 * 不放更多：一次望去四个最好挑，第五个开始就是列表而不是「常用」了；
 * 剩余的入口要么在设置页，要么在下面的次级区。
 */
@Composable
private fun QuickActions(onQuick: (QuickAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(WxSpace.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(WxSpace.md)) {
            ActionTile(
                icon = Icons.Rounded.Add,
                tint = io.github.wxgroup.reborn.ui.theme.IconBlue,
                title = "新建分组",
                subtitle = "命名后添加成员",
                onClick = { onQuick(QuickAction.NEW_GROUP) },
                modifier = Modifier.weight(1f)
            )
            ActionTile(
                icon = Icons.Rounded.Folder,
                tint = io.github.wxgroup.reborn.ui.theme.IconTeal,
                title = "成员管理",
                subtitle = "调整组内会话",
                onClick = { onQuick(QuickAction.MEMBERS) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(WxSpace.md)) {
            ActionTile(
                icon = Icons.Rounded.Palette,
                tint = io.github.wxgroup.reborn.ui.theme.IconPurple,
                title = "外观与主题",
                subtitle = "8 套内置主题",
                onClick = { onQuick(QuickAction.THEME) },
                modifier = Modifier.weight(1f)
            )
            ActionTile(
                icon = Icons.Rounded.Code,
                tint = io.github.wxgroup.reborn.ui.theme.IconOrange,
                title = "签名配置",
                subtitle = "匹配微信版本",
                onClick = { onQuick(QuickAction.SIGNATURE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    WxCard(modifier = modifier.clickableOnce(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(WxSpace.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBox(icon = icon, tint = tint)
            Spacer(Modifier.width(WxSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}

/* ── 次级入口：低频操作收在这里 ────────────────────────────────────────── */

@Composable
private fun SecondaryActions(onQuick: (QuickAction) -> Unit) {
    val colors = LocalAppColors.current
    WxCard {
        Column {
            SecondaryRow(
                icon = Icons.Rounded.Refresh,
                title = "重新检测",
                subtitle = "重读通道、微信版本与运行状态",
                onClick = { onQuick(QuickAction.REFRESH) }
            )
            HairlineDivider()
            SecondaryRow(
                icon = Icons.Rounded.Terminal,
                title = "运行日志与诊断",
                subtitle = "查看 Hook 侧落盘的最新日志",
                onClick = { onQuick(QuickAction.LOG) }
            )
            HairlineDivider()
            SecondaryRow(
                icon = Icons.Rounded.Update,
                title = "检查更新",
                subtitle = "在 GitHub 上查找新版本",
                onClick = { onQuick(QuickAction.UPDATE) }
            )
            Text(
                text = "诊断类操作已收在这里：它们不是日常路径，堆在首屏只会稀释真正重要的状态信息。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    horizontal = WxSpace.lg,
                    vertical = WxSpace.md
                )
            )
        }
    }
}

@Composable
private fun SecondaryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WxSize.rowItem)
            .clickableOnce(onClick)
            .padding(horizontal = WxSpace.lg, vertical = WxSpace.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(WxSize.iconRow)
        )
        Spacer(Modifier.width(WxSpace.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Spacer(Modifier.height(1.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}
