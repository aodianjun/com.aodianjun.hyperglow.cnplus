package com.example.hyperglow.aisegment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentProtocolTest {

    @Test
    fun parseResponseAcceptsNumberedLinesAndSkipsNoise() {
        val content = """
            ```text
            1|夜色轻轻落下
            我不是助手,以下是切分:
            2|街灯把影子拉长
            ```
        """.trimIndent()
        val parsed = SegmentProtocol.parseResponse(content, allowed = setOf(1, 2, 3))
        assertNotNull(parsed)
        assertEquals(listOf("夜色轻轻落下"), parsed.bySource[1])
        assertEquals(listOf("街灯把影子拉长"), parsed.bySource[2])
        // 编号 3 没有输出 → 缺席(处理回落不拆)
        assertNull(parsed.bySource[3])
    }

    @Test
    fun parseResponseAcceptsParenthesizedPrefix() {
        val parsed = SegmentProtocol.parseResponse(
            "(1)|第一句\n(2)|第二句",
            allowed = setOf(1, 2),
        )
        assertNotNull(parsed)
        assertEquals(listOf("第一句"), parsed.bySource[1])
        assertEquals(listOf("第二句"), parsed.bySource[2])
    }

    @Test
    fun parseResponseReturnsNullWhenNothingUsable() {
        assertNull(SegmentProtocol.parseResponse("这是说明文字,没有编号行", allowed = setOf(1)))
        assertNull(SegmentProtocol.parseResponse("", allowed = setOf(1)))
    }

    @Test
    fun parseResponseSkipsOutOfRangeNumbers() {
        val parsed = SegmentProtocol.parseResponse("9|越界编号", allowed = setOf(1))
        assertNull(parsed)
    }

    @Test
    fun validateKeepsChildrenWhenConcatenationMatches() {
        val validated = SegmentProtocol.validate(1, "夜色轻轻,落下", listOf("夜色轻轻,", "落下"))
        assertEquals(false, validated.passthrough)
        assertEquals(listOf("夜色轻轻,", "落下"), validated.children)
    }

    @Test
    fun validateFallsBackToPassthroughOnTextMismatch() {
        // AI 改字/漏字 → 整行回落不拆
        val validated = SegmentProtocol.validate(1, "夜色轻轻落下", listOf("夜色轻轻", "落下了"))
        assertTrue(validated.passthrough)
        assertEquals(listOf("夜色轻轻落下"), validated.children)
    }

    @Test
    fun validateFallsBackWhenNoChildren() {
        val validated = SegmentProtocol.validate(2, "原样一行", emptyList())
        assertTrue(validated.passthrough)
        assertEquals(listOf("原样一行"), validated.children)
    }

    @Test
    fun buildUserPayloadListsNumberedRows() {
        val payload = SegmentProtocol.buildUserPayload(
            listOf(RowToSegment(1, "第一行", 12), RowToSegment(2, "第二行", 12)),
            maxChars = 12,
        )
        assertEquals("每行最大字数:12。\n[1] 第一行\n[2] 第二行", payload)
    }

    @Test
    fun parseChoiceContentReadsFirstMessage() {
        val response = """
            {"choices":[{"message":{"content":"1|a\n2|b"}}]}
        """.trimIndent()
        assertEquals("1|a\n2|b", SegmentProtocol.parseChoiceContent(response))
        assertNull(SegmentProtocol.parseChoiceContent("{\"choices\":[]}"))
    }
}
