package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.AodStateWireLayoutGroup
import com.eza.hyperglow.aod.AodStateWireMessage
import com.eza.hyperglow.aod.AodStateWireRuby
import com.eza.hyperglow.aod.AodStateWireSnapshot
import com.eza.hyperglow.aod.AodStateWireWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationBoundLatestTest {
    @Test
    fun latestMessageReplacesEarlierMessageForCurrentGeneration() {
        val pending = GenerationBoundLatest<String>()

        assertTrue(pending.offer(generation = 4L, currentGeneration = 4L, value = "first"))
        assertTrue(pending.offer(generation = 4L, currentGeneration = 4L, value = "latest"))

        assertEquals("latest", pending.take(currentGeneration = 4L))
        assertNull(pending.take(currentGeneration = 4L))
    }

    @Test
    fun staleGenerationIsRejectedAndCannotBeDelivered() {
        val pending = GenerationBoundLatest<String>()

        assertTrue(pending.offer(generation = 6L, currentGeneration = 6L, value = "current"))
        assertEquals(
            false,
            pending.offer(generation = 5L, currentGeneration = 6L, value = "stale")
        )

        assertEquals("current", pending.take(currentGeneration = 6L))
        assertNull(pending.take(currentGeneration = 6L))
    }

    @Test
    fun keepAliveNeverOverwritesAnUndeliveredFullState() {
        val fullState = hidden(revision = 20L)
        val keepAlive = keepAlive(revision = 21L)

        // 邮箱只保留一条消息。KeepAlive 抢占这一格会丢掉新 revision 的唯一载体,其后所有
        // 心跳都对不上 revision 而被拒绝(上游 cc1f62f)。
        assertFalse(shouldReplacePendingState(fullState, keepAlive))
        assertTrue(shouldReplacePendingState(null, keepAlive))
        assertTrue(shouldReplacePendingState(keepAlive, keepAlive(revision = 22L)))
        assertTrue(shouldReplacePendingState(fullState, hidden(revision = 21L)))
        assertTrue(shouldReplacePendingState(keepAlive, hidden(revision = 21L)))
    }

    @Test
    fun newerSameRevisionKeepAliveMergesIntoPendingSnapshot() {
        val snapshot = snapshot(revision = 20L, updatedAtElapsedMs = 2_000L, keepAlive = false)
        val keepAlive = keepAlive(revision = 20L).copy(
            updatedAtElapsedMs = 3_000L,
            keepAlive = true,
            wakeSignal = 9L,
            playbackActive = true,
            pauseRetentionEligible = true
        )

        val merged = mergePendingKeepAlive(snapshot, keepAlive)

        assertTrue(merged is AodStateWireMessage.Snapshot)
        val mergedSnapshot = merged as AodStateWireMessage.Snapshot
        assertEquals(snapshot.value, mergedSnapshot.value)
        assertEquals(3_000L, mergedSnapshot.updatedAtElapsedMs)
        assertTrue(mergedSnapshot.keepAlive)
        assertEquals(9L, mergedSnapshot.wakeSignal)
        assertTrue(mergedSnapshot.playbackActive)
        assertTrue(mergedSnapshot.pauseRetentionEligible)
    }

    @Test
    fun keepAliveDoesNotMergeAcrossRevisionOrOlderTimestamp() {
        val snapshot = snapshot(revision = 20L, updatedAtElapsedMs = 2_000L, keepAlive = false)

        assertNull(mergePendingKeepAlive(snapshot, keepAlive(revision = 21L)))
        assertNull(
            mergePendingKeepAlive(
                snapshot,
                keepAlive(revision = 20L).copy(updatedAtElapsedMs = 2_000L)
            )
        )
        assertNull(
            mergePendingKeepAlive(
                snapshot,
                keepAlive(revision = 20L).copy(updatedAtElapsedMs = 3_000L, userId = 10)
            )
        )
    }

    @Test
    fun newerSameRevisionKeepAliveMergesIntoPendingHiddenState() {
        val hidden = hidden(revision = 20L).copy(
            updatedAtElapsedMs = 2_000L,
            keepAlive = false
        )
        val keepAlive = keepAlive(revision = 20L).copy(
            updatedAtElapsedMs = 3_000L,
            keepAlive = true
        )

        val merged = mergePendingKeepAlive(hidden, keepAlive)

        assertTrue(merged is AodStateWireMessage.Hidden)
        val mergedHidden = merged as AodStateWireMessage.Hidden
        assertEquals(3_000L, mergedHidden.updatedAtElapsedMs)
        assertTrue(mergedHidden.keepAlive)
    }

    private fun hidden(revision: Long) = AodStateWireMessage.Hidden(
        revision = revision,
        userId = 0,
        updatedAtElapsedMs = revision * 100L,
        keepAlive = true,
        wakeSignal = 1L,
        playbackActive = true
    )

    private fun snapshot(
        revision: Long,
        updatedAtElapsedMs: Long,
        keepAlive: Boolean
    ) = AodStateWireMessage.Snapshot(
        revision = revision,
        userId = 0,
        updatedAtElapsedMs = updatedAtElapsedMs,
        keepAlive = keepAlive,
        wakeSignal = 1L,
        playbackActive = true,
        value = AodStateWireSnapshot(
            trackGeneration = 1L,
            aodEnabled = true,
            lockscreenEnabled = true,
            seamlessTransitionEnabled = true,
            positionFollowingEnabled = false,
            burnInPattern = "static_bottom",
            burnInIntervalMs = 60_000L,
            suppressStockAodContent = false,
            aodRotateWithDevice = false,
            aodRotationMode = "portrait",
            aodCanvasAnchor = 0.5f,
            aodRotationSettleMs = 1_000L,
            aodCanvasAnchorLandscape = 0.5f,
            aodLandscapeTextScale = 1f,
            aodCanvasPaddingPortraitXPercent = 0f,
            aodCanvasPaddingPortraitYPercent = 0f,
            aodCanvasPaddingLandscapeXPercent = 0f,
            aodCanvasPaddingLandscapeYPercent = 0f,
            original = "line",
            romanized = "",
            translated = "",
            nextLine = "",
            metadata = "track",
            alignedRight = false,
            lineLevelSync = false,
            lineStartMs = 0L,
            lineEndMs = 1_000L,
            durationMs = 2_000L,
            positionMs = 500L,
            sampledAtElapsedMs = updatedAtElapsedMs,
            speed = 1f,
            words = listOf(AodStateWireWord("line", "", 0L, 1_000L, true, 0, 4)),
            ruby = listOf(AodStateWireRuby(0, 4, "")),
            layoutGroups = listOf(AodStateWireLayoutGroup(0, 4, "word", true, 1.0)),
            weight = "Medium",
            textSizeMode = "normal",
            textSizeCustom = 100,
            secondaryMode = "Main only",
            animationMode = "Gradient",
            glowMode = "Off",
            motionMode = "Fluid",
            lineSyncFillMode = "None",
            overflowMode = "Wrap",
            transitionMode = "None",
            fontFamily = "noto",
            alignmentMode = "auto",
            metadataVisible = true,
            metadataAnchor = "top",
            adaptiveSectioning = true
        )
    )

    private fun keepAlive(revision: Long) = AodStateWireMessage.KeepAlive(
        revision = revision,
        userId = 0,
        updatedAtElapsedMs = revision * 100L,
        keepAlive = true,
        wakeSignal = 1L,
        playbackActive = true
    )
}
