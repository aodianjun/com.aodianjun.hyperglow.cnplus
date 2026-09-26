package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [transitionExitEasing] / [transitionEnterEasing] — the line-switch
 * curves (issue #68 换行动画) — and for the frame recipes ([lineTransitionExitFrame] /
 * [lineTransitionEnterFrame]) shared by preview (PreviewComponents) and device
 * (AodLyricCanvasView). Any numeric drift changes both surfaces at once.
 */
class AodCanvasTransitionTest {

    @Test
    fun easingEndpointsArePinned() {
        assertEquals(0f, transitionExitEasing(0f), 1e-6f)
        assertEquals(1f, transitionExitEasing(1f), 1e-6f)
        assertEquals(0f, transitionEnterEasing(0f), 1e-6f)
        assertEquals(1f, transitionEnterEasing(1f), 1e-6f)
    }

    @Test
    fun exitEasingStartsSlowAndAccelerates() {
        // easeIn:前半程低于线性(旧行起步慢),随后加速离场。
        assertTrue(transitionExitEasing(0.5f) < 0.5f)
        assertTrue(transitionExitEasing(0.25f) < transitionExitEasing(0.5f))
        assertTrue(transitionExitEasing(0.75f) > transitionExitEasing(0.5f))
    }

    @Test
    fun enterEasingStartsFastAndSettlesWithoutOvershoot() {
        // easeOut:前半程高于线性(新行起步快),减速落位且永不超过 1(无回弹)。
        assertTrue(transitionEnterEasing(0.5f) > 0.5f)
        assertTrue(transitionEnterEasing(0.75f) < 1f)
        assertTrue(transitionEnterEasing(0.9f) < 1f)
    }

    @Test
    fun easingClampsOutOfRangeProgress() {
        assertEquals(0f, transitionExitEasing(-0.2f), 1e-6f)
        assertEquals(1f, transitionExitEasing(1.3f), 1e-6f)
        assertEquals(0f, transitionEnterEasing(-0.2f), 1e-6f)
        assertEquals(1f, transitionEnterEasing(1.3f), 1e-6f)
    }

    @Test
    fun easingIsMonotonicAndBoundedAcrossTheTransition() {
        var previousExit = 0f
        var previousEnter = 0f
        for (i in 1..20) {
            val t = i / 20f
            val exit = transitionExitEasing(t)
            val enter = transitionEnterEasing(t)
            assertTrue(exit >= previousExit)
            assertTrue(enter >= previousEnter)
            assertTrue(exit in 0f..1f)
            assertTrue(enter in 0f..1f)
            previousExit = exit
            previousEnter = enter
        }
    }

    // 历史内联实现的参数(重构前直接写在 drawRows 调用处):淡入上移 14dp。
    private val historicalFadeUpDp = 14f

