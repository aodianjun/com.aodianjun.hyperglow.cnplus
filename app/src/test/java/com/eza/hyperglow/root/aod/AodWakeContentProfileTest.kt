package com.eza.hyperglow.root.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容感知唤醒画像：行级扫光/静态内容不进入连续续期；逐字、圆点、旋转、换场才连续。
 */
class AodWakeContentProfileTest {

    @Test
    fun lineSweepIsContinuousAtCappedFrameRateButStaticIsNot() {
        val line = AodWakeContentProfile(lineSweep = true)
        assertTrue(line.continuousAnimation)
        assertTrue(line.lineSweepOnly)
        assertTrue(line.lineLevelOnly)
        assertFalse(line.staticContent)

        val static = AodWakeContentProfile()
        assertFalse(static.continuousAnimation)
        assertFalse(static.lineSweepOnly)
        assertTrue(static.staticContent)
    }

    @Test
    fun lineSweepWithTransitionIsNotCappedAndTransitionIsNotContinuous() {
        val lineInTransition = AodWakeContentProfile(lineSweep = true, lineTransition = true)
        assertTrue(lineInTransition.continuousAnimation)
        assertFalse(lineInTransition.lineSweepOnly)

        val transitionOnly = AodWakeContentProfile(lineTransition = true)
        assertFalse(transitionOnly.continuousAnimation)
        assertTrue(transitionOnly.staticContent)
    }

    @Test
    fun wordLevelAndTransientAnimationsAreContinuous() {
        assertTrue(AodWakeContentProfile(wordKaraoke = true).continuousAnimation)
        assertTrue(AodWakeContentProfile(wordEffect = true).continuousAnimation)
        assertTrue(AodWakeContentProfile(auxKaraoke = true).continuousAnimation)
        assertTrue(AodWakeContentProfile(interludeDots = true).continuousAnimation)
        assertTrue(AodWakeContentProfile(artworkSpin = true).continuousAnimation)
        assertTrue(AodWakeContentProfile(demoAnimation = true).continuousAnimation)
    }

    @Test
    fun lineTransitionIsAContentPulseNotContinuousRenewal() {
        // 换行是短暂内容变化：应走 content-change-pulse，而不是 5.5s 连续续期。
        val transition = AodWakeContentProfile(lineTransition = true)
        assertFalse(transition.continuousAnimation)
        assertTrue(transition.staticContent)
        assertFalse(transition.lineLevelOnly)
    }

    @Test
    fun pausedWordContentStillNeedsNoSyntheticFrameLoop() {
        // 画布侧已把 speed<=0 的词级内容归为 paused；这里验证 paused 本身不制造
        // continuous 需求，实际是否连续由词级/效果位决定。
        val paused = AodWakeContentProfile(paused = true)
        assertFalse(paused.continuousAnimation)
        assertTrue(paused.staticContent)
    }
}
