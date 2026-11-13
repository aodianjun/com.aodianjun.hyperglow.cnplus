package com.eza.hyperglow.aod

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2 回归:同会话过时样本(arbiter 播放中每 ~100ms tick 写新实例,收集协程读到上一拍)
 * 必须软跳过,不能走 releaseNow——release 发布 visible=false 的 Hidden,而 Xiaomi 的
 * hide 不可逆,误判即终态隐藏。
 */
class AodProjectionStaleSampleTest {
    @Test
    fun sameSessionNewerInstanceSoftSkips() {
        // ① 同会话(同 producerId/generation/trackUri)、不同实例(sequence/positionMs 推进)。
        val sample = state(sequence = 1L, positionMs = 100L)
        val current = sample.copy(
            sequence = 2L,
            positionMs = 200L,
            receivedAtElapsedMs = 200L
        )

        assertEquals(
            StaleSampleAction.SoftSkip,
            AodProjectionEngine.classifyStaleSample(sample, current)
        )
    }

    @Test
    fun sameSessionStalePauseEdgeSoftSkips() {
        // 过时样本本身是旧的暂停边(playing=false):同会话仍软跳过,暂停判定交给当前样本。
        val sample = state(playing = false)
        val current = sample.copy(sequence = 9L, playing = true)

        assertEquals(
            StaleSampleAction.SoftSkip,
            AodProjectionEngine.classifyStaleSample(sample, current)
        )
    }

    @Test
    fun trackChangeReleases() {
        // ② 换歌:trackUri 变化 = 会话失效。
        val sample = state(trackUri = "spotify:track:a")
        val current = sample.copy(trackUri = "spotify:track:b")

        assertEquals(
            StaleSampleAction.Release,
            AodProjectionEngine.classifyStaleSample(sample, current)
        )
    }

    @Test
    fun sourceChangeReleases() {
        // ② 换源:producerId 变化 = 会话失效。
        val sample = state(producerId = "producer-a")
        val current = sample.copy(producerId = "producer-b")

        assertEquals(
            StaleSampleAction.Release,
            AodProjectionEngine.classifyStaleSample(sample, current)
        )
    }

    @Test
    fun generationChangeReleases() {
        val sample = state(generation = 1)
        val current = sample.copy(generation = 2)

        assertEquals(
            StaleSampleAction.Release,
            AodProjectionEngine.classifyStaleSample(sample, current)
        )
    }

    @Test
    fun clearedActiveReleases() {
        // ③ arbiter 清空 active(断连/过期清空):释放。
        assertEquals(
            StaleSampleAction.Release,
            AodProjectionEngine.classifyStaleSample(state(), null)
        )
    }

    @Test
    fun identicalInstanceIsNeverReleased() {
        // ④ 实例相同 = isCurrentActive 已为 true,handleState 不会走到本函数;防御性验证
        // 同一实例必然同会话,返回 SoftSkip(绝不释放)。
        val state = state()

        assertEquals(
            StaleSampleAction.SoftSkip,
            AodProjectionEngine.classifyStaleSample(state, state)
        )
    }

    @Test
    fun sessionIdentityIgnoresSequenceAndPosition() {
        // 会话身份只含 producerId/generation/trackUri:位置推进(sequence/positionMs 递增)
        // 不构成会话变化——这正是「同会话新实例」判软跳过的依据。
        val sample = state(sequence = 1L, positionMs = 100L)
        val current = sample.copy(sequence = 42L, positionMs = 9_000L)

        assertEquals(
            ProjectionSessionIdentity.from(sample),
            ProjectionSessionIdentity.from(current)
        )
        assertEquals(
            StaleSampleAction.SoftSkip,
            AodProjectionEngine.classifyStaleSample(sample, current)
        )
    }

    @Test
    fun projectionTickerSurvivesNewerInstanceOfSameSession() {
        // 回归：arbiter 每 ~100ms 写新实例，100ms 投影 ticker 不能因引用身份变化退出——
        // 退出后生产者停更便无人重启，表现为画面动几秒即冻结、到点也不撤唤醒。
        val sample = state(sequence = 1L, positionMs = 100L)
        val current = sample.copy(sequence = 2L, positionMs = 200L)
        val session = ProjectionSessionIdentity.from(sample)

        assertTrue(AodProjectionEngine.shouldContinueProjectionTicker(current, session))
    }

    @Test
    fun projectionTickerStopsOnSessionOrPlaybackChange() {
        val sample = state()
        val session = ProjectionSessionIdentity.from(sample)

        assertFalse(AodProjectionEngine.shouldContinueProjectionTicker(null, session))
        assertFalse(
            AodProjectionEngine.shouldContinueProjectionTicker(
                sample.copy(trackUri = "spotify:track:b"), session
            )
        )
        assertFalse(
            AodProjectionEngine.shouldContinueProjectionTicker(
                sample.copy(playing = false), session
            )
        )
        assertFalse(
            AodProjectionEngine.shouldContinueProjectionTicker(
                sample.copy(status = "loading"), session
            )
        )
    }

    private fun state(
        producerId: String = "producer-a",
        generation: Int = 1,
        sequence: Long = 1L,
        trackUri: String = "spotify:track:a",
        positionMs: Long = 100L,
        playing: Boolean = true
    ) = LyricProducerState(
        producerId = producerId,
        generation = generation,
        sequence = sequence,
        status = "ready",
        trackUri = trackUri,
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "",
        line = "line",
        romanizedLine = "",
        translatedLine = "",
        lineIndex = 0,
        positionMs = positionMs,
        durationMs = 1_000,
        sampledAtElapsedMs = 100,
        speed = 1f,
        playing = playing,
        receivedAtElapsedMs = 100,
        words = null,
        renderModes = ProducerRenderModes(
            weight = "Medium",
            textSize = "normal",
            textSizeCustom = 100,
            secondary = "Main only",
            animation = "Karaoke fill",
            glow = "Off",
            lineSyncFill = "Top to bottom",
            overflow = "Wrap",
            transition = "Fade up",
            font = "spotify"
        )
    )
}
