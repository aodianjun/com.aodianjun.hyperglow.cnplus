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

        accumulator.onDown(1_600f)
        accumulator.onMove(1_200f)
        accumulator.onMove(800f)

        assertEquals(800, accumulator.scrollY)
    }

    @Test
    fun eachStepReportsIncrementForMiuiEndDetection() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onDown(1_600f)
        val before = accumulator.scrollY
        accumulator.onMove(1_000f)

        assertEquals(600, accumulator.scrollY - before)
    }

    @Test
    fun moveWithoutDownDoesNotCountAsDrag() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onMove(1_000f)
        accumulator.onMove(400f)

        assertEquals(0, accumulator.scrollY)
    }

    @Test
    fun gestureEndResetsStartButKeepsTotal() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onDown(1_600f)
        accumulator.onMove(1_000f)
        accumulator.onEnd()
        accumulator.onDown(1_600f)
        accumulator.onMove(1_300f)

        assertEquals(900, accumulator.scrollY)
    }

    @Test
    fun downwardDragSubtracts() {
        val accumulator = LongScreenshotDragAccumulator()

        accumulator.onDown(800f)
        accumulator.onMove(1_200f)

        assertEquals(-400, accumulator.scrollY)
    }
}
