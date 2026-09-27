package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌曲图片取图校对(包名/曲目双校对)的纯函数行为:系统播放窗口可能挂着多个来源,
 * 封面只允许取自「当前播放的音乐软件」的当前曲目,任何歧义一律拒绝——不显示。
 */
class ArtworkSourcePolicyTest {

    private fun sample(
        packageName: String,
        playing: Boolean = true,
        title: String = "Song",
        artist: String = "Artist"
    ) = ArtworkSessionSample(packageName, playing, title, artist)

    @Test
    fun playingSessionWithMatchingTrackIsSelected() {
        val chosen = resolveVerifiedArtworkSession(
            samples = listOf(
                sample("com.paused.app", playing = false),
                sample("com.music.player")
            ),
            trackTitle = "Song",
            trackArtist = "Artist"
        )

        assertEquals("com.music.player", chosen?.packageName)
    }

    @Test
    fun trackIdentityToleratesTitleFormattingNoise() {
        // "(Live)"/译名括号等写法差异不得把同曲误判为不同曲(与 isSameTrackIdentity 同口径)。
        val chosen = resolveVerifiedArtworkSession(
            samples = listOf(sample("com.music.player", title = "Song (Live)")),
            trackTitle = "Song",
            trackArtist = "Artist"
        )

        assertEquals("com.music.player", chosen?.packageName)
    }

    @Test
    fun staleArtworkFromOtherTrackIsRejected() {
        // 系统播放窗口滞留旧封面:会话曲目与当前歌词曲目不同 → 拒绝,不显示错的图。
        val chosen = resolveVerifiedArtworkSession(
            samples = listOf(
                sample("com.music.player", title = "Old Song", artist = "Someone"),
                sample("com.other.player", playing = false, title = "Song", artist = "Artist")
            ),
            trackTitle = "Song",
            trackArtist = "Artist"
        )

        assertNull(chosen)
    }

    @Test
    fun pausedSessionIsNeverTheCurrentPlayingSoftware() {
        // 全部会话都不在播 → 没有「当前播放的音乐软件」→ 不显示。
        val chosen = resolveVerifiedArtworkSession(
            samples = listOf(
                sample("com.music.player", playing = false),
                sample("com.video.app", playing = false)
            ),
            trackTitle = "Song",
            trackArtist = "Artist"
        )

        assertNull(chosen)
    }

    @Test
    fun multiplePackagesMatchingTrackFailClosed() {
        // 同曲双开会话命中两个包:数据歧义,拒绝出帧。
        val chosen = resolveVerifiedArtworkSession(
            samples = listOf(
                sample("com.music.player"),
                sample("com.music.player.lite")
            ),
            trackTitle = "Song",
            trackArtist = "Artist"
        )

        assertNull(chosen)
    }

    @Test
    fun multipleSessionsOfSamePackageAreUnambiguous() {
        // 同一包的多个会话(主/辅会话)不算歧义,取带曲目标题的那条。
        val chosen = resolveVerifiedArtworkSession(
            samples = listOf(
                sample("com.music.player", title = ""),
                sample("com.music.player", title = "Song")
            ),
            trackTitle = "Song",
            trackArtist = "Artist"
        )

        assertEquals("com.music.player", chosen?.packageName)
        assertEquals("Song", chosen?.title)
    }

    @Test
    fun unknownCurrentTrackTrustsOnlySinglePlayingSession() {
        // 当前歌词曲目未知(空标题)时只有恰好一个在播会话才可信。
        assertEquals(
            "com.music.player",
            resolveVerifiedArtworkSession(
                samples = listOf(sample("com.music.player")),
                trackTitle = "",
                trackArtist = ""
            )?.packageName
        )
        assertNull(
            resolveVerifiedArtworkSession(
                samples = listOf(sample("com.music.player"), sample("com.other.player")),
                trackTitle = "",
                trackArtist = ""
            )
        )
        assertNull(
            resolveVerifiedArtworkSession(
                samples = emptyList(),
                trackTitle = "",
                trackArtist = ""
            )
        )
    }

    @Test
    fun blankPackageSamplesAreIgnored() {
        val chosen = resolveVerifiedArtworkSession(
            samples = listOf(
                sample("", playing = true, title = "Song", artist = "Artist"),
                sample("com.music.player")
            ),
            trackTitle = "Song",
            trackArtist = "Artist"
        )

        assertEquals("com.music.player", chosen?.packageName)
    }
}
