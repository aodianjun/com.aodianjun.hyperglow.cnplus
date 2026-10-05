package com.eza.hyperglow.producer

import io.github.proify.lyricon.lyric.model.LyricWord as LyriconLyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 行窗可信化修复测试(2026-09-28 真机「时间轴对不上」回归面,形状取自《讲男讲女》id=64374
 * 实测行表对网易云 LRC 的偏差:「（男） 恋爱不见得叫人坐不安」设备 begin=20210 真实 35358,
 * 行窗 17.85s 对 13 个可见字符,间隙吞进头部;估时按剥标记后 10 字计)。
 */
class LyriconTimelineRepairTest {

    private fun line(
        begin: Long,
        end: Long,
        text: String,
        words: List<LyriconLyricWord>? = null
    ) = RichLyricLine(
        begin = begin,
        end = end,
        text = text,
        words = words,
        translation = "",
        roma = ""
    )

    private fun word(begin: Long, end: Long, text: String) =
        LyriconLyricWord(begin = begin, end = end, text = text)

    @Test
    fun normalWindowsAreUntouched() {
        // 密排行(行窗 ≈ 可唱估时)必须零变化(同一实例)——既有曲目时间轴逐像素不变。
        val lines = listOf(
            line(5_360, 9_410, "未花光心智人便透支"),
            line(9_410, 15_450, "（男） 男共女的事深究无意义")
        )
        assertSame(lines, LyriconTimelineRepair.repair(lines))
    }

    @Test
    fun wordWindowBeyondLineExpandsLineWindow() {
        // ① 词窗超出行窗(词比行还长):行窗扩到词窗并集,词级保留(非 gross 形状)。
        // 行窗 1.0–4.0s 对 9 字估时 3.15s 不满足 gross;词窗并集 [1.2s, 5.2s] 越出行尾。
        val lines = listOf(
            line(
                1_000, 4_000, "未花光心智人便透支",
                words = listOf(word(1_200, 4_600, "未花光"), word(4_600, 5_200, "心智人"))
            )
        )
        val repaired = LyriconTimelineRepair.repair(lines)
        assertEquals(1_000L, repaired[0].begin)
        assertEquals(5_200L, repaired[0].end)
        assertEquals(2, repaired[0].words?.size)
    }

    @Test
    fun normalTailWindowIsUntouched() {
        // 钉子(③,ingest 级):行窗 0–7600ms(8 字估时 2800ms;7600 = 2800×2+2000 恰在
        // gross 阈值上,严格大于不成立)、词窗并集 2000–5000ms(跨距 3000ms)是正常拖尾
        // → repair 必须原样返回同一实例(逐字节不动,换行节奏不提前)。
        val lines = listOf(
            line(
                0, 7_600, "夜空中最亮的星啊",
                words = listOf(word(2_000, 3_500, "夜空中最"), word(3_500, 5_000, "亮的星啊"))
            )
        )
        assertSame(lines, LyriconTimelineRepair.repair(lines))
    }

    @Test
    fun gapSwallowedHeadIsClampedToEndMinusEstimate() {
        // 缺陷形状:行首被贴到上一行末尾、行窗拉长 17.85s、词级跨距同假。
        // 全曲 2 个 gross 行(损坏是系统性的)→ 钳制启用;剥标记后 10 字 × 350ms = 3500ms
        // → 行首钳到 end-估时 = 38060-3500 = 34560(真实 35358,误差 0.8s)。
        val lines = listOf(
            line(16_020, 20_210, "或有可能慢慢地去摸索便成事"),
            line(
                20_210, 38_060, "（男） 恋爱不见得叫人坐不安",
                words = listOf(word(20_210, 24_000, "恋爱"), word(24_000, 38_060, "不见得"))
            ),
            line(38_320, 43_750, "心怯慌并没耐性预计失望"),
            // 第二个损坏行(凑满护栏门槛,也演示多发损坏逐行修复)。
            line(
                43_750, 61_000, "（男）其实有些事",
                words = listOf(word(43_750, 52_000, "其实"), word(52_000, 61_000, "有些事"))
            )
        )
        val repaired = LyriconTimelineRepair.repair(lines)
        val target = repaired.first { it.text.orEmpty().contains("恋爱不见得") }
        assertEquals(34_560L, target.begin)
        assertEquals(38_060L, target.end)
        assertNull(target.words)
        val second = repaired.first { it.text.orEmpty().contains("其实有些事") }
        assertEquals(59_250L, second.begin)
        // 相邻正常行零变化。
        assertEquals(16_020L, repaired.first { it.text.orEmpty().contains("或有可能") }.begin)
        assertEquals(38_320L, repaired.first { it.text.orEmpty().contains("心怯慌") }.begin)
    }

