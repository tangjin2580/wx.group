package io.github.wxgroup.reborn.ui.theme

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.wxgroup.reborn.core.GroupTheme
import io.github.wxgroup.reborn.ui.Motion

/* ══════════════════════════════════════════════════════════════════════════════
 * 颜色令牌 —— 单一配色来源
 *
 * 模块 App 的配色**不自带第二套色板**，全部从 core 的 [GroupTheme] 取：
 * 用户在「外观与主题」里选的 themeId 决定色板，深浅色决定取 light 还是 dark。
 * 于是主题页的预览、App 的各个页面、微信进程里真实渲染的收纳组行，用的是同一份
 * [GroupTheme.Palette] —— 不会出现「预览是薄荷绿、实际是微信绿」的错位。
 *
 * 仍然写死的只有三类**主题无关**的颜色：
 *   1) 页面底色（模块 App 自己的灰底；微信进程里没有对应物）
 *   2) 状态色（成功 / 警告 / 错误，靠语义识别，不跟主题走）
 *   3) 未读角标红（用户靠红色识别未读，恒定）
 *
 * 深浅色必须**各自设计**：每套主题都有独立的 light/dark 色板（见 core.GroupTheme），
 * 深色不是把浅色取反 —— 行底色用微信深色模式的 #191919，正文用 #D6D6D6 而非纯白，
 * 避免 OLED 上刺眼的纯黑白对比。
 * ══════════════════════════════════════════════════════════════════════════════ */

/** 页面底色：浅色沿用 iOS 分组列表的淡灰，深色用中性深灰而非纯黑（OLED 更柔和） */
val PageBgLight = Color(0xFFF2F2F7)
val PageBgDark = Color(0xFF101012)

/** 状态色：成功 / 警告 / 危险（语义色，不随主题变化） */
val StatusSuccess = Color(0xFF26A46C)
val StatusWarning = Color(0xFFE08A18)
val StatusError = Color(0xFFE4483D)

/** 未读角标：微信品牌红，主题无关 */
val BadgeRed = Color(0xFFFA5151)

/** 功能色相：设置项左侧图标底块（区分功能用，不是主题色） */
val IconMint = Color(0xFF2BB38A)
val IconBlue = Color(0xFF3C86EF)
val IconOrange = Color(0xFFF2922F)
val IconPurple = Color(0xFF8C7BEE)
val IconPink = Color(0xFFEF5F79)
val IconTeal = Color(0xFF2FA5AB)

/**
 * 语义化配色。业务代码只读这一处，深浅色 / 主题切换不用到处传参。
 *
 * ── 命名对照（项目内统一）──
 *  pageBg / cardBg / surfaceVariant  背景三档（页面 → 卡片 → 次级容器）
 *  textPrimary / textSecondary / disabled  文字三档
 *  outline / outlineVariant         边框两档（控件 / 分隔线）
 *  primary / onPrimary / accent     主色与其上前景色、强调色
 *  success / warning / error        状态三色
 */
data class AppColors(
    // ── 背景 ──
    val pageBg: Color,
    val cardBg: Color,
    /** 次级容器：内嵌计数块、输入区、分组行的图标底等，比 cardBg 稍深/稍浅一档 */
    val surfaceVariant: Color,
    // ── 文字 ──
    val textPrimary: Color,
    val textSecondary: Color,
    val disabled: Color,
    // ── 线条 ──
    val outline: Color,
    val outlineVariant: Color,
    // ── 状态 ──
    val success: Color,
    val warning: Color,
    val error: Color,
    // ── 主题色（由 GroupTheme.Palette 派生）──
    /** 主色：选中态、主按钮、强调文字 */
    val primary: Color,
    /** 铺在 [primary] 之上的文字色（按亮度自动取黑/白，保证对比度） */
    val onPrimary: Color,
    /** 强调色：色条、图标容器等 */
    val accent: Color,
    /** 图标容器底色（主题色的浅色版本） */
    val iconBackground: Color,
    /** 选中 / 展开态的浅色底 */
    val selectedBackground: Color,
    /** 普通行底色（微信原生中性色，不铺彩色） */
    val normalBackground: Color
)

private fun defaultAppColors(): AppColors = AppColors(
    pageBg = PageBgLight,
    cardBg = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F3F5),
    textPrimary = Color(0xFF191919),
    textSecondary = Color(0xFF8A8A8E),
    disabled = Color(0xFFC7C7CC),
    outline = Color(0x1E000000),
    outlineVariant = Color(0xFFEDEDED),
    success = StatusSuccess,
    warning = StatusWarning,
    error = StatusError,
    primary = IconMint,
    onPrimary = Color(0xFFFFFFFF),
    accent = IconMint,
    iconBackground = Color(0xFFE3F5F1),
    selectedBackground = Color(0xFFF0FAF8),
    normalBackground = Color(0xFFFFFFFF)
)

/** 自定义配色的 CompositionLocal（默认是兜底值，实际总由 [WxRebornTheme] 下发） */
val LocalAppColors = staticCompositionLocalOf { defaultAppColors() }

