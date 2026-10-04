package com.example.hyperglow.lyricfetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三个来源的响应解析（用真实抓取载荷，见 [Fixtures]）。
 * 这些断言钉住的是易变的外部契约：字段名、base64 包装、YRC 署名行、LRCLIB 的 syncedLyrics。
 */
class ProviderParsingTest {

    // ---- 网易云 -----------------------------------------------------------

    @Test
    fun neteaseSearchParsesSongs() {
        val candidates = NeteaseProvider.parseSearch(Fixtures.NETEASE_SEARCH_JSON)
        assertTrue(candidates.isNotEmpty())
        val first = candidates.first()
        assertEquals("Hello", first.title)
        assertEquals(listOf("Adele"), first.artists)
        assertEquals(295_502L, first.durationMs)
        assertEquals("Hello", first.album)
        assertTrue(first.id.isNotBlank())
    }

    @Test
    fun neteaseSearchAlsoAcceptsLegacyFieldNames() {
        val body = """{"result":{"songs":[{"id":7,"name":"x","duration":1234,
            "artists":[{"name":"a"}],"album":{"name":"b"}}]}}"""
        val candidate = NeteaseProvider.parseSearch(body).single()
        assertEquals(listOf("a"), candidate.artists)
        assertEquals(1_234L, candidate.durationMs)
        assertEquals("b", candidate.album)
    }

    @Test
    fun neteaseLyricPrefersYrcAndKeepsTranslation() {
        val parsed = assertNotNull(NeteaseProvider.parseLyric(Fixtures.NETEASE_LYRIC_JSON))
        // YRC 行形如 [6190,4440](6190,2190,0)Hello...
        assertTrue(parsed.content.contains("]("), "应优先返回 YRC 逐字文本")
        assertNotNull(parsed.translation, "tlyric 必须被解析出来")
    }

    @Test
    fun neteaseLyricFallsBackToLrc() {
        val body = """{"lrc":{"lyric":"[00:01.000]hello"},"tlyric":{"lyric":""}}"""
        val parsed = assertNotNull(NeteaseProvider.parseLyric(body))
        assertEquals("[00:01.000]hello", parsed.content)
        assertNull(parsed.translation)
    }

    @Test
    fun neteaseLyricReturnsNullWhenEmpty() {
        assertNull(NeteaseProvider.parseLyric("""{"lrc":{"lyric":""}}"""))
        assertNull(NeteaseProvider.parseLyric("not json"))
    }

    // ---- QQ 音乐 ----------------------------------------------------------

    @Test
    fun qqSearchParsesSongs() {
        val candidates = QqMusicProvider.parseSearch(Fixtures.QQ_SEARCH_JSON)
        assertEquals(1, candidates.size)
        val song = candidates.first()
        assertEquals("0039MnYb0qxYhV", song.mid)
        assertEquals(97773L, song.numericId)
        assertEquals("晴天", song.title)
        assertEquals(listOf("周杰伦"), song.artists)
        assertEquals(269_000L, song.durationMs, "interval(秒) 应换算为毫秒")
    }

    @Test
    fun qqLyricDecodesRealBase64Payload() {
        val parsed = assertNotNull(QqMusicProvider.parseLyric(Fixtures.QQ_PLAYLYRIC_JSON))
        assertTrue(parsed.content.contains("[ti:晴天]"), "base64 应解出真实 LRC 头")
        assertTrue(parsed.content.contains("[00:29.26]故事的小黄花"), "应含真实歌词行")
    }

    @Test
    fun qqDecodeAcceptsPlainTextToo() {
        assertEquals("[00:01.00]x", QqMusicProvider.decodeLyricField("[00:01.00]x"))
        assertNull(QqMusicProvider.decodeLyricField(""))
    }

    // ---- LRCLIB -----------------------------------------------------------

    @Test
    fun lrclibGetParsesSyncedLyrics() {
        val parsed = assertNotNull(LrclibProvider.parseGet(Fixtures.LRCLIB_GET_JSON))
        assertTrue(parsed.content.startsWith("[00:06.22]"))
        assertEquals("Adele", parsed.matchedArtists)
        assertEquals(296_000L, parsed.matchedDurationMs)
    }

    @Test
    fun lrclibGetRejectsEntriesWithoutSyncedLyrics() {
        assertNull(LrclibProvider.parseGet("""{"trackName":"x","syncedLyrics":""}"""))
        assertNull(LrclibProvider.parseGet("not json"))
    }

    @Test
    fun lrclibSearchKeepsOnlySyncedEntries() {
        val hits = LrclibProvider.parseSearch(Fixtures.LRCLIB_SEARCH_JSON)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.syncedLyrics.isNotBlank() })
        val body = """[{"id":1,"trackName":"a","artistName":"b","duration":10,"syncedLyrics":""}]"""
        assertTrue(LrclibProvider.parseSearch(body).isEmpty())
    }

    // ---- 端到端选择 -------------------------------------------------------

    @Test
    fun realSearchFixturesPickTheQueriedSong() {
        val neteaseQuery = TrackQuery("Hello", listOf("Adele"), "25", 295_000L)
        val neteasePick = NeteaseProvider.pickBest(
            neteaseQuery,
            NeteaseProvider.parseSearch(Fixtures.NETEASE_SEARCH_JSON),
        )
        assertEquals("Hello", assertNotNull(neteasePick).title)

        val qqQuery = TrackQuery("晴天", listOf("周杰伦"), null, 269_000L)
        val qqPick = QqMusicProvider.pickBest(
            qqQuery,
            QqMusicProvider.parseSearch(Fixtures.QQ_SEARCH_JSON).map { it.toMatchCandidate() },
        )
        assertEquals("0039MnYb0qxYhV", assertNotNull(qqPick).id)
    }
}
