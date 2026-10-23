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
     * 彩色 emoji 图标。按「办公 / 沟通 / 家庭情感 / 娱乐运动 / 生活 / 学习旅行 /
     * 金融健康科技 / 更多」组织，共 118 个；全部选自各 ROM（含小米 HyperOS）
     * emoji 字体都有的常见字形，避免出豆腐块。
     */
    val ICONS: List<String> = listOf(
        // ── 办公 ──
        "📁", "💼", "📊", "📈", "🗓️", "📌", "✏️", "📎", "🗂️", "📧", "🖊️", "🧾",
        "🗃️", "📋", "📅", "📇", "🖇️", "📤", "📥", "🔖", "🏷️", "🧮", "🗄️", "📦",
        // ── 沟通 ──
        "💬", "📞", "👥", "🤝", "👋", "📢", "🔔", "📮",
        // ── 家庭情感 ──
        "👨‍👩‍👧", "❤️", "💕", "🏠", "👶", "🐾", "🐱", "🐶", "🌸", "💐",
        "🦊", "🐼", "🍀", "🌈", "🧸",
        // ── 娱乐运动 ──
        "🎮", "🎬", "🎵", "🎤", "🎨", "🏀", "⚽", "🎲", "🎯", "🎁",
        "🎸", "🎳", "🏸", "🧩", "🎡", "🏆", "🥇", "🎉", "🎊",
        // ── 生活 ──
        "🍜", "☕", "🍳", "🛒", "💡", "🛠️", "🔧", "🚗", "🌙", "⭐",
        "🍺", "🍰", "🍎", "🥗", "🏦", "🏪", "🏕️", "🧘", "🔥", "❄️",
        // ── 学习旅行 ──
        "📚", "🎓", "📝", "🔬", "🌍", "✈️", "🚄", "🏖️", "🏔️", "🗺️",
        "🛫", "🚕", "🚲", "🚚", "🧭", "🌊", "🌵", "🌻", "🍁", "⛅",
        // ── 金融健康科技 ──
        "💰", "💳", "💎", "🏥", "💊", "🩺", "📱", "💻", "🖥️", "🌐", "🤖", "🔒"
    )

    /**
     * 单色符号图标（**沉浸款**）。
     *
     * 彩色 emoji 在微信会话列表里很跳 —— 一屏全是我们插入的行时，几个彩色 emoji
     * 会把「这是外挂」写在脸上。这批符号用系统字体的**文本呈现**绘制，并且
     * `setTextColor(分组强调色)` 后是纯色的，观感接近微信自己的单色图标：
     * 只靠形状和主题色区分，不靠彩色。
     *
     * 全部避开 Unicode 的 Emoji_Presentation=Yes 字符（⌚⏰⚡❤ 之类会被强制画成彩色），
     * 也避开生僻字形（⬡▤◈ 这类部分 ROM 字体没有，会出豆腐块）。
     */
    val GLYPHS: List<String> = listOf(
        // 星形 / 花纹
        "★", "☆", "✦", "✧", "✱", "✳", "✴", "❖", "✚", "✜", "✤", "❋",
        // 实心 / 空心几何
        "◆", "◇", "●", "○", "▲", "△", "■", "□", "◐", "◑",
        // 方向 / 循环
        "➜", "➤", "↺", "↻", "⇄", "⇅", "↑", "↓",
        // 工具 / 日常物件
        "☰", "⚑", "⚐", "⚙", "✂", "✎", "✏", "☎", "⌂", "☂",
        // 自然 / 声音 / 花
        "☀", "☾", "♫", "♪", "✿", "❀", "⁂"
    )

    /** 图标选择器的两个分组：0 = 单色符号（沉浸），1 = 彩色 emoji */
    fun iconsFor(mode: Int): List<String> = if (mode == 1) ICONS else GLYPHS

    /** 是不是单色符号（决定要不要用主题色给图标着色） */
    fun isGlyph(icon: String): Boolean {
        if (icon.isEmpty()) return false
        // 判断依据：单色符号一定在 GLYPHS 里（emoji 不可能命中），比猜字体表现靠谱
        val bare = icon.trimEnd('️')     // 去掉可能存在的变体选择符
        return GLYPHS.any { it == bare || it == icon }
    }

    /** 未读角标：微信品牌红，主题无关 */
    const val UNREAD_BADGE = 0xFFFA5151.toInt()

    // ==================================================================================
    // 色板：一套主题 = 一组完整语义色（浅/深各一份）
    // ==================================================================================

    // ── 中性基底：所有主题共用 ──────────────────────────────────────────
    // “群组区块不能像贴了一块彩色面板”——所以行底色/分隔线/正文色一律是**微信原生那套中性色**，
    // 主题只负责「色条 + 图标容器 + 选中态」这三处，深浅色也只在这里切换。
    private const val ROW_LIGHT = 0xFFFFFFFF.toInt()      // 微信浅色会话行底
    private const val ROW_DARK = 0xFF191919.toInt()       // 微信深色会话行底
    private const val DIVIDER_LIGHT = 0xFFEDEDED.toInt()  // 比微信原生的线再浅一档，只做区分
    private const val DIVIDER_DARK = 0xFF262626.toInt()
    private const val TEXT_LIGHT = 0xFF191919.toInt()     // 微信标题色
    private const val TEXT_DARK = 0xFFD6D6D6.toInt()
    private const val SUB_LIGHT = 0xFF9A9A9A.toInt()      // 微信摘要色
    private const val SUB_DARK = 0xFF8A8A8A.toInt()
    private const val DISABLED_LIGHT = 0xFFC7C7CC.toInt() // 箭头/次要装饰
    private const val DISABLED_DARK = 0xFF5A5A5E.toInt()

    /**
     * 完整配色。字段名就是语义，取色时不要自己算 —— 需要「同色系更浅/更深」用 [blend]。
     *
     * @param primary            主题主色（模块 App 按钮、已读胶囊文字）
     * @param accent             强调色基准：左侧色条取**分组自己的色板色**，这里给兜底值
     * @param iconBackground     图标容器背景（主题色的浅色版本）
     * @param selectedBackground 选中/打开态的浅色背景（只有当前分组会用）
     * @param normalBackground   普通状态的**中性**背景（白 / 微信深色底），不铺彩色
     * @param textPrimary        群组标题
     * @param textSecondary      成员数量等辅助信息
     * @param divider            极浅分隔线
     * @param cardBg             卡片背景（模块 App 内的预览卡）
     * @param disabled           箭头等需要弱化的元素
     */
    data class Palette(
        val primary: Int,
        val accent: Int,
        val iconBackground: Int,
        val selectedBackground: Int,
        val normalBackground: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val divider: Int,
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

    /**
     * 分组默认图标：未显式指定时按 id 哈希取。
     *
     * 默认给**单色符号**（GLYPHS）而不是彩色 emoji —— 新建的分组一上来就是
     * 沉浸款，想要彩色 emoji 再自己换（长按分组 → 换图标 → 彩色 emoji）。
     */
    fun iconOf(groupId: String): String =
        GLYPHS[(groupId.hashCode() and Int.MAX_VALUE) % GLYPHS.size]

    /**
     * 统一的浅色基底：所有主题共用一套中性色（白底 + 极浅分隔线 + 微信原生字色）。
     * 主题只改 accent / iconBackground / selectedBackground —— 这样切主题时
     * 「布局、尺寸、间距、正文可读性」完全不变，只是重点色换了个色相。
     */
    private fun lightPalette(
        primary: Int,
        accent: Int,
        iconBackground: Int,
        selectedBackground: Int
    ) = Palette(
        primary = primary,
        accent = accent,
        iconBackground = iconBackground,
        selectedBackground = selectedBackground,
        normalBackground = ROW_LIGHT,
        textPrimary = TEXT_LIGHT,
        textSecondary = SUB_LIGHT,
        divider = DIVIDER_LIGHT,
        cardBg = 0xFFFFFFFF.toInt(),
        disabled = DISABLED_LIGHT
    )

    /** 深色基底：行底/分隔线/字色同样统一，只有主题三色随主题变 */
    private fun darkPalette(
        primary: Int,
        accent: Int,
        iconBackground: Int,
        selectedBackground: Int
    ) = Palette(
        primary = primary,
        accent = accent,
        iconBackground = iconBackground,
        selectedBackground = selectedBackground,
        normalBackground = ROW_DARK,
        textPrimary = TEXT_DARK,
        textSecondary = SUB_DARK,
        divider = DIVIDER_DARK,
        cardBg = 0xFF1F1F1F.toInt(),
        disabled = DISABLED_DARK
    )

    // ── 1. 微信经典绿 ──────────────────────────────────────────────────
    private val GREEN_LIGHT = lightPalette(
        primary = 0xFF07C160.toInt(),
        accent = 0xFF07C160.toInt(),
        iconBackground = 0xFFE8F8EF.toInt(),
        selectedBackground = 0xFFF2FBF6.toInt()
    )
    private val GREEN_DARK = darkPalette(
        primary = 0xFF07C160.toInt(),
        accent = 0xFF2BCB70.toInt(),
        iconBackground = 0xFF1E3327.toInt(),
        selectedBackground = 0xFF1B2A22.toInt()
    )

    // ── 2. 极简灰白 ────────────────────────────────────────────────────
    private val MINIMAL_LIGHT = lightPalette(
        primary = 0xFF64748B.toInt(),
        accent = 0xFF94A3B8.toInt(),
        iconBackground = 0xFFF1F3F5.toInt(),
        selectedBackground = 0xFFF7F8F9.toInt()
    )
    private val MINIMAL_DARK = darkPalette(
        primary = 0xFF94A3B8.toInt(),
        accent = 0xFF64748B.toInt(),
        iconBackground = 0xFF262A2F.toInt(),
        selectedBackground = 0xFF212428.toInt()
    )

    // ── 3. 莫兰迪柔彩 ──────────────────────────────────────────────────
    private val MORANDI_LIGHT = lightPalette(
        primary = 0xFF9AA891.toInt(),
        accent = 0xFFB49B8E.toInt(),
        iconBackground = 0xFFEFECE7.toInt(),
        selectedBackground = 0xFFF7F5F2.toInt()
    )
    private val MORANDI_DARK = darkPalette(
        primary = 0xFFA9B6A0.toInt(),
        accent = 0xFFC0A79A.toInt(),
        iconBackground = 0xFF2C2A26.toInt(),
        selectedBackground = 0xFF241F1C.toInt()
    )

    // ── 4. 海盐薄荷 ────────────────────────────────────────────────────
    private val MINT_LIGHT = lightPalette(
        primary = 0xFF2FB39B.toInt(),
        accent = 0xFF7FD1C1.toInt(),
        iconBackground = 0xFFE3F5F1.toInt(),
        selectedBackground = 0xFFF0FAF8.toInt()
    )
    private val MINT_DARK = darkPalette(
        primary = 0xFF4FCBB0.toInt(),
        accent = 0xFF2FB39B.toInt(),
        iconBackground = 0xFF1E3230.toInt(),
        selectedBackground = 0xFF1A2827.toInt()
    )

    // ── 5. 天空蓝 ──────────────────────────────────────────────────────
    private val SKY_LIGHT = lightPalette(
        primary = 0xFF3D8BD4.toInt(),
        accent = 0xFF7FB6E8.toInt(),
        iconBackground = 0xFFE6F0FB.toInt(),
        selectedBackground = 0xFFF2F7FD.toInt()
    )
    private val SKY_DARK = darkPalette(
        primary = 0xFF5AA9E6.toInt(),
        accent = 0xFF3D8BD4.toInt(),
        iconBackground = 0xFF1E2A38.toInt(),
        selectedBackground = 0xFF1A222C.toInt()
    )

    // ── 6. 薰衣草紫 ────────────────────────────────────────────────────
    private val LAVENDER_LIGHT = lightPalette(
        primary = 0xFF8B7BD8.toInt(),
        accent = 0xFFB0A4E8.toInt(),
        iconBackground = 0xFFEEE9FB.toInt(),
        selectedBackground = 0xFFF6F3FD.toInt()
    )
    private val LAVENDER_DARK = darkPalette(
        primary = 0xFFA695EE.toInt(),
        accent = 0xFF8B7BD8.toInt(),
        iconBackground = 0xFF272238.toInt(),
        selectedBackground = 0xFF221E2E.toInt()
    )

    // ── 7. 奶油暖橙 ────────────────────────────────────────────────────
    private val CREAM_LIGHT = lightPalette(
        primary = 0xFFE8934A.toInt(),
        accent = 0xFFF3BE8E.toInt(),
        iconBackground = 0xFFFBEEDF.toInt(),
        selectedBackground = 0xFFFDF6EE.toInt()
    )
    private val CREAM_DARK = darkPalette(
        primary = 0xFFF0A868.toInt(),
        accent = 0xFFE8934A.toInt(),
        iconBackground = 0xFF33261B.toInt(),
        selectedBackground = 0xFF2A2118.toInt()
    )

    // ── 8. 午夜暗色 ────────────────────────────────────────────────────
    // ⚠️「午夜」指的是**强调色偏暗调**，不是把行涂黑：浅色模式下行底仍然是白底，
    //    否则会在微信白色会话列表里插一整块深色面板（正是这一轮要避免的东西）。
    private val MIDNIGHT_LIGHT = lightPalette(
        primary = 0xFF6C7CE0.toInt(),
        accent = 0xFF6C7CE0.toInt(),
        iconBackground = 0xFFEAECFB.toInt(),
        selectedBackground = 0xFFF3F4FE.toInt()
    )
    private val MIDNIGHT_DARK = darkPalette(
        primary = 0xFF8E9BFF.toInt(),
        accent = 0xFF6C7CE0.toInt(),
        iconBackground = 0xFF22252F.toInt(),
        selectedBackground = 0xFF1E212A.toInt()
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
