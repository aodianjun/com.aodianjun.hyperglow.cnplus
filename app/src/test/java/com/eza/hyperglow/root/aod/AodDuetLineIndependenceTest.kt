package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        val plan = planDuetRow(
            animationMode = "Gradient",
            lineLevelSync = true,
            lineSyncFillMode = "Left to right (main only)",
            lineStartMs = 147_444L,
            lineEndMs = 154_300L,
            words = words
        )
        assertEquals(OriginalLinePath.WORD_KARAOKE, plan.path)
        // 词窗压过歌词源的行级标记与「行进度效果」四档(与主行 planOriginalLine 同式)。
        assertEquals(
            OriginalLinePath.WORD_KARAOKE,
            planDuetRow(
                animationMode = "Gradient",
                lineLevelSync = true,
                lineSyncFillMode = "None",
                lineStartMs = 147_444L,
                lineEndMs = 154_300L,
                words = words
            ).path
        )
    }

    /** 并发行没有真实词窗(行级源)时保持共享扫光块——行为与修复前一致,不回退。 */
    @Test
    fun duetRowWithoutWordWindowsKeepsTheSharedSweep() {
        val plan = planDuetRow(
            animationMode = "Gradient",
            lineLevelSync = true,
            lineSyncFillMode = "Left to right (main only)",
            lineStartMs = 147_444L,
            lineEndMs = 154_300L,
            words = emptyList()
        )
        assertEquals(OriginalLinePath.BLOCK_SWEEP, plan.path)
        // 零窗占位词(布局分组合成)不算真实词窗,同样落共享扫光块。
        assertEquals(
            OriginalLinePath.BLOCK_SWEEP,
            planDuetRow(
                animationMode = "Gradient",
                lineLevelSync = true,
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 147_444L,
                lineEndMs = 154_300L,
                words = listOf(word("乐", 0L, 0L), word("鸣", 0L, 0L))
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
                lineLevelSync = true,
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 147_444L,
                lineEndMs = 154_300L,
                words = listOf(word("乐", 147_444L, 147_777L))
            ).path
        )
    }
}
