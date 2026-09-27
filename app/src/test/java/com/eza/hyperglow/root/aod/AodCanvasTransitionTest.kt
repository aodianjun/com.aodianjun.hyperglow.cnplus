package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.LINE_TRANSITION_MODES
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
        // FastOutSlowIn:cubic-bezier(0.4,0,0.2,1),端点钉死、中段高于线性(减速落位)。
        assertEquals(0f, fastOutSlowInEase(0f), 1e-4f)
        assertEquals(1f, fastOutSlowInEase(1f), 1e-4f)
        assertTrue(fastOutSlowInEase(0.5f) > 0.5f)
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
        assertEquals(
            fastOutSlowInEase(0.4f),
            lineTransitionEnterEasing("flip_out_x_flip_in_x", 0.4f),
            1e-6f
        )
    }

    @Test
    fun hyperlyricPresetTableCoversVocabulary() {
        // 25 个预设全部入表;词表除历史档/Auto/None 外都有配方,有序列式相位。
        assertEquals(25, LINE_TRANSITION_PRESETS.size)
        val legacy = setOf("Auto", "Fade up", "Crossfade", "Slide up", "Slide left", "Zoom", "None")
        for (mode in LINE_TRANSITION_MODES) {
            val preset = lineTransitionPreset(mode)
            assertTrue(mode, mode in legacy || preset != null)
            if (preset != null) {
                assertTrue(mode, isSequentialLineTransition(mode))
                assertTrue(mode, preset.outMs > 0 && preset.inMs > 0)
            }
        }
    }

    @Test
    fun nextLinePromotionRequiresMatchingSequentialTexts() {
        // 顺次换行(旧第二行 == 新第一行)才晋升;空文本/不一致(seek/源修正)退回整块进退场。
        assertTrue(shouldPromoteNextLine("下一行", "下一行"))
        assertTrue(!shouldPromoteNextLine("", "下一行"))
        assertTrue(!shouldPromoteNextLine("下一行", ""))
        assertTrue(!shouldPromoteNextLine("", ""))
        assertTrue(!shouldPromoteNextLine("旧第二行", "新第一行"))
    }

    @Test
    fun promoteFrameIsPureTranslationThatSettlesAtOrigin() {
        // 晋升帧:自 offset 起步平移归零,alpha 恒 1、无缩放/旋转/横移。
        assertEquals(
            LineTransitionFrame(alpha = 1f, translateYDp = 40f),
            lineTransitionPromoteFrame(40f, 0f)
        )
        assertEquals(
            LineTransitionFrame(alpha = 1f, translateYDp = 20f),
            lineTransitionPromoteFrame(40f, 0.5f)
        )
        assertEquals(LineTransitionFrame(alpha = 1f), lineTransitionPromoteFrame(40f, 1f))
        assertEquals(LineTransitionFrame(alpha = 1f), lineTransitionPromoteFrame(0f, 0.5f))
    }

    @Test
    fun promoteFrameClampsProgressWithoutOvershoot() {
        // 进度钳制 0..1:过冲缓动的越过量只作用于入场层,晋升不回弹。
        assertEquals(lineTransitionPromoteFrame(40f, 0f), lineTransitionPromoteFrame(40f, -0.3f))
        assertEquals(lineTransitionPromoteFrame(40f, 1f), lineTransitionPromoteFrame(40f, 1.2f))
    }

    @Test
    fun hyperlyricTechniqueKeyframesMatchReferencedAnimators() {
        val w = 200f
        val h = 100f
        // FLIP_IN_X(daimajia rotationX 90→-15→15→0 + alpha 0.25→0.5→0.75→1)
        val flipStart = lineTransitionEnterFrame("flip_out_x_flip_in_x", 0f, w, h)
        assertEquals(90f, flipStart.rotationXDeg, 1e-4f)
        assertEquals(0.25f, flipStart.alpha, 1e-4f)
        val flipMid = lineTransitionEnterFrame("flip_out_x_flip_in_x", 0.5f, w, h)
        assertEquals(0f, flipMid.rotationXDeg, 1e-4f)
        assertEquals(0.625f, flipMid.alpha, 1e-4f)
        // FLIP_OUT_X(rotationX 0→90)与 ROTATE_OUT/ROTATE_IN(rotation 0→200 / -200→0)
        assertEquals(45f, lineTransitionExitFrame("flip_out_x_flip_in_x", 0.5f, w, h).rotationXDeg, 1e-4f)
        assertEquals(100f, lineTransitionExitFrame("rotate_out_rotate_in", 0.5f, w, h).rotationDeg, 1e-4f)
        assertEquals(-100f, lineTransitionEnterFrame("rotate_out_rotate_in", 0.5f, w, h).rotationDeg, 1e-4f)
        // ZOOM_OUT 关键帧(alpha 1→0→0、scale 1→0.3→0):中点 alpha 已收 0、scale 0.3
        val zoomOutMid = lineTransitionExitFrame("zoom_out_zoom_in", 0.5f, w, h)
        assertEquals(0f, zoomOutMid.alpha, 1e-4f)
        assertEquals(0.3f, zoomOutMid.scale, 1e-4f)
        assertEquals(0.45f, lineTransitionEnterFrame("zoom_out_zoom_in", 0f, w, h).scale, 1e-4f)
        // ZOOM_IN_RIGHT 关键帧(tx 宽→-16dp→0、scale 0.1→0.475→1、alpha 0→1→1)
        val zirStart = lineTransitionEnterFrame("fade_out_left_zoom_in_right", 0f, w, h)
        assertEquals(200f, zirStart.translateXDp, 1e-4f)
        assertEquals(0.1f, zirStart.scale, 1e-4f)
        val zirMid = lineTransitionEnterFrame("fade_out_left_zoom_in_right", 0.5f, w, h)
        assertEquals(-16f, zirMid.translateXDp, 1e-4f)
        assertEquals(0.475f, zirMid.scale, 1e-4f)
        assertEquals(1f, zirMid.alpha, 1e-4f)
        // 柔缓着陆(scale 1.2→1)与 Fade 族 1/4 高度位移(FADE_OUT_UP ty=−h/4)
        assertEquals(1.2f, lineTransitionEnterFrame("slide_out_left_landing", 0f, w, h).scale, 1e-4f)
        assertEquals(-25f, lineTransitionExitFrame("fade_out_up_fade_in_up", 1f, w, h).translateYDp, 1e-4f)
        // Y 翻转走 rotationY 通道,X 通道保持 0
        val flipY = lineTransitionEnterFrame("flip_out_y_flip_in_y", 0f, w, h)
        assertEquals(90f, flipY.rotationYDeg, 1e-4f)
        assertEquals(0f, flipY.rotationXDeg, 1e-4f)
    }

    @Test
    fun hyperlyricPresetDurationsFollowTableAndSpeedScale() {
        // 表内代表档:出场 200/250/300、入场 300/400/450/600/700,速档同比缩放。
        assertEquals(300L, exitTransitionMs("fade_out_fade_in", "Normal"))
        assertEquals(300L, enterTransitionMs("fade_out_fade_in", "Normal"))
        assertEquals(250L, exitTransitionMs("fade_out_left_zoom_in_right", "Normal"))
        assertEquals(600L, enterTransitionMs("fade_out_left_zoom_in_right", "Normal"))
        assertEquals(200L, exitTransitionMs("rotate_out_rotate_in", "Normal"))
        assertEquals(600L, enterTransitionMs("rotate_out_rotate_in", "Normal"))
        assertEquals(700L, enterTransitionMs("slide_out_left_landing", "Normal"))
        assertEquals(450L, enterTransitionMs("slide_out_left_slide_in_right", "Normal"))
        assertEquals(450L, exitTransitionMs("flip_out_x_flip_in_x", "Slow"))
        assertEquals(270L, enterTransitionMs("flip_out_x_flip_in_x", "Fast"))
        for ((id, preset) in LINE_TRANSITION_PRESETS) {
            assertTrue(id, preset.outMs > 0 && preset.inMs > 0)
            assertEquals(id, preset.outMs + preset.inMs, lineTransitionTotalMs(id, "Normal"))
        }
    }
}
