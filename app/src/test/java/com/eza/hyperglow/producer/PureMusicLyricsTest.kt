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

    @Test
    fun emptyRowsFallbackDetectsAfterConfirmWindow() {
        // 「纯音乐识别」空行兜底:整首快照存在但一行都没有(SuperLyric allLyrics 为空),
        // 时长有效 + 播放越过确认窗口才判定(避免切歌瞬间/慢补源误判)。
        assertTrue(
            resolvePureMusicEnhanced(
                fullSongRows = emptyList(),
                currentLine = "",
                durationMs = 41_500L,
                positionMs = PURE_MUSIC_EMPTY_ROWS_CONFIRM_MS,
                playing = true
            )
        )
    }

    @Test
    fun emptyRowsFallbackRejectsBeforeConfirmWindow() {
        // 播放未越过确认窗口:切歌瞬间源侧歌词往往慢一拍到点,不能立即判纯音乐。
        assertFalse(
            resolvePureMusicEnhanced(
                fullSongRows = emptyList(),
                currentLine = "",
                durationMs = 41_500L,
                positionMs = PURE_MUSIC_EMPTY_ROWS_CONFIRM_MS - 1,
                playing = true
            )
        )
        // 暂停中不判(占位只在确认播放推进后接管)。
        assertFalse(
            resolvePureMusicEnhanced(
                fullSongRows = emptyList(),
                currentLine = "",
                durationMs = 41_500L,
                positionMs = 10_000L,
                playing = false
            )
        )
    }

    @Test
    fun emptyRowsFallbackRejectsMissingOrNonEmptySnapshot() {
        // 快照缺失且 producer 未声明 NONE(双路信号都没有):不判纯音乐。
        assertFalse(
            resolvePureMusicEnhanced(
                fullSongRows = null,
                currentLine = "",
                durationMs = 41_500L,
                positionMs = 10_000L,
                playing = true
            )
        )
        // 快照有行但没有标记:正常有歌词的曲目,不判。
        assertFalse(
            resolvePureMusicEnhanced(
                fullSongRows = listOf(row("普通歌词")),
                currentLine = "普通歌词",
                durationMs = 41_500L,
                positionMs = 10_000L,
                playing = true
            )
        )
    }

    @Test
    fun emptyRowsFallbackRejectsUnknownDuration() {
        // 时长无效(直播流/未知总长):position 无从谈「越过确认窗口」,不判。
        assertFalse(
            resolvePureMusicEnhanced(
                fullSongRows = emptyList(),
                currentLine = "",
                durationMs = 0L,
                positionMs = 10_000L,
                playing = true
            )
        )
    }

    @Test
    fun emptyRowsFallbackStillHonorsMarkerFirst() {
        // 标记文本命中最可靠:即使快照只有标记一行,也在空行兜底之前命中。
        assertTrue(
            resolvePureMusicEnhanced(
                fullSongRows = listOf(row("纯音乐，请欣赏")),
                currentLine = "",
                durationMs = 41_500L,
                positionMs = 0L,
                playing = true
            )
        )
    }

    @Test
    fun producerNoLyricsFoldDetectsAfterConfirmWindow() {
        // Lyricon lines=0 主路径:它对空歌词不产出快照(fullSongSnapshot 返回 null),
        // 增强判定走 producer 歌级声明 lyricKind == NONE + 当前行空白。
        assertTrue(
            resolvePureMusicEnhanced(
                fullSongRows = null,
                currentLine = "",
                durationMs = 41_500L,
                positionMs = PURE_MUSIC_EMPTY_ROWS_CONFIRM_MS,
                playing = true,
                producerNoLyrics = true
            )
        )
    }

    @Test
    fun producerNoLyricsFoldRejectsNonBlankCurrentLine() {
        // 当前行非空白不接管:Spicy 未匹配到文档时桥接的一行是真内容(可能是真歌词),
        // 换成占位文本反而抹掉它;该场景保持原呈现。
        assertFalse(
            resolvePureMusicEnhanced(
                fullSongRows = null,
                currentLine = "桥接的一行歌词",
                durationMs = 41_500L,
                positionMs = 10_000L,
                playing = true,
                producerNoLyrics = true
            )
        )
    }

    @Test
    fun producerNoLyricsFoldRejectsBeforeConfirmWindow() {
        // 确认窗口同样约束歌级声明:Spicy 对「暂未匹配到文档」也报 NONE,
        // 窗口给它留出完成匹配的时机,避免有歌词的歌在开头闪占位。
        assertFalse(
            resolvePureMusicEnhanced(
                fullSongRows = null,
                currentLine = "",
                durationMs = 41_500L,
                positionMs = PURE_MUSIC_EMPTY_ROWS_CONFIRM_MS - 1,
                playing = true,
                producerNoLyrics = true
            )
        )
    }

    private fun row(text: String) = LyricSongRow(startMs = 0L, endMs = 1_000L, text = text)
}
