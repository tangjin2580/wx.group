package io.github.wxgroup.reborn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组命名规则的回归测试。
 *
 * 覆盖任务里点名的几条：
 *   • 空 / 纯空白不可提交
 *   • 提交前 trim()
 *   • 长度上限
 *   • 允许重名（刻意不加这条限制）
 */
class GroupNameRulesTest {

    @Test
    fun `trim 去掉首尾空白`() {
        assertEquals("工作群", GroupNameRules.normalize("  工作群  "))
        assertEquals("工作群", GroupNameRules.normalize("\t工作群\n"))
        assertEquals("", GroupNameRules.normalize(null))
    }

    @Test
    fun `纯空白不可提交`() {
        assertFalse(GroupNameRules.isValid(""))
        assertFalse(GroupNameRules.isValid("   "))
        assertFalse(GroupNameRules.isValid("\t\n"))
        assertFalse(GroupNameRules.isValid(null))
        assertEquals(GroupNameRules.Error.BLANK, GroupNameRules.check("   "))
    }

    @Test
    fun `正常名称可提交`() {
        assertTrue(GroupNameRules.isValid("工作群"))
        assertTrue(GroupNameRules.isValid("  家人  "))
        assertEquals(GroupNameRules.Error.NONE, GroupNameRules.check("家人"))
    }

    @Test
    fun `超过上限不可提交`() {
        val ok = "a".repeat(GroupNameRules.MAX_LENGTH)
        val tooLong = "a".repeat(GroupNameRules.MAX_LENGTH + 1)
        assertTrue(GroupNameRules.isValid(ok))
        assertFalse(GroupNameRules.isValid(tooLong))
        assertEquals(GroupNameRules.Error.TOO_LONG, GroupNameRules.check(tooLong))
    }

    @Test
    fun `长度按 trim 后的结果算`() {
        // 前后一堆空格，但 trim 之后刚好到上限 —— 应当放行
        val padded = "  " + "a".repeat(GroupNameRules.MAX_LENGTH) + "  "
        assertTrue(GroupNameRules.isValid(padded))
        assertEquals(GroupNameRules.MAX_LENGTH, GroupNameRules.normalize(padded).length)
    }

    @Test
    fun `允许重名`() {
        // 项目不禁止同名分组，这里把「不查重」固化成行为契约：
        // 将来若真要加查重，规则得改这里，而不是悄悄改 UI
        assertTrue(GroupNameRules.isValid("工作群"))
        assertTrue(GroupNameRules.isValid("工作群"))
    }
}
