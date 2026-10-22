package io.github.wxgroup.reborn.core

import android.graphics.Color
import kotlin.math.abs

/**
 * 收纳组的外观模型：**图标 + 主题色板 + 单组配色**。
 *
 * ── 为什么要集中在这里 ──
 * 以前颜色散落在 FolderRow（行底色/图标底/字色各写一份常量）、GroupActions（对话框字色）
 * 等处，改一次配色要翻好几个文件，还容易出现「同一个语义两种色值」。
 * 现在所有颜色只有一个来源：[Theme]，任何 View / Hook 都只能从这里取色。
 *
 * ── 主题切换的优先级 ──
 *   单组自定义颜色（WxSettings.groupColors）> 当前主题色板 > 默认（微信经典绿）
 * 单组颜色**不写进 WxGroup**，而是单独存在 WxSettings 里 —— 分组数据（成员/顺序/签名）
 * 保持纯净，导入导出、微信侧落盘都不受主题升级影响。
 *
 * ── 未读角标 ──
 * 未读数/红点固定用微信品牌红 [UNREAD_BADGE]，**不随主题变化**（用户靠红色识别未读）。
 */
object GroupTheme {

    /**
     * 候选图标（emoji），换图标时按顺序切换。按「办公 / 沟通 / 家庭情感 /
     * 娱乐运动 / 生活 / 学习旅行 / 金融健康科技」七个类别组织，共 72 个；
     * 全部选自各 ROM（含小米 HyperOS）emoji 字体都有的常见字形，避免出豆腐块。
     */
    val ICONS: List<String> = listOf(
        // ── 办公 ──
        "📁", "💼", "📊", "📈", "🗓️", "📌", "✏️", "📎", "🗂️", "📧", "🖊️", "🧾",
        // ── 沟通 ──
        "💬", "📞", "👥", "🤝", "👋", "📢", "🔔", "📮",
        // ── 家庭情感 ──
        "👨‍👩‍👧", "❤️", "💕", "🏠", "👶", "🐾", "🐱", "🐶", "🌸", "💐",
        // ── 娱乐运动 ──
        "🎮", "🎬", "🎵", "🎤", "🎨", "🏀", "⚽", "🎲", "🎯", "🎁",
        // ── 生活 ──
        "🍜", "☕", "🍳", "🛒", "💡", "🛠️", "🔧", "🚗", "🌙", "⭐",
        // ── 学习旅行 ──
        "📚", "🎓", "📝", "🔬", "🌍", "✈️", "🚄", "🏖️", "🏔️", "🗺️",
        // ── 金融健康科技 ──
        "💰", "💳", "💎", "🏥", "💊", "🩺", "📱", "💻", "🖥️", "🌐", "🤖", "🔒"
    )

    /** 未读角标：微信品牌红，主题无关 */
    const val UNREAD_BADGE = 0xFFFA5151.toInt()

    // ==================================================================================
    // 色板：一套主题 = 一组完整语义色（浅/深各一份）
    // ==================================================================================

    /**
     * 完整配色。字段名就是语义，取色时不要自己算 —— 需要「同色系更浅/更深」用 [Palette.tint]。
     *
     * @param primary  主题主色（按钮、选中态文字）
     * @param accent   左侧强调色（色条 / 图标边框），同色系里饱和度最高的一档
     * @param groupBg  群组行底色（**低饱和**：主色混入大量底色，只留一点色相）
     * @param iconBg   图标容器背景
     * @param title    主要文字
     * @param sub      辅助文字
     * @param divider  分隔线
     * @param selected 选中/打开态背景
     * @param cardBg   卡片背景（模块 App 内的预览卡）
     * @param disabled 禁用态
     */
    data class Palette(
        val primary: Int,
        val accent: Int,
        val groupBg: Int,
        val iconBg: Int,
        val title: Int,
        val sub: Int,
        val divider: Int,
        val selected: Int,
        val cardBg: Int,
        val disabled: Int
    )

    /** 主题标识（存 JSON 用这个字符串，别存序号 —— 以后插入新主题序号会错位） */
    object Id {
        const val WECHAT_GREEN = "wechat_green"
        const val MINIMAL = "minimal"
        const val MORANDI = "morandi"
        const val MINT = "mint"
        const val SKY = "sky"
        const val LAVENDER = "lavender"
        const val CREAM = "cream"
        const val MIDNIGHT = "midnight"
        const val DEFAULT = WECHAT_GREEN
    }

