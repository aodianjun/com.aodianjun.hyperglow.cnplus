package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LyricOpeningFilter] — 开头元数据清理·内置三类:窗口(前 32 行且
 * ≤30s)、版权/制作明细/标题歌手头判定、2s 短续行。与 Bridge 词表逐字段对齐
 * (「作词/作曲」不在词表,测试固化该事实)。
 */
class LyricOpeningFilterTest {

    private fun lineAt(startMs: Long, text: String) =
        ElrcParser.parse("[00:${startMs / 1000}.${(startMs % 1000).toString().padStart(3, '0')}]" + text)[0]

    @Test
    fun copyrightLinesAreHidden_cnEnAndSymbol() {
        assertTrue(LyricOpeningFilter.isCopyrightLine("版权所有 (C) 2026 唱片公司"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("Copyright © 2026 Label"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("© 2026 Label"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("All Rights Reserved"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("未经许可，不得转载"))
        assertFalse(LyricOpeningFilter.isCopyrightLine("歌词正文第一句"))
    }

    @Test
    fun productionCreditLinesAreHidden() {
        assertTrue(LyricOpeningFilter.isProductionCreditLine("Piano: Yuki Kajiura", 1_000L))
        assertTrue(LyricOpeningFilter.isProductionCreditLine("钢琴：张三", 1_000L))
        assertTrue(LyricOpeningFilter.isProductionCreditLine("Mixed in Dolby Atmos by XYZ", 2_000L))
        // 无冒号且无 " by" 的 "-" 形态不是「角色: 内容」:不隐藏(Bridge 同语义)。
        assertFalse(LyricOpeningFilter.isProductionCreditLine("Piano - Yuki Kajiura", 1_000L))
        // 「作词/作曲」不在 Bridge 词表:保守保留,测试固化该边界。
        assertFalse(LyricOpeningFilter.isProductionCreditLine("作词：张三", 1_000L))
        assertFalse(LyricOpeningFilter.isProductionCreditLine("作曲：李四", 1_000L))
        // 超过 30s 窗口不隐藏。
        assertFalse(LyricOpeningFilter.isProductionCreditLine("Piano: Yuki Kajiura", 31_000L))
    }

    @Test
    fun titleArtistLeadIsHiddenWithin15s() {
        assertTrue(LyricOpeningFilter.isTitleArtistLead("歌名 - 歌手", 2_000L))
        assertTrue(LyricOpeningFilter.isTitleArtistLead("Title – Artist", 3_000L))
        assertTrue(LyricOpeningFilter.isTitleArtistLead("Title — Artist", 4_000L))
        // 无空格包围的连字符不算分隔符;句末标点拒绝;超 15s 拒绝。
        assertFalse(LyricOpeningFilter.isTitleArtistLead("歌名-歌手", 2_000L))
        assertFalse(LyricOpeningFilter.isTitleArtistLead("真的歌词。 - 假的", 2_000L))
        assertFalse(LyricOpeningFilter.isTitleArtistLead("歌名 - 歌手", 16_000L))
    }

    @Test
    fun fullOpeningBlockIsFiltered() {
        val lines = listOf(
            lineAt(0L, "版权所有 (C) 2026 唱片公司"),
            lineAt(1_000L, "Piano: Yuki Kajiura"),
            lineAt(2_000L, "歌名 - 歌手"),
            lineAt(2_500L, "歌手伴唱人员"),
            lineAt(5_000L, "真正的第一句歌词")
        )
        val filtered = LyricOpeningFilter.filterOpeningMetadata(lines)
        assertEquals(1, filtered.size)
        assertEquals("真正的第一句歌词", filtered[0].text)
    }

    @Test
    fun continuationOnlyFollowsTitleArtistCredit() {
        // 续行紧跟标题歌手头 → 隐藏;版权行后的短行 → 保留。
        val afterCredit = listOf(
            lineAt(0L, "歌名 - 歌手"),
            lineAt(1_000L, "和声合唱团")
        )
        assertEquals(0, LyricOpeningFilter.filterOpeningMetadata(afterCredit).size)

        val afterCopyright = listOf(
            lineAt(0L, "版权所有 (C) 2026"),
            lineAt(1_000L, "和声合唱团")
        )
        assertEquals(2, LyricOpeningFilter.filterOpeningMetadata(afterCopyright).size)
    }

    @Test
    fun lyricContentAfterCreditIsKept() {
        // 真实歌词行(≥8 个 CJK 字符)即使紧跟标题歌手头也不隐藏。
        val lines = listOf(
            lineAt(0L, "歌名 - 歌手"),
            lineAt(1_000L, "这是一句足够长而且真实存在的歌词内容")
        )
        val filtered = LyricOpeningFilter.filterOpeningMetadata(lines)
        assertEquals(2, filtered.size)
    }

    @Test
    fun linesBeyondWindowAreAlwaysKept() {
        // 第 34 行(索引 33 ≥ 32)的版权行超出候选窗口:保留。
        val beyondLineCount = (0 until 40).map { index ->
            if (index == 33) lineAt(0L, "版权所有 (C) 2026") else lineAt((index + 1) * 1_000L, "第${index}句歌词")
        }
        assertEquals(40, LyricOpeningFilter.filterOpeningMetadata(beyondLineCount).size)

        // 时间超过 30s 的版权行:保留。
        val lateCopyright = listOf(
            lineAt(0L, "第一句歌词"),
            lineAt(31_000L, "版权所有 (C) 2026")
        )
        assertEquals(2, LyricOpeningFilter.filterOpeningMetadata(lateCopyright).size)
    }

    @Test
    fun normalLyricsPassThroughUnchanged() {
        val lines = listOf(
            lineAt(1_000L, "第一句歌词"),
            lineAt(5_000L, "第二句歌词"),
            lineAt(9_000L, "第三句歌词")
        )
        val filtered = LyricOpeningFilter.filterOpeningMetadata(lines)
        assertEquals(lines, filtered)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals(0, LyricOpeningFilter.filterOpeningMetadata(emptyList()).size)
    }
}
