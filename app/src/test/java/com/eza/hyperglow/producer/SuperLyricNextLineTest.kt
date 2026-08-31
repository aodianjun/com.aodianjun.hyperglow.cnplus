package com.eza.hyperglow.producer

import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [SuperLyricLyricProducer]'s next-line mapping and channel staleness override.
 *
 * The 12:34 capture: Lyricon went silent → arbiter fell back to SuperLyric, whose `emit`
 * hard-coded `nextLine = ""` → the "next line" render path never drew. The SDK's `secondary`
 * field (decompiled SuperLyricApi-3.4) carries the next line; these tests pin that mapping.
 *
 * Robolectric is required because the producer's [com.hchen.superlyricapi.ISuperLyricReceiver]
 * stub extends [android.os.Binder], whose constructor is native.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SuperLyricNextLineTest {

    private val producer = SuperLyricLyricProducer { 0L }

    private fun data(
        lyric: SuperLyricLine,
        secondary: SuperLyricLine? = null,
        translation: SuperLyricLine? = null
    ): SuperLyricData = SuperLyricData().apply {
        setTitle("Test Song")
        setArtist("Test Artist")
        setLyric(lyric)
        secondary?.let { setSecondary(it) }
        translation?.let { setTranslation(it) }
    }

    @Test
    fun secondaryBecomesNextLineWithOwnStartTime() {
        // 推送带 secondary(下一行)的快照:nextLine 必须取 secondary 文本,起点取其 startTime,
        // 而不是写死空串(12:34 抓取的直接病因)。
        val current = SuperLyricLine("current line", 10_000L, 12_000L)
        val next = SuperLyricLine("next line", 12_500L, 14_000L)

        producer.receiver.onLyric("netease", data(current, secondary = next))

        val state = producer.state.value
        assertEquals("next line", state?.nextLine)
        assertEquals(12_500L, state?.nextLineStartMs)
        assertEquals("current line", state?.line)
    }

    @Test
    fun missingSecondaryFallsBackToLineEndAsNextStart() {
        // 发布方不推 secondary(部分播放器/歌曲如此):nextLine 诚实留空,nextLineStartMs 用
        // 当前行结束时间近似(下一行 ≈ 当前行结束后开始),渲染端据此安排下一行的进入动画。
        val current = SuperLyricLine("current line", 10_000L, 12_000L)

        producer.receiver.onLyric("netease", data(current))

        val state = producer.state.value
        assertEquals("", state?.nextLine)
        assertEquals(12_000L, state?.nextLineStartMs)
    }

    @Test
    fun zeroEndLineYieldsNullNextStart() {
        // 无时间信息的行(startTime/endTime=0):nextLineStartMs 保持 null,不得把 0 当作
        // 有效的下一行起点(渲染端会把它当成立即切换信号)。
        val current = SuperLyricLine("current line", 0L, 0L)

        producer.receiver.onLyric("netease", data(current))

        val state = producer.state.value
        assertNull(state?.nextLineStartMs)
    }

    @Test
    fun lineEventChannelUsesWiderStalenessWindow() {
        // SuperLyric 只在行变化时推送:间奏/疏歌词的正常行间隔 > 3s,若沿用统一的 3s
        // staleAfterMs,arbiter 会在歌中把本通道误判为死源并抖动回退链。12s 覆盖典型
        // 行间隔,仍能在一段主歌内识别真死源。
        val current = SuperLyricLine("current line", 10_000L, 12_000L)

        producer.receiver.onLyric("netease", data(current))

        val state = producer.state.value
        assertEquals(SuperLyricLyricProducer.LINE_EVENT_STALE_AFTER_MS, state?.staleAfterMs)
        assertTrue(state != null && state.staleAfterMs > LyricProducerState.STALE_AFTER_MS)
    }
}
