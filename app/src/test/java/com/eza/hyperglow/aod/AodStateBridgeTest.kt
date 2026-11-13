package com.eza.hyperglow.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun keepAliveExpiryEdgeForcesPublishAndSurvivesEncode() {
        val last = state().copy(keepAlive = false, keepAliveExpired = false)

        // 到点是语义边沿：位置连续也必须重发，否则 SystemUI 侧拿不到停续期的信号。
        val expired = last.copy(
            keepAliveExpired = true,
            positionMs = 1_100L,
            sampledAtElapsedMs = 1_100L
        )
        assertTrue(shouldRepublish(last, expired))

        val publication = encodeNormalizedAodStatePublication(
            normalizeAodDisplayState(expired),
            revision = 11L,
            updatedAtElapsedMs = 12L
        )
        assertTrue(publication.message.keepAliveExpired)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
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
    fun noLyricsFlagSurvivesNormalizedPublication() {
        // AodDisplayState -> normalize -> wire 全链路:真无歌词标记必须活到渲染端,
        // 且与 pureMusic 一样参与重发判定(状态字段变化即重发)。
        val last = state()
        assertTrue(shouldRepublish(last, last.copy(noLyrics = true)))

        val publication = encodeNormalizedAodStatePublication(
            normalizeAodDisplayState(last.copy(noLyrics = true)),
            revision = 9L,
            updatedAtElapsedMs = 10L
        )
        val message = publication.message as AodStateWireMessage.Snapshot
        assertTrue(message.value.noLyrics)
        assertFalse(message.value.pureMusic)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    @Test
    fun tailEligibleFlagSurvivesNormalizedPublication() {
        // 尾奏上下文标记与 noLyrics 同链路:归一化/编码后仍为真,且参与重发判定。
        val last = state()
        assertTrue(shouldRepublish(last, last.copy(tailEligible = true)))

        val publication = encodeNormalizedAodStatePublication(
            normalizeAodDisplayState(last.copy(tailEligible = true)),
            revision = 10L,
            updatedAtElapsedMs = 11L
        )
        val message = publication.message as AodStateWireMessage.Snapshot
        assertTrue(message.value.tailEligible)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
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

    /** 插件逐字翻译词表:空片段整条丢弃(不贡献译文文本)、时间窗钳到歌长、source 范围恒 -1。 */
    @Test
    fun translationWordsClampToDurationAndDropEmptyFragments() {
        val normalized = normalizeAodDisplayState(
            state().copy(
                translated = "我爱你",
                translationWords = listOf(
                    AodDisplayWord("我", "", 0L, 600L, true),
                    AodDisplayWord("", "", 600L, 900L, true),
                    AodDisplayWord(" 爱", "", 1_500L, 5_000L, true)
                )
            )
        )

        assertEquals(listOf("我", " 爱"), normalized.translationWords.map { it.text })
        assertTrue(normalized.translationWords.all { it.endMs <= normalized.durationMs })
        assertTrue(normalized.translationWords.all { it.sourceStart == -1 && it.sourceEnd == -1 })
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    /**
     * 超长逐字翻译词表按序裁剪（可选增强降级），不连累整包：不裁剪的话 isValidSnapshot
     * 会因聚合文本预算超限拒收整包，宿主回退 Hidden——整句歌词因插件词表超长而消失。
     */
    @Test
    fun oversizedTranslationWordsAreTrimmedInsteadOfRejectingTheSnapshot() {
        val huge = "界".repeat(AodStateWireLimits.MAX_LYRIC_CHARS)
        val normalized = normalizeAodDisplayState(
            state().copy(
                translated = "我爱你",
                translationWords = List(AodStateWireLimits.MAX_WORDS) {
                    AodDisplayWord(huge, "", 0L, 1L, true)
                }
            )
        )

        assertTrue(normalized.translationWords.size < AodStateWireLimits.MAX_WORDS)
        assertTrue(normalized.translationWords.isNotEmpty())
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    /**
     * 对唱并发行与主行共享 MAX_WORDS:联合 128 词通过(并发行整条保留);129 词(以及文档
     * 举例的主 80 + duet 60 = 140)时只丢并发行,主文本逐字保留——此前两边各自合法、合计
     * 超限时 wire 校验拒收整包,整屏降级为 Hidden(该行整屏消失,只留一条 warning)。
     */
    @Test
    fun jointWordBudgetKeepsDuetAt128AndDropsOnlyTheDuetBeyondIt() {
        val kept = normalizeAodDisplayState(
            state().copy(
                words = mainWords(68),
                duetLine = duetLine(words = duetWords(60))
            )
        )
        assertEquals(128, kept.words.size + requireNotNull(kept.duetLine).words.size)
        assertSnapshotRoundTrip(kept)

        for (mainCount in listOf(69, 80)) {
            val degraded = normalizeAodDisplayState(
                state().copy(
                    words = mainWords(mainCount),
                    duetLine = duetLine(words = duetWords(60))
                )
            )
            assertNull(
                "主 $mainCount 词 + duet 60 词合计 ${mainCount + 60} 词应整条丢弃并发行",
                degraded.duetLine
            )
            assertEquals(mainCount, degraded.words.size)
            assertEquals(mainWords(mainCount).map { it.text }, degraded.words.map { it.text })
            assertEquals("line", degraded.original)
            assertSnapshotRoundTrip(degraded)
        }
    }

    /**
     * 联合聚合 UTF-8 预算:主行 + 并发行合计正好 48KiB 时并发行保留且往返一致;多 1 字节时
     * 只丢并发行(主行原文与词表逐字保留,整包仍是 Snapshot)。此前超限会让 wire 拒收整包。
     */
    @Test
    fun jointAggregateBudgetBoundaryKeepsTheLineAndDropsOnlyTheDuet() {
        val cap = AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES
        val mainText = "界".repeat(AodStateWireLimits.MAX_LYRIC_CHARS)
        val base = state().copy(
            original = mainText,
            words = listOf(AodDisplayWord("x", "", 0L, 1L, false))
        )
        val baseBytes = aggregateTextBytes(base)
        val duetText = "界".repeat(AodStateWireLimits.MAX_LYRIC_CHARS)
        val filler = "界".repeat(143)
        val fillerCount = 100
        val tailBytes = cap - baseBytes - aodUtf8Bytes(duetText) * 3 -
            aodUtf8Bytes(filler) * fillerCount
        assertTrue(
            "补齐词须落在单字段上限内(实际 $tailBytes 字节)",
            tailBytes in 1..AodStateWireLimits.MAX_LYRIC_CHARS * 3
        )
        val tailWord = AodDisplayWord("x".repeat(tailBytes), "", 0L, 1L, false)
        val atLimit = base.copy(
            duetLine = duetLine(
                text = duetText,
                romanized = duetText,
                translated = duetText,
                words = List(fillerCount) { AodDisplayWord(filler, "", 0L, 1L, false) } + tailWord
            )
        )

        val normalized = normalizeAodDisplayState(atLimit)
        val duet = requireNotNull(normalized.duetLine)
        assertEquals(cap, aggregateTextBytes(normalized))
        assertEquals(mainText, normalized.original)
        assertEquals(listOf("x"), normalized.words.map { it.text })
        assertEquals(duetText, duet.text)
        assertEquals(tailWord.text, duet.words.last().text)
        assertSnapshotRoundTrip(normalized)

        val overLimit = normalizeAodDisplayState(
            atLimit.copy(
                duetLine = requireNotNull(atLimit.duetLine).let { line ->
                    line.copy(words = line.words.dropLast(1) + tailWord.copy(text = tailWord.text + "x"))
                }
            )
        )
        assertNull("多 1 字节即整条丢弃并发行", overLimit.duetLine)
        assertEquals(mainText, overLimit.original)
        assertEquals(listOf("x"), overLimit.words.map { it.text })
        assertSnapshotRoundTrip(overLimit)
    }

    /**
     * 并发行词级罗马音同样计入联合聚合预算(与 wire 并发行段同序:文本 → 罗马音 → 译文 →
     * 词表逐条的文本/罗马音):漏计这一档会让 wire 拒收整包、整屏 Hidden。
     */
    @Test
    fun duetWordRomanizedCountsTowardTheJointAggregateBudget() {
        val duetText = "界".repeat(AodStateWireLimits.MAX_LYRIC_CHARS)
        val duetWords = List(120) {
            AodDisplayWord("x", "界".repeat(AodStateWireLimits.MAX_LYRIC_CHARS), 0L, 1L, false)
        }

        val overBudget = normalizeAodDisplayState(
            state().copy(duetLine = duetLine(text = duetText, words = duetWords))
        )

        assertNull("120 词 × 1500B 罗马音远超 48KiB,整条并发行应被丢弃", overBudget.duetLine)
        assertSnapshotRoundTrip(overBudget)

        // 同一条并发行只去掉词级罗马音就装得下:证明计入的正是罗马音那一档。
        val withinBudget = normalizeAodDisplayState(
            state().copy(
                duetLine = duetLine(
                    text = duetText,
                    words = duetWords.map { it.copy(romanized = "") }
                )
            )
        )

        assertEquals(120, requireNotNull(withinBudget.duetLine).words.size)
        assertSnapshotRoundTrip(withinBudget)
    }

    private fun mainWords(count: Int) = List(count) { AodDisplayWord("m$it", "", 0L, 1L, false) }

    private fun duetWords(count: Int) = List(count) { AodDisplayWord("d$it", "", 0L, 1L, false) }

    private fun duetLine(
        text: String = "duet line",
        romanized: String = "",
        translated: String = "",
        words: List<AodDisplayWord> = emptyList()
    ) = AodDisplayDuetLine(
        text = text,
        romanized = romanized,
        translated = translated,
        lineStartMs = 0L,
        lineEndMs = 1_000L,
        words = words
    )

    private fun assertSnapshotRoundTrip(normalized: AodDisplayState) {
        val publication = encodeNormalizedAodStatePublication(normalized, 9L, 10L)
        assertTrue(publication.message is AodStateWireMessage.Snapshot)
        assertEquals(publication.message, AodStateWireCodec.decode(publication.envelope))
    }

    /**
     * 与 [AodStateWireCodec] 同序的聚合 UTF-8 字节合计(只用于构造精确的 48KiB 边界;
     * 是否真的被 wire 接受由 encode/decode 往返把关)。顺序按编码体读取序,含
     * aodRotationMode——解码侧把它计入同一个预算(此前校验侧漏计,压线包会「编码通过、
     * 解码拒收」)。
     */
    private fun aggregateTextBytes(state: AodDisplayState): Int {
        var total = 0
        fun add(value: String) {
            total += aodUtf8Bytes(value)
        }
        listOf(
            state.burnInPattern,
            state.aodRotationMode,
            state.original,
            state.romanized,
            state.translated,
            state.nextLine,
            state.nextLineRomanized,
            state.nextLineTranslated,
            state.metadata,
            state.title,
            state.artist,
            state.album,
            state.weight,
            state.textSizeMode,
            state.secondaryMode,
            state.animationMode,
            state.glowMode,
            state.motionMode,
            state.lineSyncFillMode,
            state.overflowMode,
            state.transitionMode,
            state.fontFamily,
            state.alignmentMode,
            state.metadataAnchor
        ).forEach(::add)
        state.words.forEach { add(it.text); add(it.romanized) }
        state.translationWords.forEach { add(it.text); add(it.romanized) }
        state.ruby.forEach { add(it.reading) }
        state.layoutGroups.forEach { add(it.kind) }
        state.duetLine?.let { duet ->
            add(duet.text)
            add(duet.romanized)
            add(duet.translated)
            duet.words.forEach { add(it.text); add(it.romanized) }
        }
        return total
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
