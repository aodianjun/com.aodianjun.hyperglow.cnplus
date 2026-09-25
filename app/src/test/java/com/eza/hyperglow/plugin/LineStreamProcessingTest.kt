package com.eza.hyperglow.plugin

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricSongRow
import com.eza.hyperglow.producer.LyricSongSnapshot
import com.eza.hyperglow.producer.ProducerRenderModes
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
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

    private fun snapshot() = LyricSongSnapshot(
        producerId = "superlyric",
        generation = 0,
        trackUri = "superlyric:song",
        durationMs = 9_000L,
        rows = listOf(
            LyricSongRow(
                startMs = 1_000, endMs = 5_000, text = "line1",
                translation = "trans1", roma = "roma1"
            ),
            LyricSongRow(startMs = 5_000, endMs = 9_000, text = "line2")
        )
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
}
