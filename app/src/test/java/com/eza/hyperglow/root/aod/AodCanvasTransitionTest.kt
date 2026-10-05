package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.LINE_TRANSITION_MODES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 全档严格序列:总长 = 退场+入场(+晋级位移 220ms×速率)。
        assertEquals(750L, lineTransitionTimeline("Fade left", "Normal", false).totalMs)
        assertEquals(1000L, lineTransitionTimeline("Landing", "Normal", false).totalMs)
        assertEquals(750L, lineTransitionTimeline("Slide swap", "Normal", false).totalMs)
        assertEquals(340L, lineTransitionTimeline("Fade up", "Normal", false).totalMs)
        assertEquals(560L, lineTransitionTimeline("Fade up", "Normal", true).totalMs)
        assertEquals(970L, lineTransitionTimeline("Fade left", "Normal", true).totalMs)
    }

    @Test
    fun sequentialPhasesKeepEnterHiddenUntilExitCompletes() {
        // 全档严格序列:退场期间入场进度恒 0(新行层不可见),退场完成后才推进入场。
        val timeline = lineTransitionTimeline("Fade left", "Normal", false)
        assertEquals(0f, lineTransitionEnterProgress(0L, timeline), 1e-6f)
        assertEquals(0f, lineTransitionEnterProgress(150L, timeline), 1e-6f)
        assertEquals(0f, lineTransitionEnterProgress(300L, timeline), 1e-6f)
        assertEquals(0.5f, lineTransitionEnterProgress(300L + 225L, timeline), 1e-6f)
        assertEquals(1f, lineTransitionEnterProgress(750L, timeline), 1e-6f)
        // 退场进度独立推进。
        assertEquals(0.5f, lineTransitionExitProgress(150L, timeline), 1e-6f)
        assertEquals(1f, lineTransitionExitProgress(300L, timeline), 1e-6f)
        // 历史档同样严格序列(此前退场/入场共用 elapsed 叠加,旧行未走完新行已进场=歌词重叠):
        // 「Fade up」退场 130ms 结束前入场恒 0,结束后才开始推进。
        val legacy = lineTransitionTimeline("Fade up", "Normal", false)
        assertEquals(0f, lineTransitionEnterProgress(130L, legacy), 1e-6f)
        assertTrue(lineTransitionEnterProgress(150L, legacy) > 0f)
    }

    @Test
    fun promotionPhaseSitsBetweenExitAndEnterAndDrivesMoveFrame() {
        // 晋级段夹在退场与入场之间:退场走完才动,入场等落位后才进。
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        assertEquals(300L, timeline.exitMs)
        assertEquals(220L, timeline.moveMs)
        assertEquals(450L, timeline.enterMs)
        assertEquals(970L, timeline.totalMs)
        assertEquals(0f, lineTransitionMoveProgress(299L, timeline), 1e-6f)
        assertEquals(0.5f, lineTransitionMoveProgress(300L + 110L, timeline), 1e-6f)
        assertEquals(1f, lineTransitionMoveProgress(520L, timeline), 1e-6f)
        assertEquals(0f, lineTransitionEnterProgress(519L, timeline), 1e-6f)
        assertEquals(0f, lineTransitionEnterProgress(520L, timeline), 1e-6f)
        assertTrue(lineTransitionEnterProgress(600L, timeline) > 0f)
        // 无晋级段时位移进度恒 1、总长不含位移。
        val plain = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", false)
        assertEquals(0L, plain.moveMs)
        assertEquals(1f, lineTransitionMoveProgress(0L, plain), 1e-6f)
        assertEquals(750L, plain.totalMs)
        // 速率档等比缩放三段(慢档 1.5×)。
        val slow = lineTransitionTimeline("fade_out_up_fade_in_up", "Slow", true)
        assertEquals(450L, slow.exitMs)
        assertEquals(330L, slow.moveMs)
        assertEquals(675L, slow.enterMs)
    }

    @Test
    fun positionClockDrivesPhasesFromLyricPositionOnly() {
        // 位置式过渡时钟:三段进度由「当前位置高水位 − 起点位置」在既有时间线上换算,
        // 段顺序/时长配方/缓动不变,与挂钟无关(见 lineTransitionClockAtPosition)。
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        val start = lineTransitionClockAtPosition(10_000L, 10_000L, 10_000L, timeline)
        assertEquals(0f, start.exitProgress, 1e-6f)
        assertEquals(0f, start.moveProgress, 1e-6f)
        assertEquals(0f, start.enterProgress, 1e-6f)
        assertFalse(start.completed)
        assertFalse(start.interrupted)
        // 退场段中段:位置 +150 → 退场 0.5,其余段仍为 0。
        val exitMid = lineTransitionClockAtPosition(10_150L, 10_000L, 10_150L, timeline)
        assertEquals(0.5f, exitMid.exitProgress, 1e-6f)
        assertEquals(0f, exitMid.moveProgress, 1e-6f)
        assertEquals(0f, exitMid.enterProgress, 1e-6f)
        // 晋级位移段中段:位置 +410(300 退场 + 110 位移)→ 位移 0.5。
        val moveMid = lineTransitionClockAtPosition(10_410L, 10_000L, 10_410L, timeline)
        assertEquals(1f, moveMid.exitProgress, 1e-6f)
        assertEquals(0.5f, moveMid.moveProgress, 1e-6f)
        assertEquals(0f, moveMid.enterProgress, 1e-6f)
        // 入场段中段:位置 +745(520 + 225)→ 入场 0.5;推进到总时长即 completed。
        val enterMid = lineTransitionClockAtPosition(10_745L, 10_000L, 10_745L, timeline)
        assertEquals(0.5f, enterMid.enterProgress, 1e-6f)
        assertFalse(enterMid.completed)
        val settled = lineTransitionClockAtPosition(10_970L, 10_000L, 10_970L, timeline)
        assertEquals(1f, settled.enterProgress, 1e-6f)
        assertTrue(settled.completed)
    }

    @Test
    fun positionClockFreezesWhenPositionIsFrozen() {
        // 暂停(位置冻结):时钟逐帧相同,过渡停在当前进度,不因挂钟流逝而结束。
        val timeline = lineTransitionTimeline("Fade up", "Normal", false)
        val first = lineTransitionClockAtPosition(20_080L, 20_000L, 20_000L, timeline)
        val later = lineTransitionClockAtPosition(20_080L, 20_000L, first.highWaterPositionMs, timeline)
        assertEquals(first, later)
        assertFalse(later.completed)
        assertFalse(later.interrupted)
        assertTrue(later.exitProgress > 0f)
    }

    @Test
    fun positionClockClampsAndEndsOnPositionJump() {
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        // 倒退跳变(seek/拖动):超出采样回漂容差 → 立即结束(interrupted),进度钳在 0..1。
        val rewound = lineTransitionClockAtPosition(6_000L, 10_000L, 10_400L, timeline)
        assertTrue(rewound.interrupted)
        assertTrue(rewound.exitProgress in 0f..1f)
        assertTrue(rewound.moveProgress in 0f..1f)
        assertTrue(rewound.enterProgress in 0f..1f)
        // 前进跳变越过总时长:钳到 1 并以 completed 结束。
        val jumpedAhead = lineTransitionClockAtPosition(30_000L, 10_000L, 30_000L, timeline)
        assertTrue(jumpedAhead.completed)
        assertFalse(jumpedAhead.interrupted)
        assertEquals(1f, jumpedAhead.exitProgress, 1e-6f)
        assertEquals(1f, jumpedAhead.moveProgress, 1e-6f)
        assertEquals(1f, jumpedAhead.enterProgress, 1e-6f)
    }

    @Test
    fun positionClockToleratesSmallBackwardDriftWithoutRewinding() {
        // 位置源 stall/resume 的毫秒级回漂(容差 300ms):不判跳变、不倒带动画进度(高水位)。
        val timeline = lineTransitionTimeline("Fade up", "Normal", false)
        val before = lineTransitionClockAtPosition(10_100L, 10_000L, 10_000L, timeline)
        val drifted = lineTransitionClockAtPosition(10_020L, 10_000L, before.highWaterPositionMs, timeline)
        assertFalse(drifted.interrupted)
        assertEquals(before.exitProgress, drifted.exitProgress, 1e-6f)
        assertEquals(10_100L, drifted.highWaterPositionMs)
        // 位置恢复推进后进度继续只进不退。
        val resumed = lineTransitionClockAtPosition(10_160L, 10_000L, drifted.highWaterPositionMs, timeline)
        assertTrue(resumed.exitProgress > drifted.exitProgress)
    }

    @Test
    fun promotionRoleFollowsContentContinuity() {
        // 内容延续判定:旧「下一行」== 新「主行」才晋级;跳行/空白/跨曲都走整体退场+进场。
        assertTrue(lineTransitionPromotes("第二句", "第二句"))
        assertFalse(lineTransitionPromotes("第二句", "第五句"))
        assertFalse(lineTransitionPromotes("", ""))
        assertFalse(lineTransitionPromotes("", "第一句"))
        assertFalse(lineTransitionPromotes("第二句", ""))
    }

    @Test
    fun promotionMoveFrameScalesUpAndBrightensIntoPlace() {
        // 位移帧:translateFraction 1→0 乘槽位差;scale 自 1/字号比 放大到 1;alpha 自旧行亮度升满。
        val start = lineTransitionMoveFrame(0f, sizeRatio = 1.4f, fromAlpha = 0.45f)
        assertEquals(1f, start.translateFraction, 1e-6f)
        assertEquals(1f / 1.4f, start.scale, 1e-6f)
        assertEquals(0.45f, start.alpha, 1e-6f)
        val mid = lineTransitionMoveFrame(0.5f, sizeRatio = 1.4f, fromAlpha = 0.45f)
        assertEquals(0.5f, mid.translateFraction, 1e-6f)
        assertEquals((1f / 1.4f + 1f) / 2f, mid.scale, 1e-6f)
        assertEquals(0.725f, mid.alpha, 1e-6f)
        val end = lineTransitionMoveFrame(1f, sizeRatio = 1.4f, fromAlpha = 0.45f)
        assertEquals(0f, end.translateFraction, 1e-6f)
        assertEquals(1f, end.scale, 1e-6f)
        assertEquals(1f, end.alpha, 1e-6f)
        // 字号比 ≤1(下一行更大等异常值)不放大不缩小。
        assertEquals(1f, lineTransitionMoveFrame(0f, sizeRatio = 1f, fromAlpha = 1f).scale, 1e-6f)
        assertEquals(1f, lineTransitionMoveFrame(0f, sizeRatio = 0.5f, fromAlpha = 1f).scale, 1e-6f)
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
                assertTrue(mode, preset.outMs > 0 && preset.inMs > 0)
            }
        }
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
            assertEquals(id, preset.outMs + preset.inMs, lineTransitionTimeline(id, "Normal", false).totalMs)
        }
    }

    @Test
    fun animatedBlockHeightUsesRowBlockSpanNotContentFrame() {
        // 真机形状:歌曲信息行在顶(0..60),行块 = 主行 90..190 + 辅助行 200..260 + 下一行
        // 270..330,画布内容裁剪框高 677。基准必须取行块自身跨度 240(= 1/4 位移 60),
        // 而不是内容框高——后者把 Fade 族竖向漂移放大近 3 倍(旧行会整块扫过歌曲信息行)。
        val rows = listOf(
            AodCanvasRowBox(topPx = 0f, bottomPx = 60f, animated = false),
            AodCanvasRowBox(topPx = 90f, bottomPx = 190f, animated = true),
            AodCanvasRowBox(topPx = 200f, bottomPx = 260f, animated = true),
            AodCanvasRowBox(topPx = 270f, bottomPx = 330f, animated = true)
        )
        assertEquals(240f, animatedBlockHeightPx(rows, fallbackPx = 677f), 1e-4f)
        // Fade 族抬起量 = 行块高/4:退场向上、入场自下方升起(与 daimajia 同参)。
        assertEquals(
            -60f,
            lineTransitionExitFrame("fade_out_up_fade_in_up", 1f, 200f, 240f).translateYDp,
            1e-4f
        )
        assertEquals(
            60f,
            lineTransitionEnterFrame("fade_out_up_fade_in_up", 0f, 200f, 240f).translateYDp,
            1e-4f
        )
    }

    @Test
    fun animatedBlockHeightFallsBackWhenNoAnimatedRows() {
        // 空块 / 只有歌曲信息行 / 退化边界(高为 0)一律回落 fallback(内容框高),零除安全。
        assertEquals(677f, animatedBlockHeightPx(emptyList(), fallbackPx = 677f), 1e-4f)
        assertEquals(
            677f,
            animatedBlockHeightPx(
                listOf(AodCanvasRowBox(topPx = 0f, bottomPx = 60f, animated = false)),
                fallbackPx = 677f
            ),
            1e-4f
        )
        assertEquals(
            677f,
            animatedBlockHeightPx(
                listOf(AodCanvasRowBox(topPx = 120f, bottomPx = 120f, animated = true)),
                fallbackPx = 677f
            ),
            1e-4f
        )
    }

    @Test
    fun staleSnapshotSkipsLineTransitionAndLandsDirectly() {
        // 过渡不追旧账:年龄超过一次过渡总时长即跳过退场/晋级/入场三段,静态落到目标几何。
        val timeline = lineTransitionTimeline("Fade up", "Normal", false)
        assertEquals(340L, timeline.totalMs)
        assertTrue(shouldSkipLineTransition(timeline.totalMs + 1L, timeline.totalMs, 1))
        // 恰好等于总时长仍照常播过渡(严格「超过」才跳过)。
        assertFalse(shouldSkipLineTransition(timeline.totalMs, timeline.totalMs, 1))
        // 年轻单条:照常播过渡;年龄未知(预览/直接构造)同样不按年龄跳过。
        assertFalse(shouldSkipLineTransition(0L, timeline.totalMs, 1))
        assertFalse(shouldSkipLineTransition(null, timeline.totalMs, 1))
        // 长档 + 晋级段(970ms)同样只按「超过」判。
        val promoting = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        assertTrue(shouldSkipLineTransition(promoting.totalMs + 1L, promoting.totalMs, 1))
        assertFalse(shouldSkipLineTransition(promoting.totalMs, promoting.totalMs, 1))
    }

    @Test
    fun sameFrameMultipleLineChangesSkipLineTransition() {
        // 同一帧内到达 ≥2 条换行快照:即便年轻也跳过,直接落最后一条的静态几何。
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        assertFalse(shouldSkipLineTransition(0L, timeline.totalMs, 1))
        assertTrue(shouldSkipLineTransition(0L, timeline.totalMs, 2))
        // doze 批投递实测形状:19ms 内 12 条(计数按到达窗口累加)。
        assertTrue(shouldSkipLineTransition(120L, timeline.totalMs, 12))
        // 年轻单条且未超时长:正常播过渡。
        assertFalse(shouldSkipLineTransition(120L, 340L, 1))
    }
}
