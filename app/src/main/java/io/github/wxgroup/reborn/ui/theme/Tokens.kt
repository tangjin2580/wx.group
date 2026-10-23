package io.github.wxgroup.reborn.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ══════════════════════════════════════════════════════════════════════════════
 * 设计令牌（Design Tokens）
 *
 * 为什么要单独抽一层：
 * 以前「16.dp」「0.5.dp」「17.sp」散落在各个 Composable 里，改一次卡片圆角要翻四五个
 * 文件，还总会有漏网的那个 —— 同一语义在两处出现两个值。
 * 现在**所有**尺寸/字号/圆角只能从下面这几个对象里取，界面代码里不再出现魔法数字。
 *
 * ── 分层 ──
 *   [WxSpace]  间距：页面留白、卡片内边距、图标与文字的间隙
 *   [WxShape]  圆角：容器 / 控件 / 胶囊，三档层级
 *   [WxSize]   控件尺寸：触摸区、图标、按钮、列表行
 *   [AppTypography] 字号：标题 / 正文 / 辅助 / 数字
 *
 * 颜色令牌在 [AppColors]（随主题变化）；这些是**与主题无关**的度量令牌。
 * ══════════════════════════════════════════════════════════════════════════════ */

/**
 * 间距令牌：4dp 为基数，按需取。
 *
 * @property screen 页面左右留白（所有列表 / 卡片统一，保证边缘对齐）
 * @property card 卡片内边距（横向）
 * @property row 列表行内边距（横向）
 * @property gapIconText 图标与文字之间的固定间隙
 */
object WxSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp        // 页面留白 / 卡片横向内边距
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    val screen = lg       // 页面左右边距
    val betweenCards = xl // 相邻卡片 / 区块之间的垂直间距
    val gapIconText = md  // 图标 → 文字
    val gapTextIcon = xs  // 文字 → 右侧图标
}

/**
 * 圆角令牌：三档层级，避免同一界面出现 12/13/14dp 这种参差。
 *
 * @property card 大容器（卡片、分组行、对话框）
 * @property control 中等控件（按钮、输入框、图标底块）
 * @property pill 胶囊（角标、状态标签）
 */
object WxShape {
    val card: CornerBasedShape = RoundedCornerShape(16.dp)
    val cardTop: CornerBasedShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val control: CornerBasedShape = RoundedCornerShape(12.dp)
    val field: CornerBasedShape = RoundedCornerShape(12.dp)
    val inner: CornerBasedShape = RoundedCornerShape(7.dp)   // 图标底块
    val pill: CornerBasedShape = CircleShape

    /** 装进 Material3 的 Shapes：Card 默认走 medium 形状 */
    val material: Shapes = Shapes(
        extraSmall = control,
        small = control,
        medium = card,
        large = card,
        extraLarge = card
    )
}

/**
 * 尺寸令牌：组件高度、图标大小、触摸区。
 *
 * ── 触摸区 ──
 * [touchMin] 是可点击元素的最小边长（48dp，Android 无障碍规范下限）。
 * 视觉图标往往只有 20dp，但必须给它撑够 48dp 的点击区：
 * 「图标可视尺寸」和「实际可点区域」分开设计，既不影响视觉密度也好按。
 */
object WxSize {
    val touchMin = 48.dp      // 最小触摸目标
    val touchCompact = 40.dp  // 布局实在挤不下时的下限（如拖动手柄）

    val iconRow = 20.dp       // 行内图标视觉尺寸
    val iconNav = 24.dp       // 底部导航图标
    val iconSmall = 18.dp     // 图标底块内的矢量图标
    val iconInBlock = 18.dp

    val iconBox = 30.dp       // 图标底块（正方形容器）
    val avatarBox = 36.dp     // 分组图标（比设置项图标稍大一级）

    val rowItem = 52.dp       // 设置项最小行高
    val rowGroup = 64.dp      // 分组列表行高（拖动排序依赖等高）
    val rowHero = 72.dp       // 仪表盘主状态行
    val button = 44.dp        // 按钮高度
    val buttonSmall = 36.dp

    val badgeHeight = 20.dp
    val badgeWidthSingle = 20.dp
    val badgeWidthDouble = 28.dp

    val strokeCard = 0.5.dp   // 卡片描边（iOS 风格细线）
    val strokeControl = 1.dp  // 控件描边
    val elevationNone = 0.dp
}

/**
 * 排版令牌：字号与字重的组合，角色固定。
 *
 * - [displayLarge]  大标题（页面 Large Title）
 * - [titleMedium]   列表标题 / 卡片标题（17sp）
 * - [bodyMedium]    正文（15sp）
 * - [bodySmall]     辅助说明（13sp）
 * - [labelLarge]    按钮文字（15sp Medium）
 * - [titleSmall]    区块小标题（13sp Medium，用于分组名）
 *
 * 数字（成员数 / 未读数）默认继承所在层级，不单独设数字字体 —— 本项目没有
 * 表格/统计类排版需求，等宽数字反而会让中文界面显得僵硬。
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
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
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )
)

/**
 * 兼容旧引用：以前各文件直接写 `ui.theme.CardShape`，现在统一指向 [WxShape.card]。
 * 新代码请直接用 [WxShape.card]，保留常量只是为了让改动不必一次铺开到所有文件。
 */
val CardShape: Shape = WxShape.card
