package io.github.wxgroup.reborn.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wxgroup.reborn.ui.theme.CardShape
import io.github.wxgroup.reborn.ui.theme.LocalAppColors

/* ══════════════════════════════════════════════════════════════════════════════
 * iOS 风格设置列表组件（可复用）
 *
 * 对应 iOS 设置里的三种行：
 *   [SettingsGroup]       一「段」卡片容器（可选标题 + 白底圆角 + 0.5dp 描边）
 *   [SettingsItem]        普通跳转行（图标 + 标题 + 副标题 + 右侧值 + chevron）
 *   [SettingsSwitchItem]  开关行（同上，右侧换成 Switch）
 * ══════════════════════════════════════════════════════════════════════════════ */

/**
 * 矢量图标底块：30dp 圆角方块 + 线性图标（Material Symbols Rounded 风格）。
 * 不同功能传不同色相，靠「形状 + 颜色」区分，不靠彩色 emoji —— 更克制也更统一。
 */
@Composable
fun IconBox(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDesc: String? = null
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 单字符号底块 —— 分组图标用的就是「★ ⚑ ☰ ◈」这类单色符号。
 *
 * 和 [IconBox] 的区别：这里渲染的是字符而不是矢量图标，
 * 所以能用 [tint] 直接给它着色（符号随分组强调色变化，更沉浸）。
 */
@Composable
fun GlyphBox(
    glyph: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        // 纯装饰：符号的语义由同一行的分组名承载，这里不额外暴露给无障碍服务
        Text(
            text = glyph,
            color = tint,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 一段设置分组（iOS Grouped List 的 Section）。
 *
 * @param title 分组标题；为 null 或空时不显示标题行
 * @param content 组内若干条目
 */
@Composable
fun SettingsGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            // 卡片纯白（深色 #1C1C1E），无阴影，只有极淡描边
            colors = CardDefaults.cardColors(containerColor = colors.cardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(0.5.dp, colors.outlineVariant)
        ) {
            Column(content = content)
        }
    }
}

/**
 * 普通设置条目（可点击跳转）。
 *
 * 行高最小 52dp（触摸区够了）；左侧 30dp 图标底块；右侧可选「值文本 + chevron」。
 *
 * ── 按下反馈 ──
 * 以前这里 `indication = null` 把涟漪**关掉了**，注释却写着「交给下面的行内涟漪处理」——
 * 而下面根本没有涟漪，只有 0.97 的缩放。结果就是点设置项几乎没有任何反馈。
 * 现在改成默认涟漪（[LocalIndication]），并去掉缩放：
 * 同一组件叠「涟漪 + 缩放」两种按压反馈会互相打架，保留更标准、也更好认的涟漪。
 */
@Composable
fun SettingsItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    valueText: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalAppColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(
                enabled = onClick != null,
                onClick = { onClick?.invoke() }
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon = icon, tint = iconTint, contentDesc = title)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
        if (!valueText.isNullOrEmpty()) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Spacer(Modifier.width(4.dp))
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textSecondary.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 开关型设置条目。
 *
 * 参数含义同 [SettingsItem]，只是右侧是 Switch（薄荷绿）。
 * 整行可点：点标题、副标题、空白处都等于切换开关。
 */
@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalAppColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon = icon, tint = iconTint, contentDesc = title)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // 开启态跟着当前主题走（以前写死薄荷绿，换了主题开关不变色）
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.textSecondary.copy(alpha = 0.35f),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/**
 * 可折叠的说明卡片 —— 默认折叠，首次启动由外部（ViewModel）置为展开。
 *
 * 说明类信息全部走折叠，避免「大面积文字」压迫视觉，也符合信息密度适中的目标。
 */
@Composable
fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    text: String
) {
    val colors = LocalAppColors.current
    // 箭头跟着展开态转，让「点这里会展开」这件事可见
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrowRotation"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = colors.cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(0.5.dp, colors.outlineVariant)
    ) {
        Column {
            // 标题行：点整行切换展开 / 收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBox(
                    icon = Icons.Rounded.Info,
                    tint = colors.textSecondary,
                    contentDesc = title
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                // 展开 / 折叠指示箭头（展开时旋转 180°）
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(
                        start = 16.dp, end = 16.dp, bottom = 14.dp
                )
                )
            }
        }
    }
}