    @Test
    fun plausibleWordTimingAnchorsGrossWindow() {
        // 行窗损坏但词级跨距可信(真实逐字时间)→ 行窗向词对齐,词级保留(不受钳制护栏影响)。
        val lines = listOf(
            line(16_020, 20_210, "或有可能慢慢地去摸索便成事"),
            line(
                20_210, 38_060, "（男） 恋爱不见得叫人坐不安",
                words = listOf(word(35_358, 37_000, "恋爱"), word(37_000, 39_000, "不见得"))
            ),
            line(39_619, 43_750, "心怯慌并没耐性预计失望")
        )
        val repaired = LyriconTimelineRepair.repair(lines)
        val target = repaired.first { it.text.orEmpty().contains("恋爱不见得") }
        assertEquals(35_358L, target.begin)
        assertEquals(39_000L, target.end)
        assertEquals(2, target.words?.size)
    }

    @Test
    fun chainedLongGapWindowKeepsItsStart() {
        // LRC 链式形状(end = next.begin):长窗是间隙挂在行尾的正常表达,行首即真实起唱,
        // 不得钳(否则正常行被推后十几秒)。两个链式 gross 行(护栏门槛已满足)都保起点。
        val lines = listOf(
            line(1_000, 16_050, "或有可能慢慢地去摸索便成事"),
            line(16_050, 35_358, "（男） 恋爱不见得叫人坐不安"),
            line(35_358, 39_619, "心怯慌并没耐性预计失望")
        )
        val repaired = LyriconTimelineRepair.repair(lines)
        val target = repaired.first { it.text.orEmpty().contains("或有可能") }
        assertEquals(1_000L, target.begin)
        assertEquals(16_050L, target.end)
        val second = repaired.first { it.text.orEmpty().contains("恋爱不见得") }
        assertEquals(16_050L, second.begin)
    }

    @Test
    fun slowBalladWordTimingIsPreserved() {
        // 慢歌长音(8 字 12s)是真实词级:词锚定生效、词表保留,不降级、不钳制。
        val words = listOf(word(0, 6_000, "夜空中最"), word(6_000, 12_000, "亮的星啊"))
        val lines = listOf(line(0, 12_000, "夜空中最亮的星啊", words = words))
        val repaired = LyriconTimelineRepair.repair(lines)
        assertEquals(2, repaired[0].words?.size)
        assertEquals(0L, repaired[0].begin)
        assertEquals(12_000L, repaired[0].end)
    }

    @Test
    fun isolatedGrossLineIsNotClamped() {
        // 慢歌收尾长音假阳性防线:孤立长窗行(全曲仅一处损坏形状)不做钳制,原样透传——
        // 实测损坏是整首系统性的,真实长音是孤立的;钳制只在 ≥MIN_GROSS_LINES_FOR_CLAMP 启用。
        val lines = listOf(
            line(1_000, 5_000, "未花光心智人便透支"),
            line(5_000, 20_000, "其实有些事")
        )
        assertSame(lines, LyriconTimelineRepair.repair(lines))
    }

    @Test
    fun repairedLinesStaySortedByBegin() {
        // 钳制把行首后移后输出仍稳定按 begin 升序(钳制理论上不越界,排序是兜底)。
        // 第三行窗拉长凑满护栏门槛:不贴附上一行 → 不钳,保形。
        val lines = listOf(
            line(1_000, 2_000, "短句"),
            line(2_000, 30_000, "（男） 恋爱不见得叫人坐不安"),
            line(32_200, 45_000, "心怯慌并没耐性预计失望")
        )
        val repaired = LyriconTimelineRepair.repair(lines)
        val begins = repaired.map { it.begin }
        assertEquals(listOf(1_000L, 26_500L, 32_200L), begins)
    }
}