    @Test
    fun fadeUpFramesMatchHistoricalInlineMath() {
        // 退场:alpha = 1-p,translateY = -14dp*p(历史:canvas.translate(0, -14dp*exitProgress))
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(
                LineTransitionFrame(alpha = 1f - p, translateYDp = -historicalFadeUpDp * p),
                lineTransitionExitFrame("Fade up", p)
            )
            // 入场:alpha = p,translateY = 14dp*(1-p)(历史:canvas.translate(0, 14dp*(1-enterProgress)))
            assertEquals(
                LineTransitionFrame(alpha = p, translateYDp = historicalFadeUpDp * (1f - p)),
                lineTransitionEnterFrame("Fade up", p)
            )
        }
    }

    @Test
    fun crossfadeAndNoneArePureAlphaWithoutMotion() {
        for (mode in listOf("Crossfade", "None")) {
            for (p in listOf(0f, 0.5f, 1f)) {
                assertEquals(
                    LineTransitionFrame(alpha = 1f - p),
                    lineTransitionExitFrame(mode, p)
                )
                assertEquals(
                    LineTransitionFrame(alpha = p),
                    lineTransitionEnterFrame(mode, p)
                )
            }
        }
    }

    @Test
    fun slideModesTranslateAlongTheirOwnAxisOnly() {
        // Slide up:退场 alpha=1-p² 保持更久、位移 28dp 向上;入场 alpha 提前拉满、自下方 28dp 滑入
        val exitUp = lineTransitionExitFrame("Slide up", 0.5f)
        assertEquals(0.75f, exitUp.alpha, 1e-6f)
        assertEquals(-14f, exitUp.translateYDp, 1e-6f)
        assertEquals(0f, exitUp.translateXDp, 1e-6f)
        assertEquals(1f, exitUp.scale, 1e-6f)

        val enterUp = lineTransitionEnterFrame("Slide up", 0.5f)
        assertEquals(0.8f, enterUp.alpha, 1e-6f)
        assertEquals(14f, enterUp.translateYDp, 1e-6f)
        assertEquals(0f, enterUp.translateXDp, 1e-6f)

        // Slide left:镜像到 X 轴,方向相反(退场向左、入场自右侧)
        val exitLeft = lineTransitionExitFrame("Slide left", 0.5f)
        assertEquals(0.75f, exitLeft.alpha, 1e-6f)
        assertEquals(-14f, exitLeft.translateXDp, 1e-6f)
        assertEquals(0f, exitLeft.translateYDp, 1e-6f)

        val enterLeft = lineTransitionEnterFrame("Slide left", 0.5f)
        assertEquals(0.8f, enterLeft.alpha, 1e-6f)
        assertEquals(14f, enterLeft.translateXDp, 1e-6f)
        assertEquals(0f, enterLeft.translateYDp, 1e-6f)

        // 入场 alpha = min(1, p*1.6) 在 p≥0.625 时饱和到 1
        assertEquals(1f, lineTransitionEnterFrame("Slide up", 1f).alpha, 1e-6f)
    }

    @Test
    fun zoomScalesAroundContentCenterWithoutTranslation() {
        val exitMid = lineTransitionExitFrame("Zoom", 0.5f)
        assertEquals(0.5f, exitMid.alpha, 1e-6f)
        assertEquals(1.03f, exitMid.scale, 1e-6f)
        assertEquals(0f, exitMid.translateXDp, 1e-6f)
        assertEquals(0f, exitMid.translateYDp, 1e-6f)

        val enterMid = lineTransitionEnterFrame("Zoom", 0.5f)
        assertEquals(0.5f, enterMid.alpha, 1e-6f)
        assertEquals(0.96f, enterMid.scale, 1e-6f)

        // 边界:退场不放大超过 1.06、入场终值回到 1
        assertEquals(1.06f, lineTransitionExitFrame("Zoom", 1f).scale, 1e-6f)
        assertEquals(1f, lineTransitionEnterFrame("Zoom", 1f).scale, 1e-6f)
        assertEquals(0.92f, lineTransitionEnterFrame("Zoom", 0f).scale, 1e-6f)
    }

    @Test
    fun unknownModeFallsBackToFadeUp() {
        // 与 wire 归一化兜底一致:未知值按历史默认 Fade up 处理,不引入第四种观感
        for (mode in listOf("Slide", "", "zoom", "fade up")) {
            assertEquals(
                lineTransitionExitFrame("Fade up", 0.3f),
                lineTransitionExitFrame(mode, 0.3f)
            )
            assertEquals(
                lineTransitionEnterFrame("Fade up", 0.3f),
                lineTransitionEnterFrame(mode, 0.3f)
            )
        }
    }

    @Test
    fun progressIsClampedToUnitInterval() {
        assertEquals(lineTransitionExitFrame("Fade up", 0f), lineTransitionExitFrame("Fade up", -0.5f))
        assertEquals(lineTransitionExitFrame("Fade up", 1f), lineTransitionExitFrame("Fade up", 1.5f))
        assertEquals(lineTransitionEnterFrame("Zoom", 0f), lineTransitionEnterFrame("Zoom", -1f))
        assertEquals(lineTransitionEnterFrame("Zoom", 1f), lineTransitionEnterFrame("Zoom", 42f))
    }

    @Test
    fun noneModeNeverStartsLineTransition() {
        assertEquals(false, shouldStartLineTransition(true, "None", handoffActive = false))
        assertEquals(true, shouldStartLineTransition(true, "Fade up", handoffActive = false))
        assertEquals(false, shouldStartLineTransition(true, "Slide up", handoffActive = true))
        assertEquals(false, shouldStartLineTransition(false, "Zoom", handoffActive = false))
    }
}