    /** 深浅色模式：跟随系统 / 强制浅色 / 强制深色 */
    object Mode {
        const val SYSTEM = "system"
        const val LIGHT = "light"
        const val DARK = "dark"
        const val DEFAULT = SYSTEM
    }

    data class Theme(
        val id: String,
        val label: String,
        val light: Palette,
        val dark: Palette,
        /** 单组配色候选（按显示顺序轮转，保证相邻分组颜色不接近） */
        val swatches: List<Int>
    ) {
        fun palette(isDark: Boolean): Palette = if (isDark) dark else light
    }

    /** 分组默认图标：未显式指定时按 id 哈希取（稳定、不同分组天然不一样） */
    fun iconOf(groupId: String): String =
        ICONS[(groupId.hashCode() and Int.MAX_VALUE) % ICONS.size]

    // ── 1. 微信经典绿 ──────────────────────────────────────────────────
    private val GREEN_LIGHT = Palette(
        primary = 0xFF07C160.toInt(),
        accent = 0xFF07C160.toInt(),
        groupBg = 0xFFF6FBF8.toInt(),
        iconBg = 0xFFE8F8EF.toInt(),
        title = 0xFF191919.toInt(),
        sub = 0xFF9A9A9A.toInt(),
        divider = 0xFFEDEDED.toInt(),
        selected = 0xFFE8F8EF.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = 0xFFC7C7CC.toInt()
    )
    private val GREEN_DARK = Palette(
        primary = 0xFF07C160.toInt(),
        accent = 0xFF2BCB70.toInt(),
        groupBg = 0xFF181D1A.toInt(),
        iconBg = 0xFF1E3327.toInt(),
        title = 0xFFD6D6D6.toInt(),
        sub = 0xFF8A8A8A.toInt(),
        divider = 0xFF262626.toInt(),
        selected = 0xFF1E3327.toInt(),
        cardBg = 0xFF1F1F1F.toInt(),
        disabled = 0xFF5A5A5E.toInt()
    )

    // ── 2. 极简灰白 ────────────────────────────────────────────────────
    private val MINIMAL_LIGHT = Palette(
        primary = 0xFF64748B.toInt(),
        accent = 0xFF94A3B8.toInt(),
        groupBg = 0xFFFAFAFB.toInt(),
        iconBg = 0xFFF1F3F5.toInt(),
        title = 0xFF1F2937.toInt(),
        sub = 0xFF8A94A6.toInt(),
        divider = 0xFFEDEFF2.toInt(),
        selected = 0xFFEFF1F4.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = 0xFFC7CBD1.toInt()
    )
    private val MINIMAL_DARK = Palette(
        primary = 0xFF94A3B8.toInt(),
        accent = 0xFF64748B.toInt(),
        groupBg = 0xFF1A1C1F.toInt(),
        iconBg = 0xFF262A2F.toInt(),
        title = 0xFFE2E6EA.toInt(),
        sub = 0xFF8C939C.toInt(),
        divider = 0xFF2A2E33.toInt(),
        selected = 0xFF262A2F.toInt(),
        cardBg = 0xFF202226.toInt(),
        disabled = 0xFF5A5E64.toInt()
    )

    // ── 3. 莫兰迪柔彩 ──────────────────────────────────────────────────
    private val MORANDI_LIGHT = Palette(
        primary = 0xFF9AA891.toInt(),
        accent = 0xFFB49B8E.toInt(),
        groupBg = 0xFFF8F7F5.toInt(),
        iconBg = 0xFFEFECE7.toInt(),
        title = 0xFF3A3733.toInt(),
        sub = 0xFF908B84.toInt(),
        divider = 0xFFEAE7E2.toInt(),
        selected = 0xFFEBE6DF.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = 0xFFC8C3BC.toInt()
    )
    private val MORANDI_DARK = Palette(
        primary = 0xFFA9B6A0.toInt(),
        accent = 0xFFC0A79A.toInt(),
        groupBg = 0xFF1C1B19.toInt(),
        iconBg = 0xFF2C2A26.toInt(),
        title = 0xFFDFDAD3.toInt(),
        sub = 0xFF938D85.toInt(),
        divider = 0xFF2E2C28.toInt(),
        selected = 0xFF2C2A26.toInt(),
        cardBg = 0xFF232120.toInt(),
        disabled = 0xFF5C5852.toInt()
    )

