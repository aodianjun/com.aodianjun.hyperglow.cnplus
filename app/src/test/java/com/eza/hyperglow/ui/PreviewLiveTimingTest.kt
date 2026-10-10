package com.eza.hyperglow.ui

import com.eza.hyperglow.root.aod.projectedPositionMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * App 内预览的实时时间基准(纯函数,JVM 可测):实时态按真实播放位置驱动扫光/逐字,
 * 演示态保持演示循环映射。见 [previewSweepProgress] / [previewVirtualPositionMs] /
 * [projectedPositionMs]。
 */
class PreviewLiveTimingTest {

    @Test
    fun projectedPositionExtrapolatesByElapsedAndSpeed() {
        // 采样时刻即当前:位置就是快照位置。
        assertEquals(1_000L, projectedPositionMs(1_000L, 5_000L, 1f, 5_000L))
        // 采样后过了 1.5s:位置前进 1.5s(与实机 projectedPosition 同一公式)。
        assertEquals(2_500L, projectedPositionMs(1_000L, 5_000L, 1f, 6_500L))
        // 倍速播放按速率放大。
        assertEquals(4_000L, projectedPositionMs(1_000L, 5_000L, 2f, 6_500L))
    }

    @Test
    fun projectedPositionClampsNegativeElapsed() {
        // 采样时刻晚于当前(时钟回拨/快照迟到):按 0 计,不倒推。
        assertEquals(1_000L, projectedPositionMs(1_000L, 5_000L, 1f, 4_000L))
    }

    @Test
    fun sweepProgressUsesRealPositionWhenLive() {
        // 实时态:真实位置在行窗内的比例(与实机 lineProgress 同判据)。
        assertEquals(0.5f, previewSweepProgress(1_500L, 1_000L, 2_000L, 0.1f), 1e-6f)
        // 行窗之前 → 0;行窗之后 → 1(钳制)。
        assertEquals(0f, previewSweepProgress(500L, 1_000L, 2_000L, 0.9f), 1e-6f)
        assertEquals(1f, previewSweepProgress(2_500L, 1_000L, 2_000L, 0.9f), 1e-6f)
    }

    @Test
    fun sweepProgressFallsBackToDemoWhenNoLivePosition() {
        // 演示态(或实时行窗退化由调用方传 null):取演示循环进度,历史行为不变。
        assertEquals(0.42f, previewSweepProgress(null, 1_000L, 2_000L, 0.42f), 1e-6f)
    }

    @Test
    fun degenerateWindowPinsLikeDevice() {
        // 行窗退化(end<=start)与实机 progress() 同判据:位置越过终点全亮,否则全暗。
        assertEquals(1f, previewSweepProgress(2_000L, 2_000L, 2_000L, 0.3f), 1e-6f)
        assertEquals(0f, previewSweepProgress(1_000L, 2_000L, 2_000L, 0.3f), 1e-6f)
    }

    @Test
    fun virtualPositionIsRealPositionWhenLive() {
        // 实时态:逐字虚拟播放位置就是真实播放位置(真实词窗/合成词窗都按真实位置取比例)。
        assertEquals(1_750L, previewVirtualPositionMs(1_750L, 0L, 2_500L, 0.2f))
        assertEquals(1_750L, previewVirtualPositionMs(1_750L, 100L, 200L, 0.2f))
    }

    @Test
    fun virtualPositionMapsDemoProgressOverSpan() {
        // 演示态:演示进度映射到词位总跨度(合成源路径,历史行为逐值不变)。
        assertEquals(500L, previewVirtualPositionMs(null, 0L, 2_500L, 0.2f))
        assertEquals(2_500L, previewVirtualPositionMs(null, 0L, 2_500L, 1f))
        // 窗口起点非 0 时映射自起点起(实时行窗退化退回演示映射的合成路径)。
        assertEquals(1_500L, previewVirtualPositionMs(null, 1_000L, 3_000L, 0.25f))
        // 进度越界钳制(与既有 coerceIn 语义一致)。
        assertEquals(2_500L, previewVirtualPositionMs(null, 0L, 2_500L, 1.5f))
        assertEquals(0L, previewVirtualPositionMs(null, 0L, 2_500L, -0.5f))
    }
}
