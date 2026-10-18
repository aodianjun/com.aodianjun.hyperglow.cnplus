package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodPowerSessionPolicyTest {
    private val first = SpicyPowerSessionState(
        session = ProjectionSessionIdentity("producer", 1, "track:1"),
        playing = true,
        aodEnabled = true,
        keepAwake = true
    )

    @Test
    fun songChangeLeaseBridgesLoadingUntilTimedLyricsArrive() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)

        assertTrue(policy.resolve(first, 1_000L, persistentKeepAlive = false).keepAlive)
        assertTrue(policy.resolve(first, 7_000L, persistentKeepAlive = false).keepAlive)
        assertTrue(policy.resolve(first, 7_500L, persistentKeepAlive = true).keepAlive)
        assertTrue(policy.resolve(first, 20_000L, persistentKeepAlive = true).keepAlive)
    }

    @Test
    fun untimedSongExpiresNaturallyAfterPresentationLease() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)

        assertTrue(policy.resolve(first, 1_000L, persistentKeepAlive = false).keepAlive)
        val expired = policy.resolve(first, 9_000L, persistentKeepAlive = false)
        assertFalse(expired.keepAlive)
        assertFalse(expired.presentationLeaseActive)
        // 租约结束同样是「无保活资格」：渲染面必须停掉续期，否则 AOD 亮到播放停止。
        assertTrue(expired.keepAliveExpired)
    }

    @Test
    fun newGenerationStartsFreshLeaseAfterPreviousSongExpired() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)
        policy.resolve(first, 1_000L, persistentKeepAlive = false)
        assertTrue(policy.resolve(first, 9_000L, persistentKeepAlive = false).keepAliveExpired)

        val next = first.copy(
            session = ProjectionSessionIdentity("producer", 2, "track:2")
        )
        val fresh = policy.resolve(next, 9_001L, persistentKeepAlive = false)
        assertTrue(fresh.keepAlive)
        assertFalse(fresh.keepAliveExpired)
    }

    @Test
    fun finiteDurationBoundsOneContinuousPlayingSession() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)
        val bounded = first.copy(keepAliveDurationMs = 300_000L)

        assertTrue(policy.resolve(bounded, 1_000L, persistentKeepAlive = true).keepAlive)
        assertTrue(policy.resolve(bounded, 300_000L, persistentKeepAlive = true).keepAlive)
        val expired = policy.resolve(bounded, 301_000L, persistentKeepAlive = true)
        assertFalse(expired.keepAlive)
        assertTrue(expired.keepAliveExpired)
    }

    @Test
    fun songChangeDoesNotResetFiniteDuration() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)
        val bounded = first.copy(keepAliveDurationMs = 300_000L)
        assertTrue(policy.resolve(bounded, 1_000L, persistentKeepAlive = true).keepAlive)

        val next = bounded.copy(session = ProjectionSessionIdentity("producer", 2, "track:2"))
        val expired = policy.resolve(next, 302_000L, persistentKeepAlive = true)
        assertFalse(expired.keepAlive)
        assertTrue(expired.keepAliveExpired)
        assertFalse(policy.resolve(next, 303_000L, persistentKeepAlive = false).keepAlive)
    }

    @Test
    fun nonPlayingEdgeStartsNewFiniteDuration() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)
        val bounded = first.copy(keepAliveDurationMs = 300_000L)
        assertTrue(policy.resolve(bounded, 1_000L, persistentKeepAlive = true).keepAlive)
        assertTrue(policy.resolve(bounded, 302_000L, persistentKeepAlive = true).keepAliveExpired)

        assertFalse(
            policy.resolve(bounded.copy(playing = false), 303_000L, persistentKeepAlive = true)
                .keepAlive
        )
        val restarted = policy.resolve(bounded, 304_000L, persistentKeepAlive = true)
        assertTrue(restarted.keepAlive)
        assertFalse(restarted.keepAliveExpired)
    }

    @Test
    fun indefiniteDurationKeepsSessionAlive() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)

        val firstDecision = policy.resolve(first, 1_000L, persistentKeepAlive = true)
        assertTrue(firstDecision.keepAlive)
        assertFalse(firstDecision.keepAliveExpired)
        val later = policy.resolve(first, 10_800_000L, persistentKeepAlive = true)
        assertTrue(later.keepAlive)
        assertFalse(later.keepAliveExpired)
    }

    @Test
    fun disabledMasterPolicyNeverStartsLease() {
        val policy = AodPowerSessionPolicy(songChangeLeaseMs = 8_000L)

        val keepAwakeOff = policy.resolve(first.copy(keepAwake = false), 1_000L, false)
        assertFalse(keepAwakeOff.keepAlive)
        assertFalse(keepAwakeOff.keepAliveExpired)
        val aodOff = policy.resolve(first.copy(aodEnabled = false), 1_000L, false)
        assertFalse(aodOff.keepAlive)
        assertFalse(aodOff.keepAliveExpired)
        val notPlaying = policy.resolve(first.copy(playing = false), 1_000L, false)
        assertFalse(notPlaying.keepAlive)
        assertFalse(notPlaying.keepAliveExpired)
    }
}
