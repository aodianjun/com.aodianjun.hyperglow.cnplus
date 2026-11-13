package com.eza.hyperglow.ui

import com.eza.hyperglow.producer.LyricDuetLine
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricWord
import com.eza.hyperglow.producer.ProducerRenderModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 实时预览的并发行映射钉子([LyricProducerState.toPreviewDuetLine]):和声身份必须活过
 * state → snapshot 映射,预览才与实机同源走辅助行车道(小字号辅助行);真对唱(不同
 * 演唱者,非 BG)保持 false,继续按主行同款并排。漏传时预览把和声按对唱同款大字行
 * 渲染——真机 2026-10-07 反馈的错观感,演示快照自带标记掩盖了这一缺口。
 */
class PreviewDuetLineMappingTest {

    private fun state(duetLine: LyricDuetLine?) = LyricProducerState(
        producerId = "test",
        generation = 1,
        sequence = 1L,
        status = "ready",
        trackUri = "test://track",
        title = "蝴蝶",
        artist = "洛天依",
        album = "",
        imageId = "",
        line = "主行",
        romanizedLine = "",
        translatedLine = "",
        lineIndex = 0,
        positionMs = 1_000L,
        durationMs = 30_000L,
        sampledAtElapsedMs = 0L,
        speed = 1f,
        playing = true,
        receivedAtElapsedMs = 0L,
        words = null,
        renderModes = ProducerRenderModes("", "", 100, "", "", "", "", "", "", ""),
        duetLine = duetLine
    )

    @Test
    fun harmonyFlagPassesThroughTheLivePreviewMapping() {
        // 和声行(role=BG 的 x-bg 回声)原样过面:预览渲染侧据此走辅助行车道,与实机
        // AodStateProjector → wire → 画布的同名标记同源。
        val mapped = state(
            LyricDuetLine(
                text = "(人间百相总让我神往)",
                harmony = true,
                lineStartMs = 0L,
                lineEndMs = 5_000L
            )
        ).toPreviewDuetLine(duetMarkers = false)
        assertEquals(true, mapped?.harmony)
        assertEquals("(人间百相总让我神往)", mapped?.text)
    }

    @Test
    fun leadDuetLineStaysNonHarmony() {
        // 不同演唱者的对唱行不带和声标记:预览保持主行同款并排(与实机同源)。
        val mapped = state(
            LyricDuetLine(text = "second singer", lineStartMs = 0L, lineEndMs = 5_000L)
        ).toPreviewDuetLine(duetMarkers = false)
        assertEquals(false, mapped?.harmony)
    }

    @Test
    fun markerStrippingAndAlignmentStillApplyWithHarmony() {
        // 标记识别开启时按标记版选分侧并剥文本/词表(与实机 LyricCanvasMapper 同口径);
        // 和声身份与标记剥离正交,两者同时生效。
        val mapped = state(
            LyricDuetLine(
                text = "（女）(回声)",
                alignedRight = false,
                alignedRightMarkers = true,
                harmony = true,
                lineStartMs = 0L,
                lineEndMs = 5_000L,
                words = listOf(LyricWord("（女）(回声)", "", 0L, 5_000L, true, -1, -1))
            )
        ).toPreviewDuetLine(duetMarkers = true)
        assertEquals("(回声)", mapped?.text)
        assertEquals(true, mapped?.alignedRight)
        assertEquals(true, mapped?.harmony)
        assertEquals("(回声)", mapped?.words?.firstOrNull()?.text)
    }

    @Test
    fun blankDuetTextDropsTheLine() {
        // 文本为空整条丢弃(防御性口径:生产者的候选恒非空,投影/预览两侧都不放空行上屏)。
        assertNull(
            state(LyricDuetLine(text = "   ", harmony = true)).toPreviewDuetLine(duetMarkers = false)
        )
    }

    @Test
    fun absentDuetLineMapsToNull() {
        assertNull(state(null).toPreviewDuetLine(duetMarkers = false))
    }
}
