package com.eza.hyperglow.ui

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时预览的 LyricProducerState → LyricSnapshot 映射钉子(ProducerCollectors.toPreviewSnapshot):
 * 「显示第二行辅助文字」的预览行构建直接消费快照的 nextLineRomanized/nextLineTranslated,映射
 * 漏传时两字段恒为空串,放歌(实时快照)的预览永远缺第四行——enrichState(#130)只接通了回填
 * 生产者状态的半边,演示快照自带字段掩盖了这一缺口。
 */
class PreviewSnapshotMappingTest {

    private fun state(
        nextLine: String = "",
        nextLineRomanized: String = "",
        nextLineTranslated: String = "",
        playing: Boolean = true,
        status: String = "connected",
        line: String = "第一行歌词"
    ) = LyricProducerState(
        producerId = "test",
        generation = 1,
        sequence = 1L,
        status = status,
        trackUri = "test://track",
        title = "蝴蝶",
        artist = "洛天依",
        album = "",
        imageId = "",
        line = line,
        romanizedLine = "dì yī háng gē cí",
        translatedLine = "first line",
        lineIndex = 0,
        positionMs = 1_000L,
        durationMs = 200_000L,
        sampledAtElapsedMs = 5_000L,
        speed = 1f,
        playing = playing,
        receivedAtElapsedMs = 5_000L,
        words = null,
        renderModes = ProducerRenderModes("", "", 100, "", "", "", "", "", "", ""),
        nextLine = nextLine,
        nextLineRomanized = nextLineRomanized,
        nextLineTranslated = nextLineTranslated
    )

    @Test
    fun nextLineAuxFieldsPassThroughToPreviewSnapshot() {
        val snapshot = state(
            nextLine = "第二行歌词",
            nextLineRomanized = "dì èr háng gē cí",
            nextLineTranslated = "second line"
        ).toPreviewSnapshot(
            metadataParts = "title,artist",
            metadataSeparators = "",
            duetMarkers = false,
            hideAlbumWhenSameAsTitle = false,
            artworkFrame = null
        )
        assertEquals("第二行歌词", snapshot.nextLine)
        assertEquals("dì èr háng gē cí", snapshot.nextLineRomanized)
        assertEquals("second line", snapshot.nextLineTranslated)
    }

    @Test
    fun nextLineTextStripsDuetMarkerButAuxFieldsPassRaw() {
        // 行文本按面「识别对唱标记」剥行首标记;辅助文字与实机 projectToDisplay 同口径
        // 原样透传(实机读 state.nextLineRomanized/Translated 不剥标记),两端才同源。
        val snapshot = state(
            nextLine = "（男）第二行歌词",
            nextLineRomanized = "（男）dì èr háng",
            nextLineTranslated = "（男）second line"
        ).toPreviewSnapshot(
            metadataParts = "title",
            metadataSeparators = "",
            duetMarkers = true,
            hideAlbumWhenSameAsTitle = false,
            artworkFrame = null
        )
        assertEquals("第二行歌词", snapshot.nextLine)
        assertEquals("（男）dì èr háng", snapshot.nextLineRomanized)
        assertEquals("（男）second line", snapshot.nextLineTranslated)
    }

    @Test
    fun pausedTransportDoesNotTakeOverThePreview() {
        // 暂停时仲裁器有意保留冻结状态(isFaulted 要求 playing),active 会长期停在暂停前
        // 那句歌词上。预览若照单全收就冻在旧歌词上,而不是回退到循环播放的演示歌词
        // (「蝴蝶」演示行)——本用例钉住「不在播即交还演示」的判据。
        assertFalse(presentsLivePreview(state(playing = false)))
        // 暂停态即使仍带着完整的歌词内容(行文本/下一行/状态 ready)也不接管预览:
        // 内容与在播与否无关,不能作为判据。
        assertFalse(
            presentsLivePreview(
                state(
                    playing = false,
                    status = "ready",
                    line = "暂停时残留的歌词",
                    nextLine = "下一行"
                )
            )
        )
    }

    @Test
    fun playingTransportStillTakesOverThePreview() {
        assertTrue(presentsLivePreview(state(playing = true)))
        // 逐行推流源(SuperLyric)以有无当前行派生 playing,行文本为空的元数据态不接管。
        assertTrue(presentsLivePreview(state(playing = true, status = "ready", line = "")))
    }
}