    // ── 4. 海盐薄荷 ────────────────────────────────────────────────────
    private val MINT_LIGHT = Palette(
        primary = 0xFF2FB39B.toInt(),
        accent = 0xFF7FD1C1.toInt(),
        groupBg = 0xFFF5FBFA.toInt(),
        iconBg = 0xFFE3F5F1.toInt(),
        title = 0xFF1A3330.toInt(),
        sub = 0xFF7E9C97.toInt(),
        divider = 0xFFE6F1EF.toInt(),
        selected = 0xFFDEF2EE.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = 0xFFB9C9C5.toInt()
    )
    private val MINT_DARK = Palette(
        primary = 0xFF4FCBB0.toInt(),
        accent = 0xFF2FB39B.toInt(),
        groupBg = 0xFF171F1E.toInt(),
        iconBg = 0xFF1E3230.toInt(),
        title = 0xFFD5E5E2.toInt(),
        sub = 0xFF7E9C97.toInt(),
        divider = 0xFF23302E.toInt(),
        selected = 0xFF1E3230.toInt(),
        cardBg = 0xFF1D2423.toInt(),
        disabled = 0xFF4E605D.toInt()
    )

    // ── 5. 天空蓝 ──────────────────────────────────────────────────────
    private val SKY_LIGHT = Palette(
        primary = 0xFF3D8BD4.toInt(),
        accent = 0xFF7FB6E8.toInt(),
        groupBg = 0xFFF5F9FE.toInt(),
        iconBg = 0xFFE6F0FB.toInt(),
        title = 0xFF1B2A3A.toInt(),
        sub = 0xFF7E93AC.toInt(),
        divider = 0xFFE5EDF7.toInt(),
        selected = 0xFFE1EDFA.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = 0xFFB7C6D8.toInt()
    )
    private val SKY_DARK = Palette(
        primary = 0xFF5AA9E6.toInt(),
        accent = 0xFF3D8BD4.toInt(),
        groupBg = 0xFF171C22.toInt(),
        iconBg = 0xFF1E2A38.toInt(),
        title = 0xFFD5E2F0.toInt(),
        sub = 0xFF7E93AC.toInt(),
        divider = 0xFF253141.toInt(),
        selected = 0xFF1E2A38.toInt(),
        cardBg = 0xFF1D232A.toInt(),
        disabled = 0xFF4E5C6B.toInt()
    )

    // ── 6. 薰衣草紫 ────────────────────────────────────────────────────
    private val LAVENDER_LIGHT = Palette(
        primary = 0xFF8B7BD8.toInt(),
        accent = 0xFFB0A4E8.toInt(),
        groupBg = 0xFFF8F6FD.toInt(),
        iconBg = 0xFFEEE9FB.toInt(),
        title = 0xFF2A2440.toInt(),
        sub = 0xFF8E86A6.toInt(),
        divider = 0xFFEBE6F7.toInt(),
        selected = 0xFFE8E1FA.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = 0xFFC1BAD4.toInt()
    )
    private val LAVENDER_DARK = Palette(
        primary = 0xFFA695EE.toInt(),
        accent = 0xFF8B7BD8.toInt(),
        groupBg = 0xFF1B1922.toInt(),
        iconBg = 0xFF272238.toInt(),
        title = 0xFFDFD9F0.toInt(),
        sub = 0xFF8E86A6.toInt(),
        divider = 0xFF2A2438.toInt(),
        selected = 0xFF272238.toInt(),
        cardBg = 0xFF211E29.toInt(),
        disabled = 0xFF5C5570.toInt()
    )

