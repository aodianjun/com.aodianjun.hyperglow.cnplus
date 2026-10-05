package com.eza.hyperglow.producer

import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SuperLyricLyricProducer] ingest 行窗/词窗基准统一测试。
 *
 * 真机(2026-10-04 录屏)实测 SuperLyric 对同一行两阶段下发:同一行窗
 * 68565..74461 下 `words=13 ↔ words=0` 交替(184 稳定化的直接病因)。本层保证
 * 「带词表的一笔」自身不与词窗矛盾:词窗越出行窗 → 行窗扩到并集;正常拖尾
 * 与不带词表的一笔逐值不变(渲染侧 184 稳定化降级为防御层)。
 *
 * Robolectric 同 [SuperLyricNextLineTest]:receiver stub 继承 android.os.Binder。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SuperLyricTimelineNormalizeTest {

    private val producer = SuperLyricLyricProducer { 0L }

    private fun data(lyric: SuperLyricLine): SuperLyricData = SuperLyricData().apply {
        setTitle("Test Song")
        setArtist("Test Artist")
        setLyric(lyric)
    }

    @Test
    fun wordWindowBeyondLineExpandsEmittedWindow() {
        // ① 词窗超出行窗(词尾越出行尾 1s):发射行窗扩到并集,词级原样保留。
        val line = SuperLyricLine(
            "未花光心智人便透支",
            arrayOf(
                SuperLyricWord("未花光", 10_500L, 11_000L),
                SuperLyricWord("心智人", 11_000L, 13_000L)
            ),
            10_000L, 12_000L
        )
        producer.receiver.onLyric("netease", data(line))
        val state = producer.state.value
        assertEquals(10_000L, state?.lineStartMs)
        assertEquals(13_000L, state?.lineEndMs)
        assertEquals(2, state?.words?.size)
    }

    @Test
    fun normalTailWindowIsEmittedByteIdentical() {
        // 钉子(③):真机两阶段行窗 68565..74461、词窗在行内、行窗未超估时
        // (5896ms ≤ 9 字估时 3150ms×2+2000)→ 发射行窗两端逐值不变(正常拖尾不动)。
        val line = SuperLyricLine(
            "未花光心智人便透支",
            arrayOf(
                SuperLyricWord("未花光", 69_000L, 70_500L),
                SuperLyricWord("心智人", 70_500L, 72_000L)
            ),
            68_565L, 74_461L
        )
        producer.receiver.onLyric("netease", data(line))
        val state = producer.state.value
        assertEquals(68_565L, state?.lineStartMs)
        assertEquals(74_461L, state?.lineEndMs)
    }

    @Test
    fun lineWithoutWordsKeepsItsWindowAsIs() {
        // 不带词表的一笔没有数据可归一 → 行窗原样(渲染侧 184 稳定化覆盖这一阶段)。
        val line = SuperLyricLine("未花光心智人便透支", 10_000L, 12_000L)
        producer.receiver.onLyric("netease", data(line))
        val state = producer.state.value
        assertEquals(10_000L, state?.lineStartMs)
        assertEquals(12_000L, state?.lineEndMs)
        assertNull(state?.words)
    }
}
