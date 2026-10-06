package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.LINE_TRANSITION_MODES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // 速率档只等比缩放时长(五档,由慢到快):Slowest 2.0×、Slow 1.5×、Fast 0.6×、
        // Fastest 0.4×、Normal/未知 1×。
        assertEquals(2f, lineTransitionDurationScale("Slowest"), 1e-6f)
        assertEquals(1.5f, lineTransitionDurationScale("Slow"), 1e-6f)
        assertEquals(1f, lineTransitionDurationScale("Normal"), 1e-6f)
        assertEquals(0.6f, lineTransitionDurationScale("Fast"), 1e-6f)
        assertEquals(0.4f, lineTransitionDurationScale("Fastest"), 1e-6f)
        for (unknown in listOf("", "slow", "Warp")) {
            assertEquals(1f, lineTransitionDurationScale(unknown), 1e-6f)
        }
        // 历史档基准 210/130ms 不动;五档按倍率换算且入场始终长于退场(总长由入场决定)。
        assertEquals(210L, enterTransitionMs("Fade up", "Normal"))
        assertEquals(130L, exitTransitionMs("Fade up", "Normal"))
        assertEquals(420L, enterTransitionMs("Fade up", "Slowest"))
        assertEquals(260L, exitTransitionMs("Fade up", "Slowest"))
        assertEquals(315L, enterTransitionMs("Fade up", "Slow"))
        assertEquals(195L, exitTransitionMs("Fade up", "Slow"))
        assertEquals(126L, enterTransitionMs("Fade up", "Fast"))
        assertEquals(78L, exitTransitionMs("Fade up", "Fast"))
        assertEquals(84L, enterTransitionMs("Fade up", "Fastest"))
        assertEquals(52L, exitTransitionMs("Fade up", "Fastest"))
        for (speed in listOf("Slowest", "Slow", "Normal", "Fast", "Fastest")) {
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
    fun staleSnapshotSkipsFullTransitionForCompressedReplay() {
        // 过渡不追旧账:年龄超过一次过渡总时长即不播整段,改走压缩补播(见
        // compressedLineTransitionTimeline:总长 ~140ms、每段 ≥40ms 的连续三段)。
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
    fun sameFrameMultipleLineChangesSkipFullTransition() {
        // 同一帧内到达 ≥2 条换行快照:即便年轻也不播整段,以最后一条为目标几何走压缩补播。
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        assertFalse(shouldSkipLineTransition(0L, timeline.totalMs, 1))
        assertTrue(shouldSkipLineTransition(0L, timeline.totalMs, 2))
        // doze 批投递实测形状:19ms 内 12 条(计数按到达窗口累加)。
        assertTrue(shouldSkipLineTransition(120L, timeline.totalMs, 12))
        // 年轻单条且未超时长:正常播过渡。
        assertFalse(shouldSkipLineTransition(120L, 340L, 1))
    }

    @Test
    fun transitionPositionTracksSteadyPlaybackWithoutLag() {
        // 正常播放:位置按 speed 实时推进 → 平滑位置逐帧同步,过渡时长/缓动逐帧不变。
        var state = TransitionPositionState(10_000L, 10_000L)
        var position = 10_000L
        repeat(20) {
            position += 16L
            state = advanceTransitionPosition(position, state, 16L, 1f)
            assertEquals(position, state.smoothedPositionMs)
            assertEquals(position, state.rawHighWaterPositionMs)
        }
        // 2 倍速播放:限速上限随 speed 等比放大(位置锚语义不变)。
        position += 32L
        state = advanceTransitionPosition(position, state, 16L, 2f)
        assertEquals(position, state.smoothedPositionMs)
    }

    @Test
    fun transitionPositionRateLimitsBatchJumpToRealTime() {
        // doze 批投递:一帧内位置 +2000ms(真机同毫秒两条位置、跨度约 2 秒)→ 平滑位置
        // 只按实时速率前进一个帧长;原始高水位立即到顶,seek 判定基准不滞后。
        val jumped = advanceTransitionPosition(
            12_000L,
            TransitionPositionState(10_000L, 10_000L),
            16L,
            1f
        )
        assertEquals(10_016L, jumped.smoothedPositionMs)
        assertEquals(12_000L, jumped.rawHighWaterPositionMs)
        // 后续帧位置静止时平滑位置继续按实时速率补齐,直到并轨(不永久滞后)。
        var current = advanceTransitionPosition(12_000L, jumped, 16L, 1f)
        assertEquals(10_032L, current.smoothedPositionMs)
        repeat(200) { current = advanceTransitionPosition(12_000L, current, 16L, 1f) }
        assertEquals(12_000L, current.smoothedPositionMs)
        assertEquals(12_000L, current.rawHighWaterPositionMs)
    }

    @Test
    fun transitionPositionHoldsOnRewindAndKeepsRawHighWater() {
        // 位置源 stall/resume 的毫秒级回漂:平滑位置原地不动、高水位不倒退(不倒带动画)。
        val previous = TransitionPositionState(10_100L, 10_100L)
        val drifted = advanceTransitionPosition(10_020L, previous, 16L, 1f)
        assertEquals(10_100L, drifted.smoothedPositionMs)
        assertEquals(10_100L, drifted.rawHighWaterPositionMs)
        // 批跳变后原始高水位立即到顶,倒退超容差的 seek 仍命中(与限速无关)。
        val afterJump = advanceTransitionPosition(12_000L, previous, 16L, 1f)
        assertTrue(isTransitionSeekJump(11_600L, afterJump.rawHighWaterPositionMs))
        assertFalse(isTransitionSeekJump(11_800L, afterJump.rawHighWaterPositionMs))
    }

    @Test
    fun transitionPositionFollowsRawWhenPaused() {
        // 暂停驻留(speed=0):位置变化只可能来自 seek/刷新 → 不限速,保持既有跳变语义
        // (瞬间落位 / completed 立即结束),不引入「冻在半路」的新状态。
        val paused = advanceTransitionPosition(
            30_000L,
            TransitionPositionState(10_000L, 10_000L),
            16L,
            0f
        )
        assertEquals(30_000L, paused.smoothedPositionMs)
        assertEquals(30_000L, paused.rawHighWaterPositionMs)
    }

    @Test
    fun seekJumpDetectionMatchesRewindToleranceBoundary() {
        // 倒退恰好等于容差不判跳变(严格「超出」才判);容差外命中;前进永不判 seek。
        assertFalse(isTransitionSeekJump(9_700L, 10_000L))
        assertTrue(isTransitionSeekJump(9_699L, 10_000L))
        assertFalse(isTransitionSeekJump(10_000L, 10_000L))
        assertFalse(isTransitionSeekJump(12_000L, 10_000L))
    }

    @Test
    fun smoothedClockSpreadsDozeBatchJumpAcrossFramesInsteadOfOneFrameSpike() {
        // 真机形状(aodwalk2/recwalk2):过渡进行中 doze 批投递把位置一帧推进约 2 秒。
        // 旧实现位置高水位一步到顶 → 过渡在该帧内推完,位移段出现 -68px/帧 孤立尖峰
        // (总位移约 220px 的两帧吃掉 61%)。限速后批跳变按实时速率补齐:任一帧的进度
        // 步进不超过一个实时帧的量,位移段各帧步长回到缓动曲线本身的形状(无孤立尖峰)。
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        val startPosition = 100_000L
        val frameMs = 16L
        var state = TransitionPositionState(startPosition, startPosition)
        var position = startPosition
        var previousMoveProgress = 0f
        val moveSteps = ArrayList<Float>()
        var completed = false
        var frames = 0
        // 前 3 帧按 60Hz 正常推进;第 4 帧批投递 +2000ms;随后位置继续按实时推进。
        while (!completed && frames < 200) {
            position += if (frames == 3) 2_000L else frameMs
            state = advanceTransitionPosition(position, state, frameMs, 1f)
            val clock = lineTransitionClockAtPosition(
                state.smoothedPositionMs,
                startPosition,
                state.smoothedPositionMs,
                timeline
            )
            moveSteps += clock.moveProgress - previousMoveProgress
            previousMoveProgress = clock.moveProgress
            completed = clock.completed
            frames++
        }
        assertTrue("transition must complete", completed)
        // 限速成立:任一帧的进度步进不超过一个实时帧的量(旧实现在批跳变帧直接 completed)。
        val maxStepPerFrame = frameMs.toFloat() / timeline.moveMs
        moveSteps.forEach { step -> assertTrue("step=$step", step <= maxStepPerFrame + 1e-4f) }
        // 批跳变不在一帧内推完:走完整条时间线需要约 totalMs/frameMs 帧(970/16 ≈ 61)。
        assertTrue("frames=$frames", frames >= (timeline.totalMs / frameMs).toInt())
        // 位移段(220ms,约 13–14 帧)任一帧不得吃掉总位移的 1/4(旧实现两帧 61%);
        // 且最大单帧步进不超过均值的 3 倍(缓动曲线峰值约 2.6×,无孤立尖峰)。
        val nonzero = moveSteps.filter { it > 0f }
        assertTrue(nonzero.isNotEmpty())
        val maxStep = nonzero.maxOrNull()!!
        val meanStep = nonzero.sum() / nonzero.size
        assertTrue("maxStep=$maxStep", maxStep <= 0.25f)
        assertTrue("maxStep=$maxStep mean=$meanStep", maxStep <= meanStep * 3f)
    }

    @Test
    fun compressedTimelineKeepsThreePhasesWithinBoundedTotal() {
        // 旧账压缩补播:总时长 ~120–160ms、每段 ≥40ms、有无晋级段与基线一致(段顺序不变)。
        val cases = listOf(
            Triple("Fade up", "Normal", false),
            Triple("Fade up", "Slow", true),
            Triple("fade_out_up_fade_in_up", "Normal", true),
            Triple("fade_out_up_fade_in_up", "Fast", true),
            Triple("slide_out_left_landing", "Slow", true),
            Triple("slide_out_right_landing", "Normal", true)
        )
        for ((mode, speed, promoting) in cases) {
            val compressed = compressedLineTransitionTimeline(mode, speed, promoting)
            assertTrue("$mode/$speed total=${compressed.totalMs}", compressed.totalMs in 120L..160L)
            assertTrue("exit=${compressed.exitMs}", compressed.exitMs >= COMPRESSED_TRANSITION_MIN_SEGMENT_MS)
            assertTrue("enter=${compressed.enterMs}", compressed.enterMs >= COMPRESSED_TRANSITION_MIN_SEGMENT_MS)
            if (promoting) {
                assertTrue("move=${compressed.moveMs}", compressed.moveMs >= COMPRESSED_TRANSITION_MIN_SEGMENT_MS)
            } else {
                assertEquals(0L, compressed.moveMs)
            }
        }
    }

    @Test
    fun compressedReplayClockWalksPhasesInBoundedWallTime() {
        // 压缩补播按挂钟在压缩时间线上推进:0 起算,~140ms 内连续走完三段(旧版是一帧硬切),
        // 段顺序不变(退场完成前位移/入场为 0,位移完成前入场为 0),到总长即 completed。
        val timeline = compressedLineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        val atStart = lineTransitionClockAtElapsed(0L, timeline)
        assertEquals(0f, atStart.exitProgress, 1e-6f)
        assertEquals(0f, atStart.moveProgress, 1e-6f)
        assertEquals(0f, atStart.enterProgress, 1e-6f)
        assertFalse(atStart.completed)
        val midExit = lineTransitionClockAtElapsed(timeline.exitMs / 2L, timeline)
        assertTrue(midExit.exitProgress in 0.4f..0.6f)
        assertEquals(0f, midExit.moveProgress, 1e-6f)
        val midMove = lineTransitionClockAtElapsed(timeline.exitMs + timeline.moveMs / 2L, timeline)
        assertEquals(1f, midMove.exitProgress, 1e-6f)
        assertTrue(midMove.moveProgress in 0.4f..0.6f)
        assertEquals(0f, midMove.enterProgress, 1e-6f)
        val midEnter = lineTransitionClockAtElapsed(
            timeline.exitMs + timeline.moveMs + timeline.enterMs / 2L,
            timeline
        )
        assertEquals(1f, midEnter.moveProgress, 1e-6f)
        assertTrue(midEnter.enterProgress in 0.4f..0.6f)
        assertFalse(midEnter.completed)
        val done = lineTransitionClockAtElapsed(timeline.totalMs, timeline)
        assertEquals(1f, done.enterProgress, 1e-6f)
        assertTrue(done.completed)
    }

    // ------------------------------------------------------------------
    // round 2→4:位移段以配置时长为准(220ms × 速率倍率),只保留极远距离的平均速度护栏。
    // 旧「峰值速度上限」把 216px 恒拉长到 ~1.2s 且末尾 max 抹掉速率档(真机表现
    // 「改速率没用 + 太慢」);起步不跳靠位置限速平滑 + 起点几何锚定(round 1/3),不靠慢。
    // ------------------------------------------------------------------

    @Test
    fun moveDistanceUsesLargestRowBaselineDelta() {
        // 位移距离取主行对 + 辅助行对中基线差绝对值最大者;空/非有限值不参与。
        assertEquals(220f, lineTransitionMoveDistancePx(listOf(100f to 320f)), 1e-4f)
        assertEquals(
            220f,
            lineTransitionMoveDistancePx(listOf(100f to 320f, 200f to 260f)),
            1e-4f
        )
        assertEquals(160f, lineTransitionMoveDistancePx(listOf(300f to 140f, 10f to 20f)), 1e-4f)
        assertEquals(0f, lineTransitionMoveDistancePx(emptyList()), 1e-4f)
        assertEquals(50f, lineTransitionMoveDistancePx(listOf(Float.NaN to 10f, 0f to 50f)), 1e-4f)
        assertEquals(0f, lineTransitionMoveDistancePx(listOf(Float.POSITIVE_INFINITY to 10f)), 1e-4f)
    }

    @Test
    fun moveDurationFollowsConfiguredSpeedAcrossFiveTiers() {
        // 典型 216px 位移(真机 A/B 行距):五档 = 440/330/220/132/88ms——速率档全程可见,
        // 不再被任何速度上限抹平(旧实现五档恒 ~1188ms)。
        assertEquals(440L, moveTransitionMs("Slowest", 216f))
        assertEquals(330L, moveTransitionMs("Slow", 216f))
        assertEquals(220L, moveTransitionMs("Normal", 216f))
        assertEquals(132L, moveTransitionMs("Fast", 216f))
        assertEquals(88L, moveTransitionMs("Fastest", 216f))
        // 护栏下限(216 / 3000 × 1000 = 72ms)不支配最短档:配置时长仍是唯一决定项。
        assertEquals(220L, moveTransitionMs("Normal", 40f))
        assertEquals(220L, moveTransitionMs("Normal", 10f))
    }

    @Test
    fun moveDurationGuardOnlyEngagesOnVeryLongTravel() {
        // 极远距离(1500px 级横屏大位移)平均速度护栏生效:下限 500ms,不被 0.4× 倍率
        // 压穿(Fastest 也至少 500ms);Normal 220ms 同样被护栏抬起。
        assertEquals(500L, moveTransitionMs("Fastest", 1500f))
        assertEquals(500L, moveTransitionMs("Normal", 1500f))
        assertEquals(500L, moveTransitionMs("Slow", 1500f))
        assertEquals(500L, moveTransitionMs("Slowest", 1500f))
        // 护栏与配置时长取较大者:900px → 300ms 护栏,极快被抬到 300ms。
        assertEquals(300L, moveTransitionMs("Fastest", 900f))
        // 216px 下限 72ms < 最短配置 88ms:护栏不介入。
        assertEquals(88L, moveTransitionMs("Fastest", 216f))
    }

    @Test
    fun moveDurationFallsBackToConfiguredBaseWithoutDistance() {
        // 取不到距离(0/负/NaN/∞)回退配置时长 220ms × 速率倍率。
        for (invalid in listOf(0f, -5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(440L, moveTransitionMs("Slowest", invalid))
            assertEquals(330L, moveTransitionMs("Slow", invalid))
            assertEquals(220L, moveTransitionMs("Normal", invalid))
            assertEquals(132L, moveTransitionMs("Fast", invalid))
            assertEquals(88L, moveTransitionMs("Fastest", invalid))
        }
        // 未知速率档按 Normal(1×)处理。
        assertEquals(220L, moveTransitionMs("Warp", 216f))
    }

    @Test
    fun promotionTimelineCarriesConfiguredMoveDuration() {
        // 晋级时间线的位移段按配置时长;退场/入场配方不变;总长 = 三段之和。
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true, 216f)
        assertEquals(300L, timeline.exitMs)
        assertEquals(220L, timeline.moveMs)
        assertEquals(450L, timeline.enterMs)
        assertEquals(970L, timeline.totalMs)
        assertEquals(moveTransitionMs("Normal", 216f), timeline.moveMs)
        // 无位移段时不引入时长(非晋级照旧)。
        val plain = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", false, 216f)
        assertEquals(0L, plain.moveMs)
        assertEquals(750L, plain.totalMs)
        // 五档 × 216px 在整条时间线上逐档可辨(预设档退场 300 / 入场 450 同步缩放)。
        assertEquals(1940L, lineTransitionTimeline("fade_out_up_fade_in_up", "Slowest", true, 216f).totalMs)
        assertEquals(1455L, lineTransitionTimeline("fade_out_up_fade_in_up", "Slow", true, 216f).totalMs)
        assertEquals(970L, lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true, 216f).totalMs)
        assertEquals(582L, lineTransitionTimeline("fade_out_up_fade_in_up", "Fast", true, 216f).totalMs)
        assertEquals(388L, lineTransitionTimeline("fade_out_up_fade_in_up", "Fastest", true, 216f).totalMs)
    }

    @Test
    fun promotionMoveStepsFollowTheEaseCurveOnly() {
        // 撤去峰值限速后逐帧形状仍只由 FastOutSlowIn 决定:单调不减、总位移 = 距离、
        // 单帧步长 ≤ 距离 × 帧进度 × 峰值斜率上界(2.8,实测 ≈2.73),起步帧小于均值。
        val distance = 216f
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true, distance)
        assertEquals(220L, timeline.moveMs)
        val frameMs = 1000f / 60f
        val steps = ArrayList<Float>()
        var previousEased = 0f
        var elapsed = timeline.moveStartMs.toFloat()
        while (elapsed < timeline.moveStartMs + timeline.moveMs) {
            elapsed += frameMs
            val eased = moveTransitionEase(
                lineTransitionMoveProgress(elapsed.toLong(), timeline)
            )
            steps += distance * (eased - previousEased)
            previousEased = eased
        }
        assertTrue(steps.all { it >= 0f })
        assertEquals(distance, steps.sum(), 1.5f)
        val upperBound = distance * (frameMs / timeline.moveMs) * 2.8f
        assertTrue("max=${steps.maxOrNull()!!}", steps.maxOrNull()!! <= upperBound)
        assertTrue("first=${steps.first()}", steps.first() <= steps.average().toFloat())
    }

    @Test
    fun compressedReplayKeepsConfiguredMoveDuration() {
        // 旧账压缩补播:退场/入场维持 ~140ms 短时长(每段 ≥40ms),位移段按配置时长
        // (不再压到 40ms,也不吃峰值限速);挂钟时钟在压缩时间线上仍连续走完三段。
        val distance = 216f
        val compressed = compressedLineTransitionTimeline(
            "fade_out_up_fade_in_up",
            "Normal",
            true,
            distance
        )
        assertEquals(moveTransitionMs("Normal", distance), compressed.moveMs)
        assertEquals(220L, compressed.moveMs)
        assertTrue("exit=${compressed.exitMs}", compressed.exitMs >= COMPRESSED_TRANSITION_MIN_SEGMENT_MS)
        assertTrue("enter=${compressed.enterMs}", compressed.enterMs >= COMPRESSED_TRANSITION_MIN_SEGMENT_MS)
        assertEquals(
            compressed.exitMs + compressed.moveMs + compressed.enterMs,
            compressed.totalMs
        )
        // 退场/入场合计仍压在 ~140ms 预算内。
        assertTrue(
            "fixed=${compressed.exitMs + compressed.enterMs}",
            compressed.exitMs + compressed.enterMs <= COMPRESSED_TRANSITION_TOTAL_MS + 80L
        )
        val atStart = lineTransitionClockAtElapsed(0L, compressed)
        assertFalse(atStart.completed)
        val done = lineTransitionClockAtElapsed(compressed.totalMs, compressed)
        assertTrue(done.completed)
        assertEquals(1f, done.moveProgress, 1e-6f)
        assertEquals(1f, done.enterProgress, 1e-6f)
        // 距离取不到(0)时维持历史行为:三段整体压缩到 ~140ms。
        val legacy = compressedLineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true)
        assertTrue("legacyTotal=${legacy.totalMs}", legacy.totalMs in 120L..160L)
    }

    // ------------------------------------------------------------------
    // round 3:过渡起点几何与上一帧连续(按 owner 确认的「起步那一下跳」修正——
    // 真机 recwalk4 逐帧实测:位移段主体已 ~9px/帧平滑,唯起步第一帧 -38px)
    // ------------------------------------------------------------------

    @Test
    fun promotionPlacementStartsAtPreviousFrameGeometry() {
        // 起点连续:progress=0 时首行基线恰为旧「下一行」基线、缩放恰为 1/字号比——即上一帧
        // 实际绘制几何;progress=1 时恰为目标主行基线、缩放 1(末帧即静态形态)。
        val start = lineTransitionMovePlacement(1000f, 400f, sizeRatio = 2f, easedProgress = 0f)
        assertEquals(1000f, start.baselinePx, 1e-4f)
        assertEquals(0.5f, start.scale, 1e-4f)
        assertEquals(start.baselinePx, start.pivotYPx, 1e-4f) // 枢轴=首行基线:缩放不移动首行
        val end = lineTransitionMovePlacement(1000f, 400f, sizeRatio = 2f, easedProgress = 1f)
        assertEquals(400f, end.baselinePx, 1e-4f)
        assertEquals(1f, end.scale, 1e-4f)
        // 中段:基线两槽间线性插值、缩放同轴放大。
        val mid = lineTransitionMovePlacement(1000f, 400f, sizeRatio = 2f, easedProgress = 0.5f)
        assertEquals(700f, mid.baselinePx, 1e-4f)
        assertEquals(0.75f, mid.scale, 1e-4f)
        // 越界进度钳制;字号比 ≤1(下一行更大等异常值)不放大不缩小。
        assertEquals(1000f, lineTransitionMovePlacement(1000f, 400f, 2f, -0.5f).baselinePx, 1e-4f)
        assertEquals(400f, lineTransitionMovePlacement(1000f, 400f, 2f, 1.5f).baselinePx, 1e-4f)
        assertEquals(1f, lineTransitionMovePlacement(1000f, 400f, 1f, 0f).scale, 1e-4f)
        assertEquals(1f, lineTransitionMovePlacement(1000f, 400f, 0.5f, 0f).scale, 1e-4f)
    }

    @Test
    fun promotionPlacementFixesLegacyPivotOffsetAtMoveStart() {
        // 必要性证明(真机 recwalk4 形状):旧实现 translate((旧−目标)×(1−进度)) 后再绕
        // **目标行盒中心**缩放,两步复合后 progress≈0 的首行落点 = 枢轴 + (旧基线 − 枢轴) ×
        // 缩放比,并不等于旧下一行基线——目标行盒中心(2 行盒)离旧槽位多远就偏多少,量级
        // 数十像素(实测起步第一帧 -38px)。新放置 progress=0 的首行落点恒等于旧基线(差值 0)。
        val fromBaseline = 1775f
        val targetBaseline = 1584f
        val sizeRatio = 1.885f
        val ascent = -66f
        val targetRowHeight = 230f
        val pivot = targetBaseline + ascent + targetRowHeight / 2f
        val legacyScale = 1f / sizeRatio
        val legacyAtStart = pivot + (fromBaseline - pivot) * legacyScale
        assertTrue(
            "legacyStart=$legacyAtStart from=$fromBaseline",
            fromBaseline - legacyAtStart > 30f
        )
        assertEquals(
            fromBaseline,
            lineTransitionMovePlacement(fromBaseline, targetBaseline, sizeRatio, 0f).baselinePx,
            1e-3f
        )
    }

    @Test
    fun moveStartAnchorSnapsFirstDrawnMoveFrameToZero() {
        // 位移段起点锚定:首帧晚到/批跳变把 delta 一次越过退场段(真机形状:一帧跳进位移段
        // ~0.22)时,重锚到「当前平滑位置 − 退场时长」——该帧 moveProgress 恰为 0(画上一帧
        // 几何),其后按自身时长平滑推进。
        val timeline = lineTransitionTimeline("fade_out_up_fade_in_up", "Normal", true, 216f)
        val start = 10_000L
        val jumped = start + timeline.exitMs + 260L
        val anchor = moveStartAnchorPosition(
            start, jumped, timeline.exitMs, timeline.moveMs,
            alreadyAnchored = false, rateBounded = true
        )
        assertEquals(jumped - timeline.exitMs, anchor)
        val clock = lineTransitionClockAtPosition(jumped, anchor!!, jumped, timeline)
        assertEquals(1f, clock.exitProgress, 1e-6f)
        assertEquals(0f, clock.moveProgress, 1e-6f)
        assertFalse(clock.completed)
        // 未越过退场段不锚(正常逐帧推进);已锚定不重复锚;无位移段不锚;
        // 位置不限速(暂停/seek)不锚——瞬间落位/立即结束语义保持。
        assertNull(
            moveStartAnchorPosition(start, start + 100L, timeline.exitMs, timeline.moveMs, false, true)
        )
        assertNull(
            moveStartAnchorPosition(start, jumped, timeline.exitMs, timeline.moveMs, true, true)
        )
        assertNull(
            moveStartAnchorPosition(start, jumped, timeline.exitMs, 0L, false, true)
        )
        assertNull(
            moveStartAnchorPosition(start, jumped, timeline.exitMs, timeline.moveMs, false, false)
        )
    }

    @Test
    fun anchoredMoveStartKeepsFirstTrackedFrameContinuousAtSixtyFps() {
        // 验收口径(真机 A/B 复验同此):首帧晚到(doze/低节拍)把位移段一帧跳进时,锚定后
        // 位移段第一帧 moveProgress=0(位移 0,即上一帧几何),其后按 60fps 推进——第一帧
        // 位移 ≤ 其后相邻帧位移均值的 1.6 倍;逐帧形状只由缓动决定(不再有额外不连续)。
        // 形状取真机 recwalk4 的默认档:退场 130ms + 配置位移 220ms(216px),首帧晚到 260ms
        // → 不重锚时首帧直接跳进位移段 ~0.22(实测 -38px);重锚后首帧位移 0。
        val distance = 216f
        val timeline = lineTransitionTimeline("Fade up", "Normal", true, distance)
        assertEquals(130L, timeline.exitMs)
        assertEquals(220L, timeline.moveMs)
        val frameMs = 16L
        var smoothed = 10_000L + timeline.exitMs + 260L
        val anchoredStart = smoothed - timeline.exitMs
        val unanchoredMoveProgress =
            (smoothed - 10_000L - timeline.exitMs).toFloat() / timeline.moveMs
        val unanchoredFirstStep = distance * moveTransitionEase(unanchoredMoveProgress)
        assertTrue("unanchored=$unanchoredFirstStep", unanchoredFirstStep > 30f)
        val steps = ArrayList<Float>()
        steps += 0f // 锚定帧:moveProgress 恰为 0 → 位移 0
        var previousEased = 0f
        var delta = timeline.exitMs
        var finalClock = lineTransitionClockAtPosition(smoothed, anchoredStart, smoothed, timeline)
        while (delta < timeline.exitMs + timeline.moveMs) {
            delta += frameMs
            smoothed += frameMs
            finalClock = lineTransitionClockAtPosition(smoothed, anchoredStart, smoothed, timeline)
            val eased = moveTransitionEase(finalClock.moveProgress)
            steps += distance * (eased - previousEased)
            previousEased = eased
        }
        val first = steps.first()
        val followingMean = steps.drop(1).average().toFloat()
        assertTrue("first=$first mean=$followingMean", first <= followingMean * 1.6f)
        // 逐帧形状自洽:单帧步长 ≤ 距离 × 帧进度 × 缓动峰值斜率上界(2.8)。
        val upperBound = distance * (frameMs.toFloat() / timeline.moveMs) * 2.8f
        assertTrue("max=${steps.maxOrNull()!!}", steps.maxOrNull()!! <= upperBound)
        assertEquals(distance, steps.sum(), 1.5f)
        // 位移段走完时入场段尚未走完(严格序列不变);总时长仍按锚定后的起点整段播完。
        assertEquals(1f, finalClock.moveProgress, 1e-6f)
        assertFalse(finalClock.completed)
        val settled = lineTransitionClockAtPosition(
            anchoredStart + timeline.totalMs,
            anchoredStart,
            anchoredStart + timeline.totalMs,
            timeline
        )
        assertTrue(settled.completed)
    }

    // --- 空档预览:同曲同文的窗口更新不是换行,不触发换行动画 ---

    @Test
    fun sameLineTextWindowUpdateIsNotALineChange() {
        // 空档预览 → 该行真正开始:主行文本不变,只有行窗从预览退化窗
        // [nextLineStartMs, nextLineStartMs] 换成真实行窗 [lineStartMs, lineEndMs]。
        val preview = AodCanvasLineIdentity(
            trackGeneration = 7L,
            lineStartMs = 8_000L,
            lineEndMs = 8_000L,
            original = "下一句"
        )
        val started = preview.copy(lineEndMs = 11_000L)
        assertTrue(isSameLineTextUpdate(preview, started))
        // 真实换行(文本变了)→ 照常播换行动画。
        assertFalse(isSameLineTextUpdate(preview, started.copy(original = "再下一句")))
        // 换歌/重播(文本恰好相同但曲目身份变了)→ 照常播。
        assertFalse(isSameLineTextUpdate(preview, started.copy(trackGeneration = 8L)))
        // 空文本(初始内容/未就绪)不算同文更新。
        assertFalse(isSameLineTextUpdate(preview.copy(original = ""), started.copy(original = "")))
    }

    @Test
    fun sameLineTextWindowUpdateSuppressesTheSecondEntryAnimation() {
        // 接入语义(setContent 的行变更判定):同曲同文的窗口更新从 lineChanged 里排除 →
        // shouldStartLineTransition 不再为同一句启动第二次入场动画;文本真的变了仍照常播。
        val preview = AodCanvasLineIdentity(7L, 8_000L, 8_000L, "下一句")
        val started = preview.copy(lineEndMs = 11_000L)
        val previewUpdate = preview.original.isNotBlank() &&
            preview != started && !isSameLineTextUpdate(preview, started)
        assertFalse(previewUpdate)
        assertFalse(shouldStartLineTransition(previewUpdate, "Fade up", handoffActive = false))

        val nextLine = started.copy(original = "再下一句")
        val realChange = preview.original.isNotBlank() &&
            preview != nextLine && !isSameLineTextUpdate(preview, nextLine)
        assertTrue(realChange)
        assertTrue(shouldStartLineTransition(realChange, "Fade up", handoffActive = false))
    }
}
