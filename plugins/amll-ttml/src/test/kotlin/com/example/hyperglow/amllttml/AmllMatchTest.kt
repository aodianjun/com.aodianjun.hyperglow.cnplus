package com.example.hyperglow.amllttml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmllMatchTest {

    private fun query(title: String, vararg artists: String) =
        AmllQuery(title = title, artists = artists.toList(), album = null)

    private fun candidate(
        id: Long,
        musicNames: List<String>,
        artistNames: List<String>,
    ) = AmllMatch.Candidate(id = id, musicNames = musicNames, artistNames = artistNames, albumNames = emptyList())

    @Test
    fun normalizeStripsVersionNotesAndFeat() {
        assertEquals("晴天", AmllMatch.normalize("晴天 (Live)"))
        assertEquals("hello", AmllMatch.normalize("Hello feat. Someone"))
        assertEquals("takemehand", AmllMatch.normalize("Take Me Hand"))
    }

    @Test
    fun exactTitleAndArtistScoresHigh() {
        val q = query("蝴蝶", "洛天依Official")
        val c = candidate(1, listOf("蝴蝶"), listOf("洛天依Official"))
        val score = AmllMatch.score(q, c)
        assertNotNull(score)
        assertTrue(score >= 0.95)
    }

    @Test
    fun coverVersionIsRejectedWhenArtistsDisagree() {
        // 标题完全一致但艺人不同（翻唱/伴奏）：必须硬否决。
        val q = query("晴天", "周杰伦")
        val c = candidate(1, listOf("晴天"), listOf("RyaVocal"))
        assertNull(AmllMatch.score(q, c))
    }

    @Test
    fun missingArtistsOnEitherSideSkipsArtistVeto() {
        val noQueryArtist = AmllQuery(title = "晴天", artists = emptyList(), album = null)
        assertNotNull(AmllMatch.score(noQueryArtist, candidate(1, listOf("晴天"), listOf("任何人"))))

        val noCandidateArtist = candidate(2, listOf("晴天"), emptyList())
        assertNotNull(AmllMatch.score(query("晴天", "周杰伦"), noCandidateArtist))
    }

    @Test
    fun versionNotesAreStrippedSoTitleMatchesFully() {
        // 媒体元数据常带版本注记（如网易云显示的「蝴蝶 (Cocoon Broken)」）：
        // 归一化剥除括号内容后与库中名完全相等 → 满分。
        val q = query("蝴蝶 (Cocoon Broken)", "洛天依Official")
        val c = candidate(1, listOf("蝴蝶"), listOf("洛天依Official"))
        assertEquals(1.0, AmllMatch.score(q, c) ?: 0.0, 0.001)
    }

    @Test
    fun partialContainmentScoresLowerButPasses() {
        // 真包含且短串占比 ≥ 60%（"晴天" 是 "晴天转" 的 2/3）→ 0.75 档。
        val q = query("晴天", "周杰伦")
        val c = candidate(1, listOf("晴天转"), listOf("周杰伦"))
        val score = AmllMatch.score(q, c)
        assertNotNull(score)
        assertTrue(score!! in 0.7..0.99)
    }

    @Test
    fun shortTitleContainmentIsRejectedBelowSixtyPercent() {
        // 短串不足长串 60% → 拒绝（避免 "hello" 命中 "hello goodbye world"）。
        val q = query("蝴蝶", "洛天依Official")
        val c = candidate(1, listOf("蝴蝶cocoonbroken"), listOf("洛天依Official"))
        assertNull(AmllMatch.score(q, c))
    }

    @Test
    fun unrelatedTitleIsRejected() {
        val q = query("蝴蝶", "洛天依Official")
        val c = candidate(1, listOf("北望去"), listOf("洛天依Official"))
        assertNull(AmllMatch.score(q, c))
    }

    @Test
    fun pickBestChoosesHighestAboveThreshold() {
        val q = query("蝴蝶", "洛天依Official")
        val candidates = listOf(
            candidate(1, listOf("蝴蝶"), listOf("其他人")),        // 艺人否决
            candidate(2, listOf("蝴蝶"), listOf("洛天依Official")), // 1.0
            candidate(3, listOf("蝴蝶 (Cocoon Broken)"), listOf("洛天依Official")), // 部分匹配
        )
        assertEquals(2L, AmllMatch.pickBest(q, candidates)?.id)
    }

    @Test
    fun pickBestReturnsNullWhenNothingQualifies() {
        val q = query("蝴蝶", "洛天依Official")
        assertNull(AmllMatch.pickBest(q, listOf(candidate(1, listOf("无关歌"), listOf("无关人")))))
        assertNull(AmllMatch.pickBest(q, emptyList()))
    }

    @Test
    fun cacheKeyIgnoresRenderSwitchesAndAlbum() {
        val a = AmllQuery("蝴蝶", listOf("洛天依Official"), "再生")
        val b = AmllQuery("蝴蝶", listOf("洛天依Official"), null)
        assertEquals(a.cacheKey("https://api.amll.dev"), b.cacheKey("https://api.amll.dev"))
    }

    @Test
    fun searchTitleVariantsPreferRawThenStrippedThenNormalized() {
        // 三变体按序去重：原文（库里带后缀/标点的版本只有原文能区分）→ 仅剥括号/feat.
        // （保留空格标点）→ 全量规范化（仅字母数字 CJK）。
        assertEquals(
            listOf("蝴蝶 (Cocoon Broken)", "蝴蝶"),
            AmllMatch.searchTitleVariants("蝴蝶 (Cocoon Broken)"),
        )
        assertEquals(
            listOf("Orchelia's vox (feat. k a e z & Lily)", "Orchelia's vox", "orcheliasvox"),
            AmllMatch.searchTitleVariants("Orchelia's vox (feat. k a e z & Lily)"),
        )
        assertEquals(
            listOf("Hello, World!", "helloworld"),
            AmllMatch.searchTitleVariants("Hello, World!"),
        )
        assertEquals(listOf("蝴蝶"), AmllMatch.searchTitleVariants("蝴蝶"))
        assertEquals(listOf("蝴蝶"), AmllMatch.searchTitleVariants("  蝴蝶  "))
        assertTrue(AmllMatch.searchTitleVariants("").isEmpty())
        assertTrue(AmllMatch.searchTitleVariants("   ").isEmpty())
    }

    @Test
    fun cacheKeyIsSharedAcrossTitleSpellings() {
        // 带括号后缀与干净标题（含艺人大小写差异）必须落到同一缓存键：修复后带后缀会话
        // 取到的结果能被干净标题的会话复用（反之亦然），这也是负缓存不再「只毒一半」的前提。
        val decorated = AmllQuery("蝴蝶 (Cocoon Broken)", listOf("洛天依Official"), null)
        val clean = AmllQuery("蝴蝶", listOf("洛天依official"), null)
        assertEquals(
            decorated.cacheKey("https://api.amll.dev"),
            clean.cacheKey("https://api.amll.dev"),
        )
    }
}
