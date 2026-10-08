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
}
