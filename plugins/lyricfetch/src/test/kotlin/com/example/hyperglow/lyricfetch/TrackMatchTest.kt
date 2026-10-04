package com.example.hyperglow.lyricfetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 匹配打分：标题归一化、艺人包含、时长硬否决。 */
class TrackMatchTest {

    private fun query(
        title: String = "Hello",
        artists: List<String> = listOf("Adele"),
        durationMs: Long? = 295_000L,
    ) = TrackQuery(title, artists, null, durationMs)

    private fun candidate(
        title: String = "Hello",
        artists: List<String> = listOf("Adele"),
        durationMs: Long? = 295_502L,
    ) = TrackMatch.Candidate("1", title, artists, null, durationMs)

    @Test
    fun normalizeStripsBracketsAndFeat() {
        assertEquals("hello", TrackMatch.normalize("Hello"))
        assertEquals("hello", TrackMatch.normalize("Hello (Remastered 2015)"))
        assertEquals("hello", TrackMatch.normalize("Hello【Live】"))
        assertEquals("hello", TrackMatch.normalize("Hello feat. Someone"))
        assertEquals("晴天", TrackMatch.normalize("晴天 (Live)"))
        assertEquals("", TrackMatch.normalize("  ---  "))
    }

    @Test
    fun exactMatchScoresHigh() {
        val score = TrackMatch.score(query(), candidate())!!
        assertTrue(score > 0.95, "score=$score")
    }

    @Test
    fun titleMismatchIsRejected() {
        assertNull(TrackMatch.score(query(), candidate(title = "Hello Goodbye")))
        assertNull(TrackMatch.score(query(title = "Hello"), candidate(title = "Yellow")))
    }

    @Test
    fun durationMismatchHardRejects() {
        // 同名翻唱/现场版：时长差 40s 直接否决
        assertNull(TrackMatch.score(query(durationMs = 295_000L), candidate(durationMs = 335_000L)))
    }

    @Test
    fun durationUnknownStillScores() {
        val score = TrackMatch.score(query(durationMs = null), candidate(durationMs = 295_502L))
        assertTrue(score != null && score > 0.9, "score=$score")
    }

    @Test
    fun artistContainmentScoresLower() {
        val exact = TrackMatch.score(query(), candidate())!!
        val partial = TrackMatch.score(query(), candidate(artists = listOf("Adele Adkins")))!!
        assertTrue(partial < exact, "partial=$partial exact=$exact")
        assertTrue(partial > TrackMatch.MIN_SCORE, "partial=$partial")
    }

    @Test
    fun chineseSongMatches() {
        val score = TrackMatch.score(
            query(title = "晴天", artists = listOf("周杰伦"), durationMs = 269_000L),
            candidate(title = "晴天", artists = listOf("周杰伦"), durationMs = 269_000L),
        )!!
        assertTrue(score > 0.95, "score=$score")
    }

    @Test
    fun coverVersionWithMatchingTitleIsRejected() {
        // 实测：网易云搜 "晴天 周杰伦" 首位是翻唱 "晴天 (原唱 周杰伦) - RyaVocal"，
        // 标题归一化后与原文相等、时长也接近，只有艺人维度能把它挡掉。
        assertNull(
            TrackMatch.score(
                query(title = "晴天", artists = listOf("周杰伦"), durationMs = 269_000L),
                candidate(title = "晴天 (原唱 周杰伦)", artists = listOf("RyaVocal"), durationMs = 270_738L),
            )
        )
    }

    @Test
    fun candidateWithoutArtistStillScores() {
        // 候选没给艺人时不做艺人否决（信息不足，交给标题+时长）：标题与时长都精确时仍应过阈值
        val score = TrackMatch.score(query(), candidate(artists = emptyList()))
        assertTrue(score != null && score >= TrackMatch.MIN_SCORE, "score=$score")
    }

    @Test
    fun cacheKeyIsStableForSameSong() {
        assertEquals(query().cacheKey, query().cacheKey)
        assertTrue(query().cacheKey != query(title = "Other").cacheKey)
    }
}
