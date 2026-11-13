package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.INTERLUDE_COUNTDOWN_DELAY_MS
import com.eza.hyperglow.aod.MIN_INTERLUDE_GAP_MS
import com.eza.hyperglow.aod.interludeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长间奏倒计时圆点(参考 HyperLyric「歌词长间奏显示倒计时圆点」)。
 *
 * 本文件只测两件可离线验证的事:
 * 1. 间奏判定([interludeSpan]):空隙 ≥4s 才成立,缺端点/外推不可信时不臆造窗口;
 * 2. 圆点数学([InterludeDots]):逐点点亮进度、放大倍率、渐隐次序与簇宽——这些是
 *    "参考实现同名常量"的钉子,改任何一个常量都必须同时改这里(防静默漂移)。
 *
 * 渲染落点(android.graphics / Compose)无可离线断言的几何,由 PR 上的 CI + 真机冒烟覆盖。
 */
class InterludeDotsTest {

    // --- 间奏判定 ---

    @Test
    fun gapAtOrAboveThresholdStartsAnInterlude() {
        // 恰好 4s:阈值是"不小于"(参考实现 `gap < MIN_INTERLUDE_GAP_MS` 才跳过)。
        val exact = interludeSpan(
            lineStartMs = 0L,
            lineEndMs = 1_000L,
            hasActiveLine = true,
            nextLineStartMs = 5_000L
        )
        assertEquals(1_000L..5_000L, exact)

        val longer = interludeSpan(
            lineStartMs = 0L,
            lineEndMs = 2_000L,
            hasActiveLine = true,
            nextLineStartMs = 12_000L
        )
        assertEquals(2_000L..12_000L, longer)
    }

    @Test
    fun gapBelowThresholdIsNotAnInterlude() {
        // 3.9s:差一点点也不算——圆点只有 3 个,短空隙画出来是"闪一下"的噪音。
        assertNull(
            interludeSpan(
                lineStartMs = 0L,
                lineEndMs = 1_000L,
                hasActiveLine = true,
                nextLineStartMs = 4_900L
            )
        )
        assertTrue(4_900L - 1_000L < MIN_INTERLUDE_GAP_MS)
    }

    @Test
    fun missingEndpointsNeverInventAWindow() {
        // 无下一行起点:不能拿歌长臆造终点。
        assertNull(
            interludeSpan(
                lineStartMs = 0L,
                lineEndMs = 1_000L,
                hasActiveLine = true,
                nextLineStartMs = null
            )
        )
        // 无活动行且没有覆盖该位置的占位行窗口:起点也无从谈起。
        assertNull(
            interludeSpan(
                lineStartMs = 0L,
                lineEndMs = 0L,
                hasActiveLine = false,
                nextLineStartMs = 20_000L
            )
        )
        // 无活动行但状态携带一条覆盖该位置的占位行窗口(Spicy 的空白间奏行):该窗口即
        // 间奏区间,起点取窗口起点(连续文档下等于上一唱词行的结束),终点取下一行起点。
        assertEquals(
            3_000L..9_000L,
            interludeSpan(
                lineStartMs = 3_000L,
                lineEndMs = 9_000L,
                hasActiveLine = false,
                nextLineStartMs = 9_000L
            )
        )
    }

    @Test
    fun unreliableExtrapolationSuppressesTheCountdown() {
        // 数据源停写/外推越界时不启动倒计时:与空档预览同口径,不能用过期快照
        // 驱动一段假的间奏动画。
        assertNull(
            interludeSpan(
                lineStartMs = 0L,
                lineEndMs = 1_000L,
                hasActiveLine = true,
                nextLineStartMs = 30_000L,
                extrapolationReliable = false
            )
        )
    }

    // --- 本面窗口映射(开关 + 「显示下一行」延迟) ---

    @Test
    fun surfaceSwitchOffDropsTheWindowEntirely() {
        assertNull(
            interludeDotsWindow(
                interludeStartMs = 1_000L,
                interludeEndMs = 9_000L,
                enabled = false,
                showsNextLine = false
            )
        )
    }

    @Test
    fun showsNextLineRemovesTheOneSecondDelay() {
        // 开了「显示下一行」:屏幕上已有下一行,圆点无需为上一行保留 1s 停留,
        // 从间奏起点即开始(语义对应参考实现开了歌词预览的档位)。
        assertEquals(
            1_000L..9_000L,
            interludeDotsWindow(
                interludeStartMs = 1_000L,
                interludeEndMs = 9_000L,
                enabled = true,
                showsNextLine = true
            )
        )
        // 两开关都关:保留 1s 延迟(参考实现默认档)。
        assertEquals(
            1_000L + INTERLUDE_COUNTDOWN_DELAY_MS..9_000L,
            interludeDotsWindow(
                interludeStartMs = 1_000L,
                interludeEndMs = 9_000L,
                enabled = true,
                showsNextLine = false
            )
        )
    }

