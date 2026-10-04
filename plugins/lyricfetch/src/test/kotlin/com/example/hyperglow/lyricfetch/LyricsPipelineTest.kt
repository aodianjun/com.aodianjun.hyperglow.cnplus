package com.example.hyperglow.lyricfetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 解析管线：accompanist AutoParser 负责格式识别，本管线补翻译合并、音节合并与规范化。
 * 夹具取自真实抓取（2026-10，LRCLIB /api/get 与网易云 /api/song/lyric/v1）。
 */
class LyricsPipelineTest {

    private val lrcFixture = """
        [00:06.22] Hello, it's me
        [00:11.84] I was wondering if after all these years you'd like to meet
        [00:17.96] To go over everything
    """.trimIndent()

    /** 网易云 YRC：前两行是 JSON 署名行（解析器应跳过），其后是逐字行。 */
    private val yrcFixture = """
        {"t":0,"c":[{"tx":"作词: "},{"tx":"Adele Adkins"}]}
        [6190,4440](6190,2190,0)Hello(8380,540,0), (8920,360,0)it's (9280,1350,0)me
        [11770,6090](11770,360,0)I (12130,210,0)was (12340,1350,0)wondering (13690,240,0)if (13930,840,0)after
    """.trimIndent()

    private val translationFixture = """
        [00:06.220]你好 是我
        [00:11.320]我犹豫着要不要给你来电 不确定多年后你是否还愿相见
        [00:17.730]愿意闲聊寒暄 细数从前
    """.trimIndent()

    @Test
    fun parsesPlainLrcAsLineLevel() {
        val lines = LyricsPipeline.parse(
            RawLyrics("lrclib", lrcFixture),
            mergeSyllables = true,
            wantTranslation = false,
        )
        assertEquals(3, lines.size)
        assertEquals("Hello, it's me", lines[0].text)
        assertEquals(6_220L, lines[0].beginMs)
        assertTrue(lines[0].words.isEmpty(), "行级歌词不应有词表")
        assertTrue(!LyricsPipeline.hasWordLevel(lines))
    }

    @Test
    fun parsesYrcAsWordLevel() {
        val lines = LyricsPipeline.parse(
            RawLyrics("netease", yrcFixture),
            mergeSyllables = false,
            wantTranslation = false,
        )
        assertEquals(2, lines.size, "JSON 署名行必须被跳过")
        val first = lines[0]
        assertEquals(6_190L, first.beginMs)
        assertEquals(10_630L, first.endMs)
        assertTrue(first.words.size >= 4, "words=${first.words.map { it.text }}")
        assertEquals(first.words.joinToString("") { it.text }, first.text)
        assertTrue(LyricsPipeline.hasWordLevel(lines))
    }

    @Test
    fun mergesTranslationByStartTime() {
        val lines = LyricsPipeline.parse(
            RawLyrics("netease", yrcFixture, translation = translationFixture),
            mergeSyllables = false,
            wantTranslation = true,
        )
        assertEquals("你好 是我", lines[0].translation)
        assertEquals("我犹豫着要不要给你来电 不确定多年后你是否还愿相见", lines[1].translation)
    }

    @Test
    fun translationRespectsSwitch() {
        val lines = LyricsPipeline.parse(
            RawLyrics("netease", yrcFixture, translation = translationFixture),
            mergeSyllables = false,
            wantTranslation = false,
        )
        assertTrue(lines.all { it.translation == null })
    }

    @Test
    fun mergesLatinSyllablesButNotCjk() {
        val words = listOf(
            ParsedWord("Hel", 0, 100),
            ParsedWord("lo", 100, 200),
            ParsedWord(" ", 200, 220),
            ParsedWord("你", 220, 300),
            ParsedWord("好", 300, 400),
        )
        val merged = LyricsPipeline.mergeSyllableWords(words)
        assertEquals(listOf("Hello", " ", "你", "好"), merged.map { it.text })
        assertEquals(0L, merged[0].beginMs)
        assertEquals(200L, merged[0].endMs)
    }

    @Test
    fun sanitizesZeroAndNegativeTimes() {
        val content = """
            [00:00.00]first
            [00:02.00]second
        """.trimIndent()
        val lines = LyricsPipeline.parse(RawLyrics("t", content), false, false)
        assertTrue(lines.all { it.beginMs >= 0 && it.endMs >= it.beginMs })
        assertEquals(listOf(0L, 2_000L), lines.map { it.beginMs })
    }

    @Test
    fun mixedJsonCreditsAndClassicLrcLinesParse() {
        // 实测：网易云新版 lrc 字段是「JSON 署名行 + 传统 LRC 行」混排（曲目 186016 晴天）。
        val mixed = """
            {"t":0,"c":[{"tx":"作词: "},{"tx":"周杰伦"}]}
            {"t":1000,"c":[{"tx":"作曲: "},{"tx":"周杰伦"}]}
            [00:28.950]故事的小黄花
            [00:32.460]从出生那年就飘着
        """.trimIndent()
        val lines = LyricsPipeline.parse(RawLyrics("netease", mixed), false, false)
        assertEquals(2, lines.size, "JSON 署名行必须被跳过")
        assertEquals("故事的小黄花", lines[0].text)
        assertEquals(28_950L, lines[0].beginMs)
    }

    @Test
    fun emptyOrGarbageContentYieldsNothing() {
        assertTrue(LyricsPipeline.parse(RawLyrics("t", ""), false, false).isEmpty())
        assertTrue(LyricsPipeline.parse(RawLyrics("t", "not a lyric at all"), false, false).isEmpty())
    }
}
