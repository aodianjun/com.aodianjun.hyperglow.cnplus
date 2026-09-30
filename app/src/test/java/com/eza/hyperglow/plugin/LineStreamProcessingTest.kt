package com.eza.hyperglow.plugin

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricSongRow
import com.eza.hyperglow.producer.LyricSongSnapshot
import com.eza.hyperglow.producer.LyricWord
import com.eza.hyperglow.producer.ProducerRenderModes
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐行源接入插件链的纯函数测试：[LineStreamAggregator] 的累积/去重/会话重置，
 * [PluginSongBridge.fromSnapshot] 的映射，以及 enrichState 对合成快照的回向富化。
 */
class LineStreamAggregatorTest {

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium", textSize = "normal", textSizeCustom = 100,
        secondary = "Main only", animation = "Karaoke fill", glow = "Off",
        lineSyncFill = "Top to bottom", overflow = "Wrap", transition = "Fade up",
        font = "spotify"
    )

    /** SuperLyric 形态的最小状态：lineStartMs/lineEndMs 真实，durationMs 恒 0（未用）。 */
    private fun state(
        producerId: String = "superlyric",
        generation: Int = 0,
        trackUri: String = "superlyric:song",
        line: String = "",
        lineStartMs: Long = 0L,
        lineEndMs: Long = 0L,
        translatedLine: String = "",
        romanizedLine: String = ""
    ) = LyricProducerState(
        producerId = producerId,
        generation = generation,
        sequence = 1L,
        status = "ready",
        trackUri = trackUri,
        title = "song", artist = "", album = "", imageId = "",
        line = line, romanizedLine = romanizedLine, translatedLine = translatedLine,
        lineIndex = 0, positionMs = lineStartMs, durationMs = lineEndMs,
        lineStartMs = lineStartMs, lineEndMs = lineEndMs,
        sampledAtElapsedMs = 0L, speed = 1f, playing = true,
        receivedAtElapsedMs = 0L, words = null, renderModes = renderModes()
    )

    @Test
    fun accumulatesDistinctLinesInStartOrder() {
        val aggregator = LineStreamAggregator()
        val first = aggregator.onState(state(line = "first", lineStartMs = 1_000, lineEndMs = 5_000))
        assertTrue(first.addedNewRow)
        assertEquals(1, first.snapshot?.rows?.size)
        val second = aggregator.onState(state(line = "second", lineStartMs = 5_000, lineEndMs = 9_000))
        assertTrue(second.addedNewRow)
        val rows = second.snapshot?.rows.orEmpty()
        assertEquals(2, rows.size)
        assertEquals("first", rows[0].text)
        assertEquals("second", rows[1].text)
        assertEquals(9_000L, second.snapshot?.durationMs)
    }

    @Test
    fun repeatedEmissionOfSameLineIsNotNewContent() {
        val aggregator = LineStreamAggregator()
        aggregator.onState(state(line = "first", lineStartMs = 1_000, lineEndMs = 5_000))
        val repeat = aggregator.onState(state(line = "first", lineStartMs = 1_000, lineEndMs = 5_000))
        assertFalse(repeat.addedNewRow)
        assertEquals(1, repeat.snapshot?.rows?.size)
    }

    @Test
    fun sameStartWithCorrectedTextOverwritesAndCountsAsNew() {
        val aggregator = LineStreamAggregator()
        aggregator.onState(state(line = "old", lineStartMs = 1_000, lineEndMs = 5_000))
        val corrected = aggregator.onState(state(line = "new", lineStartMs = 1_000, lineEndMs = 5_000))
        assertTrue(corrected.addedNewRow)
        assertEquals("new", corrected.snapshot?.rows?.single()?.text)
    }

    @Test
    fun lateArrivingTranslationFillsRowWithoutMarkingNew() {
        val aggregator = LineStreamAggregator()
        aggregator.onState(state(line = "first", lineStartMs = 1_000, lineEndMs = 5_000))
        val late = aggregator.onState(
            state(line = "first", lineStartMs = 1_000, lineEndMs = 5_000, translatedLine = "translation")
        )
        assertFalse(late.addedNewRow)
        assertEquals("translation", late.snapshot?.rows?.single()?.translation)
    }

    @Test
    fun sessionKeyChangeResetsBuffer() {
        val aggregator = LineStreamAggregator()
        aggregator.onState(state(line = "first", lineStartMs = 1_000, lineEndMs = 5_000))
        val next = aggregator.onState(
            state(
                trackUri = "superlyric:another",
                line = "new song line", lineStartMs = 2_000, lineEndMs = 6_000
            )
        )
        assertTrue(next.addedNewRow)
        val rows = next.snapshot?.rows.orEmpty()
        assertEquals(1, rows.size)
        assertEquals("new song line", rows[0].text)
    }

    @Test
    fun blankLineOrZeroWindowIsIgnored() {
        val aggregator = LineStreamAggregator()
        assertNull(aggregator.onState(state(line = "")).snapshot)
        assertNull(
            aggregator.onState(state(line = "text", lineStartMs = 5_000, lineEndMs = 5_000)).snapshot
        )
    }

    @Test
    fun snapshotCarriesSessionTriple() {
        val aggregator = LineStreamAggregator()
        val st = state(line = "first", lineStartMs = 1_000, lineEndMs = 5_000)
        val snapshot = aggregator.onState(st).snapshot!!
        assertTrue(snapshot.matches(st))
        assertFalse(snapshot.matches(st.copy(producerId = "lyricon")))
        assertFalse(snapshot.matches(st.copy(trackUri = "superlyric:other")))
    }
}