    // ── 7. 奶油暖橙 ────────────────────────────────────────────────────
    private val CREAM_LIGHT = Palette(
        primary = 0xFFE8934A.toInt(),
        accent = 0xFFF3BE8E.toInt(),
        groupBg = 0xFFFEF8F2.toInt(),
        iconBg = 0xFFFBEEDF.toInt(),
        title = 0xFF3A2C1E.toInt(),
        sub = 0xFFA08D77.toInt(),
        divider = 0xFFF4E9DC.toInt(),
        selected = 0xFFFAEEDF.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = 0xFFD6C6B4.toInt()
    )
    private val CREAM_DARK = Palette(
        primary = 0xFFF0A868.toInt(),
        accent = 0xFFE8934A.toInt(),
        groupBg = 0xFF201A14.toInt(),
        iconBg = 0xFF33261B.toInt(),
        title = 0xFFF0E2D2.toInt(),
        sub = 0xFFA08D77.toInt(),
        divider = 0xFF332A21.toInt(),
        selected = 0xFF33261B.toInt(),
        cardBg = 0xFF262019.toInt(),
        disabled = 0xFF6B5B4A.toInt()
    )

    // ── 8. 午夜暗色（本身就是深色主题，浅色档也保持深色）──────────────
    private val MIDNIGHT_LIGHT = Palette(
        primary = 0xFF7C8CF0.toInt(),
        accent = 0xFF5A6AD8.toInt(),
        groupBg = 0xFF14161C.toInt(),
        iconBg = 0xFF22252F.toInt(),
        title = 0xFFE6E9F2.toInt(),
        sub = 0xFF8A90A0.toInt(),
        divider = 0xFF232733.toInt(),
        selected = 0xFF22252F.toInt(),
        cardBg = 0xFF1A1D25.toInt(),
        disabled = 0xFF4A4F5C.toInt()
    )
    private val MIDNIGHT_DARK = Palette(
        primary = 0xFF8E9BFF.toInt(),
        accent = 0xFF6C7CE0.toInt(),
        groupBg = 0xFF101218.toInt(),
        iconBg = 0xFF1D202A.toInt(),
        title = 0xFFE6E9F2.toInt(),
        sub = 0xFF8A90A0.toInt(),
        divider = 0xFF1E212A.toInt(),
        selected = 0xFF1D202A.toInt(),
        cardBg = 0xFF161922.toInt(),
        disabled = 0xFF464B58.toInt()
    )

    /** 全部内置主题（顺序 = 设置页里的展示顺序） */
    val THEMES: List<Theme> = listOf(
        Theme(
            Id.WECHAT_GREEN, "微信经典绿", GREEN_LIGHT, GREEN_DARK,
            listOf(0xFF07C160.toInt(), 0xFF4CC3D9.toInt(), 0xFF5B8DEF.toInt(), 0xFFF0A030.toInt(),
                0xFF9B6BF0.toInt(), 0xFF34B37E.toInt(), 0xFFE05A6A.toInt(), 0xFF6BA86B.toInt())
        ),
        Theme(
            Id.MINIMAL, "极简灰白", MINIMAL_LIGHT, MINIMAL_DARK,
            listOf(0xFF64748B.toInt(), 0xFF94A3B8.toInt(), 0xFF7C8794.toInt(), 0xFFADB6C2.toInt(),
                0xFF55606D.toInt(), 0xFF8E99A6.toInt())
        ),
        Theme(
            Id.MORANDI, "莫兰迪柔彩", MORANDI_LIGHT, MORANDI_DARK,
            listOf(0xFF9AA891.toInt(), 0xFFB49B8E.toInt(), 0xFFA3A8C0.toInt(), 0xFFC7B8A8.toInt(),
                0xFF8FA3A8.toInt(), 0xFFBFA8B4.toInt())
        ),
        Theme(
            Id.MINT, "海盐薄荷", MINT_LIGHT, MINT_DARK,
            listOf(0xFF2FB39B.toInt(), 0xFF7FD1C1.toInt(), 0xFF4FCBB0.toInt(), 0xFF6BC5D2.toInt(),
                0xFF57B894.toInt(), 0xFF93D6C8.toInt())
        ),
        Theme(
            Id.SKY, "天空蓝", SKY_LIGHT, SKY_DARK,
            listOf(0xFF3D8BD4.toInt(), 0xFF7FB6E8.toInt(), 0xFF5AA9E6.toInt(), 0xFF6F9FE0.toInt(),
                0xFF4FA5C9.toInt(), 0xFF93C4F0.toInt())
        ),
        Theme(
            Id.LAVENDER, "薰衣草紫", LAVENDER_LIGHT, LAVENDER_DARK,
            listOf(0xFF8B7BD8.toInt(), 0xFFB0A4E8.toInt(), 0xFFA695EE.toInt(), 0xFF9C8CDD.toInt(),
                0xFFC0A8E4.toInt(), 0xFF7F74C8.toInt())
        ),
        Theme(
            Id.CREAM, "奶油暖橙", CREAM_LIGHT, CREAM_DARK,
            listOf(0xFFE8934A.toInt(), 0xFFF3BE8E.toInt(), 0xFFEFB27A.toInt(), 0xFFE8A65F.toInt(),
                0xFFD98A5E.toInt(), 0xFFF0A868.toInt())
        ),
        Theme(
            Id.MIDNIGHT, "午夜暗色", MIDNIGHT_LIGHT, MIDNIGHT_DARK,
            listOf(0xFF7C8CF0.toInt(), 0xFF5A6AD8.toInt(), 0xFF8E9BFF.toInt(), 0xFF6F7FE6.toInt(),
                0xFF9A86E8.toInt(), 0xFF5566C0.toInt())
        )
    )

