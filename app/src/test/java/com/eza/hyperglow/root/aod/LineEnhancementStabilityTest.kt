package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineEnhancementStabilityTest {
    @Test
    fun newLineAlwaysAdoptsIncomingEnhancements() {
        // 换行:来件形态一律采用(新行按自己第一次拿到的形态排版)。
        assertTrue(shouldAdoptLineEnhancements(false, true, true, 1_000L, 2_000L))
        assertTrue(shouldAdoptLineEnhancements(false, true, false, 1_000L, 2_000L))
        assertTrue(shouldAdoptLineEnhancements(false, false, false, 1_000L, 2_000L))
    }

    @Test
    fun sameLineWithUnchangedLayoutSignatureAdoptsRefinements() {
        // 同行、折行签名未变(只有时间戳/罗马音细化):采用——折行结果不会变,不产生重排。
        assertTrue(shouldAdoptLineEnhancements(true, false, false, 5_000L, 4_000L))
        assertTrue(shouldAdoptLineEnhancements(true, false, true, 5_000L, 4_000L))
    }

    @Test
    fun sameLineUpgradeIsAcceptedOnlyBeforeTheLineStarts() {
        // 「无词→带词」升级:行开始前的宽限窗内采用(该行尚未演唱,重排不可见)。
        assertTrue(shouldAdoptLineEnhancements(true, true, true, positionMs = 3_900L, lineStartMs = 4_000L))
        // 边界:恰好落在宽限窗末尾仍采用。
        assertTrue(
            shouldAdoptLineEnhancements(
                true, true, true,
                positionMs = 4_300L, lineStartMs = 4_000L, graceMs = 300L
            )
        )
        // 超出宽限窗(该行已在演唱)则冻结,避免演唱中重排。
        assertFalse(
            shouldAdoptLineEnhancements(
                true, true, true,
                positionMs = 4_301L, lineStartMs = 4_000L, graceMs = 300L
            )
        )
    }

    @Test
    fun sameLineDowngradeOrMidLineTextChangeIsRejected() {
        // 「带词→无词」回退:拒绝,保留带词版(否则折行路径回退 = 位置重载)。
        assertFalse(shouldAdoptLineEnhancements(true, true, false, positionMs = 4_000L, lineStartMs = 4_000L))
        // 演唱中词表文本变化(同为带词、签名已变):拒绝——会改变折行结果。
        assertFalse(shouldAdoptLineEnhancements(true, true, false, positionMs = 9_000L, lineStartMs = 4_000L))
    }

    @Test
    fun nextLineEqualToMainLineIsStaleAndBlankOrDifferentIsNot() {
        // 换行后上游 nextLine 仍等于刚被晋级的主行文本 → 未就绪(不能当新下一行用)。
        assertTrue(isNextLineStale("却没有漂亮的鳞片", "却没有漂亮的鳞片"))
        assertTrue(isNextLineStale("  却没有漂亮的鳞片 ", "却没有漂亮的鳞片"))
        // 真正的新下一行、或空文本(未推来)→ 不算未就绪(空文本由渲染侧按无内容处理)。
        assertFalse(isNextLineStale("它依然飞过了田野", "却没有漂亮的鳞片"))
        assertFalse(isNextLineStale("", "却没有漂亮的鳞片"))
    }

    @Test
    fun layoutSignatureTracksWordTextsButIgnoresTiming() {
        val base = listOf(AodCanvasWord("你", "", 0L, 100L, true))
        val refined = listOf(AodCanvasWord("你", "ni", 5L, 200L, false))
        val other = listOf(AodCanvasWord("好", "", 0L, 100L, true))
        assertEquals(
            aodLineLayoutSignature(base, emptyList()),
            aodLineLayoutSignature(refined, emptyList())
        )
        assertNotEquals(
            aodLineLayoutSignature(base, emptyList()),
            aodLineLayoutSignature(other, emptyList())
        )
    }
}
