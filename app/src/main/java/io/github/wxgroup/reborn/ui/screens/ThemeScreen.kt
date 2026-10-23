package io.github.wxgroup.reborn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.ui.components.ColorDot
import io.github.wxgroup.reborn.ui.components.EmptyState
import io.github.wxgroup.reborn.ui.components.SectionTitle
import io.github.wxgroup.reborn.ui.components.WxCard
import io.github.wxgroup.reborn.ui.components.clickableOnce
import io.github.wxgroup.reborn.ui.state.GroupUi
import io.github.wxgroup.reborn.ui.theme.BadgeRed
import io.github.wxgroup.reborn.ui.theme.LocalAppColors
import io.github.wxgroup.reborn.ui.theme.WxShape
import io.github.wxgroup.reborn.ui.theme.WxSize
import io.github.wxgroup.reborn.ui.theme.WxSpace

/* ══════════════════════════════════════════════════════════════════════════════
 * 外观与主题（Compose 版）
 *
 * ── 从「挑色块」升级到「看实际效果」──
 * 旧主题页每套主题只摆一排圆点 + 一行预览，用户其实是在**盲选**：
 * 圆点里的主色 ≠ 最终显示效果，尤其「选中态底色」「未读角标和行底的对比」
 * 「图标容器和行底的关系」这几项，只有把真实组件画出来才知道好不好看。
 *
 * 现在每套主题给三样东西：
 *   1) 真实分组行预览（普通态 + 选中态，带未读角标与置顶标记）
 *   2) 调色板明细：主色 / 强调色 / 行底色 / 图标底色 / 正文色，各自标注名字
 *   3) 一组按钮与开关 —— 检查主色上的前景色对比度、次级按钮的辨识度
 *
 * ── 深浅色各自设计 ──
 * 每套主题都有一份独立的 dark 色板（见 core.GroupTheme），不是把浅色取反。
 * 页面上的预览跟随当前深浅色选择，所以选「深」时看到的就是深色下的真实效果。
 *
 * ── 切换要「平滑且不必重启微信」──
 * 改的是 WxSettings（经 /data/local/tmp 镜像同步给微信进程），
 * 颜色过渡交给 AppColors 的 animateColorAsState；整个过程不涉及 Activity 重建。
 * ══════════════════════════════════════════════════════════════════════════════ */

@Composable
fun ThemeScreen(
    themeId: String,
    darkMode: String,
    previewDark: Boolean,
    groups: List<GroupUi>,
    groupColors: Map<String, Int>,
    onThemeSelected: (String) -> Unit,
    onDarkModeChange: (String) -> Unit,
    onGroupColor: (String, Int?) -> Unit,
    onResetTheme: () -> Unit,
    contentPadding: PaddingValues
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
        item {
            Column {
                SectionTitle(text = stringResource(R.string.theme_mode_section))
                DarkModePicker(current = darkMode, onPick = onDarkModeChange)
            }
        }

        item {
            Column {
                SectionTitle(text = stringResource(R.string.theme_list_section))
                Text(
                    text = stringResource(R.string.theme_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppColors.current.textSecondary,
                    modifier = Modifier.padding(start = WxSpace.xs, bottom = WxSpace.sm)
                )
            }
        }

        items(GroupTheme.THEMES, key = { it.id }) { theme ->
            ThemeCard(
                theme = theme,
                selected = theme.id == themeId,
                dark = previewDark,
                onClick = { onThemeSelected(theme.id) }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .padding(top = WxSpace.sm)
                    .clickableOnce(onClick = onResetTheme)
                    .heightIn(min = WxSize.rowItem)
                    .padding(horizontal = WxSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = null,
                    tint = LocalAppColors.current.textSecondary,
                    modifier = Modifier.size(WxSize.iconRow)
                )
                Spacer(Modifier.width(WxSpace.md))
                Text(
                    text = stringResource(R.string.theme_reset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAppColors.current.textSecondary
                )
            }
        }

        item {
            Column(modifier = Modifier.padding(top = WxSpace.md)) {
                SectionTitle(text = stringResource(R.string.theme_group_colors_section))
                if (groups.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.SettingsBrightness,
                        title = stringResource(R.string.theme_group_empty),
                        message = stringResource(R.string.theme_group_empty_msg)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.theme_group_colors_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAppColors.current.textSecondary,
                        modifier = Modifier.padding(start = WxSpace.xs, bottom = WxSpace.sm)
                    )
                }
            }
        }

        if (groups.isNotEmpty()) {
            items(groups, key = { it.id }) { group ->
                GroupColorRow(
                    group = group,
                    customColor = groupColors[group.id],
                    swatches = GroupTheme.of(themeId).swatches,
                    palette = GroupTheme.of(themeId).palette(previewDark),
                    onPick = { onGroupColor(group.id, it) }
                )
            }
        }
    }
}

/* ── 深浅色模式 ───────────────────────────────────────────────────────────── */