    /** 默认主题的候选配色（兼容旧引用的写法） */
    val COLORS: List<Int> = THEMES.first().swatches

    /** 按 id 取主题；id 为空/未知（旧配置、手改坏了）时回退默认主题，绝不返回 null */
    fun of(id: String?): Theme = THEMES.firstOrNull { it.id == id } ?: THEMES.first { it.id == Id.DEFAULT }

    fun labelOf(id: String?): String = of(id).label

    /**
     * 解析某个分组最终使用的强调色：
     *   单组自定义 > 主题色板按显示顺序轮转 > 主题主色
     *
     * @param index 分组在**显示顺序**里的下标（保证相邻分组拿到不同色）
     */
    fun colorForGroup(groupId: String, index: Int, settings: WxSettings): Int {
        settings.groupColors[groupId]?.let { return it }
        val t = of(settings.themeId)
        val sw = t.swatches
        if (sw.isEmpty()) return t.light.primary
        // 下标可能为负（分组不在列表里），取模前先归正
        val i = if (index < 0) (groupId.hashCode() and Int.MAX_VALUE) else index
        return sw[i % sw.size]
    }

    /** 该分组是不是「跟随主题」（没有单独的自定义色） */
    fun isFollowingTheme(groupId: String, settings: WxSettings): Boolean = !settings.groupColors.containsKey(groupId)

    // ==================================================================================
    // 颜色工具（所有派生色都走这里，避免各处自己算术）
    // ==================================================================================

    /** 把 [to] 按 [ratio]（0–1）混进 [base]，用来生成「同色系但更浅/更低饱和」的派生色 */
    fun blend(base: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val a = Color.alpha(base)
        return Color.argb(
            a,
            (Color.red(base) + (Color.red(to) - Color.red(base)) * r).toInt().coerceIn(0, 255),
            (Color.green(base) + (Color.green(to) - Color.green(base)) * r).toInt().coerceIn(0, 255),
            (Color.blue(base) + (Color.blue(to) - Color.blue(base)) * r).toInt().coerceIn(0, 255)
        )
    }

    /** 两个颜色是否「过于接近」（给相邻分组挑色时用来跳过相近色） */
    fun closeEnough(a: Int, b: Int, threshold: Int = 24): Boolean =
        abs(Color.red(a) - Color.red(b)) < threshold &&
            abs(Color.green(a) - Color.green(b)) < threshold &&
            abs(Color.blue(a) - Color.blue(b)) < threshold

    /** "#AARRGGBB" / "#RRGGBB" → Int；解析失败返回 [fallback]（主题配置是用户可编辑的，必须容错） */
    fun parseColor(text: String?, fallback: Int): Int {
        if (text.isNullOrBlank()) return fallback
        return try {
            Color.parseColor(text)
        } catch (t: Throwable) {
            fallback
        }
    }

    /** Int → "#AARRGGBB"（存 JSON 用字符串，避免 Java int 符号带来的歧义） */
    fun colorHex(color: Int): String = String.format("#%08X", color)
}
