package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.AodStateWireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLyricClientTest {
    @Test
    fun retryDelayUsesExponentialBackoffWithThirtySecondCap() {
        assertEquals(1_000L, retryDelayMs(1))
        assertEquals(2_000L, retryDelayMs(2))
        assertEquals(4_000L, retryDelayMs(3))
        assertEquals(30_000L, retryDelayMs(6))
        assertEquals(30_000L, retryDelayMs(50))
    }

    @Test
    fun retryMarkersFollowSparseAttemptCurve() {
        assertTrue(shouldLogBindAttempt(1))
        assertTrue(shouldLogBindAttempt(2))
        assertTrue(shouldLogBindAttempt(3))
        assertFalse(shouldLogBindAttempt(4))
        assertTrue(shouldLogBindAttempt(5))
        assertTrue(shouldLogBindAttempt(10))
        assertTrue(shouldLogBindAttempt(20))
        assertFalse(shouldLogBindAttempt(21))
        assertTrue(shouldLogBindAttempt(50))
        assertFalse(shouldLogBindAttempt(51))
    }

    @Test
    fun staleHeartbeatMergeIsAnIdempotentNoOp() {
        val pending = hidden(revision = 7, updatedAt = 1_000)

        assertNull(mergePendingKeepAlive(pending, keepAlive(revision = 7, updatedAt = 1_000)))
        assertNull(mergePendingKeepAlive(pending, keepAlive(revision = 7, updatedAt = 900)))
        assertNull(mergePendingKeepAlive(pending, keepAlive(revision = 8, updatedAt = 2_000)))
    }

    @Test
    fun freshHeartbeatMergesScalarsWithPlaybackGrace() {
        val pending = hidden(revision = 7, updatedAt = 1_000, keepAlive = true)
        val incoming = keepAlive(
            revision = 7,
            updatedAt = 2_000,
            keepAlive = false,
            playbackActive = true,
            wakeSignal = 9L
        )

        val merged = mergePendingKeepAlive(pending, incoming) as AodStateWireMessage.Hidden

        assertEquals(2_000L, merged.updatedAtElapsedMs)
        assertTrue(merged.keepAlive)
        assertTrue(merged.playbackActive)
        assertEquals(9L, merged.wakeSignal)
    }

    @Test
    fun heartbeatGraceKeepsLeaseExpiryAuthorityWithSnapshots() {
        assertFalse(heartbeatKeepAliveWithGrace(keepAlive = false, playbackActive = false))
        assertTrue(heartbeatKeepAliveWithGrace(keepAlive = true, playbackActive = false))
        assertTrue(heartbeatKeepAliveWithGrace(keepAlive = false, playbackActive = true))
        assertTrue(heartbeatKeepAliveWithGrace(keepAlive = true, playbackActive = true))
    }

    @Test
    fun keepAliveNeverDisplacesAPendingSnapshot() {
        val pending = hidden(revision = 3, updatedAt = 500)
        val stale = keepAlive(revision = 3, updatedAt = 500)

        assertTrue(shouldReplacePendingState(null, keepAlive(revision = 3, updatedAt = 600)))
        assertFalse(shouldReplacePendingState(pending, keepAlive(revision = 3, updatedAt = 600)))
        assertTrue(shouldReplacePendingState(stale, keepAlive(revision = 3, updatedAt = 600)))
        assertTrue(shouldReplacePendingState(stale, hidden(revision = 3, updatedAt = 600)))
    }

    private fun hidden(revision: Long, updatedAt: Long, keepAlive: Boolean = true) =
        AodStateWireMessage.Hidden(
            revision = revision,
            userId = 0,
            updatedAtElapsedMs = updatedAt,
            keepAlive = keepAlive,
            wakeSignal = 0L
        )

    private fun keepAlive(
        revision: Long,
        updatedAt: Long,
        keepAlive: Boolean = true,
        playbackActive: Boolean = false,
        wakeSignal: Long = 0L
    ) = AodStateWireMessage.KeepAlive(
        revision = revision,
        userId = 0,
        updatedAtElapsedMs = updatedAt,
        keepAlive = keepAlive,
        wakeSignal = wakeSignal,
        playbackActive = playbackActive
    )
}