/**
 * 三选一：跟随系统 / 浅色 / 深色。
 *
 * 选中态同时用「主色底块 + 图标 + 文字」三重表达，不靠颜色单独承担状态；
 * 选项之间用 selectableGroup 语义分组，TalkBack 会朗读「第 2 项，共 3 项」。
 */
@Composable
private fun DarkModePicker(current: String, onPick: (String) -> Unit) {
    val colors = LocalAppColors.current
    val options = listOf(
        GroupTheme.Mode.SYSTEM to Icons.Rounded.SettingsBrightness,
        GroupTheme.Mode.LIGHT to Icons.Rounded.LightMode,
        GroupTheme.Mode.DARK to Icons.Rounded.DarkMode
    )
    Row(modifier = Modifier.selectableGroup()) {
        options.forEachIndexed { i, (mode, icon) ->
            val selected = mode == current
            val label = labelForMode(mode)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(WxSize.button)
                    .clip(WxShape.control)
                    .background(if (selected) colors.selectedBackground else colors.cardBg)
                    .border(WxSize.strokeCard, colors.outlineVariant, WxShape.control)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onPick(mode) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) colors.primary else colors.textSecondary,
                        modifier = Modifier.size(WxSize.iconSmall)
                    )
                    Spacer(Modifier.width(WxSpace.xs))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) colors.primary else colors.textSecondary,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
            if (i < options.size - 1) Spacer(Modifier.width(WxSpace.sm))
        }
    }
}

@Composable
private fun labelForMode(mode: String): String = stringResource(
    when (mode) {
        GroupTheme.Mode.LIGHT -> R.string.theme_mode_light
        GroupTheme.Mode.DARK -> R.string.theme_mode_dark
        else -> R.string.theme_mode_system
    }
)

/* ── 主题卡 ──────────────────────────────────────────────────────────────── */

/**
 * 一套主题的卡片：标题 + 选中标记 + 真实行预览 + 调色板明细 + 控件 preview。
 *
 * 预览用的是**这套主题自己的色板**（不是当前应用的主题色），
 * 所以列表里 8 张卡可以横向对比，不必一张张点开试。
 */
@Composable
private fun ThemeCard(
    theme: GroupTheme.Theme,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    val palette = theme.palette(dark)
    val accent = theme.swatches.firstOrNull() ?: palette.primary
    val primary = Color(palette.primary)

    WxCard(
        modifier = Modifier.clickableOnce(onClick = onClick),
        borderColor = if (selected) primary else colors.outlineVariant
    ) {
        Column(modifier = Modifier.padding(WxSpace.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = theme.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = if (selected) stringResource(R.string.theme_in_use) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = primary
                    )
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color(GroupTheme.onColorFor(palette.primary)),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(WxSpace.sm))

            // 真实行预览：普通态 + 选中态，两行各带不同的未读 / 置顶组合
            PreviewGroupRow(
                palette = palette,
                accent = accent,
                glyph = "★",
                name = stringResource(R.string.theme_preview_row_selected),
                subtitle = stringResource(R.string.theme_preview_row_selected_sub),
                selected = true,
                unread = 3
            )
            Spacer(Modifier.height(WxSpace.sm))
            PreviewGroupRow(
                palette = palette,
                accent = accent,
                glyph = "⬡",
                name = stringResource(R.string.theme_preview_row_normal),
                subtitle = stringResource(R.string.theme_preview_row_normal_sub),
                selected = false,
                unread = 0
            )

            Spacer(Modifier.height(WxSpace.md))

            // 调色板明细：用户选主题时真正想看的是「这几个色搭在一起什么效果」
            PaletteLegend(palette = palette)

            Spacer(Modifier.height(WxSpace.sm))

            // 控件预览：检查主色上的前景色对比度（浅主色配黑字、深主色配白字）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(WxSize.buttonSmall)
                        .clip(WxShape.control)
                        .background(primary)
                        .padding(horizontal = WxSpace.md),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.theme_preview_button),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(GroupTheme.onColorFor(palette.primary))
                    )
                }
                Spacer(Modifier.width(WxSpace.sm))
                Box(
                    modifier = Modifier
                        .height(WxSize.buttonSmall)
                        .clip(WxShape.control)
                        .background(primary.copy(alpha = 0.12f))
                        .padding(horizontal = WxSpace.md),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.theme_preview_secondary),
                        style = MaterialTheme.typography.labelMedium,
                        color = primary
                    )
                }
                Spacer(Modifier.width(WxSpace.sm))
                // 开关：选中态用主色实心，未选中用次级描边
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 22.dp)
                        .clip(CircleShape)
                        .background(primary),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 3.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(GroupTheme.onColorFor(palette.primary)))
                    )
                }
            }
        }
    }
}

