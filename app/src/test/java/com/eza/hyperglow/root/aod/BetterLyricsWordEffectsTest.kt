package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterLyricsWordEffectsTest {
    @Test
    fun timedKaraokeWordRequiresValidWindow() {
        // 布局分组合成的占位词(0..0)不是词级时间源:词位路径必须跳过,
        // 否则 timedWordProgress 对零窗恒返回 1(全亮)、整行与 Minimal 档同观感。
        assertFalse(isTimedKaraokeWord(0L, 0L))
        assertFalse(isTimedKaraokeWord(500L, 500L))
        assertFalse(isTimedKaraokeWord(500L, 400L))
        assertTrue(isTimedKaraokeWord(0L, 1L))
        assertTrue(isTimedKaraokeWord(95_507L, 95_687L))
    }

    @Test
    fun longSyllableThresholdMatchesReferenceSevenHundredMillis() {
        assertTrue(isLongKaraokeSyllable(700L))
        assertTrue(isLongKaraokeSyllable(1_500L))
        assertFalse(isLongKaraokeSyllable(699L))
        assertFalse(isLongKaraokeSyllable(0L))
    }

    @Test
    fun longSyllablesScaleToBetterLyricsPeakOnlyInBetterLyricsMode() {
        assertEquals(1.15f, karaokeScalePeak(betterLyrics = true, longSyllable = true))
        // 短音节与非 BetterLyrics 档沿用基础峰值(既有逐字卡拉OK观感不变)。
        assertEquals(1.0505f, karaokeScalePeak(betterLyrics = true, longSyllable = false))
        assertEquals(1.0505f, karaokeScalePeak(betterLyrics = false, longSyllable = true))
    }

    @Test
    fun betterLyricsDisablesTheInWordSweepOnlyForLongSyllables() {
        // 口径 B:BetterLyrics 档只对长音节(≥700ms)关扫光——该词块「开始唱即整块亮起」。
        assertFalse(karaokeSweepEnabled(betterLyrics = true, longSyllable = true))
        // 其余音节恢复历史词内扫光带(0.3.156 (183) 的整档关闭撤销;底层跳变成因已修)。
        assertTrue(karaokeSweepEnabled(betterLyrics = true, longSyllable = false))
        // 非 BetterLyrics 档(基础卡拉OK路径)长/短音节全部保持词内扫光,历史观感不变。
        assertTrue(karaokeSweepEnabled(betterLyrics = false, longSyllable = true))
        assertTrue(karaokeSweepEnabled(betterLyrics = false, longSyllable = false))
    }

    @Test
    fun scaleCurveRisesToPeakThenFallsBackToOne() {
        assertEquals(0.95, karaokeScaleAt(0f, 1.15f).toDouble(), 1e-5)
        assertEquals(1.15, karaokeScaleAt(0.7f, 1.15f).toDouble(), 1e-5)
        assertEquals(1.0, karaokeScaleAt(1f, 1.15f).toDouble(), 1e-5)
    }

    @Test
    fun unsungSyllableSinksByTenPercentOfLineHeightWithOnePixelFloor() {
        assertEquals(10.0, karaokeFloatSinkPx(100f).toDouble(), 1e-4)
        assertEquals(1.0, karaokeFloatSinkPx(0f).toDouble(), 1e-4)
    }

    @Test
    fun floatOffsetSinksUnplayedRisesWhilePlayingAndRestsWhenSung() {
        // 未唱恒下沉;已唱完归零。
        assertEquals(10.0, karaokeFloatOffsetPx(0f, 1_000L, 10f).toDouble(), 1e-4)
        assertEquals(0.0, karaokeFloatOffsetPx(1f, 1_000L, 10f).toDouble(), 1e-4)
        // 长词 1000ms:上浮段占 45%(450/1000),演唱进度 0.45 时刚好回到基线,半程回一半。
        assertEquals(0.0, karaokeFloatOffsetPx(0.45f, 1_000L, 10f).toDouble(), 1e-4)
        assertEquals(5.0, karaokeFloatOffsetPx(0.225f, 1_000L, 10f).toDouble(), 1e-4)
        // 短词(≤450ms)整段用于上浮:半程回到一半。
        assertEquals(5.0, karaokeFloatOffsetPx(0.5f, 300L, 10f).toDouble(), 1e-4)
    }

    @Test
    fun syntheticCharTimeWindowSplitsBlockSpanByGeometry() {
        // 整块 1000ms/总宽 100px:0..10px 的字符占前 100ms,50..60px 占中段 500..600ms。
        assertEquals(0L until 100L, syntheticCharTimeWindow(0L, 1_000L, 100f, 0f, 10f))
        assertEquals(500L until 600L, syntheticCharTimeWindow(0L, 1_000L, 100f, 50f, 10f))
        // 带块起点偏移:窗口整体平移。
        assertEquals(1_200L until 1_300L, syntheticCharTimeWindow(1_000L, 2_000L, 100f, 20f, 10f))
        // 前缀贴近块尾/零宽字符:钳制到块内且不越过块尾。
        assertEquals(950L until 1_000L, syntheticCharTimeWindow(0L, 1_000L, 100f, 95f, 10f))
        assertEquals(1_000L until 1_000L, syntheticCharTimeWindow(0L, 1_000L, 100f, 100f, 0f))
    }

    @Test
    fun syntheticBlocksSplitCjkPerCharAndWesternPerWord() {
        // 中文逐字成块、西文按词成块(含词内标点)、空白跳过。
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), syntheticKaraokeBlocks("蝴蝶飞"))
        assertEquals(listOf(0 until 5, 6 until 11), syntheticKaraokeBlocks("hello world"))
        assertEquals(listOf(0 until 5, 6 until 7, 7 until 8), syntheticKaraokeBlocks("hello 你好"))
        assertEquals(listOf(0 until 5), syntheticKaraokeBlocks("don't"))
        assertEquals(listOf(0 until 1, 2 until 3), syntheticKaraokeBlocks("你 好"))
        assertEquals(emptyList<IntRange>(), syntheticKaraokeBlocks("   "))
    }

    @Test
    fun karaokeUnitAdvancesByCodePointAndClampsToSliceEnd() {
        // 非 BMP 占位符 🎶(U+1F3B6)是代理对:整个码点一步推进,绝不按 UTF-16 单码元切
        // (旧实现按单码元推进 → 两个孤立代理项 → Android 绘制成未知符号方框)。
        assertEquals(2, karaokeUnitEnd("🎶", 0, 2))
        // BMP 字符仍是单码元一步。
        assertEquals(1, karaokeUnitEnd("a🎶b", 0, 4))
        assertEquals(3, karaokeUnitEnd("a🎶b", 1, 4))
        assertEquals(4, karaokeUnitEnd("a🎶b", 3, 4))
        // 边界收敛:块/行切片边界落在代理对中间时不得越出(宁可钳到边界,不产出越界切片)。
        assertEquals(1, karaokeUnitEnd("🎶", 0, 1))
        // 已到/越过边界的入参安全返回边界(循环终止条件不被破坏)。
        assertEquals(2, karaokeUnitEnd("🎶", 2, 2))
        assertEquals(3, karaokeUnitEnd("🎶", 3, 3))
    }

    @Test
    fun syntheticBlocksKeepNonBmpPlaceholderAsOneBlock() {
        // 占位符整块:0 until 2——代理对不被拆成两块(逐字合成的最小输入)。
        assertEquals(listOf(0 until 2), syntheticKaraokeBlocks("🎶"))
        // emoji 与 CJK/西文相邻时也不劈代理对。
        assertEquals(listOf(0 until 2, 2 until 3), syntheticKaraokeBlocks("🎶好"))
        assertEquals(listOf(0 until 1, 1 until 3, 3 until 4), syntheticKaraokeBlocks("好🎶好"))
    }

    @Test
    fun karaokeUnitSlicesNeverProduceLoneSurrogatesAndReassembleSource() {
        // 行级合成逐字切片的完整不变量:逐码点推进拼接 == 原文,且每段不含孤立代理项
        // (旧实现逐单码元切会把每个 🎶 劈成两段孤立代理项)。
        val text = "前🎶a🎶后"
        var index = 0
        val pieces = ArrayList<String>()
        while (index < text.length) {
            val end = karaokeUnitEnd(text, index, text.length)
            val piece = text.substring(index, end)
            assertFalse("lone surrogate in \"$piece\"", hasLoneSurrogate(piece))
            pieces += piece
            index = end
        }
        assertEquals(text, pieces.joinToString(""))
        assertEquals(listOf("前", "🎶", "a", "🎶", "后"), pieces)
    }

    /** 文本是否含孤立代理项(高代理项后无低代理项,或低代理项前无高代理项)。 */
    private fun hasLoneSurrogate(text: String): Boolean = text.withIndex().any { (index, ch) ->
        when {
            ch.isHighSurrogate() -> index + 1 >= text.length || !text[index + 1].isLowSurrogate()
            ch.isLowSurrogate() -> index == 0 || !text[index - 1].isHighSurrogate()
            else -> false
        }
    }

    @Test
    fun karaokeAlphaFollowsRowBrightnessFactorAndKeepsUnsungRelativeDim() {
        // 主行语义(因子 1):已唱满亮,未唱沿用 0.35 相对暗度(steadyTextAlpha(0.35)=0.56,
        // 与历史逐字档逐值一致)。
        assertEquals(255, karaokeSungAlpha(1f))
        assertEquals(143, karaokeUnsungAlpha(1f))
        // 辅助文字行语义:「高亮辅助文字」关闭时整行一起变暗(因子 0.56),已唱=行亮度、
        // 未唱按同一比例更暗(255×0.56=142.8→143;255×0.56×0.56=79.968→80)。
        assertEquals(143, karaokeSungAlpha(0.56f))
        assertEquals(80, karaokeUnsungAlpha(0.56f))
        assertTrue(karaokeUnsungAlpha(0.56f) < karaokeSungAlpha(0.56f))
        // 钳制:越界因子不产生负值/溢出;未唱上限恒为 0.56 相对暗度封顶(143,永不到 255)。
        assertEquals(0, karaokeSungAlpha(-1f))
        assertEquals(255, karaokeSungAlpha(2f))
        assertEquals(0, karaokeUnsungAlpha(-1f))
        assertEquals(143, karaokeUnsungAlpha(2f))
    }
}
