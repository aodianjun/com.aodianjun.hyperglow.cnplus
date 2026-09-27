package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对唱左右分侧纯函数测试（镜像 HyperLyric `LyricPresentationResolver.alignAgents` /
 * `alignTypedAgents` 的语义契约）。
 */
class DuetAlignmentTest {

    private fun line(agent: String? = null, type: String? = null, right: Boolean = false) =
        DuetLine(agentId = agent, agentType = type, sourceAlignedRight = right)

    // --- 元数据读取 ---

    @Test
    fun agentIdReadsKeyFamilyInPriorityOrder() {
        // 与 HyperLyric AGENT_METADATA_KEYS 同序:agent → amll:agent → vocal → amll:vocal。
        assertEquals("v1", duetAgentId(mapOf("agent" to "v1")))
        assertEquals("v1", duetAgentId(mapOf("amll:agent" to "v1")))
        assertEquals("v1", duetAgentId(mapOf("vocal" to "v1")))
        assertEquals("v1", duetAgentId(mapOf("amll:vocal" to "v1")))
        assertEquals("first", duetAgentId(mapOf("agent" to "first", "amll:agent" to "second")))
    }

    @Test
    fun agentIdIgnoresBlankAndMissingMetadata() {
        assertNull(duetAgentId(null))
        assertNull(duetAgentId(emptyMap()))
        assertNull(duetAgentId(mapOf("agent" to "   ")))
        // 空白的首选键应让位给次选键,而不是直接判定为未标注。
        assertEquals("v2", duetAgentId(mapOf("agent" to "  ", "vocal" to "v2")))
    }

    @Test
    fun agentTypeReadsKeyFamilyAndLowercases() {
        assertEquals("other", duetAgentType(mapOf("amll:agent-type" to "OTHER")))
        assertEquals("group", duetAgentType(mapOf("agent:type" to "Group")))
        assertEquals("other", duetAgentType(mapOf("agentType" to "other")))
        assertEquals("other", duetAgentType(mapOf("vocal:type" to "other")))
        assertNull(duetAgentType(mapOf("agent" to "v1")))
    }

    // --- 无类型:首个歌手居左、其余居右 ---

