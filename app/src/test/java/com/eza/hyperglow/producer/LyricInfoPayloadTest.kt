package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [parseLyricInfoPayload] — lyricInfo JSON 解析按 LyricInfo README 的两种
 * 格式固定:完整版(songName/artist/album/songId/lyric/format/translation)与精简版
 * (播放器原生输出,QQ 音乐翻译在 transLyric、songId 可能是数字)。
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
}
