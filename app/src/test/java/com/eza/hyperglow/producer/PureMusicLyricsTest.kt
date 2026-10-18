package com.eza.hyperglow.producer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯音乐标记检测(移植 HyperLyric `PureMusicLyricDetector`):剥离标点/空白后与
 * 「纯音乐请欣赏」精确相等即判定;整首快照含一行标记即为纯音乐曲目,流式单行源按当前行判定。
 */
class PureMusicLyricsTest {

    @Test
    fun markerLineMatchesAfterNormalizingPunctuationAndSpaces() {
        // 参考实现逐字对齐:带标点/空白/表情的变体也应命中(不能只做等值比较)。
        assertTrue(isPureMusicMarkerLine("纯音乐请欣赏"))
        assertTrue(isPureMusicMarkerLine("纯音乐，请欣赏"))
        assertTrue(isPureMusicMarkerLine("纯音乐请欣赏 🎶"))
        assertTrue(isPureMusicMarkerLine("  纯音乐请欣赏  "))
        assertFalse(isPureMusicMarkerLine("纯音乐请欣赏啊"))
        assertFalse(isPureMusicMarkerLine("这是一句普通歌词"))
        assertFalse(isPureMusicMarkerLine(""))
        assertFalse(isPureMusicMarkerLine(null))
    }

    @Test
    fun wholeSongIsPureMusicWhenAnyRowCarriesMarker() {
        val rows = listOf(
            row("纯音乐请欣赏")
        )
        assertTrue(isPureMusicSong(rows))
        assertFalse(isPureMusicSong(listOf(row("普通歌词"))))
    }

    @Test
    fun resolvePrefersFullSnapshotOverCurrentLine() {
        // 有整首快照时以快照为准(这才是歌曲级结论);拿不到快照时回落当前行文本。
        val snapshotRows = listOf(row("普通歌词"))
        assertFalse(resolvePureMusic(snapshotRows, currentLine = "纯音乐请欣赏"))

        assertTrue(resolvePureMusic(listOf(row("纯音乐请欣赏")), currentLine = "普通歌词"))
        assertTrue(resolvePureMusic(null, currentLine = "纯音乐请欣赏"))
        assertFalse(resolvePureMusic(null, currentLine = "普通歌词"))
    }

    private fun row(text: String) = LyricSongRow(startMs = 0L, endMs = 1_000L, text = text)
}
