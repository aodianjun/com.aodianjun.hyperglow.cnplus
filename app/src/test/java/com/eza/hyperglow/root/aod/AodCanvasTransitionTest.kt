package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [transitionExitEasing] / [transitionEnterEasing] — the line-switch
 * curves (issue #68 换行动画) — and for the frame recipes ([lineTransitionExitFrame] /
 * [lineTransitionEnterFrame]) shared by preview (PreviewComponents) and device
 * (AodLyricCanvasView). Any numeric drift changes both surfaces at once.
 * Also covers the HyperLyric-referenced sequential modes (`Fade left` / `Landing` /
 * `Slide swap`): per-mode durations, phase mapping and the referenced curves
 * (OvershootInterpolator / QuintEaseOut / FastOutLinearIn).
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

    @Test
    fun speedScalesDurationsWithoutTouchingFrames() {
        // 速率档只等比缩放时长:Slow 1.5×、Fast 0.6×、Normal/未知 1×。
        assertEquals(1.5f, lineTransitionDurationScale("Slow"), 1e-6f)
        assertEquals(1f, lineTransitionDurationScale("Normal"), 1e-6f)
        assertEquals(0.6f, lineTransitionDurationScale("Fast"), 1e-6f)
        for (unknown in listOf("", "slow", "Warp")) {
            assertEquals(1f, lineTransitionDurationScale(unknown), 1e-6f)
        }
        // 历史档基准 210/130ms 不动;快/慢档按倍率换算且入场始终长于退场(总长由入场决定)。
        assertEquals(210L, enterTransitionMs("Fade up", "Normal"))
        assertEquals(130L, exitTransitionMs("Fade up", "Normal"))
        assertEquals(315L, enterTransitionMs("Fade up", "Slow"))
        assertEquals(195L, exitTransitionMs("Fade up", "Slow"))
        assertEquals(126L, enterTransitionMs("Fade up", "Fast"))
        assertEquals(78L, exitTransitionMs("Fade up", "Fast"))
        for (speed in listOf("Slow", "Normal", "Fast")) {
            assertTrue(enterTransitionMs("Fade up", speed) > exitTransitionMs("Fade up", speed))
        }
    }

    @Test
    fun hyperlyricModesUseSequentialDurationsScaledBySpeed() {
        // 参考 HyperLyric 档基准:退场 300ms;入场 Fade left/Slide swap 450ms、Landing 700ms。
        assertEquals(450L, enterTransitionMs("Fade left", "Normal"))
        assertEquals(300L, exitTransitionMs("Fade left", "Normal"))
        assertEquals(700L, enterTransitionMs("Landing", "Normal"))
        assertEquals(300L, exitTransitionMs("Landing", "Normal"))
        assertEquals(450L, enterTransitionMs("Slide swap", "Normal"))
        assertEquals(300L, exitTransitionMs("Slide swap", "Normal"))
        // 速档等比缩放同样作用于参考档。
        assertEquals(675L, enterTransitionMs("Fade left", "Slow"))
        assertEquals(450L, exitTransitionMs("Fade left", "Slow"))
        assertEquals(270L, enterTransitionMs("Fade left", "Fast"))
        assertEquals(180L, exitTransitionMs("Fade left", "Fast"))
        assertEquals(1050L, enterTransitionMs("Landing", "Slow"))
        assertEquals(420L, enterTransitionMs("Landing", "Fast"))
        // 参考档序列相位;历史档叠加(总长=较长者)。
        assertTrue(isSequentialLineTransition("Fade left"))
        assertTrue(isSequentialLineTransition("Landing"))
        assertTrue(isSequentialLineTransition("Slide swap"))
        for (mode in listOf("Fade up", "Crossfade", "Slide up", "Slide left", "Zoom", "None")) {
            assertTrue(!isSequentialLineTransition(mode))
        }
        assertEquals(750L, lineTransitionTotalMs("Fade left", "Normal"))
        assertEquals(1000L, lineTransitionTotalMs("Landing", "Normal"))
        assertEquals(750L, lineTransitionTotalMs("Slide swap", "Normal"))
        assertEquals(210L, lineTransitionTotalMs("Fade up", "Normal"))
    }

    @Test
    fun sequentialPhasesKeepEnterHiddenUntilExitCompletes() {
        // 序列档:退场期间入场进度恒 0(新行层不可见),退场完成后才推进入场。
        assertEquals(0f, lineTransitionEnterProgress(0L, "Fade left", "Normal"), 1e-6f)
        assertEquals(0f, lineTransitionEnterProgress(150L, "Fade left", "Normal"), 1e-6f)
        assertEquals(0f, lineTransitionEnterProgress(300L, "Fade left", "Normal"), 1e-6f)
        assertEquals(0.5f, lineTransitionEnterProgress(300L + 225L, "Fade left", "Normal"), 1e-6f)
        assertEquals(1f, lineTransitionEnterProgress(750L, "Fade left", "Normal"), 1e-6f)
        // 退场进度独立推进,与历史档同式。
        assertEquals(0.5f, lineTransitionExitProgress(150L, "Fade left", "Normal"), 1e-6f)
        assertEquals(1f, lineTransitionExitProgress(300L, "Fade left", "Normal"), 1e-6f)
        // 历史档退场/入场共用 elapsed 叠加(入场与退场同时推进)。
        assertTrue(lineTransitionEnterProgress(150L, "Fade up", "Normal") > 0f)
    }

    @Test
    fun hyperlyricFrameRecipesMatchReferencedMotion() {
        val width = 200f
        // Fade left 退场(daimajia FadeOutLeft 同参):淡出并左移 1/4 行块宽。
        val exitMid = lineTransitionExitFrame("Fade left", 0.5f, width)
        assertEquals(0.5f, exitMid.alpha, 1e-6f)
        assertEquals(-25f, exitMid.translateXDp, 1e-6f)
        assertEquals(0f, exitMid.translateYDp, 1e-6f)
        assertEquals(1f, exitMid.scale, 1e-6f)
        // Landing 退场与 Fade left 同配方。
        assertEquals(exitMid, lineTransitionExitFrame("Landing", 0.5f, width))
        // Slide swap 退场(daimajia SlideOutLeft 同参):淡出并整宽滑出。
        val slideExit = lineTransitionExitFrame("Slide swap", 0.5f, width)
        assertEquals(0.5f, slideExit.alpha, 1e-6f)
        assertEquals(-100f, slideExit.translateXDp, 1e-6f)

        // Fade left 入场(daimajia FadeInRight 同参):自 1/4 宽右侧淡入。
        val enterStart = lineTransitionEnterFrame("Fade left", 0f, width)
        assertEquals(0f, enterStart.alpha, 1e-6f)
        assertEquals(50f, enterStart.translateXDp, 1e-6f)
        val enterEnd = lineTransitionEnterFrame("Fade left", 1f, width)
        assertEquals(1f, enterEnd.alpha, 1e-6f)
        assertEquals(0f, enterEnd.translateXDp, 1e-6f)
        // 过冲进度短暂 >1:位移越过落位点向左、alpha 钳 1。
        val overshoot = lineTransitionEnterFrame("Fade left", 1.1f, width)
        assertEquals(1f, overshoot.alpha, 1e-6f)
        assertEquals(-5f, overshoot.translateXDp, 1e-6f)

        // Landing 入场(LandingSoft 同参):自 1.2 收落至 1 并淡入。
        assertEquals(1.2f, lineTransitionEnterFrame("Landing", 0f, width).scale, 1e-6f)
        assertEquals(1f, lineTransitionEnterFrame("Landing", 1f, width).scale, 1e-6f)
        assertEquals(0f, lineTransitionEnterFrame("Landing", 0f, width).alpha, 1e-6f)
        assertEquals(1f, lineTransitionEnterFrame("Landing", 1f, width).alpha, 1e-6f)

        // Slide swap 入场(daimajia SlideInRight 同参):自整宽右侧滑入。
        val swapStart = lineTransitionEnterFrame("Slide swap", 0f, width)
        assertEquals(0f, swapStart.alpha, 1e-6f)
        assertEquals(200f, swapStart.translateXDp, 1e-6f)
        assertEquals(0f, lineTransitionEnterFrame("Slide swap", 1f, width).translateXDp, 1e-6f)
    }

    @Test
    fun hyperlyricEasingsMatchReferencedCurves() {
        // 过冲(Android OvershootInterpolator 同式):端点钉死、中后段越过 1 再回落。
        assertEquals(0f, overshootEase(0f, 1.6f), 1e-6f)
        assertEquals(1f, overshootEase(1f, 1.6f), 1e-6f)
        assertEquals(1.059375f, overshootEase(0.75f, 1.6f), 1e-4f)
        assertTrue(overshootEase(0.85f, 1.6f) > 1f)
        // 五次方缓出(Glider QuintEaseOut 同式):0.5 处恰为 1-0.5⁵。
        assertEquals(0f, quintOutEase(0f), 1e-6f)
        assertEquals(1f, quintOutEase(1f), 1e-6f)
        assertEquals(0.96875f, quintOutEase(0.5f), 1e-6f)
        // FastOutLinearIn:cubic-bezier(0.4,0,1,1),端点钉死、中段低于线性(加速离场)。
        assertEquals(0f, fastOutLinearInEase(0f), 1e-4f)
        assertEquals(1f, fastOutLinearInEase(1f), 1e-4f)
        assertTrue(fastOutLinearInEase(0.5f) < 0.5f)
        assertTrue(fastOutLinearInEase(0.5f) > 0.25f)
        // 分派:历史档沿用原曲线,参考档走各自同源曲线。
        assertEquals(transitionExitEasing(0.4f), lineTransitionExitEasing("Fade up", 0.4f), 1e-6f)
        assertEquals(transitionEnterEasing(0.4f), lineTransitionEnterEasing("Fade up", 0.4f), 1e-6f)
        assertEquals(overshootEase(0.4f, 1.6f), lineTransitionEnterEasing("Fade left", 0.4f), 1e-6f)
        assertEquals(quintOutEase(0.4f), lineTransitionEnterEasing("Landing", 0.4f), 1e-6f)
        assertEquals(
            fastOutLinearInEase(0.4f),
            lineTransitionExitEasing("Slide swap", 0.4f),
            1e-6f
        )
    }
}
