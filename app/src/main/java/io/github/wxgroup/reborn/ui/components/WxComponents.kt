package io.github.wxgroup.reborn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.WxShape
import io.github.wxgroup.reborn.ui.theme.WxSize
import io.github.wxgroup.reborn.ui.theme.WxSpace

/* ══════════════════════════════════════════════════════════════════════════════
 * 通用组件层
 *
 * 放的是**跨页面复用**的容器与状态组件，业务无关。
 * 目的：同一个「卡片」在全-app只有一个实现 —— 圆角、描边、内边距由这里统一，
 * 页面代码只关心内容，不再各自贴一串 Modifier。
 * ══════════════════════════════════════════════════════════════════════════════ */

/**
 * 统一卡片容器。
 *
 * 全项目卡片的形状 / 描边 / 无阴影策略都在这里，避免各页面写出四种微差的圆角。
 * [toned] 给需要一点强调的卡片（如状态卡）的边框染色，不必各处自己 copy(alpha)。
 */
@Composable
fun WxCard(
    modifier: Modifier = Modifier,
    containerColor: Color = LocalAppColors.current.cardBg,
    borderColor: Color = LocalAppColors.current.outlineVariant,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = WxShape.card,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = WxSize.elevationNone),
        border = BorderStroke(WxSize.strokeCard, borderColor),
        content = content
    )
}

/**
 * 状态标签：图标 + 文字 + 语义底色。
 *
 * ⚠️ 状态**不能只靠颜色传达** —— 色弱用户在绿/红之间几乎分不出差别。
 * 所以每个状态都配了形状明确的图标（✓ / ! / i）和文字，颜色只是第三重冗余。
 *
 * @param level 语义级别：ok / warn / error / neutral
 */
@Composable
fun StatusPill(
    text: String,
    level: StatusLevel,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val (fg, bg, icon) = when (level) {
        StatusLevel.OK -> Triple(colors.success, colors.success.copy(alpha = 0.14f), Icons.Rounded.CheckCircle)
        StatusLevel.WARN -> Triple(colors.warning, colors.warning.copy(alpha = 0.16f), Icons.Rounded.Info)
        StatusLevel.ERROR -> Triple(colors.error, colors.error.copy(alpha = 0.14f), Icons.Rounded.ErrorOutline)
        StatusLevel.NEUTRAL -> Triple(colors.textSecondary, colors.textSecondary.copy(alpha = 0.14f), Icons.Rounded.Info)
    }
    Surface(
        modifier = modifier,
        shape = WxShape.pill,
        color = bg,
        contentColor = fg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = WxSpace.sm, vertical = WxSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(WxSpace.xs))
            Text(text = text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/** [StatusPill] 的语义级别 */
enum class StatusLevel { OK, WARN, ERROR, NEUTRAL }

/**
 * 键值行：左标签 + 右取值（可带状态标签）。
 * 用于状态仪表盘的明细格里，保证标签与取值的对齐方式全局一致。
 */
@Composable
fun MetaRow(
    label: String,
    value: String,
    level: StatusLevel? = null,
    shortText: String? = null
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WxSpace.lg, vertical = WxSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Spacer(Modifier.weight(1f))
        if (shortText != null) {
            Text(
                text = shortText,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(WxSpace.sm))
        }
        if (level != null) {
            StatusPill(text = value, level = level)
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
        }
    }
}

/** 卡片内的分隔线：缩进到文字起点，右侧留边（iOS 列表习惯） */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    HorizontalDivider(
        modifier = modifier.padding(horizontal = WxSpace.lg),
        thickness = WxSize.strokeCard,
        color = colors.outlineVariant
    )
}

/**
 * 空状态 / 异常状态页。
 *
 * 统一处理四种「没有数据」的场景：列表为空、加载中、搜索无命中、读取异常。
 * 每种都给图标 + 主文案 + 可选次文案 + 可选操作按钮，避免页面各自堆一个居中 TextView。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WxSpace.xxxl, vertical = WxSpace.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(WxSpace.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Medium
        )
        if (!message.isNullOrEmpty()) {
            Spacer(Modifier.height(WxSpace.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(WxSpace.lg))
            OutlinedButton(onClick = onAction, shape = WxShape.control) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 主按钮：高度 44dp、圆角走控件令牌、背景取当前主题主色。
 * 前景色用 [AppColors.onPrimary]（按主色亮度自动取黑/白），亮色主题也不会看不清。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalAppColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(WxSize.button),
        shape = WxShape.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.disabled,
            disabledContentColor = colors.cardBg
        )
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 次要按钮：主色描边 + 透明底，与主按钮成对出现 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(WxSize.button),
        shape = WxShape.control,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 危险操作按钮：语义错误色实心，与「删除」这类不可逆动作视觉绑定 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Button(
        onClick = onClick,
        modifier = modifier.height(WxSize.button),
        shape = WxShape.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.error,
            contentColor = Color.White
        )
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 开关（统一跟随当前主题主色） */
@Composable
fun WxSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onPrimary,
            checkedTrackColor = colors.primary,
            uncheckedThumbColor = colors.cardBg,
            uncheckedTrackColor = colors.textSecondary.copy(alpha = 0.35f),
            uncheckedBorderColor = Color.Transparent
        )
    )
}

/** 区块小标题（卡片外的分组标题，如「快捷操作」） */
@Composable
fun SectionTitle(text: String) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = WxSpace.lg, bottom = WxSpace.sm)
    )
}

/** 圆形色样（主题预览里展示主色 / 背景 / 文字 / 强调等单个色值） */
@Composable
fun ColorDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    selected: Boolean = false
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            // 选中态加一圈外环 + 极淡描边：靠「形状」而不是「换个颜色」表达选中
            .then(
                if (selected) Modifier.border(WxSize.strokeControl, colors.onPrimary, CircleShape)
                else Modifier.border(WxSize.strokeCard, colors.outlineVariant, CircleShape)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