/** 把 core 的 [GroupTheme.Palette] 翻译成 Compose 语义色 */
private fun appColorsOf(p: GroupTheme.Palette, dark: Boolean): AppColors {
    val primary = Color(p.primary)
    return AppColors(
        pageBg = if (dark) PageBgDark else PageBgLight,
        cardBg = Color(p.cardBg),
        // 次级容器 = 卡片底色朝正文色混 5%：浅色更浅灰、深色更深灰，永远差一档可读
        surfaceVariant = Color(GroupTheme.blend(p.cardBg, p.textPrimary, 0.05f)),
        textPrimary = Color(p.textPrimary),
        textSecondary = Color(p.textSecondary),
        disabled = Color(p.disabled),
        outline = Color(GroupTheme.blend(p.divider, p.textPrimary, 0.20f)),
        outlineVariant = Color(p.divider),
        success = StatusSuccess,
        warning = StatusWarning,
        error = StatusError,
        primary = primary,
        // 复用 core 同一份亮度判断，不在 Compose 侧再写一套
        onPrimary = Color(GroupTheme.onColorFor(p.primary)),
        accent = Color(p.accent),
        iconBackground = Color(p.iconBackground),
        selectedBackground = Color(p.selectedBackground),
        normalBackground = Color(p.normalBackground)
    )
}

@Composable
private fun animColor(target: Color, spec: AnimationSpec<Color>, label: String): Color =
    animateColorAsState(targetValue = target, animationSpec = spec, label = label).value

/**
 * 主题切换的颜色过渡。
 *
 * 用 Compose 原生 [animateColorAsState]：状态切走时动画自动被新状态接管，
 * 不存在「旧动画还在往旧 View 里写颜色」的问题。系统开了「移除动画」时时长取 0，
 * 直接跳终值（这也是 UI 流畅和尊重系统设置的正确做法）。
 */
@Composable
private fun animateAppColors(target: AppColors, durationMs: Int): AppColors {
    val spec: AnimationSpec<Color> = tween(durationMillis = durationMs.coerceAtLeast(0))
    return AppColors(
        pageBg = animColor(target.pageBg, spec, "pageBg"),
        cardBg = animColor(target.cardBg, spec, "cardBg"),
        surfaceVariant = animColor(target.surfaceVariant, spec, "surfaceVariant"),
        textPrimary = animColor(target.textPrimary, spec, "textPrimary"),
        textSecondary = animColor(target.textSecondary, spec, "textSecondary"),
        disabled = animColor(target.disabled, spec, "disabled"),
        outline = animColor(target.outline, spec, "outline"),
        outlineVariant = animColor(target.outlineVariant, spec, "outlineVariant"),
        success = animColor(target.success, spec, "success"),
        warning = animColor(target.warning, spec, "warning"),
        error = animColor(target.error, spec, "error"),
        primary = animColor(target.primary, spec, "primary"),
        onPrimary = animColor(target.onPrimary, spec, "onPrimary"),
        accent = animColor(target.accent, spec, "accent"),
        iconBackground = animColor(target.iconBackground, spec, "iconBackground"),
        selectedBackground = animColor(target.selectedBackground, spec, "selectedBackground"),
        normalBackground = animColor(target.normalBackground, spec, "normalBackground")
    )
}

/** 主题切换过渡时长；系统「移除动画」时为 0（与 Motion 同源，不另立常量） */
@Composable
private fun themeTransitionMs(): Int {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        if (scale <= 0f) 0 else Motion.THEME_MS.toInt()
    }
}

/**
 * 当前是否应该用深色配色。
 *
 * 抽成一个 composable 是为了让「主题页预览」和「主界面」判定一致 ——
 * 以前两处各自判断，出现过「预览是深色、应用是浅色」的情况。
 */
@Composable
fun resolvedDarkTheme(mode: String): Boolean = when (mode) {
    GroupTheme.Mode.LIGHT -> false
    GroupTheme.Mode.DARK -> true
    else -> androidx.compose.foundation.isSystemInDarkTheme()
}

/**
 * 应用主题。
 *
 * @param mode 深浅色模式，取值见 [GroupTheme.Mode]；system 时跟随系统设置。
 * @param themeId 主题 id，取值见 [GroupTheme.Id]；空/未知值由 [GroupTheme.of] 兜底为默认主题。
 * @param content 页面内容。
 */
@Composable
fun WxRebornTheme(
    mode: String = GroupTheme.Mode.SYSTEM,
    themeId: String = GroupTheme.Id.DEFAULT,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        GroupTheme.Mode.LIGHT -> false
        GroupTheme.Mode.DARK -> true
        else -> isSystemInDarkTheme()
    }

    // 主题色板的唯一来源：core 的 GroupTheme（与微信进程渲染、主题页预览同源）
    val palette = GroupTheme.of(themeId).palette(dark)
    val colors = animateAppColors(appColorsOf(palette, dark), themeTransitionMs())
    val onPrimary = colors.onPrimary

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = onPrimary,
            primaryContainer = colors.selectedBackground,
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.accent,
            onSecondary = onPrimary,
            surface = colors.cardBg,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.textSecondary,
            background = colors.pageBg,
            onBackground = colors.textPrimary,
            outline = colors.outline,
            outlineVariant = colors.outlineVariant,
            surfaceContainer = colors.surfaceVariant,
            error = StatusError,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = onPrimary,
            primaryContainer = colors.selectedBackground,
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.accent,
            onSecondary = onPrimary,
            surface = colors.cardBg,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.textSecondary,
            background = colors.pageBg,
            onBackground = colors.textPrimary,
            outline = colors.outline,
            outlineVariant = colors.outlineVariant,
            surfaceContainer = colors.surfaceVariant,
            error = StatusError,
            onError = Color.White
        )
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = WxShape.material,
            content = content
        )
    }
}
