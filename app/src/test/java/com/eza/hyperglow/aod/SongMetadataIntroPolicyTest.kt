package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMetadataIntroPolicyTest {
    @Test
    fun neverShowsWhenMetadataUnavailable() {
        val policy = SongMetadataIntroPolicy()

        assertFalse(
            policy.shouldShowLargeMetadata(
                SongMetadataIntroInput(
                    session = ProjectionSessionIdentity("producer", 1, "track:1"),
                    metadataAvailable = false,
                    lyricState = SongIntroLyricState.INTERLUDE,
                    positionMs = 1_000L,
                    nextLyricStartMs = null,
                    speed = 1f,
                    nowElapsedMs = 1_000L
                )
            )
        )
    }

    @Test
    fun activeLyricShowsLyricsNotMetadata() {
        val policy = SongMetadataIntroPolicy()

        // 有活动歌词行时禁止显示大元数据,避免歌名顶掉歌词
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 1_000L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        // 持续 ACTIVE 仍不显示
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 10_000L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
    }

    @Test
    fun introStatesShowMetadata() {
        val policy = SongMetadataIntroPolicy()

        // 无歌词/未知状态(歌曲开头)显示大元数据
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 1_000L, lyricState = SongIntroLyricState.NONE)
            )
        )
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 1_000L, lyricState = SongIntroLyricState.UNKNOWN)
            )
        )
    }

    @Test
    fun interludeShowsOnlyWhenGapEnough() {
        val policy = SongMetadataIntroPolicy(durationMs = 3_000L)

        // 间奏且距离下一句歌词足够远 → 显示
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 1_000L, nextStart = 10_000L)
            )
        )
        // 间奏但下一句马上就开始 → 不显示
        assertFalse(
            policy.shouldShowLargeMetadata(
                input(now = 1_000L, nextStart = 1_500L)
            )
        )
    }

    @Test
    fun sessionChangeResetsState() {
        val policy = SongMetadataIntroPolicy()

        // 上一首进入 COMPLETE 后,切歌(新 session)应重置为 PENDING 重新判定
        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L, generation = 1)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 5_000L, generation = 1)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 9_000L, generation = 1)))
        // 切歌后仍是 INTERLUDE 且 gap 足够 → 重新显示
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 5_000L, generation = 2)
            )
        )
    }

    private fun input(
        now: Long,
        position: Long = now,
        nextStart: Long? = null,
        lyricState: SongIntroLyricState = SongIntroLyricState.INTERLUDE,
        generation: Int = 7
    ) = SongMetadataIntroInput(
        session = ProjectionSessionIdentity("producer", generation, "spotify:track:$generation"),
        metadataAvailable = true,
        lyricState = lyricState,
        positionMs = position,
        nextLyricStartMs = nextStart,
        speed = 1f,
        nowElapsedMs = now
    )
}