    @Test
    fun delayThatSwallowsTheWholeWindowYieldsNothing() {
        // 空隙 4.5s 但延迟 1s 后只剩 3.5s 仍要画——只有延迟后窗口退化(起点 ≥ 终点)才放弃。
        assertNull(
            interludeDotsWindow(
                interludeStartMs = 1_000L,
                interludeEndMs = 1_500L,
                enabled = true,
                showsNextLine = false
            )
        )
        // 没有长间奏(0/0)也就没有窗口。
        assertNull(
            interludeDotsWindow(
                interludeStartMs = 0L,
                interludeEndMs = 0L,
                enabled = true,
                showsNextLine = false
            )
        )
    }

    // --- 生效判据与进度 ---

    @Test
    fun dotsActivateFromWindowStartAndStopAnimatingWhenFinished() {
        val window = 1_000L..9_000L
        assertFalse(interludeDotsActive(window, 999L))
        assertTrue(interludeDotsActive(window, 1_000L))
        assertTrue(interludeDotsActive(window, 9_000L))

        assertEquals(0f, interludeDotsProgress(1_000L, window))
        assertEquals(0.5f, interludeDotsProgress(5_000L, window))
        assertEquals(1f, interludeDotsProgress(9_000L, window))
        // 越界钳制:位置早于窗口(不该发生,但渲染期前向投影可能瞬时回退)。
        assertEquals(0f, interludeDotsProgress(0L, window))
        assertEquals(1f, interludeDotsProgress(99_000L, window))

        // 帧调度:窗口内未走完才续帧;窗口前/走完都不续(动画自带终止条件)。
        assertFalse(interludeDotsAnimating(window, 999L))
        assertTrue(interludeDotsAnimating(window, 5_000L))
        assertFalse(interludeDotsAnimating(window, 9_000L))
        // 暂停(speed=0)不续帧。
        assertFalse(interludeDotsAnimating(window, 5_000L, speed = 0f))
        assertFalse(interludeDotsAnimating(null, 5_000L))
    }

    // --- 圆点数学(参考实现的常量钉子) ---

    @Test
    fun smoothStepEasesBothEnds() {
        assertEquals(0f, interludeDotSmoothStep(0f))
        assertEquals(1f, interludeDotSmoothStep(1f))
        assertEquals(0.5f, interludeDotSmoothStep(0.5f))
        // 缓动对称:v 与 1-v 的和恒为 1。
        assertEquals(1f, interludeDotSmoothStep(0.25f) + interludeDotSmoothStep(0.75f))
        // 越界钳制。
        assertEquals(0f, interludeDotSmoothStep(-1f))
        assertEquals(1f, interludeDotSmoothStep(2f))
    }

