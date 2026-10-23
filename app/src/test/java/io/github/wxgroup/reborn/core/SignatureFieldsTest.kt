package io.github.wxgroup.reborn.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 签名「字段名映射」的行为约定。
 *
 * 为什么钉这几个用例：内置分析器产出的 fields 要能**覆盖**代码里的默认候选，
 * 又不能因为签名里缺这一项就让功能失效（8.0.78 上「类名还在但语义变了」那类
 * 静默失效最难受）。顺序与去重一旦写反，表现就是「分析了但没生效」。
 *
 * 只测纯逻辑（不碰 org.json，单测里没有 Android 的 JSON 实现）。
 */
class SignatureFieldsTest {

    private fun sig(fields: Map<String, String>) = WeChatSignatures(
        wechatVersion = "8.0.78",
        classes = emptyMap(),
        methods = emptyMap(),
        fields = fields,
        revision = SignatureAnalyzer.ANALYZED_REVISION_BASE
    )

    @Test
    fun `字段名：签名里的候选排在默认候选前面`() {
        val names = sig(mapOf("username" to "field_x")).fieldNames("username", listOf("field_username"))
        assertEquals(listOf("field_x", "field_username"), names)
    }

    @Test
    fun `字段名：签名没配这一项时完整回退默认候选`() {
        val names = sig(emptyMap()).fieldNames("unread", listOf("field_unReadCount", "q2"))
        assertEquals(listOf("field_unReadCount", "q2"), names)
    }

    @Test
    fun `字段名：签名与默认重名时只保留一次`() {
        val names = sig(mapOf("flag" to "field_flag")).fieldNames("flag", listOf("field_flag", "flag"))
        assertEquals(listOf("field_flag", "flag"), names)
    }

    @Test
    fun `字段名：支持逗号分隔的多个候选并去掉空白`() {
        val names = sig(mapOf("username" to "field_username, field_talker"))
            .fieldNames("username", listOf("field_x"))
        assertEquals(listOf("field_username", "field_talker", "field_x"), names)
    }

    @Test
    fun `字段名：fieldName 取第一个候选`() {
        assertEquals("field_flag", sig(emptyMap()).fieldName("flag", "field_flag"))
        assertEquals("abc", sig(mapOf("flag" to "abc")).fieldName("flag", "field_flag"))
    }

    @Test
    fun `分析产物的 revision 必须高于内置签名，否则会被内置覆盖`() {
        // 内置签名目前的 revision 是个位数（见 assets/wechat_signatures.json）
        org.junit.Assert.assertTrue(
            SignatureAnalyzer.ANALYZED_REVISION_BASE > 1000
        )
    }
}
