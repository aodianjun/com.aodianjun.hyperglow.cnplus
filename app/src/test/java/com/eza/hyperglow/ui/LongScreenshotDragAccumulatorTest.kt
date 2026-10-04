package com.eza.hyperglow.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MIUI 长截屏假触摸位移累计的行为钉子(见 [LongScreenshotDragAccumulator])。
 *
 * MIUI 的假触摸是「DOWN 于底部 → 每次 MOVE 上移半屏」的连续拖拽,scrollY 的增量必须为正,
 * 否则 MIUI 会以 `scrolledY == 0` 判定「已到底」并在一帧后结束采集(真机日志实证)。
 */
class LongScreenshotDragAccumulatorTest {
    @Test
    fun upwardDragAccumulatesPositiveScroll() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onDown(1_600f, 0L)
        accumulator.onMove(1_200f, 16L)
        accumulator.onMove(800f, 32L)

        assertEquals(800, accumulator.scrollY)
    }

    @Test
    fun eachStepReportsIncrementForMiuiEndDetection() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onDown(1_600f, 0L)
        val before = accumulator.scrollY
        accumulator.onMove(1_000f, 16L)

        assertEquals(600, accumulator.scrollY - before)
    }

    @Test
    fun moveWithoutDownDoesNotCountAsDrag() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onMove(1_000f, 16L)
        accumulator.onMove(400f, 32L)

        assertEquals(0, accumulator.scrollY)
    }

    @Test
    fun gestureEndResetsStartButKeepsTotal() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onDown(1_600f, 0L)
        accumulator.onMove(1_000f, 16L)
        accumulator.onEnd(32L)
        accumulator.onDown(1_600f, 48L)
        accumulator.onMove(1_300f, 64L)

        assertEquals(900, accumulator.scrollY)
    }

    @Test
    fun downwardDragSubtracts() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onDown(800f, 0L)
        accumulator.onMove(1_200f, 16L)

        assertEquals(-400, accumulator.scrollY)
    }

    /** 触顶后累计值冻结(增量 0),MIUI 据此结束采集,不再无限拼接。 */
    @Test
    fun scrollOffsetFreezesAtCapSoMiuiSeesTheEnd() {
        val accumulator = LongScreenshotDragAccumulator(maxScrollPx = 1_000)

        accumulator.onDown(10_000f, 0L)
        accumulator.onMove(9_000f, 16L)
        assertEquals(1_000, accumulator.scrollY)

        val frozen = accumulator.scrollY
        accumulator.onMove(8_000f, 32L)
        accumulator.onMove(7_000f, 48L)

        assertEquals(frozen, accumulator.scrollY)
        assertEquals(0, accumulator.scrollY - frozen)
    }

    /** 触顶后的新一次长截屏(间隔超过会话阈值)重新计数,第二次采集不因旧值退化成单屏。 */
    @Test
    fun newSessionAfterCapResetsTheOffset() {
        val accumulator = LongScreenshotDragAccumulator(maxScrollPx = 1_000)

        accumulator.onDown(10_000f, 0L)
        accumulator.onMove(8_000f, 16L)
        accumulator.onEnd(32L)
        assertEquals(1_000, accumulator.scrollY)

        accumulator.onDown(10_000f, 32L + 5_000L)
        assertEquals(0, accumulator.scrollY)
    }

    /** 同一次采集内的连续步进(间隔很短)不重置,累计值保持单调递增。 */
    @Test
    fun stepsWithinOneSessionDoNotResetTheOffset() {
        val accumulator = LongScreenshotDragAccumulator(maxScrollPx = 1_000)

        accumulator.onDown(10_000f, 0L)
        accumulator.onMove(8_000f, 16L)
        accumulator.onEnd(32L)
        accumulator.onDown(10_000f, 64L)
        accumulator.onMove(9_000f, 80L)

        assertEquals(1_000, accumulator.scrollY)
    }
}
