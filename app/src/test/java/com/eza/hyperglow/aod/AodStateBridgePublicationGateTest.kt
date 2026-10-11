package com.eza.hyperglow.aod

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 2.5(TOCTOU):投影侧的最终验证([ProjectionPublicationGuard.canPublish])与提交
 * ([AodStateBridge.publish])分属 guard/bridge 两把锁,暂停确认/延迟释放在两者之间插入
 * release(invalidate + 发布 Hidden)时,在途的旧可见快照仍会提交成功——AOD 继续显示
 * 过期歌词直到新鲜度窗口过期。
 *
 * 这里用确定性屏障钉住「验证通过 → 释放 → 旧提交」:提交走
 * [AodStateBridge.publishIfCurrent],代次在 bridge 锁内比对,旧快照不再覆盖 Hidden;
 * 同代次的正常提交仍必须成功(正向对照,证明闸门不是一律拒绝)。
 *
 * Robolectric 是必需的:[AodStateBridge] 的静态初始化与 publish 走 android.os.Bundle /
 * SystemClock / RemoteCallbackList。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AodStateBridgePublicationGateTest {
    @Test
    fun releaseBetweenValidationAndCommitRejectsInFlightVisibleSnapshot() {
        val guard = ProjectionPublicationGuard()
        val state = producerState()
        val token = requireNotNull(guard.begin(state))
        val validated = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val validationPassed = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val inFlight = Thread {
            try {
                // 线程 A(投影协程):采样代次 → 最终验证通过 → (屏障) → 提交在途快照。
                val epoch = AodStateBridge.currentPublicationEpoch()
                validationPassed.set(guard.canPublish(token, state, state))
                validated.countDown()
                commit.await(5, TimeUnit.SECONDS)
                AodStateBridge.publishIfCurrent(epoch, visibleState())
            } catch (error: Throwable) {
                failure.set(error)
                validated.countDown()
            }
        }
        inFlight.start()
        try {
            assertTrue("投影线程须在屏障处就位", validated.await(5, TimeUnit.SECONDS))
            assertTrue("验证必须先通过(屏障前置条件)", validationPassed.get())
            // 主线程在屏障处执行释放:guard 失效 + 代次递增 + 发布 Hidden(与 releaseNow 同序)。
            guard.invalidate()
            AodStateBridge.invalidatePublications()
            AodStateBridge.publish(hiddenState())
            assertFalse(AodStateBridge.hasVisibleState())
        } finally {
            commit.countDown()
            inFlight.join(5_000L)
        }

        assertNull(failure.get())
        // 旧可见快照被代次闸门拒绝:release 发布的 Hidden 仍是最后一个提交。
        assertFalse(AodStateBridge.hasVisibleState())
    }

    @Test
    fun sameEpochCommitStillPublishes() {
        val guard = ProjectionPublicationGuard()
        val state = producerState()
        val token = requireNotNull(guard.begin(state))
        val epoch = AodStateBridge.currentPublicationEpoch()

        assertTrue(guard.canPublish(token, state, state))
        AodStateBridge.publishIfCurrent(epoch, visibleState())

        // 无释放时同一代次的提交照常落盘(闸门只作废旧代次,不做一律拒绝)。
        assertTrue(AodStateBridge.hasVisibleState())
    }

    private fun producerState() = LyricProducerState(
        producerId = "producer",
        generation = 7,
        sequence = 1L,
        status = "ready",
        trackUri = "spotify:track:gate",
        title = "title",
        artist = "artist",
        album = "album",
        imageId = "image",
        line = "line",
        romanizedLine = "",
        translatedLine = "",
        lineIndex = 0,
        positionMs = 100L,
        durationMs = 1_000L,
        sampledAtElapsedMs = 10L,
        speed = 1f,
        playing = true,
        receivedAtElapsedMs = 10L,
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

    private fun visibleState() = AodDisplayState(
        visible = true,
        trackGeneration = 7L,
        original = "gate line",
        lineStartMs = 0L,
        lineEndMs = 2_000L,
        durationMs = 2_000L,
        positionMs = 1_234L,
        sampledAtElapsedMs = 1_234L
    )

    /** 带区分位(wakeSignal),避免与上一次发布相同而被 shouldRepublish 抑制。 */
    private fun hiddenState() = AodDisplayState(visible = false, wakeSignal = 7L)
}