    @Test
    fun singleAgentKeepsEveryLineLeft() {
        val lines = listOf(line("v1"), line("v1"), line("v1"))
        assertEquals(listOf(false, false, false), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun noAgentLeavesSourceValuesUntouched() {
        val lines = listOf(line(), line(), line())
        assertEquals(listOf(false, false, false), resolveDuetAlignment(lines, enabled = true))
        // 行没有任何身份时源显式值原样穿过(不能被「推导」抹掉)。
        val explicit = listOf(line(right = true), line())
        assertEquals(listOf(true, false), resolveDuetAlignment(explicit, enabled = true))
    }

    @Test
    fun firstAgentGoesLeftAndOthersGoRight() {
        val lines = listOf(line("v1"), line("v2"), line("v1"), line("v2"))
        assertEquals(listOf(false, true, false, true), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun threeAgentsKeepOnlyTheFirstOnTheLeft() {
        // HyperLyric `rightByAgent[index > 0]`:除首个身份外全部居右。
        val lines = listOf(line("a"), line("b"), line("c"), line("a"))
        assertEquals(listOf(false, true, true, false), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun sourceExplicitRightIsNeverDowngraded() {
        // 源显式标记右对齐的行恒保留(HyperLyric `if (line.isAlignedRight) return@map line`)。
        val lines = listOf(line("v1", right = true), line("v1"), line("v2"))
        assertEquals(listOf(true, false, true), resolveDuetAlignment(lines, enabled = true))
    }

    // --- 有显式类型:group 恒左、other 起右、换歌手翻转 ---

    @Test
    fun typedOtherStartsRightTypedGroupStaysLeft() {
        val lines = listOf(
            line("chorus", type = "group"),
            line("v1", type = "other"),
            line("v2", type = "other"),
            line("chorus", type = "group")
        )
        // group 行不参与交替并保持源值;首位 other 起右,换歌手翻转。
        assertEquals(listOf(false, true, false, false), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun typedNonOtherFirstSingerStartsLeft() {
        val lines = listOf(line("v1", type = "person"), line("v2", type = "person"))
        assertEquals(listOf(false, true), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun typedSameSingerKeepsSideAcrossGaps() {
        val lines = listOf(
            line("v1", type = "other"),
            line("v1", type = "other"),
            line("v2", type = "other"),
            line("v1", type = "other")
        )
        // 同一歌手保持同侧;每次换歌手翻转。
        assertEquals(listOf(true, true, false, true), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun typedGroupOnlyLinesStayLeft() {
        val lines = listOf(line("chorus", type = "group"), line("chorus", type = "group"))
        assertEquals(listOf(false, false), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun typedSourceRightWinsOverAlternation() {
        val lines = listOf(
            line("v1", type = "other"),
            line("v2", type = "other", right = true),
            line("v2", type = "other")
        )
        assertEquals(listOf(true, true, false), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun typedSourceRightLineStillAdvancesAlternationState() {
        // 源显式居右只决定该行输出,不冻结交替状态(HyperLyric 先算 right 再判 isAlignedRight):
        // 若源值行被提前跳过,第三行的翻转换算就会基于错误的 lastAgent。
        val lines = listOf(
            line("v1", type = "other"),
            line("v2", type = "other", right = true),
            line("v3", type = "other")
        )
        assertEquals(listOf(true, true, true), resolveDuetAlignment(lines, enabled = true))
    }

    // --- enabled 门控与退化输入 ---

    @Test
    fun disabledReturnsSourceValuesOnly() {
        val lines = listOf(line("v1"), line("v2"), line("v1", right = true))
        assertEquals(
            listOf(false, false, true),
            resolveDuetAlignment(lines, enabled = false)
        )
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertTrue(resolveDuetAlignment(emptyList(), enabled = true).isEmpty())
    }

    @Test
    fun entityTypesOnlyConsideredWhenSomeLineDeclaresThem() {
        // 只有部分行带类型时整首走交替分支(HyperLyric 的 hasExplicitAgentTypes 门)。
        val lines = listOf(
            line("v1", type = "other"),
            line("v2"),
            line("v3")
        )
        // v1 起右 → v2 换歌手翻转到左 → v3 再翻转到右。
        assertEquals(listOf(true, false, true), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun groupAndOtherConstantsMatchHyperLyricVocabulary() {
        assertEquals("group", DuetAlignment.AGENT_TYPE_GROUP)
        assertEquals("other", DuetAlignment.AGENT_TYPE_OTHER)
        assertEquals(listOf("agent", "amll:agent", "vocal", "amll:vocal"), DuetAlignment.AGENT_KEYS)
        assertEquals(
            listOf("amll:agent-type", "agent:type", "agentType", "vocal:type"),
            DuetAlignment.AGENT_TYPE_KEYS
        )
    }

    @Test
    fun resultLengthAlwaysMatchesInput() {
        val lines = listOf(line("v1"), line(right = true), DuetLine())
        assertEquals(lines.size, resolveDuetAlignment(lines, enabled = true).size)
        assertEquals(lines.size, resolveDuetAlignment(lines, enabled = false).size)
    }

    @Test
    fun mixedTypedAndUntypedKeepsSourceForUntypedLines() {
        // 交替分支只改写「有身份且非 group」的行;无身份行保持源值。
        val lines = listOf(
            line("v1", type = "other"),
            line(),
            line("v2", type = "other")
        )
        assertEquals(listOf(true, false, false), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun firstAgentIsTheFirstNonGroupSingerNotTheFirstLine() {
        val lines = listOf(
            line("chorus", type = "group"),
            line("v1", type = "other"),
            line("v2", type = "other")
        )
        val resolved = resolveDuetAlignment(lines, enabled = true)
        assertFalse(resolved[0])
        assertTrue(resolved[1])
        assertFalse(resolved[2])
    }
}
