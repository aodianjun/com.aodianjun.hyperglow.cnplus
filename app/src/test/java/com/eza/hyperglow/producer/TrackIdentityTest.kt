package com.eza.hyperglow.producer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isSameTrackIdentity] — Bridge TrackIdentity 的最小移植:
 * 标题尾缀/译名/feat 写法变化不再误判切歌;区分性尾缀(Part 1/2)与歌手变化
 * 仍判为变化。原则:宁可把同曲判成变化,不可漏切歌。
 */
class TrackIdentityTest {

    @Test
    fun identicalTitlesAreSameTrack() {
        assertTrue(isSameTrackIdentity("歌名", "歌手", "歌名", "歌手"))
    }

    @Test
    fun noiseSuffixVariantsAreSameTrack() {
        // 尾缀噪声词表(live/explicit 等):同一首歌的发布变体不触发切歌。
        assertTrue(isSameTrackIdentity("Song", "Artist", "Song (Live)", "Artist"))
        assertTrue(isSameTrackIdentity("Song (Explicit)", "Artist", "song", "Artist"))
    }

    @Test
    fun translationBracketSuffixIsSameTrack() {
        // 译名对:假名标题 + 汉字括号译名(Bridge 同款双向剥离的保守版)。
        assertTrue(
            isSameTrackIdentity(
                "嘘つきは恋のはじまり (谎言是恋爱的伊始)",
                "洛天依Official/40mP",
                "嘘つきは恋のはじまり",
                "洛天依Official/40mP"
            )
        )
    }

    @Test
    fun featuredTitleSplitVariantsAreSameTrack() {
        assertTrue(isSameTrackIdentity("Sing feat. ABC", "X", "Sing", "X"))
        assertTrue(isSameTrackIdentity("Sing (feat. ABC)", "X", "Sing feat ABC", "X"))
    }

    @Test
    fun artistSeparatorVariantsAreSameTrack() {
        // 歌手分隔符写法变化:集合相等即同曲;任一侧为空视为未知放行。
        assertTrue(isSameTrackIdentity("歌名", "洛天依/40mP", "歌名", "洛天依、40mP"))
        assertTrue(isSameTrackIdentity("歌名", "", "歌名", "洛天依/40mP"))
    }

    @Test
    fun distinguishingSuffixesAreNotMerged() {
        // 区分性尾缀(Part 1/Part 2)不在噪声词表:判为变化,防止旧歌词滞留。
        assertFalse(isSameTrackIdentity("Suite (Part 1)", "X", "Suite (Part 2)", "X"))
    }

    @Test
    fun artistChangeIsStillASongChange() {
        assertFalse(isSameTrackIdentity("歌名", "歌手A", "歌名", "歌手B"))
    }

    @Test
    fun blankTitleFallsBackToChange() {
        // 一侧标题为空白:保持"变化"(首次出现必须触发 generation++)。
        assertFalse(isSameTrackIdentity("", "", "歌名", "歌手"))
        assertFalse(isSameTrackIdentity("歌名", "歌手", "", ""))
    }

    @Test
    fun remixStyleSuffixIsNotInTheNoiseVocabulary() {
        // remix/sped up 可能是不同作品:保守起见不剥离,判为变化。
        assertFalse(isSameTrackIdentity("Song", "X", "Song (Remix)", "X"))
    }
}