/** 调色板明细：每个色样下面写清它是干嘛的，避免「好看但不知道用在哪」 */
@Composable
private fun PaletteLegend(palette: GroupTheme.Palette) {
    val colors = LocalAppColors.current
    val items = listOf(
        stringResource(R.string.theme_palette_primary) to palette.primary,
        stringResource(R.string.theme_palette_accent) to palette.accent,
        stringResource(R.string.theme_palette_icon) to palette.iconBackground,
        stringResource(R.string.theme_palette_row) to palette.normalBackground,
        stringResource(R.string.theme_palette_text) to palette.textPrimary
    )
    Row(horizontalArrangement = Arrangement.spacedBy(WxSpace.md)) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(value))
                        .border(WxSize.strokeCard, colors.outlineVariant, CircleShape)
                )
                Spacer(Modifier.height(WxSpace.xxs))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        }
    }
}

/* ── 真实行预览组件（与微信里的收纳组行同构）───────────────────────────── */

/**
 * 按给定色板画出微信里的那一「行」，不只是色块。
 *
 * 包含：分组图标（强调色着色 + 图标底色）、名称、副标题、未读角标、
 * 选中态底色与左侧色条 —— 也就是判断「这套主题在微信里好不好看」需要的全部元素。
 */
@Composable
private fun PreviewGroupRow(
    palette: GroupTheme.Palette,
    accent: Int,
    glyph: String,
    name: String,
    subtitle: String,
    selected: Boolean,
    unread: Int
) {
    val rowBg = if (selected) palette.selectedBackground else palette.normalBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WxSize.rowGroup)
            .clip(WxShape.card)
            .background(Color(rowBg))
            .border(WxSize.strokeCard, Color(palette.divider), WxShape.card),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧色条：分组自己的强调色，选中时加粗加长，是「哪个组被打开」的第一眼线索
        Box(
            modifier = Modifier
                .width(if (selected) 4.dp else 3.dp)
                .height(26.dp)
                .padding(start = WxSpace.xxs)
                .clip(CircleShape)
                .background(Color(accent))
        )
        Spacer(Modifier.width(WxSpace.sm))
        Box(
            modifier = Modifier
                .size(WxSize.avatarBox)
                .clip(WxShape.inner)
                .background(Color(palette.iconBackground)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = glyph, color = Color(accent), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(WxSpace.gapIconText))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = Color(palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .height(WxSize.badgeHeight)
                    .width(WxSize.badgeWidthSingle)
                    .clip(CircleShape)
                    .background(BadgeRed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = unread.toString(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.width(WxSpace.md))
    }
}

/* ── 单组配色 ────────────────────────────────────────────────────────────── */

/**
 * 单个分组的配色行：给出当前效果预览 + 候选色 + 「跟随主题」。
 *
 * 选完立刻写回（ViewModel → GroupStore → 通知微信），上面的预览同步变化，
 * 不需要保存按钮 —— 所见即所得。
 */
@Composable
private fun GroupColorRow(
    group: GroupUi,
    customColor: Int?,
    swatches: List<Int>,
    palette: GroupTheme.Palette,
    onPick: (Int?) -> Unit
) {
    val colors = LocalAppColors.current
    // 预览用当前主题的色板：随主题切换一起变，才看得出「单组自定义色 vs 主题默认色」的差别
    val effective = customColor ?: group.iconTint.toInt()
    var expanded by remember { mutableStateOf(false) }

    WxCard {
        Column(modifier = Modifier.padding(WxSpace.md)) {
            PreviewGroupRow(
                palette = palette,
                accent = effective,
                glyph = group.icon,
                name = group.name,
                subtitle = if (customColor == null) {
                    stringResource(R.string.theme_group_following)
                } else {
                    stringResource(R.string.theme_group_custom_color)
                },
                selected = false,
                unread = 0
            )
            Spacer(Modifier.height(WxSpace.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WxSize.touchMin)
                    .clickableOnce(onClick = { expanded = !expanded }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (customColor == null) {
                        stringResource(R.string.theme_group_following)
                    } else {
                        stringResource(R.string.theme_group_custom_color)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                ColorDot(color = Color(effective), selected = false)
            }

            if (expanded) {
                Spacer(Modifier.height(WxSpace.sm))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(WxSpace.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 「跟随主题」：摘掉自定义色，回到按主题轮转的色
                    item {
                        SwatchOption(
                            color = null,
                            selected = customColor == null,
                            label = stringResource(R.string.theme_group_following_short),
                            onPick = onPick
                        )
                    }
                    items(swatches) { color ->
                        SwatchOption(
                            color = color,
                            selected = customColor == color,
                            label = null,
                            onPick = onPick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwatchOption(
    color: Int?,
    selected: Boolean,
    label: String?,
    onPick: (Int?) -> Unit
) {
    val colors = LocalAppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(WxSize.touchMin)
                .clip(CircleShape)
                .clickableOnce(onClick = { onPick(color) }),
            contentAlignment = Alignment.Center
        ) {
            if (color == null) {
                // 「跟随主题」：斜杠符号 + 描边，语义明确，不是「不知道选哪个」
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .border(WxSize.strokeControl, colors.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "A",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
            } else {
                ColorDot(color = Color(color), size = 30.dp, selected = selected)
            }
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
    }
}

/** 顶部导航返回时要复用的一句提示（保持和设置页一致的表述） */
