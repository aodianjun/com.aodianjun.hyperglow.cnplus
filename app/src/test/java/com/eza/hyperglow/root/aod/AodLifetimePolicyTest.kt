package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.nextLyricRetentionAnchor
import com.eza.hyperglow.root.projection.stampTransportGapEdge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLifetimePolicyTest {
    @Test
    fun powerLifetimeDoesNotDependOnCanvasVisibility() {
        assertTrue(shouldActivateAodPowerLifetime(true, true, true))
        assertFalse(shouldActivateAodPowerLifetime(false, true, true))
        assertFalse(shouldActivateAodPowerLifetime(true, false, true))
        assertFalse(shouldActivateAodPowerLifetime(true, true, false))
    }

    @Test
    fun wakeSignalOnlyFiresForNewContentEvents() {
        assertFalse(isNewAodWakeSignal(9L, 0L))
        assertFalse(isNewAodWakeSignal(9L, 9L))
        assertTrue(isNewAodWakeSignal(0L, 9L))
        assertTrue(isNewAodWakeSignal(8L, 9L))
    }

    @Test
    fun keepAlivePowerSessionSurvivesTransientHiddenEdgeAndRetriesDetachedWake() {
        val timed = LyricSnapshot(
            visible = true,
            playbackActive = true,
            keepAlive = true,
            lineStartMs = 1_000L,
            lineEndMs = 4_000L,
            original = "line"
        )
        val untimedKeepAlive = timed.copy(lineStartMs = 0L, lineEndMs = 0L)

        assertTrue(hasPersistentAodPowerIntent(timed))
        assertTrue(hasPersistentAodPowerIntent(untimedKeepAlive))
        assertFalse(hasPersistentAodPowerIntent(untimedKeepAlive.copy(keepAlive = false)))
        assertFalse(hasPersistentAodPowerIntent(untimedKeepAlive.copy(playbackActive = false)))
        assertTrue(shouldStartAodPowerGrace(true, true, true, true))
        assertFalse(shouldStartAodPowerGrace(true, false, true, true))
        assertFalse(shouldStartAodPowerGrace(true, true, true, false))
        // 分离态唤醒重试只对每个新信号执行一次;同信号重复心跳不得反复重试
        assertTrue(shouldRetryDetachedAodWake(false, true, 9L, Long.MIN_VALUE))
        assertFalse(shouldRetryDetachedAodWake(false, true, 9L, 9L))
        assertFalse(shouldRetryDetachedAodWake(true, true, 9L, Long.MIN_VALUE))
        assertFalse(shouldRetryDetachedAodWake(false, false, 9L, Long.MIN_VALUE))
        assertFalse(shouldRetryDetachedAodWake(false, true, 0L, Long.MIN_VALUE))
        // 心跳只能维持正在呈现的 AOD:隐藏间隙的宽限窗口不得被心跳无限续期
        assertFalse(shouldAcceptKeepAliveHeartbeat(projectionVisible = false, graceActive = true))
        assertTrue(shouldAcceptKeepAliveHeartbeat(projectionVisible = true, graceActive = true))
        assertFalse(shouldAcceptKeepAliveHeartbeat(projectionVisible = false, graceActive = false))
    }

    @Test
    fun racedHideRecoversOnceAndNeverFightsXiaomiOwnedDisplayPower() {
        assertTrue(isAodDisplayOffState(1))
        assertFalse(isAodDisplayOffState(2))
        assertFalse(isAodDisplayOffState(3))
        assertFalse(isAodDisplayOffState(4))

        // The captured race: guard activates, presenting AOD vanishes 1.79 s later.
        assertTrue(
            shouldRecoverRacedAodHide(
                surfaceAttached = true,
                keepAliveRequested = true,
                recoveryPending = true,
                lifetimeActiveSinceElapsedMs = 1_000L,
                nowElapsedMs = 2_790L
            )
        )
        // One shot per activation: a second off edge in the same window does not re-wake.
        assertFalse(
            shouldRecoverRacedAodHide(
                surfaceAttached = true,
                keepAliveRequested = true,
                recoveryPending = false,
                lifetimeActiveSinceElapsedMs = 1_000L,
                nowElapsedMs = 2_790L
            )
        )
        // Past the hide animation, the off edge is Xiaomi's decision to keep.
        assertFalse(
            shouldRecoverRacedAodHide(
                surfaceAttached = true,
                keepAliveRequested = true,
                recoveryPending = true,
                lifetimeActiveSinceElapsedMs = 1_000L,
                nowElapsedMs = 1_000L + HIDE_RACE_RECOVERY_WINDOW_MS + 1L
            )
        )
        // Released keepalive, detached surface, and an unarmed guard never recover.
        assertFalse(
            shouldRecoverRacedAodHide(true, false, true, 1_000L, 2_000L)
        )
        assertFalse(
            shouldRecoverRacedAodHide(false, true, true, 1_000L, 2_000L)
        )
        assertFalse(
            shouldRecoverRacedAodHide(true, true, true, Long.MIN_VALUE, 2_000L)
        )
    }

    @Test
    fun managedPositionFallbackStopsRetryingAfterBoundedAttempts() {
        assertTrue(shouldRetryManagedAodPosition(0, 5))
        assertTrue(shouldRetryManagedAodPosition(4, 5))
        assertFalse(shouldRetryManagedAodPosition(5, 5))
    }

    @Test
    fun pausedAodSnapshotStaysVisibleButReleasesKeepAliveImmediately() {
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            keepAlive = true,
            positionFollowingEnabled = true,
            durationMs = 20_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val hidden = live.copy(
            visible = false,
            playbackActive = false,
            pauseRetentionEligible = true,
            updatedAtElapsedMs = 3_000L,
            keepAlive = false
        )
        val retained = retainedAodSnapshotAfterUpdate(
            hidden, live, null, null, true, 3_000L, 30_000L
        )!!

        assertTrue(retained.visible)
        assertFalse(retained.keepAlive)
        assertTrue(retained.positionFollowingEnabled)
        assertEquals(6_000L, retained.positionMs)
        assertEquals(0f, retained.speed)
        assertEquals(
            retained,
            retainedAodSnapshotAfterUpdate(hidden, null, retained, null, true, 8_000L, 30_000L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(hidden, null, retained, null, true, 33_000L, 30_000L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(hidden, live, retained, null, false, 8_000L, 30_000L)
        )
    }

    @Test
    fun disabledPauseShowContentClearsPausedLyricsImmediately() {
        // 「暂停时显示歌曲信息、歌词」关闭时,暂停驻留边不得冻结歌词:
        // AOD 与锁屏立即清除歌曲信息、歌词,不显示任何暂停驻留内容。
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            keepAlive = true,
            durationMs = 20_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val paused = live.copy(
            visible = false,
            playbackActive = false,
            pauseRetentionEligible = true,
            updatedAtElapsedMs = 3_000L
        )

        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(
                paused, live, null, null, true, 3_000L, 30_000L,
                pauseRetentionEnabled = false
            )
        )
        // 已经驻留中的快照在开关关闭后同样不再保留。
        val retained = retainedAodSnapshotAfterUpdate(
            paused, live, null, null, true, 3_000L, 30_000L
        )!!
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(
                paused, null, retained, null, true, 3_500L, 30_000L,
                pauseRetentionEnabled = false
            )
        )
        // 传输间隙驻留(仍在播放)不受该开关影响。
        val gap = live.copy(visible = false, updatedAtElapsedMs = 3_000L)
        assertTrue(
            retainedAodSnapshotAfterUpdate(
                gap, live, null, null, true, 3_000L, 5_000L,
                pauseRetentionEnabled = false
            ) != null
        )
    }

    @Test
    fun sharedPauseLingerSupportsImmediateBoundedAndIndefiniteModes() {
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            durationMs = 20_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val paused = live.copy(
            visible = false,
            playbackActive = false,
            pauseRetentionEligible = true,
            updatedAtElapsedMs = 3_000L
        )

        assertEquals(null, retainedAodSnapshotAfterUpdate(paused, live, null, null, true, 3_000L, 0L))
        listOf(5_000L, 10_000L, 30_000L).forEach { duration ->
            val retained = retainedAodSnapshotAfterUpdate(
                paused, live, null, null, true, 3_000L, duration
            )!!
            assertTrue(retainedAodSnapshotAfterUpdate(
                paused, null, retained, null, true, 3_000L + duration - 1L, duration
            ) != null)
            assertEquals(null, retainedAodSnapshotAfterUpdate(
                paused, null, retained, null, true, 3_000L + duration, duration
            ))
        }
        val indefinite = retainedAodSnapshotAfterUpdate(paused, live, null, null, true, 3_000L, -1L)!!
        assertEquals(
            indefinite,
            retainedAodSnapshotAfterUpdate(paused, null, indefinite, null, true, Long.MAX_VALUE, -1L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(paused, live, null, null, true, 8_000L, 5_000L)
        )
    }

    @Test
    fun delayedHideReplaysOnlyForInactiveCurrentControllerGeneration() {
        assertTrue(shouldReplaySuppressedPolicyHide(false, 4L, 4L, true))
        assertFalse(shouldReplaySuppressedPolicyHide(true, 4L, 4L, true))
        assertFalse(shouldReplaySuppressedPolicyHide(false, 3L, 4L, true))
        assertFalse(shouldReplaySuppressedPolicyHide(false, 4L, 4L, false))
        assertFalse(shouldReplaySuppressedPolicyHide(false, -1L, 4L, true))
    }

    @Test
    fun unchangedManagedPositionIsReassertedWithoutAnimation() {
        assertFalse(shouldAnimateAodPosition(true, overridden = true, placementChanged = false))
        assertTrue(shouldAnimateAodPosition(true, overridden = true, placementChanged = true))
        assertTrue(shouldAnimateAodPosition(true, overridden = false, placementChanged = false))
        assertFalse(shouldAnimateAodPosition(false, overridden = true, placementChanged = true))
    }

    @Test
    fun replayedPausedStateCannotRestartTheLingerFromItsOwnPublishTime() {
        // producer 会在 Spotify 每次修订同一暂停态时重发,且每次都带新的 updatedAtElapsedMs。
        // 锚定到到达消息上会让驻留计时被无限重置:早已暂停的歌词在会话被"碰一下"时
        // 再驻留一整轮,过期歌词就盖在了显示另一个播放器的 AOD 上。
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            durationMs = 300_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val pausedAt = 3_000L
        val paused = live.copy(
            visible = false,
            playbackActive = false,
            pauseRetentionEligible = true,
            updatedAtElapsedMs = pausedAt
        )
        val anchor = nextLyricRetentionAnchor(paused, null, pausedAt)!!
        assertEquals(pausedAt, anchor.atElapsedMs)
        assertTrue(anchor.pauseRetentionEligible)

        val retained = retainedAodSnapshotAfterUpdate(
            paused, live, null, anchor, true, pausedAt, 5_000L
        )!!
        assertEquals(pausedAt, retained.sampledAtElapsedMs)

        // 60 秒后 producer 带着当前时间戳重发同一暂停态。锚点不变,驻留保持过期而非重启。
        val republished = paused.copy(updatedAtElapsedMs = 63_000L)
        val replayedAnchor = nextLyricRetentionAnchor(republished, anchor, 63_000L)
        assertEquals(anchor, replayedAnchor)
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(
                republished, live, null, replayedAnchor, true, 63_000L, 5_000L
            )
        )

        // 播放恢复结束驻留回合;下一次真实暂停重新锚定。
        assertEquals(null, nextLyricRetentionAnchor(live, anchor, 64_000L))
        assertEquals(
            65_000L,
            nextLyricRetentionAnchor(
                paused.copy(updatedAtElapsedMs = 65_000L), null, 65_000L
            )?.atElapsedMs
        )
    }

    @Test
    fun stillPlayingTransportGapRetentionIsBoundedByThePowerGrace() {
        // 标记仍在播放的隐藏边是传输间隙。producer 持续重发间隙时没有别的东西在计时,
        // 冻结歌词必须以间隙自身的界过期,而不是活在一条不断前移的边上。
        val live = LyricSnapshot(
            visible = true,
            playbackActive = true,
            keepAlive = true,
            durationMs = 300_000L,
            positionMs = 4_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            original = "line"
        )
        val gap = live.copy(visible = false, updatedAtElapsedMs = 2_000L)
        val anchor = nextLyricRetentionAnchor(gap, null, 2_000L)!!
        assertFalse(anchor.pauseRetentionEligible)

        assertTrue(
            retainedAodSnapshotAfterUpdate(gap, live, null, anchor, true, 2_000L, 5_000L) != null
        )
        assertEquals(
            anchor,
            nextLyricRetentionAnchor(gap.copy(updatedAtElapsedMs = 40_000L), anchor, 40_000L)
        )
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(
                gap.copy(updatedAtElapsedMs = 40_000L), live, null, anchor, true, 40_000L, 5_000L
            )
        )
    }

    @Test
    fun transportRetentionAnchorUsesTheProjectionStampedEdgeAfterReattach() {
        val live = LyricSnapshot(
            revision = 7L,
            trackGeneration = 3L,
            visible = true,
            playbackActive = true,
            keepAlive = true,
            updatedAtElapsedMs = 1_000L,
            sampledAtElapsedMs = 1_000L,
            original = "line"
        )
        val firstGap = stampTransportGapEdge(
            live,
            live.copy(visible = false, updatedAtElapsedMs = 2_000L)
        )
        val heartbeatRefreshed = firstGap.copy(updatedAtElapsedMs = 40_000L)
        val rebuiltAnchor = nextLyricRetentionAnchor(heartbeatRefreshed, null, 40_000L)

        assertEquals(2_000L, firstGap.transportGapStartedAtElapsedMs)
        assertEquals(2_000L, rebuiltAnchor?.atElapsedMs)
        assertEquals(
            null,
            retainedAodSnapshotAfterUpdate(
                heartbeatRefreshed,
                live,
                null,
                rebuiltAnchor,
                true,
                40_000L,
                5_000L
            )
        )
    }
}
