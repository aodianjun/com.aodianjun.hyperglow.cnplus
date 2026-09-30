package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对唱并发行选取([selectDuetLineIndex])的判定语义测试——移植上游 99ba119d4
 * `SpicyBridgeDocumentStore.concurrentRowsAt` 的三段式语义(覆盖/退出缓冲/预加入)。
 */
class DuetConcurrentTest {

    private fun windows(vararg ranges: Pair<Long, Long>): List<DuetLineWindow> =
        ranges.map { DuetLineWindow(it.first, it.second, false) }

    @Test
    fun coveringOverlapSelectsLatestStarted() {
        // 主行 10..30;行 0..40 与 12..28 都覆盖 position=15,取开始更晚的后者。
        val w = windows(0L to 40_000L, 10_000L to 30_000L, 12_000L to 28_000L)
        assertEquals(2, selectDuetLineIndex(w, 1, 15_000L))
    }

    @Test
    fun exactlyOneSecondOverlapJoins() {
        val w = windows(10_000L to 30_000L, 29_000L to 40_000L)
        assertEquals(1, selectDuetLineIndex(w, 0, 29_500L))
    }

    @Test
    fun overlapBelowOneSecondNeverJoins() {
        val w = windows(10_000L to 30_000L, 29_001L to 40_000L)
        assertEquals(-1, selectDuetLineIndex(w, 0, 29_500L))
    }

    @Test
    fun dyingTailDoesNotFlickerTwoLineSection() {
        // 覆盖中但共享窗口 <1s(将死的行尾:重叠 30000-29500=500ms):不加入,双行段不闪现。
        val w = windows(10_000L to 30_000L, 29_500L to 30_600L)
        assertEquals(-1, selectDuetLineIndex(w, 0, 29_900L))
    }

    @Test
    fun endedOverlapHeldByExitBuffer() {
        // position=35:行 12..33 已唱完但与主行重叠 18s → 退出缓冲保留,双行段一起淡出。
        val w = windows(10_000L to 30_000L, 12_000L to 33_000L)
        assertEquals(1, selectDuetLineIndex(w, 0, 35_000L))
    }

    @Test
    fun endedOverlapWithoutEnoughSharedWindowDoesNotHold() {
        val w = windows(10_000L to 30_000L, 29_500L to 33_000L)
        assertEquals(-1, selectDuetLineIndex(w, 0, 33_500L))
    }

    @Test
    fun soloPrimaryPreJoinsEarliestFutureOverlap() {
        // 主行窗口内最早开始的未来重叠行预加入(显示侧在窗口开始前不绘制)。
        val w = windows(10_000L to 30_000L, 20_000L to 40_000L)
        assertEquals(1, selectDuetLineIndex(w, 0, 5_000L))
    }

    @Test
    fun interludeRowsNeverJoin() {
        val w = listOf(
            DuetLineWindow(10_000L, 30_000L, false),
            DuetLineWindow(12_000L, 28_000L, true)
        )
        assertEquals(-1, selectDuetLineIndex(w, 0, 15_000L))
    }

    @Test
    fun interludePrimaryHasNoCompanion() {
        val w = listOf(
            DuetLineWindow(10_000L, 30_000L, true),
            DuetLineWindow(12_000L, 28_000L, false)
        )
        assertEquals(-1, selectDuetLineIndex(w, 0, 15_000L))
    }

    @Test
    fun outOfRangePrimaryReturnsMinusOne() {
        assertEquals(-1, selectDuetLineIndex(windows(0L to 10_000L), 5, 1_000L))
    }
}
