package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMetadataIntroPolicyTest {
    @Test
    fun alwaysShowsWhenMetadataAvailable() {
        val policy = SongMetadataIntroPolicy()

        // 所有歌词状态下,只要 metadata 可用就显示
        SongIntroLyricState.entries.forEach { state ->
            assertTrue(
                policy.shouldShowLargeMetadata(
                    input(now = 1_000L, lyricState = state)
                )
            )
        }
    }

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
    fun activeStateNoLongerBlocksDisplay() {
        val policy = SongMetadataIntroPolicy()

        // ACTIVE 状态时不再阻止显示(回归:息屏打开显示歌词信息无效)
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 1_000L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
        // 持续显示,不会因 duration 超时或 ACTIVE 状态而消失
        assertTrue(
            policy.shouldShowLargeMetadata(
                input(now = 10_000L, lyricState = SongIntroLyricState.ACTIVE)
            )
        )
    }

    @Test
    fun noDurationBasedTimeout() {
        val policy = SongMetadataIntroPolicy(durationMs = 3_000L)

        // 进入显示后,即使超过 3 秒仍持续显示
        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 5_000L)))
        assertTrue(policy.shouldShowLargeMetadata(input(now = 30_000L)))
    }

    @Test
    fun sessionChangeResetsButStillShows() {
        val policy = SongMetadataIntroPolicy()

        assertTrue(policy.shouldShowLargeMetadata(input(now = 1_000L, generation = 1)))
        // 切歌后仍显示(metadata 可用)
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