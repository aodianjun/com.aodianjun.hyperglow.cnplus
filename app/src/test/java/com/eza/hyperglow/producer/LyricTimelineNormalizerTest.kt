package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricTimelineNormalizer] 三类口径单测(2026-10-05 生产者 ingest 时间基准统一):
 * ① 词窗超出行窗 → 行窗扩到词窗并集;
 * ② 行窗远超可唱估时(既有判据)且词窗可信 → 行窗向词窗对齐;
 * ③ 正常拖尾(行窗 > 词窗并集但差距在正常范围)→ 行窗逐值原样。
 * 估时/判据从 LyriconTimelineRepair 平移而来(全仓单一副本),原语义断言一并迁移。
 */
class LyricTimelineNormalizerTest {

    @Test
    fun normalTailWindowIsReturnedByteIdentical() {
        // 钉子(③):行窗 8000ms、词窗并集 3000ms、估时 3000ms 是正常拖尾形状——
        // 8000 = 估时×2 + 2000 恰好不满足 gross 的严格大于 → 行窗两端逐值原样返回。
        val window = LyricTimelineNormalizer.normalizeLineWindow(
            beginMs = 0L,
            endMs = 8_000L,
            wordBeginMs = 3_000L,
            wordEndMs = 6_000L,
            estimatedSingMs = 3_000L
        )
        assertEquals(0L, window.beginMs)
        assertEquals(8_000L, window.endMs)
    }

    @Test
    fun wordWindowBeyondLineExpandsLineToUnion() {
        // ① 尾部越出:行窗 [1000,4000]、词窗并集 [1200,5000] → [1000,5000](原行尾保留)。
        val tail = LyricTimelineNormalizer.normalizeLineWindow(
            beginMs = 1_000L, endMs = 4_000L,
            wordBeginMs = 1_200L, wordEndMs = 5_000L,
            estimatedSingMs = 2_100L
        )
        assertEquals(1_000L, tail.beginMs)
        assertEquals(5_000L, tail.endMs)
        // ① 头部越出:词窗并集 [800,3000] → [800,4000](原行首保留)。
        val head = LyricTimelineNormalizer.normalizeLineWindow(
            beginMs = 1_000L, endMs = 4_000L,
            wordBeginMs = 800L, wordEndMs = 3_000L,
            estimatedSingMs = 2_100L
        )
        assertEquals(800L, head.beginMs)
        assertEquals(4_000L, head.endMs)
    }

    @Test
    fun grossWindowAnchorsToPlausibleWords() {
        // ② 行窗 17.85s 对估时 3.5s(远超),词窗跨距 3.642s 可信 → 向词窗对齐。
        val window = LyricTimelineNormalizer.normalizeLineWindow(
            beginMs = 20_210L, endMs = 38_060L,
            wordBeginMs = 35_358L, wordEndMs = 39_000L,
            estimatedSingMs = 3_500L
        )
        assertEquals(35_358L, window.beginMs)
        assertEquals(39_000L, window.endMs)
    }

    @Test
    fun grossWindowWithImplausibleWordsStaysUntouched() {
        // 词窗同样失真(铺满间隙的合成词,跨距 69.79s ≫ 词锚定档)→ 本函数不动行窗,
        // 交由既有丢词/钳制路径;也不按 ① 扩到假词窗(否则假词窗会拉长行窗)。
        val window = LyricTimelineNormalizer.normalizeLineWindow(
            beginMs = 20_210L, endMs = 38_060L,
            wordBeginMs = 20_210L, wordEndMs = 90_000L,
            estimatedSingMs = 3_500L
        )
        assertEquals(20_210L, window.beginMs)
        assertEquals(38_060L, window.endMs)
    }

    @Test
    fun missingWordWindowReturnsLineUnchanged() {
        // 不带词窗的一笔没有数据可归一 → 原样返回(长窗是否可信由既有护栏另行判定)。
        val window = LyricTimelineNormalizer.normalizeLineWindow(
            beginMs = 1_000L, endMs = 20_000L,
            wordBeginMs = null, wordEndMs = null,
            estimatedSingMs = 3_500L
        )
        assertEquals(1_000L, window.beginMs)
        assertEquals(20_000L, window.endMs)
    }

    @Test
    fun estimateAndGrossJudgeKeepExistingSemantics() {
        // 估时忽略行首标记——标记不发声;纯标记行按 0 字计(长间奏行窗不得被误判成损坏行)。
        assertEquals(3_500L, LyricTimelineNormalizer.estimatedSingMs("（男） 恋爱不见得叫人坐不安"))
        assertEquals(3_500L, LyricTimelineNormalizer.estimatedSingMs("恋爱不见得叫人坐不安"))
        assertEquals(4_550L, LyricTimelineNormalizer.estimatedSingMs("或有可能慢慢地去摸索便成事"))
        assertEquals(3_500L, LyricTimelineNormalizer.estimatedSingMs("（副歌） 恋爱不见得叫人坐不安"))
        assertEquals(0L, LyricTimelineNormalizer.estimatedSingMs("（间奏）"))
        assertFalse(LyricTimelineNormalizer.grossWindowMs("（间奏）", 30_000L))
        // gross 判据为严格大于:估时×2+2000 恰等不算(正常拖尾钉子的同一形状)。
        assertFalse(LyricTimelineNormalizer.grossWindowMs(3_000L, 8_000L))
        assertTrue(LyricTimelineNormalizer.grossWindowMs(3_000L, 8_001L))
    }
}
