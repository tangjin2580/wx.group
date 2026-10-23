package io.github.wxgroup.reborn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ══════════════════════════════════════════════════════════════════════════════
 * 色彩令牌 —— 「海盐薄荷」主题
 *
 * 全部写死颜色值，不走系统动态取色（dynamicColor）：
 * 模块 App 的取色必须和微信进程里分组行的渲染保持一致，动态取色在两端
 * 会得到不同结果，会让「预览」和「实际」对不上。
 * ══════════════════════════════════════════════════════════════════════════════ */

/** 页面底色：亮色 #F2F2F7（iOS 分组列表的淡灰底），深色纯黑 */
val PageBgLight = Color(0xFFF2F2F7)
val PageBgDark = Color(0xFF000000)

/** 卡片底色：亮色纯白，深色 #1C1C1E（≈ iOS 深色下的卡片） */
val CardBgLight = Color(0xFFFFFFFF)
val CardBgDark = Color(0xFF1C1C1E)

/** 卡片描边：0.5dp 极淡描边（亮色 ~12% 黑 / 深色 ~16% 白），不用阴影 */
val CardStrokeLight = Color(0x1E000000)
val CardStrokeDark = Color(0x29FFFFFF)

/** 文字：主文字 + 副文字（副标题用 iOS secondaryLabel 的灰） */
val TextPrimaryLight = Color(0xFF000000)
val TextPrimaryDark = Color(0xFFFFFFFF)
val TextSecondaryLight = Color(0xFF8A8A8E)
val TextSecondaryDark = Color(0xFF8E8E93)

/** 主色（薄荷绿）：浅色下压深一点保证白底对比度，深色下提亮避免发闷 */
val Mint40 = Color(0xFF2BB38A)
val Mint80 = Color(0xFF57D3A6)
val OnMint = Color(0xFFFFFFFF)

/** 状态色：成功绿 / 警告橙 / 错误红（与主题主色区分开，靠语义识别） */
val StatusSuccess = Color(0xFF26A46C)
val StatusWarning = Color(0xFFF0A137)
val StatusError = Color(0xFFF04E45)

/** 图标底块的色相：不同功能不同色，统一低饱和「海盐」调 */
val IconMint = Color(0xFF2BB38A)
val IconBlue = Color(0xFF3C86EF)
val IconOrange = Color(0xFFF2922F)
val IconPurple = Color(0xFF8C7BEE)
val IconPink = Color(0xFFEF5F79)
val IconTeal = Color(0xFF2FA5AB)

/**
 * 自定义配色：页面/卡片/描边/文字/状态色。
 * 用 CompositionLocal 下发，业务代码里只读一处，深浅色切换时不用到处传参。
 */
data class AppColors(
    val pageBg: Color,
    val cardBg: Color,
    val cardStroke: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val error: Color
)

private val LightAppColors = AppColors(
    pageBg = PageBgLight,
    cardBg = CardBgLight,
    cardStroke = CardStrokeLight,
    textPrimary = TextPrimaryLight,
    textSecondary = TextSecondaryLight,
    success = StatusSuccess,
    warning = StatusWarning,
    error = StatusError
)

private val DarkAppColors = AppColors(
    pageBg = PageBgDark,
    cardBg = CardBgDark,
    cardStroke = CardStrokeDark,
    textPrimary = TextPrimaryDark,
    textSecondary = TextSecondaryDark,
    success = StatusSuccess,
    warning = StatusWarning,
    error = StatusError
)

/** 自定义配色的 CompositionLocal（默认亮色） */
val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** 卡片圆角 16dp */
val CardShape = RoundedCornerShape(16.dp)

/**
 * 应用主题：海盐薄荷绿 + 支持浅色/深色/跟随系统。
 *
 * @param mode 深浅色模式，取值见 core 里的 [io.github.wxgroup.reborn.core.GroupTheme.Mode]。
 * @param content 页面内容。
 */
@Composable
fun WxRebornTheme(
    mode: String = io.github.wxgroup.reborn.core.GroupTheme.Mode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        io.github.wxgroup.reborn.core.GroupTheme.Mode.LIGHT -> false
        io.github.wxgroup.reborn.core.GroupTheme.Mode.DARK -> true
        else -> isSystemInDarkTheme()
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = Mint80,
            onPrimary = Color(0xFF003524),
            primaryContainer = Color(0xFF0A3D2E),
            onPrimaryContainer = Mint80,
            secondary = Color(0xFF4FD6C1),
            surface = CardBgDark,
            onSurface = TextPrimaryDark,
            surfaceVariant = Color(0xFF2C2C2E),
            onSurfaceVariant = TextSecondaryDark,
            background = PageBgDark,
            onBackground = TextPrimaryDark,
            outline = CardStrokeDark
        )
    } else {
        lightColorScheme(
            primary = Mint40,
            onPrimary = OnMint,
            primaryContainer = Color(0xFFDFF5EC),
            onPrimaryContainer = Color(0xFF0B6B50),
            secondary = Color(0xFF1F9E86),
            surface = CardBgLight,
            onSurface = TextPrimaryLight,
            surfaceVariant = Color(0xFFF2F2F7),
            onSurfaceVariant = TextSecondaryLight,
            background = PageBgLight,
            onBackground = TextPrimaryLight,
            outline = CardStrokeLight
        )
    }

    val appColors = if (dark) DarkAppColors else LightAppColors

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            // Card 默认走 medium 形状，这里统一给 16dp 圆角
            shapes = Shapes(medium = CardShape, large = CardShape),
            content = content
        )
    }
}

/**
 * 排版规范（按视觉规范里的字号令牌）：
 * - 大标题 34sp 粗体（displayLarge）
 * - 条目标题 17sp（titleMedium）
 * - 副标题 13sp（bodySmall），颜色在调用处取 textSecondary
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 41.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )
)
