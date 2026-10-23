package io.github.wxgroup.reborn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题取色的回归测试。
 *
 * 只测不依赖 Android 框架的纯逻辑（[GroupTheme] 的色板是 Int 常量，
 * [WxSettings] 是纯 data class，都不碰 android.graphics / org.json）。
 *
 * 重点是这条优先级：**单组自定义颜色 > 主题色板轮转 > 主题主色** ——
 * 也就是「切主题不能把用户自己给分组挑的颜色冲掉」。
 */
class GroupThemeTest {

    @Test
    fun `未知主题 id 回退到默认主题`() {
        assertEquals(GroupTheme.Id.DEFAULT, GroupTheme.of(null).id)
        assertEquals(GroupTheme.Id.DEFAULT, GroupTheme.of("").id)
        assertEquals(GroupTheme.Id.DEFAULT, GroupTheme.of("不存在的主题").id)
        assertEquals(GroupTheme.Id.MINT, GroupTheme.of(GroupTheme.Id.MINT).id)
    }

    @Test
    fun `单组自定义颜色优先于主题色板`() {
        val custom = 0xFF123456.toInt()
        val settings = WxSettings(
            themeId = GroupTheme.Id.MINT,
            groupColors = mapOf("g1" to custom)
        )
        // 无论它排第几、换什么主题，自定义色都必须赢
        assertEquals(custom, GroupTheme.colorForGroup("g1", 0, settings))
        assertEquals(custom, GroupTheme.colorForGroup("g1", 3, settings))
        assertEquals(
            custom,
            GroupTheme.colorForGroup("g1", 0, settings.copy(themeId = GroupTheme.Id.SKY))
        )
        assertFalse(GroupTheme.isFollowingTheme("g1", settings))
    }

    @Test
    fun `切主题只影响跟随主题的分组`() {
        val settings = WxSettings(themeId = GroupTheme.Id.MINT)
        val underMint = GroupTheme.colorForGroup("g1", 0, settings)
        val underSky = GroupTheme.colorForGroup(
            "g1", 0, settings.copy(themeId = GroupTheme.Id.SKY)
        )
        assertNotEquals(underMint, underSky)
        assertTrue(GroupTheme.isFollowingTheme("g1", settings))
    }

    @Test
    fun `色板按顺序轮转且相邻分组不同色`() {
        val settings = WxSettings(themeId = GroupTheme.Id.WECHAT_GREEN)
        val swatches = GroupTheme.of(GroupTheme.Id.WECHAT_GREEN).swatches
        val first = GroupTheme.colorForGroup("g1", 0, settings)
        val second = GroupTheme.colorForGroup("g2", 1, settings)
        assertEquals(swatches[0], first)
        assertEquals(swatches[1], second)
        assertNotEquals(first, second)
    }

    @Test
    fun `下标为负也能取到合法颜色`() {
        val settings = WxSettings(themeId = GroupTheme.Id.WECHAT_GREEN)
        val swatches = GroupTheme.of(GroupTheme.Id.WECHAT_GREEN).swatches
        val c = GroupTheme.colorForGroup("g1", -1, settings)
        assertTrue(swatches.contains(c))
    }

    @Test
    fun `深浅色两套色板都完整`() {
        for (t in GroupTheme.THEMES) {
            assertTrue(t.swatches.isNotEmpty())
            // 主题只改重点色，中性基底（行底 / 分隔线 / 字色）各主题共用，
            // 这样切主题不会让微信里的行突然变成一块彩色面板
            assertEquals(0xFFFFFFFF.toInt(), t.light.normalBackground)
            assertEquals(0xFF191919.toInt(), t.dark.normalBackground)
            assertTrue(t.light.primary != t.light.normalBackground)
            assertTrue(t.dark.primary != t.dark.normalBackground)
        }
    }

    // 注：[GroupTheme.onColorFor] / [GroupTheme.blend] 内部走 android.graphics.Color，
    // 在 JVM 单元测试里是 Stub（调用即抛），因此这两个函数不在这里覆盖 ——
    // 它们只影响「主色块上的文字色」，属于设备端可目视确认的部分。
}
