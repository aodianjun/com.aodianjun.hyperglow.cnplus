package com.eza.hyperglow.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodStateBridgeTest {
    @Test
    fun identicalStateIsSuppressed() {
        val state = state()

        assertFalse(shouldRepublish(state, state.copy(positionMs = 1_100L, sampledAtElapsedMs = 1_100L)))
    }

    @Test
    fun discontinuousPositionForcesPublish() {
        val last = state(positionMs = 1_000L, sampledAtElapsedMs = 1_000L)
        val next = last.copy(positionMs = 1_851L, sampledAtElapsedMs = 1_100L)

        assertTrue(shouldRepublish(last, next))
    }

    @Test
    fun transportedRangesSurviveTrimmedDisplayMapping() {
        assertEquals(0 to 4, trimAodSourceRange(6, 4, 2, 2, 6))
        assertEquals(-1 to -1, trimAodSourceRange(6, 4, 2, -1, -1))
        assertEquals(-1 to -1, trimAodSourceRange(6, 4, 2, 4, 2))
    }

    @Test
    fun speedChangeForcesPublish() {
        val last = state()

        assertTrue(shouldRepublish(last, last.copy(speed = 1.25f)))
    }

    @Test
    fun trackGenerationChangeForcesPublish() {
        val last = state()

        // 切歌后 trackGeneration 变化，即使位置增量很小(本应被"无实质变化"抑制)，也必须强制重发，
        // 让 SystemUI 立即拿到新歌的 metadata/标题，而不是在位置源静默期间被抑制。
        val next = last.copy(trackGeneration = 42L, positionMs = 1_100L, sampledAtElapsedMs = 1_100L)

        assertTrue(shouldRepublish(last, next))
    }

    @Test
    fun adaptiveSectioningDefaultsOnAndChangesRepublishState() {
        val last = state()

        assertTrue(last.adaptiveSectioning)
        assertTrue(shouldRepublish(last, last.copy(adaptiveSectioning = false)))
    }

    @Test
    fun burnInScheduleChangesRepublishState() {
        val last = state()

        assertTrue(shouldRepublish(last, last.copy(burnInPattern = "four_corner")))
        assertTrue(shouldRepublish(last, last.copy(burnInIntervalMs = 120_000L)))
    }

    @Test
    fun pausedHiddenThenTerminalHiddenRepublishes() {
        val paused = AodDisplayState(visible = false, pauseRetentionEligible = true)

        assertTrue(shouldRepublish(paused, paused.copy(pauseRetentionEligible = false)))
    }

    @Test
    fun utf16TruncationNeverSplitsEmojiSurrogatePair() {
        val source = "x".repeat(AodStateWireLimits.MAX_LYRIC_CHARS - 1) + "😀"

        val normalized = normalizeAodDisplayState(state().copy(original = source))

        assertEquals(AodStateWireLimits.MAX_LYRIC_CHARS - 1, normalized.original.length)
        assertTrue(normalized.original.isNotEmpty())
        assertTrue(AodStateWireCodec.encode(
            encodeNormalizedAodStatePublication(normalized, 9L, 10L).message
        ) != null)
    }

    @Test
    fun keepAliveRefreshAlsoRefreshesReconnectReplayTimestamp() {
        val current = requireNotNull(
            encodeNormalizedAodStatePublication(
                normalizeAodDisplayState(state()),
                revision = 9L,
                updatedAtElapsedMs = 10L
            ).message as? AodStateWireMessage.Snapshot
        )

        val refreshed = refreshAodStateWireSnapshot(current, updatedAtElapsedMs = 4_010L)

        assertEquals(4_010L, refreshed.updatedAtElapsedMs)
        assertEquals(current.value, refreshed.value)
        assertEquals(current.revision, refreshed.revision)
    }

    @Test
    fun fullSnapshotIsRepublishedOnItsOwnCadence() {
        assertTrue(shouldRepublishFullSnapshot(nowElapsedMs = 10L, lastFullPublishAtElapsedMs = Long.MIN_VALUE))
        assertFalse(shouldRepublishFullSnapshot(nowElapsedMs = 4_000L, lastFullPublishAtElapsedMs = 0L))
        assertTrue(shouldRepublishFullSnapshot(nowElapsedMs = 4_500L, lastFullPublishAtElapsedMs = 0L))
        assertTrue(shouldRepublishFullSnapshot(nowElapsedMs = 9_000L, lastFullPublishAtElapsedMs = 0L))
    }

    @Test
    fun aggregateOverflowDropsEnhancementEntriesButKeepsTheLine() {
        val normalized = normalizeAodDisplayState(
            state().copy(
                keepAlive = true,
                words = List(100) {
                    AodDisplayWord("x".repeat(500), "", 0L, 1L, false)
                }
            )
        )

        // 词级数据是可选增强：超预算就丢弃增强条目，行文本与 KeepAlive 语义保留，
        // 而不是让整包降级为 Hidden（那会让整句歌词消失）。
        assertTrue(normalized.words.size < 100)
        assertEquals("line", normalized.original)
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertTrue(publication.message.keepAlive)
        assertEquals(9L, publication.message.revision)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun illFormedUtf16InTextFieldsIsRepairedBeforeEncode() {
        val highLone = "line" + 0xD800.toChar() + "tail"
        val lowLone = 0xDC00.toChar() + "head"

        val normalized = normalizeAodDisplayState(
            state().copy(original = highLone, translated = lowLone)
        )

        // 一个非法码点曾让整包被拒收、静默降级为 Hidden；现在替换为 U+FFFD 后正常下发。
        assertEquals("line" + 0xFFFD.toChar() + "tail", normalized.original)
        assertEquals(0xFFFD.toChar() + "head", normalized.translated)
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun truncationLandingOnWhitespaceStillEncodes() {
        val source = "x".repeat(AodStateWireLimits.MAX_LYRIC_CHARS - 1) + " " + "y".repeat(8)

        val normalized = normalizeAodDisplayState(state().copy(original = source))

        // 截断落点正好停在空白上时，takeUtf16Prefix 后必须二次 trim——
        // 否则 original != original.trim()，快照被拒收并降级为 Hidden。
        assertEquals("x".repeat(AodStateWireLimits.MAX_LYRIC_CHARS - 1), normalized.original)
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun nonFiniteLayoutGroupConfidenceIsDroppedInsteadOfRejecting() {
        val normalized = normalizeAodDisplayState(
            state().copy(
                layoutGroups = listOf(
                    AodDisplayLayoutGroup(0, 4, "phrase", true, Double.NaN),
                    AodDisplayLayoutGroup(0, 4, "phrase", true, 2.5),
                    AodDisplayLayoutGroup(0, 4, "phrase", true, 0.5)
                )
            )
        )

        // NaN 置信度曾让整包拒收（coerceIn 对 NaN 不生效）；非有限直接丢弃，越界钳进 [0,1]。
        assertEquals(2, normalized.layoutGroups.size)
        assertTrue(normalized.layoutGroups.all { it.confidence.isFinite() && it.confidence in 0.0..1.0 })
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun zeroDurationVisibleStateFallsBackToLineTiming() {
        val normalized = normalizeAodDisplayState(
            state().copy(durationMs = 0L, positionMs = 500L, lineEndMs = 3_000L)
        )

        assertEquals(3_000L, normalized.durationMs)
        assertTrue(normalized.lineEndMs <= normalized.durationMs)
        assertTrue(normalized.positionMs <= normalized.durationMs)
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun lineEndBeyondDurationIsClampedIntoSnapshot() {
        val normalized = normalizeAodDisplayState(
            state().copy(lineEndMs = 9_000L)
        )

        assertEquals(2_000L, normalized.durationMs)
        assertEquals(2_000L, normalized.lineEndMs)
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun wordTimingsBeyondDurationAreClampedIntoSnapshot() {
        val normalized = normalizeAodDisplayState(
            state().copy(
                words = listOf(AodDisplayWord("a", "", 1_500L, 5_000L, false))
            )
        )

        assertTrue(normalized.words.all { it.endMs <= normalized.durationMs })
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun hiddenStateKeepsZeroDurationNormalization() {
        val normalized = normalizeAodDisplayState(
            AodDisplayState(visible = false, durationMs = 0L, lineEndMs = 5_000L)
        )

        assertEquals(0L, normalized.durationMs)
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Hidden)
    }

    private fun state(
        positionMs: Long = 1_000L,
        sampledAtElapsedMs: Long = 1_000L
    ) = AodDisplayState(
        visible = true,
        original = "line",
        lineStartMs = 0L,
        lineEndMs = 2_000L,
        durationMs = 2_000L,
        positionMs = positionMs,
        sampledAtElapsedMs = sampledAtElapsedMs
    )
}
