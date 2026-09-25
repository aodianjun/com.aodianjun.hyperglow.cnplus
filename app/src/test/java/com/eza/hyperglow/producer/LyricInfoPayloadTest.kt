package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [parseLyricInfoPayload] — lyricInfo JSON 解析覆盖三种方言:LyricInfo 完整版
 * (songName/artist/album/songId/lyric/format/translation + 可选 rawLyric/roma)、精简版
 * (播放器原生输出,QQ 音乐翻译在 transLyric、songId 可能是数字)与 ColorOS Live Lyrics
 * Bridge Provider v5 契约(在相同 rawLyric 逐字语义上增加规范翻译 translationLyric)。
 *
 * 宽松提取的关键约束:任何字段类型不匹配只降级该字段,绝不让整个 payload 解析失败
 * (否则连歌词一起丢,息屏只剩歌名)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LyricInfoPayloadTest {

    @Test
    fun parsesFullVersionCanonicalFormat() {
        val payload = parseLyricInfoPayload(
            """
            {
              "songName": "歌名",
              "artist": "歌手",
              "album": "专辑",
              "songId": "12345",
              "lyric": "[00:16.440]<00:16.440>歌<00:16.800>词",
              "format": "elrc",
              "translation": "[00:16.440]翻译"
            }
            """.trimIndent()
        )
        assertEquals("歌名", payload?.songName)
        assertEquals("歌手", payload?.artist)
        assertEquals("专辑", payload?.album)
        assertEquals("12345", payload?.songId)
        assertEquals("[00:16.440]<00:16.440>歌<00:16.800>词", payload?.lyric)
        assertEquals("elrc", payload?.format)
        assertEquals("[00:16.440]翻译", payload?.translation)
    }

    @Test
    fun parsesLiteNativeFormat_withQqMusicExtras() {
        // QQ 音乐精简版原生输出:transLyric 携带翻译,noLyric/lyricType/txtlyric 为额外字段。
        val payload = parseLyricInfoPayload(
            """
            {
              "lyric": "[00:16.44]歌词",
              "songName": "歌名",
              "artist": "歌手",
              "noLyric": 0,
              "lyricType": 2,
              "transLyric": "[00:16.44]翻译",
              "txtlyric": ""
            }
            """.trimIndent()
        )
        assertEquals("歌名", payload?.songName)
        assertEquals("歌手", payload?.artist)
        assertEquals("[00:16.44]歌词", payload?.lyric)
        assertEquals("[00:16.44]翻译", payload?.transLyric)
        assertNull(payload?.translation)
    }

    @Test
    fun numericSongIdDegradesToTextInsteadOfFailingWholePayload() {
        // 精简版 songId 可能是数字而非字符串:严格 data-class 反序列化会抛异常导致整个
        // payload 丢失;宽松提取把它当文本接受。
        val payload = parseLyricInfoPayload(
            """{"songName":"歌名","songId":12345,"lyric":"[00:01.00]词"}"""
        )
        assertEquals("歌名", payload?.songName)
        assertEquals("12345", payload?.songId)
        assertEquals("[00:01.00]词", payload?.lyric)
    }

    @Test
    fun missingAndNullFieldsDegradeGracefully() {
        val payload = parseLyricInfoPayload(
            """{"songName":"歌名","artist":null}"""
        )
        assertEquals("歌名", payload?.songName)
        assertNull(payload?.artist)
        assertNull(payload?.lyric)
        assertNull(payload?.transLyric)
    }

    @Test
    fun nonObjectOrMalformedJsonReturnsNull() {
        assertNull(parseLyricInfoPayload("not json"))
        assertNull(parseLyricInfoPayload("""["array"]"""))
        assertNull(parseLyricInfoPayload("""42"""))
    }

    // --- isNativePerLinePayload:精简版原生逐行格式判定 ---

    @Test
    fun nativePerLinePayload_songNameCarryingLyricLine_isDetected() {
        // 实测 logcat 形态:songName=当前歌词行,artist="歌名 - 歌手",lyric=单行当前歌词
        val payload = LyricInfoPayload(
            songName = "この胸の鼓動さえ聞こえてしまいそうなほど",
            artist = "嘘つきは恋のはじまり (谎言是恋爱的伊始) - 洛天依Official/40mP",
            lyric = "[00:16.44]この胸の鼓動さえ聞こえてしまいそうなほど"
        )
        assertTrue(isNativePerLinePayload(payload, "嘘つきは恋のはじまり"))
    }

    @Test
    fun artistCompositeContainingMetadataTitle_isDetected() {
        // lyric 无时间戳(解析为空)时,靠 artist 复合串信号判定
        val payload = LyricInfoPayload(
            songName = "当前歌词行",
            artist = "歌名 - 歌手",
            lyric = "当前歌词行"
        )
        assertTrue(isNativePerLinePayload(payload, "歌名"))
    }

    @Test
    fun fullVersionPayload_isNotNativePerLine() {
        // 完整版:artist 为纯歌手名,lyric 为整首多行,两个信号都不命中
        val payload = LyricInfoPayload(
            songName = "歌名",
            artist = "歌手",
            album = "专辑",
            songId = "12345",
            lyric = "[00:16.440]<00:16.440>歌<00:16.800>词\n[00:20.000]第二行",
            format = "elrc",
            translation = "[00:16.440]翻译"
        )
        assertFalse(isNativePerLinePayload(payload, "歌名"))
    }

    @Test
    fun nativePerLineDetection_nullPayloadIsNegative() {
        assertFalse(isNativePerLinePayload(null, "歌名"))
    }

    // --- ColorOS Live Lyrics Bridge Provider v5 契约(PLAYER_INTEGRATION)---

    @Test
    fun parsesBridgeProviderV5PayloadExtensionFields() {
        // Bridge 协议 §2 示例 payload:rawLyric 逐字 lane、translationLyric 规范翻译 lane、
        // trackKey/sessionGeneration/noLyric 等诊断字段,宽松提取全部接受。
        val payload = parseLyricInfoPayload(
            """
            {
              "songName": "示例歌曲",
              "artist": "示例歌手",
              "songId": "track-42",
              "lyricType": 0,
              "lyric": "[00:10.000]第一句\n[00:14.500]第二句\n",
              "rawLyric": "[00:10.000]<00:10.000>第一<00:10.700>句<00:12.800>\n",
              "translationLyric": "[00:10.000]First line\n",
              "trackKey": "track-42|示例歌曲|示例歌手|180",
              "sessionGeneration": 12,
              "noLyric": false
            }
            """.trimIndent()
        )
        assertEquals("示例歌曲", payload?.songName)
        assertTrue(payload?.rawLyric?.contains("<00:10.700>") == true)
        assertTrue(payload?.translationLyric?.contains("First line") == true)
        assertNull(payload?.roma)
    }

    @Test
    fun resolveTimedLines_bridgeWordTimedRawLyricWinsOverLineLyric() {
        // Bridge 契约:rawLyric 是逐字超集(行时间齐备),携带逐字标签时优先于 lyric。
        val payload = LyricInfoPayload(
            lyric = "[00:10.000]第一句\n[00:14.500]第二句",
            rawLyric = "[00:10.000]<00:10.000>第一<00:10.700>句<00:12.800>\n" +
                "[00:14.500]<00:14.500>第二<00:15.200>句<00:17.000>"
        )
        val lines = resolveLyricInfoTimedLines(payload)
        assertEquals(2, lines.size)
        assertTrue(lines.all { !it.words.isNullOrEmpty() })
    }

    @Test
    fun resolveTimedLines_plainLyricOnly_staysLineLevel() {
        val payload = LyricInfoPayload(lyric = "[00:10.000]第一句\n[00:14.500]第二句")
        val lines = resolveLyricInfoTimedLines(payload)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.words.isNullOrEmpty() })
    }

    @Test
    fun resolveTimedLines_rawOnlyPayload_fallsBackToRaw() {
        // raw-only payload(lyric 缺失):Bridge LyricInfoContract 的 display fallback 同形态。
        val payload = LyricInfoPayload(
            rawLyric = "[00:10.000]<00:10.000>第一<00:10.700>句<00:12.800>"
        )
        val lines = resolveLyricInfoTimedLines(payload)
        assertEquals(1, lines.size)
        assertTrue(!lines[0].words.isNullOrEmpty())
    }

    @Test
    fun resolveTimedLines_rawWithoutWordTags_neverShadowsLyric() {
        // Bridge §4.2:只有逐行时间时应省略 rawLyric;防呆起见,无逐字标签的 rawLyric
        // 不得抢走更完整的 lyric。
        val payload = LyricInfoPayload(
            lyric = "[00:10.000]第一句\n[00:14.500]第二句",
            rawLyric = "[00:10.000]第一句"
        )
        assertEquals(2, resolveLyricInfoTimedLines(payload).size)
    }

    @Test
    fun resolveTranslationLines_prefersCanonicalTranslationLyric() {
        // Bridge 规范字段 translationLyric 优先于两个历史别名。
        val payload = LyricInfoPayload(
            translationLyric = "[00:10.000]First line",
            translation = "[00:10.000]legacy translation",
            transLyric = "[00:10.000]lite translation"
        )
        val lines = resolveLyricInfoTranslationLines(payload)
        assertEquals(1, lines.size)
        assertEquals("First line", lines[0].text)
    }

    @Test
    fun resolveTranslationLines_fallsBackToLiteTransLyric() {
        val payload = LyricInfoPayload(transLyric = "[00:16.44]翻译")
        val lines = resolveLyricInfoTranslationLines(payload)
        assertEquals("翻译", lines[0].text)
    }

    @Test
    fun resolveTimedLines_nullPayloadYieldsEmpty() {
        assertTrue(resolveLyricInfoTimedLines(null).isEmpty())
        assertTrue(resolveLyricInfoTranslationLines(null).isEmpty())
    }

    // --- Bridge LyricInfoContract 翻译别名族(issue #68) ---

    @Test
    fun resolveTranslationLines_acceptsBridgeAliasFamily() {
        // Bridge LyricInfoContract 共 8 个翻译键;新增 5 个别名逐一兜底解析。
        listOf(
            "translatedLyric",
            "translateLyric",
            "lyricTranslation",
            "translationLrc",
            "transLrc"
        ).forEach { key ->
            val payload = parseLyricInfoPayload("""{"$key":"[00:10.000]First line"}""")
            val lines = resolveLyricInfoTranslationLines(payload)
            assertEquals("key=$key", 1, lines.size)
            assertEquals("First line", lines[0].text)
        }
    }

    @Test
    fun resolveTranslationLines_canonicalStillWinsOverNewAliases() {
        val payload = parseLyricInfoPayload(
            """{"translationLyric":"[00:10.000]canonical","translatedLyric":"[00:10.000]alias"}"""
        )
        assertEquals("canonical", resolveLyricInfoTranslationLines(payload)[0].text)
    }

    // --- matchSupplementalLine:翻译/roma ±120ms 最近行对齐(带双护栏) ---

    private fun lineAt(startMs: Long, text: String): ElrcParser.TimedLine =
        ElrcParser.parse(
            "[00:${startMs / 1000}.${(startMs % 1000).toString().padStart(3, '0')}]" + text
        )[0]

    @Test
    fun matchSupplemental_exactStart_stillMatches() {
        val primary = lineAt(10_000L, "主行")
        assertEquals("翻译", matchSupplementalLine(primary, listOf(primary), listOf(lineAt(10_000L, "翻译")))?.text)
    }

    @Test
    fun matchSupplemental_offBy80ms_attachesNearest() {
        // 发布误差 80ms:旧实现精确相等会丢,窗口内最近行挂上。
        val primary = lineAt(10_000L, "主行")
        assertEquals("翻译", matchSupplementalLine(primary, listOf(primary), listOf(lineAt(10_080L, "翻译")))?.text)
    }

    @Test
    fun matchSupplemental_beyondWindow_returnsNull() {
        val primary = lineAt(10_000L, "主行")
        assertNull(matchSupplementalLine(primary, listOf(primary), listOf(lineAt(10_150L, "翻译"))))
    }

    @Test
    fun matchSupplemental_skipsWhenCloserPrimaryExists() {
        // 最近主行护栏:候选与乙(10.100)完全重合,不得挂到甲(差 100ms)。
        val a = lineAt(10_000L, "甲")
        val b = lineAt(10_100L, "乙")
        assertNull(matchSupplementalLine(a, listOf(a, b), listOf(lineAt(10_100L, "翻译乙"))))
    }

    @Test
    fun matchSupplemental_skipsWhenNeighborAlreadyRendersSameText() {
        // 重复文本护栏:窗口内邻行已渲染与候选相同的文本 → 候选是重复歌词而非翻译。
        val a = lineAt(10_000L, "主行")
        val b = lineAt(10_050L, "重复的翻译文本")
        assertNull(matchSupplementalLine(a, listOf(a, b), listOf(lineAt(10_000L, "重复的翻译文本"))))
    }
}