    @Test
    fun dotsLightUpLeftToRightBeforeTheFadeStarts() {
        // 点亮段是总进度的前 60%(FADE_START_PROGRESS = 3/5),60% 前全部点亮完成。
        val atFadeStart = INTERLUDE_DOT_FADE_START_PROGRESS
        for (index in 0 until INTERLUDE_DOT_COUNT) {
            assertEquals(
                "第 $index 点在渐隐段起点处必须已完全点亮",
                1f,
                interludeDotLightingProgress(atFadeStart, index)
            )
        }
        // 进度 0:没有一个点点亮。
        for (index in 0 until INTERLUDE_DOT_COUNT) {
            assertEquals(0f, interludeDotLightingProgress(0f, index))
        }
        // 逐点次序:同一进度下左边的点永远不比右边的暗。
        for (progress in listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)) {
            for (index in 1 until INTERLUDE_DOT_COUNT) {
                assertTrue(
                    "进度 $progress 下第 ${index - 1} 点不应暗于第 $index 点",
                    interludeDotLightingProgress(progress, index - 1) >=
                        interludeDotLightingProgress(progress, index)
                )
            }
        }
        // 首点在总进度刚过 0 时就开始亮(平滑起亮,不是硬切)。
        assertTrue(interludeDotLightingProgress(0.05f, 0) > 0f)
        assertEquals(0f, interludeDotLightingProgress(0.05f, INTERLUDE_DOT_COUNT - 1))
    }

    @Test
    fun litDotsGrowByFortyPercent() {
        assertEquals(1f, interludeDotScale(0f))
        assertEquals(1f + INTERLUDE_DOT_SCALE_AMOUNT, interludeDotScale(1f))
        // 半径 = 字号 × 0.25 × 放大倍率。
        val textSize = 100f
        assertEquals(
            textSize * INTERLUDE_DOT_RADIUS_TEXT_SIZE_FACTOR,
            interludeDotRadius(textSize, 0f)
        )
        assertEquals(
            textSize * INTERLUDE_DOT_RADIUS_TEXT_SIZE_FACTOR * (1f + INTERLUDE_DOT_SCALE_AMOUNT),
            interludeDotRadius(textSize, 1f)
        )
    }

    @Test
    fun fadingStartsAfterSixtyPercentAndRetiresRightmostFirst() {
        // 渐隐段之前全部不隐。
        for (index in 0 until INTERLUDE_DOT_COUNT) {
            assertEquals(1f, interludeDotExitAlpha(0f, index))
            assertEquals(1f, interludeDotExitAlpha(INTERLUDE_DOT_FADE_START_PROGRESS, index))
        }
        // 进度到 1:全部隐去(圆点让位给即将上屏的下一行)。
        for (index in 0 until INTERLUDE_DOT_COUNT) {
            assertEquals(0f, interludeDotExitAlpha(1f, index))
        }
        // 渐隐中段:最右先隐(exitAlpha 更接近 0),最左最晚。
        val mid = (INTERLUDE_DOT_FADE_START_PROGRESS + 1f) / 2f
        val alphas = (0 until INTERLUDE_DOT_COUNT).map { interludeDotExitAlpha(mid, it) }
        for (index in 1 until INTERLUDE_DOT_COUNT) {
            assertTrue(
                "渐隐段内第 ${index - 1} 点不应比第 $index 点更透明",
                alphas[index - 1] >= alphas[index]
            )
        }
        assertTrue(alphas[INTERLUDE_DOT_COUNT - 1] < 1f)
    }

    @Test
    fun clusterWidthGrowsWithLightingAndStaysInsideMaxWidth() {
        val textSize = 100f
        val maxWidth = interludeDotsMaxWidth(textSize)
        assertEquals(
            "簇宽上限 = 全放大时各点直径 + 间距",
            textSize * INTERLUDE_DOT_RADIUS_TEXT_SIZE_FACTOR * 2f *
                (1f + INTERLUDE_DOT_SCALE_AMOUNT) * INTERLUDE_DOT_COUNT +
                textSize * INTERLUDE_DOT_GAP_TEXT_SIZE_FACTOR * (INTERLUDE_DOT_COUNT - 1),
            maxWidth
        )
        // 未点亮时最窄,全点亮时达到上限(簇宽随点亮同步变化,居中/右对齐跟着走)。
        val dark = interludeDotsWidth(textSize, 0f)
        val lit = interludeDotsWidth(textSize, INTERLUDE_DOT_FADE_START_PROGRESS)
        assertTrue(dark < lit)
        assertTrue(lit <= maxWidth + 0.001f)
        assertEquals(maxWidth, lit, 0.001f)
    }

    @Test
    fun clusterStartXFollowsTheThreeAlignments() {
        val width = 120f
        val frame = 400f
        assertEquals(0f, interludeDotsStartX(width, frame, 0f, 0f, "start"))
        assertEquals(140f, interludeDotsStartX(width, frame, 0f, 0f, "center"))
        assertEquals(280f, interludeDotsStartX(width, frame, 0f, 0f, "end"))
        // 未知名按 start 处理;左右 padding 参与可用宽。
        assertEquals(10f, interludeDotsStartX(width, frame, 10f, 10f, "start"))
        assertEquals(10f + (380f - width) / 2f, interludeDotsStartX(width, frame, 10f, 10f, "center"))
        assertEquals(10f + 380f - width, interludeDotsStartX(width, frame, 10f, 10f, "end"))
        // 簇宽超过可用宽时不给负坐标(否则圆点整体移出可视区)。
        assertEquals(0f, interludeDotsStartX(900f, frame, 0f, 0f, "center"))
    }

    @Test
    fun contentFieldsParseIntoAWindowOnlyWhenBothEndsArePositive() {
        assertNull(interludeDotsWindowOf(0L, 0L))
        assertNull(interludeDotsWindowOf(5_000L, 5_000L))
        assertNull(interludeDotsWindowOf(0L, 9_000L))
        assertEquals(1_000L..9_000L, interludeDotsWindowOf(1_000L, 9_000L))
    }
}
