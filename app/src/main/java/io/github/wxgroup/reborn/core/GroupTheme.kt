package io.github.wxgroup.reborn.core

/**
 * 收纳组的外观：图标（emoji）与配色。
 *
 * 为什么用 emoji 而不是去下载图标包：emoji 内置于 Android，零资源体积、零网络依赖、
 * 无版权问题，且「常用图标」（工作/家人/游戏/旅行…）覆盖得足够全。
 * 每个分组默认按 id 哈希从这两张表里取一个，保证不同分组天然长得不一样；
 * 用户也可以在「换图标」菜单里显式指定。
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

    /** 候选配色（ARGB），浅色背景也看得清 */
    val COLORS: List<Int> = listOf(
        0xFF5B8DEF.toInt(), 0xFF9B6BF0.toInt(), 0xFF34B37E.toInt(), 0xFFF0A030.toInt(),
        0xFFE05A6A.toInt(), 0xFF4CC3D9.toInt(), 0xFFB07CC6.toInt(), 0xFF6BA86B.toInt(),
        0xFFE0885A.toInt(), 0xFF5A9BD5.toInt()
    )

    /** 分组默认图标：未显式指定时按 id 哈希取 */
    fun iconOf(groupId: String): String =
        ICONS[(groupId.hashCode() and Int.MAX_VALUE) % ICONS.size]

    /** 分组默认配色：按 id 哈希取（稳定、可区分） */
    fun colorOf(groupId: String): Int =
        COLORS[(groupId.hashCode() and Int.MAX_VALUE) % COLORS.size]
}
