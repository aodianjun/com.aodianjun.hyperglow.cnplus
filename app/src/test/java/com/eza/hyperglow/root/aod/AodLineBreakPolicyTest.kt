package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [adjustForCjkLineBreak] — Bridge LyricLineBreakPolicy 同表的
 * CJK 避头尾回退:行首禁则字符与行尾禁则字符不落断点,保底一个码点。
 */
class AodLineBreakPolicyTest {

    @Test
    fun prohibitedLineStartWalksBreakBack() {
        // "大家好。今天":宽度只容得下 3 字,断点后的「。」不得开新行 → 回退到 2。
        assertEquals(2, adjustForCjkLineBreak("大家好。今天", 0, 3, 7))
    }

    @Test
    fun prohibitedLineEndWalksBreakBack() {
        // "好（你":宽度只容得下 2 字,断点后的行尾是「(」(行尾禁则) → 回退,「(」随
        // 「你」一起去下一行;回退后再无禁则,停在第 1 个码点。
        assertEquals(1, adjustForCjkLineBreak("好（你", 0, 2, 4))
    }

    @Test
    fun noProhibitionKeepsMeasuredBreak() {
        assertEquals(3, adjustForCjkLineBreak("ABCDE", 0, 3, 5))
        // 拉丁文本后跟空白:空白不是禁则字符,断点不动。
        assertEquals(3, adjustForCjkLineBreak("abc d", 0, 3, 5))
    }

    @Test
    fun wholeSegmentFittingSkipsAdjustment() {
        // fitEnd == hardEnd:整段放得下,无折行发生,不调整。
        assertEquals(7, adjustForCjkLineBreak("大家好。今天", 0, 7, 7))
    }

    @Test
    fun progressFloorIsOneCodePoint() {
        // 全部是行首禁则字符:回退到保底(一个码点),绝不原地打转。
        val text = "。。。。。。。"
        assertEquals(1, adjustForCjkLineBreak(text, 0, 5, text.length))
    }

    @Test
    fun guardBoundsLongProhibitedRuns() {
        // 20 个连续禁则字符:guard 上限 16 步,回退量为 fitEnd-16。
        val text = "。".repeat(20)
        val adjusted = adjustForCjkLineBreak(text, 0, 18, text.length)
        assertEquals(18 - 16, adjusted)
    }

    @Test
    fun surrogatePairsStayIntactWhileWalking() {
        // 断点前是代理对(U+1F3B5 音符):offsetByCodePoints 按码点回退,不拆散代理对。
        val text = "oke" + String(Character.toChars(0x1F3B5)) + "。好吗"
        val fitEnd = 5 // o,k,e + 代理对(2 个 char) = 5 个 char,断点后是「。」
        assertEquals(3, adjustForCjkLineBreak(text, 0, fitEnd, text.length))
    }

    @Test
    fun prohibitedStartTableMatchesBridge() {
        // 抽查禁则表与 Bridge 一致:句读/闭合括号/促音/长音符。
        assertTrue(isProhibitedLineStart('。'.code))
        assertTrue(isProhibitedLineStart('、'.code))
        assertTrue(isProhibitedLineStart('」'.code))
        assertTrue(isProhibitedLineStart('ー'.code))
        assertTrue(isProhibitedLineStart('っ'.code))
        assertFalse(isProhibitedLineStart('好'.code))
        assertTrue(isProhibitedLineEnd('（'.code))
        assertTrue(isProhibitedLineEnd('「'.code))
        assertFalse(isProhibitedLineEnd('）'.code))
    }
}