class PluginSongBridgeSnapshotTest {

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium", textSize = "normal", textSizeCustom = 100,
        secondary = "Main only", animation = "Karaoke fill", glow = "Off",
        lineSyncFill = "Top to bottom", overflow = "Wrap", transition = "Fade up",
        font = "spotify"
    )

    private fun state(trackUri: String = "superlyric:song", positionMs: Long = 0L) =
        LyricProducerState(
            producerId = "superlyric",
            generation = 0,
            sequence = 1L,
            status = "ready",
            trackUri = trackUri,
            title = "song", artist = "artist", album = "album", imageId = "",
            line = "raw", romanizedLine = "", translatedLine = "",
            lineIndex = 0, positionMs = positionMs, durationMs = 0L,
            sampledAtElapsedMs = 0L, speed = 1f, playing = true,
            receivedAtElapsedMs = 0L, words = null, renderModes = renderModes()
        )

    private fun snapshot(rows: List<LyricSongRow> = defaultRows()) = LyricSongSnapshot(
        producerId = "superlyric",
        generation = 0,
        trackUri = "superlyric:song",
        durationMs = 9_000L,
        rows = rows
    )

    private fun defaultRows() = listOf(
        LyricSongRow(
            startMs = 1_000, endMs = 5_000, text = "line1",
            translation = "trans1", roma = "roma1"
        ),
        LyricSongRow(startMs = 5_000, endMs = 9_000, text = "line2")
    )

    @Test
    fun fromSnapshotMapsRowsAndCarriesSessionIdentity() {
        val song = PluginSongBridge.fromSnapshot(state(), snapshot())
        assertEquals("superlyric:song", song.id)
        assertEquals("song", song.name)
        assertEquals(9_000L, song.duration)
        val rows = song.lyrics.orEmpty()
        assertEquals(2, rows.size)
        assertEquals(1_000L, rows[0].begin)
        assertEquals(5_000L, rows[0].end)
        assertEquals(4_000L, rows[0].duration)
        assertEquals("line1", rows[0].text)
        assertEquals("trans1", rows[0].translation)
        assertEquals("roma1", rows[0].roma)
        assertEquals("roma1", rows[0].secondary)
        assertEquals("LEAD", rows[0].metadata?.values?.get("role"))
        assertEquals("line2", rows[1].text)
        assertNull(rows[1].translation)
        assertNull(rows[1].words)
    }

    @Test
    fun fromSnapshot_backfillsTranslationFromWordsAndCarriesThemAcross() {
        // 只带词表的翻译(冗余对文本缺位)不能被桥丢掉:文本按词拼出兜底,词表原样过桥。
        val wordOnly = LyricSongRow(
            startMs = 1_000, endMs = 5_000, text = "line1",
            translationWords = listOf(
                LyricWord("译", "", 1_000L, 3_000L, false),
                LyricWord("文", "", 3_000L, 5_000L, false)
            )
        )
        val song = PluginSongBridge.fromSnapshot(state(), snapshot(listOf(wordOnly)))
        val row = song.lyrics.orEmpty().single()
        assertEquals("译文", row.translation)
        assertEquals(listOf("译", "文"), row.translationWords?.map { it.text })
    }

    @Test
    fun fromSnapshot_keepsTranslationTextAndDoesNotLetWordsOverrideIt() {
        // 冗余对同时存在:文本优先,词表只兜底(与 LyricSongRow.effectiveTranslation 同规则)。
        val both = LyricSongRow(
            startMs = 1_000, endMs = 5_000, text = "line1",
            translation = "文本",
            translationWords = listOf(LyricWord("词", "", 1_000L, 5_000L, false))
        )
        val song = PluginSongBridge.fromSnapshot(state(), snapshot(listOf(both)))
        val row = song.lyrics.orEmpty().single()
        assertEquals("文本", row.translation)
        assertEquals(listOf("词"), row.translationWords?.map { it.text })
    }

    @Test
    fun enrichStatePatchesActiveRowTranslationFromSynthesizedSong() {
        val st = state(positionMs = 6_000L) // 活动行 = 第二行 [5000, 9000)
        val original = PluginSongBridge.fromSnapshot(st, snapshot())
        val translated = original.copy(
            lyrics = original.lyrics?.mapIndexed { index, row ->
                row.copy(translation = "T$index")
            }
        )
        val patched = PatchedSong(
            sessionKey = PluginSongBridge.sessionKey(st),
            song = translated,
            changedSongFields = emptySet(),
            changedLyricFields = setOf(PluginLyricField.TRANSLATION)
        )
        val enriched = PluginSongBridge.enrichState(st, patched)
        assertEquals("T1", enriched.translatedLine)
        // TEXT 未变：原文行与 nextLine 不被插件覆盖。
        assertEquals("raw", enriched.line)
    }

    @Test
    fun enrichState_appliesWordsOnlyTranslationResult() {
        // 插件只给 translationWords(如声明 TRANSLATION_WORDS 的词级翻译结果)时同样回填译文。
        val st = state(positionMs = 6_000L) // 活动行 = 第二行 [5000, 9000)
        val original = PluginSongBridge.fromSnapshot(st, snapshot())
        val wordsOnly = original.copy(
            lyrics = original.lyrics?.mapIndexed { index, row ->
                row.copy(
                    translation = null,
                    translationWords = listOf(
                        PluginWord(begin = 5_000L, end = 9_000L, duration = 4_000L, text = "W$index")
                    )
                )
            }
        )
        val patched = PatchedSong(
            sessionKey = PluginSongBridge.sessionKey(st),
            song = wordsOnly,
            changedSongFields = emptySet(),
            changedLyricFields = setOf(PluginLyricField.TRANSLATION_WORDS)
        )

        val enriched = PluginSongBridge.enrichState(st, patched)

        assertEquals("W1", enriched.translatedLine)
    }

    @Test
    fun enrichState_blankTranslationResultKeepsProducerLine() {
        // 词表也为空时不覆盖生产者译文(keepUnlessBlank:增强显示而非替换显示)。
        val st = state(positionMs = 6_000L).copy(translatedLine = "producer-trans")
        val original = PluginSongBridge.fromSnapshot(st, snapshot())
        val blanked = original.copy(
            lyrics = original.lyrics?.map { row ->
                row.copy(translation = null, translationWords = emptyList())
            }
        )
        val patched = PatchedSong(
            sessionKey = PluginSongBridge.sessionKey(st),
            song = blanked,
            changedSongFields = emptySet(),
            changedLyricFields = setOf(PluginLyricField.TRANSLATION_WORDS)
        )

        val enriched = PluginSongBridge.enrichState(st, patched)

        assertEquals("producer-trans", enriched.translatedLine)
    }

    @Test
    fun enrichStateKeepsProducerLineWhenPatchedRowTextIsBlank() {
        val st = state(positionMs = 6_000L) // 活动行 = 第二行 [5000, 9000)
        val original = PluginSongBridge.fromSnapshot(st, snapshot())
        val blanked = original.copy(
            lyrics = original.lyrics?.map { row -> row.copy(text = "") }
        )
        val patched = PatchedSong(
            sessionKey = PluginSongBridge.sessionKey(st),
            song = blanked,
            changedSongFields = emptySet(),
            changedLyricFields = setOf(PluginLyricField.TEXT)
        )

        val enriched = PluginSongBridge.enrichState(st, patched)

        // 插件空文本不覆盖非空生产者行：否则活动行被抹空，AOD 只剩 🎶 占位。
        assertEquals("raw", enriched.line)
    }

    @Test
    fun enrichStateKeepsProducerWordsWhenPatchedRowHasNoWords() {
        val producerWords = listOf(
            LyricWord("raw", "", 5_000L, 6_000L, false)
        )
        val st = state(positionMs = 6_000L).copy(words = producerWords)
        val original = PluginSongBridge.fromSnapshot(st, snapshot())
        val stripped = original.copy(
            lyrics = original.lyrics?.map { row -> row.copy(words = null) }
        )
        val patched = PatchedSong(
            sessionKey = PluginSongBridge.sessionKey(st),
            song = stripped,
            changedSongFields = emptySet(),
            changedLyricFields = setOf(PluginLyricField.WORDS)
        )

        val enriched = PluginSongBridge.enrichState(st, patched)

        // 空词表不覆盖生产者词级时间轴（逐字卡拉OK依赖它）。
        assertEquals(producerWords, enriched.words)
    }

    @Test
    fun diffOnRowCountChangeOnlyMarksContentBearingFields() {
        val base = song(
            listOf(
                line(0, 1_000, "a"),
                line(1_000, 2_000, "b")
            )
        )
        val restructured = song(
            listOf(
                line(0, 500, "a1"),
                line(500, 1_000, "a2"),
                line(1_000, 2_000, "b")
            )
        )
        val emptyRows = song(
            listOf(
                line(0, 500, ""),
                line(500, 1_000, ""),
                line(1_000, 2_000, "")
            )
        )

        val (songFields, lyricFields) = PluginPipeline.diff(base, restructured)

        // 行数变化 ≠ 正文变化：只标记新表里实际携带内容的字段。
        assertTrue(PluginLyricField.TEXT in lyricFields)
        assertFalse(PluginLyricField.TRANSLATION in lyricFields)
        assertFalse(PluginLyricField.ROMA in lyricFields)
        assertFalse(PluginLyricField.WORDS in lyricFields)
        assertTrue(songFields.isEmpty())

        val (_, emptyLyricFields) = PluginPipeline.diff(base, emptyRows)
        assertTrue(emptyLyricFields.isEmpty())
    }

    @Test
    fun diffOnRowCountChange_marksTranslationWordsWhenWordsCarryContent() {
        // 行数变化的 REPLACE 也要识别词级翻译内容：只带 translationWords 的新表必须标记
        // TRANSLATION_WORDS（文本字段为空），否则回向不会回填译文。
        val base = song(
            listOf(
                line(0, 1_000, "a"),
                line(1_000, 2_000, "b")
            )
        )
        val wordsOnly = song(
            listOf(
                line(0, 500, "a1").copy(
                    translationWords = listOf(PluginWord(begin = 0, end = 500, duration = 500, text = "译"))
                ),
                line(500, 1_000, "a2"),
                line(1_000, 2_000, "b")
            )
        )

        val (_, lyricFields) = PluginPipeline.diff(base, wordsOnly)

        assertTrue(PluginLyricField.TRANSLATION_WORDS in lyricFields)
        assertFalse(PluginLyricField.TRANSLATION in lyricFields)
    }

    private fun line(begin: Long, end: Long, text: String) = PluginLyricLine(
        begin = begin,
        end = end,
        duration = end - begin,
        isAlignedRight = false,
        metadata = PluginMetadata(values = mapOf("role" to "LEAD")),
        text = text
    )

    private fun song(rows: List<PluginLyricLine>) = PluginSong(
        id = "superlyric:song",
        name = "song",
        artist = "artist",
        album = "album",
        duration = 9_000L,
        metadata = PluginMetadata(values = mapOf("producerId" to "superlyric")),
        lyrics = rows
    )
}
