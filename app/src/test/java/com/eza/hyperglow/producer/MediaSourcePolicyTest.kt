package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「当前音频源是不是音乐」判定（纯函数）：视频/播客等非音乐来源不得进入歌词显示链，
 * 未声明的未知来源 fail-open 放行（平台默认内容类型是 UNKNOWN，按「未声明即非音乐」
 * 会误伤所有未声明属性的音乐应用）。
 */
class MediaSourcePolicyTest {

    @Test
    fun videoAppPackagesAreNotMusic() {
        // 真机实证的触发源：哔哩哔哩与抖音的媒体会话被歌词链当成了歌曲。
        assertFalse(MediaSourcePolicy.isLyricEligible("tv.danmaku.bili", null))
        assertFalse(MediaSourcePolicy.isLyricEligible("com.ss.android.ugc.aweme", null))
        assertFalse(MediaSourcePolicy.isLyricEligible("com.google.android.youtube", null))
        assertEquals(
            MediaSourceKind.VIDEO,
            MediaSourcePolicy.classify("tv.danmaku.bili", null)
        )
    }

    @Test
    fun packageMatchIsTrimmedAndCaseInsensitive() {
        assertFalse(MediaSourcePolicy.isLyricEligible("  TV.Danmaku.Bili  ", null))
    }

    @Test
    fun musicAndUnknownPackagesAreEligible() {
        // 已知音乐应用与未收录的包名都放行:宁漏勿错。
        assertTrue(MediaSourcePolicy.isLyricEligible("com.netease.cloudmusic", null))
        assertTrue(MediaSourcePolicy.isLyricEligible("com.tencent.qqmusic", null))
        assertTrue(MediaSourcePolicy.isLyricEligible("com.example.unknown.player", null))
        assertEquals(
            MediaSourceKind.UNKNOWN,
            MediaSourcePolicy.classify("com.example.unknown.player", null)
        )
    }

    @Test
    fun explicitMovieContentTypeIsNotMusic() {
        // 包名未收录但会话显式声明了 MOVIE:仍按非音乐排除(下载类/本地视频播放器)。
        assertFalse(
            MediaSourcePolicy.isLyricEligible(
                "com.example.local.videoplayer",
                MediaContentType.MOVIE
            )
        )
    }

    @Test
    fun explicitMusicContentTypeIsEligible() {
        assertTrue(
            MediaSourcePolicy.isLyricEligible("com.example.player", MediaContentType.MUSIC)
        )
        assertEquals(
            MediaSourceKind.MUSIC,
            MediaSourcePolicy.classify("com.example.player", MediaContentType.MUSIC)
        )
    }

    @Test
    fun speechAndSonificationAreNotMusic() {
        // 播客/有声书与提示音不是音乐:歌词对其无意义。
        assertFalse(
            MediaSourcePolicy.isLyricEligible("com.example.podcast", MediaContentType.SPEECH)
        )
        assertFalse(
            MediaSourcePolicy.isLyricEligible("com.example.ui", MediaContentType.SONIFICATION)
        )
    }

    @Test
    fun unknownSourceFailsOpen() {
        // 平台默认属性是 USAGE_MEDIA + CONTENT_TYPE_UNKNOWN,绝大多数应用从不声明内容类型。
        assertTrue(MediaSourcePolicy.isLyricEligible(null, null))
        assertTrue(MediaSourcePolicy.isLyricEligible("", null))
        assertTrue(MediaSourcePolicy.isLyricEligible("com.example.player", MediaContentType.UNKNOWN))
        assertTrue(MediaSourcePolicy.isLyricEligible(null, 99))
    }

    @Test
    fun videoPackageWinsOverMusicContentType() {
        // 包名判据优先:视频应用即使声明了 MUSIC(自报不准)也按视频处理。
        assertEquals(
            MediaSourceKind.VIDEO,
            MediaSourcePolicy.classify("tv.danmaku.bili", MediaContentType.MUSIC)
        )
    }

    @Test
    fun disabledFilterAllowsEverySource() {
        // 用户关闭开关后恢复历史行为:所有来源都放行。
        assertTrue(MediaSourcePolicy.isLyricEligible("tv.danmaku.bili", null, filterEnabled = false))
        assertTrue(
            MediaSourcePolicy.isLyricEligible(
                "tv.danmaku.bili",
                MediaContentType.MOVIE,
                filterEnabled = false
            )
        )
    }
}
