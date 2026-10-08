package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.projection.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发行相对主行独立的画布侧纯函数(见 AodDuetLineIndependence):主行换行不改变并发行
 * 的内容键与槽位;并发行自己换行(文本/行窗变化)才换键,槽位一律取过渡起点快照。
 */
class AodDuetLineIndependenceTest {

    /**
     * 内容键只由并发行自己的文本与行窗起点构成:主行换行(活动行变化)没有输入项,
     * 「主行换行不改变并发行内容键」由函数形状结构性成立;同一文本在下一段和声里
     * 是另一个键(行窗起点不同)→ 触发并发行自己的过渡。
     */
    @Test
    fun contentKeyDependsOnlyOnDuetOwnContent() {
        assertEquals("(少年狂)@88600", aodDuetContentKey("(少年狂)", 88_600L))
        assertNotEquals(
            aodDuetContentKey("(少年狂)", 88_600L),
            aodDuetContentKey("(少年狂)", 145_900L)
        )
        assertNull(aodDuetContentKey("", 88_600L))
        assertNull(aodDuetContentKey(null, 88_600L))
    }

    /**
     * 主行换行后新布局把并发行行推下/推上:静止槽位仍取起点快照——并发行不动;
     * 并发行自己换行、行数变化时,起点没有的行(新出现的辅助行)退回自己的基线。
     */
    @Test
    fun frozenBaselinesKeepSlotAcrossMainLineChange() {
        val before = listOf(300f, 420f)
        val after = listOf(380f, 500f)
        assertEquals(before, frozenDuetBaselines(before, after))
        assertEquals(
            listOf(300f, 420f, 700f),
            frozenDuetBaselines(listOf(300f, 420f), listOf(380f, 500f, 700f))
        )
        // 起点还没有并发行(刚加入):全部退回当前基线,由加入淡入接管。
        assertEquals(after, frozenDuetBaselines(emptyList(), after))
    }

    private fun word(text: String, startMs: Long, endMs: Long) =
        AodCanvasWord(
            text = text,
            romanized = "",
            startMs = startMs,
            endMs = endMs,
            boundaryAfter = true
        )

    private fun projectedWord(text: String, startMs: Long, endMs: Long) =
        LyricWord(
            text = text,
            romanized = "",
            startMs = startMs,
            endMs = endMs,
            boundaryAfter = true
        )

    /**
     * 并发行带真实词窗时走**词级卡拉OK**(逐词真实时间戳),不是按行窗线性铺满的共享扫光块
     * (owner 2026-10-08:「并发时间戳没对上,明显第二句走的不是真实时间戳」)。词窗取真机
     * 形态:v1《乐鸣东方》147.444–154.300 里末字「方」独占 5.65s(拖长音)——按行窗铺光会让
     * 并发行在音频早已唱到别的句子之后还在慢慢亮,按词窗则在 1.2s 内点亮前三个字、随后
     * 「方」按自己的 5.65s 窗推进。
     */
    @Test
    fun duetRowWithRealWordWindowsTakesWordKaraoke() {
        val words = listOf(
            word("乐", 147_444L, 147_777L),
            word("鸣", 147_777L, 148_216L),
            word("东", 148_216L, 148_648L),
            word("方", 148_648L, 154_300L)
        )
        // 事实取自画布侧判据(实机接线同源)。
        val plan = planDuetRow(
            animationMode = "Gradient",
            timed = hasTimedWordWindows(words),
            lineLevelSync = true,
            lineSyncFillMode = "Left to right (main only)",
            lineStartMs = 147_444L,
            lineEndMs = 154_300L
        )
        assertEquals(OriginalLinePath.WORD_KARAOKE, plan.path)
        // 词窗压过歌词源的行级标记与「行进度效果」四档(与主行 planOriginalLine 同式)。
        assertEquals(
            OriginalLinePath.WORD_KARAOKE,
            planDuetRow(
                animationMode = "Gradient",
                timed = hasTimedWordWindows(words),
                lineLevelSync = true,
                lineSyncFillMode = "None",
                lineStartMs = 147_444L,
                lineEndMs = 154_300L
            ).path
        )
    }

    /** 并发行没有真实词窗(行级源)时保持共享扫光块——行为与修复前一致,不回退。 */
    @Test
    fun duetRowWithoutWordWindowsKeepsTheSharedSweep() {
        assertEquals(
            OriginalLinePath.BLOCK_SWEEP,
            planDuetRow(
                animationMode = "Gradient",
                timed = hasTimedWordWindows(emptyList()),
                lineLevelSync = true,
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 147_444L,
                lineEndMs = 154_300L
            ).path
        )
        // 零窗占位词(布局分组合成)不算真实词窗,同样落共享扫光块。
        assertEquals(
            OriginalLinePath.BLOCK_SWEEP,
            planDuetRow(
                animationMode = "Gradient",
                timed = hasTimedWordWindows(listOf(word("乐", 0L, 0L), word("鸣", 0L, 0L))),
                lineLevelSync = true,
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 147_444L,
                lineEndMs = 154_300L
            ).path
        )
    }

    /** Minimal 档的并发行与主行同款:静态全亮(无扫光/逐字),不单独另立形态。 */
    @Test
    fun duetRowInMinimalModeStaysStatic() {
        assertEquals(
            OriginalLinePath.STATIC,
            planDuetRow(
                animationMode = "Minimal",
                timed = true,
                lineLevelSync = true,
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 147_444L,
                lineEndMs = 154_300L
            ).path
        )
    }

    /**
     * 两套词表类型各有一个真实词窗判据(实机 `AodCanvasWord` / 预览 `LyricWord`)——**必须同值**。
     * 判据分叉就是「预览有逐字、实机没有」那类故障(本次修复前恰好相反:预览对、实机错)。
     */
    @Test
    fun bothWordModelsAgreeOnWhatCountsAsRealWordWindows() {
        val cases = listOf(
            listOf(147_444L to 147_777L, 148_648L to 154_300L) to true,
            listOf(0L to 0L, 0L to 0L) to false,
            emptyList<Pair<Long, Long>>() to false,
            listOf(5_000L to 5_000L) to false
        )
        cases.forEach { (windows, expected) ->
            val canvas = windows.map { (s, e) -> word("字", s, e) }
            val projected = windows.map { (s, e) -> projectedWord("字", s, e) }
            assertEquals(
                "windows=$windows",
                expected,
                hasTimedWordWindows(canvas)
            )
            assertEquals(
                "windows=$windows",
                hasTimedWordWindows(canvas),
                projectedWordsHaveTimedWindows(projected)
            )
        }
        // 空白文本不算真实词窗(两套判据同式)。
        assertEquals(
            hasTimedWordWindows(listOf(word("", 1_000L, 2_000L))),
            projectedWordsHaveTimedWindows(listOf(projectedWord("", 1_000L, 2_000L)))
        )
        assertEquals(false, hasTimedWordWindows(listOf(word("", 1_000L, 2_000L))))
    }

    /** 逐字测量桩:1 字 = 10px(片段宽只需可加,不涉及真实字形)。 */
    private val measure: (String) -> Float = { it.length * 10f }

    /**
     * 真机形态(《乐鸣东方》L33 的 x-bg 和声,02:50.514–02:55.737,词窗逐值取自 lmdf.ttml):
     * 和声行自带逐音节真实词窗,末字「往)」独占 1.98s(同句主行「往」2.22s)——均匀合成把
     * 整行 5.2s 平摊给 9 个音节,整行抢拍;逐字段必须**原样保留**每个音节的词窗
     * (首字 300ms、末字 1980ms 各自成立)。
     */
    @Test
    fun harmonySegmentsKeepTheRowsOwnSyllableWindows() {
        val words = listOf(
            word("(人", 170_514L, 170_814L),
            word("间", 170_814L, 171_507L),
            word("百", 171_507L, 171_703L),
            word("相", 171_703L, 171_938L),
            // 跨 span 的空白文本节点(逐字歌词的间距):无论解析器把它做成独立零窗词还是
            // 并入相邻音节,零窗占位都不得截断文本。
            word(" ", 171_938L, 171_938L),
            word("总", 171_938L, 172_407L),
            word("让", 172_407L, 172_850L),
            word("我", 172_850L, 173_069L),
            word("神", 173_280L, 173_757L),
            word("往)", 173_757L, 175_737L)
        )
        val text = "(人间百相 总让我神往)"
        val segments = harmonyTimedSegments(words, text, measure)
        assertNotNull(segments)
        // 文本逐字符一致:片段直接相连即整行文本(显示文本恒取行文本,见纯函数注释)。
        assertEquals(text, segments!!.joinToString("") { it.text })
        // 真实词窗原样保留(不是按行窗平摊的合成窗):首字 300ms、末字「往)」1980ms。
        assertEquals(300L, segments.first().endMs - segments.first().startMs)
        assertEquals(1_980L, segments.last().endMs - segments.last().startMs)
        assertEquals(
            words.map { it.startMs to it.endMs },
            segments.map { it.startMs to it.endMs }
        )
    }

    /**
     * 文本重建不出整行时返回 null(调用方回落行窗均匀合成):和声的显示文本恒取行文本,
     * 按片段画就会在逐字开关开/关之间显示两份不同文本——宁可不要逐字效果(与翻译行
     * translatedTimedSegments 同一取舍)。
     */
    @Test
    fun harmonySegmentsFallBackWhenTextCannotBeRebuilt() {
        val words = listOf(word("人间", 1_000L, 2_000L), word("百相", 2_000L, 3_000L))
        assertNull(harmonyTimedSegments(words, "(人间百相)", measure))
        assertNull(harmonyTimedSegments(words, "人间百相总让我神往", measure))
    }

    /** 无词表(行级源/未装插件)与全零窗占位词(布局分组合成)都回落合成路径,不回退成静态全亮。 */
    @Test
    fun harmonySegmentsRejectMissingOrUntimedWords() {
        assertNull(harmonyTimedSegments(emptyList(), "和声", measure))
        assertNull(
            harmonyTimedSegments(
                listOf(word("和", 0L, 0L), word("声", 0L, 0L)),
                "和声",
                measure
            )
        )
    }

    /**
     * 折行不改变行文本:片段按宽度均衡折到多行后,各行文本顺序相连仍是整行和声文本
     * (实机 harmonyTimedLines / 预览同一共享折行函数,永不渲染截断或另一份文本)。
     */
    @Test
    fun harmonySegmentsSurviveWrappingWithIdenticalText() {
        val words = listOf(
            word("和", 1_000L, 1_500L),
            word("声", 1_500L, 2_000L),
            word("回", 2_000L, 2_500L),
            word("响", 2_500L, 4_000L)
        )
        val text = "和声回响"
        val segments = harmonyTimedSegments(words, text, measure)!!
        // 单行(不折行)= 全部片段一段。
        val single = secondaryTimedVisualRanges(segments, 1_000f, 2, wrap = false)
        assertEquals(listOf(segments.indices), single)
        // 折行(maxLines 与实机 MAX_SECONDARY_LAYOUT_LINES 同值 2)后文本仍逐字符一致。
        val wrapped = secondaryTimedVisualRanges(segments, 20f, 2, wrap = true)
        assertEquals(
            text,
            wrapped.joinToString("") { range -> range.map(segments::get).joinToString("") { it.text } }
        )
        assertTrue(wrapped.all { !it.isEmpty() })
    }
}
