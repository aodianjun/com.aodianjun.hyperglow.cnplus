package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // --- 对唱文本标记(（男）/（女）/（合）)识别 ---

    @Test
    fun duetMarkerParsesLeadingTokens() {
        assertEquals(
            DuetMarker("女", "男共女的事总有人偏私"),
            parseDuetMarker("（女） 男共女的事总有人偏私")
        )
        assertEquals(
            DuetMarker("男", "男共女的事深究无意义"),
            parseDuetMarker("（男）男共女的事深究无意义")
        )
        assertEquals(DuetMarker("合", "如讲起恋爱这课题"), parseDuetMarker("（合）如讲起恋爱这课题"))
        // 半角括号同样识别。
        assertEquals(DuetMarker("女", "谁亦说得易"), parseDuetMarker("(女) 谁亦说得易"))
    }

    @Test
    fun duetMarkerIgnoresInlineAndBareMarkerLines() {
        // 标记只认行首;纯标记行保留原样(不产出空行)。
        assertNull(parseDuetMarker("恋爱不见得（男）叫人坐不安"))
        assertNull(parseDuetMarker("（男）"))
        assertNull(parseDuetMarker("（女）   "))
        assertNull(parseDuetMarker("普通歌词"))
    }

    @Test
    fun stripDuetMarkerIsIdempotent() {
        val stripped = stripDuetMarker("（女） 男共女的事总有人偏私")
        assertEquals("男共女的事总有人偏私", stripped)
        assertEquals(stripped, stripDuetMarker(stripped))
        assertEquals("普通歌词", stripDuetMarker("普通歌词"))
    }

    @Test
    fun stripDuetMarkerWordsDropsOrTrimsLeadingMarkerWord() {
        val words = listOf(
            LyricWord("（女）男共女", "", 0L, 100L, boundaryAfter = false),
            LyricWord("的事", "", 100L, 200L, boundaryAfter = false)
        )
        val stripped = stripDuetMarkerWords(words)
        assertEquals(2, stripped.size)
        assertEquals("男共女", stripped[0].text)
        // 首词整词就是标记 → 移除该词。
        val markerOnly = listOf(
            LyricWord("（女）", "", 0L, 100L, boundaryAfter = false),
            LyricWord("的事", "", 100L, 200L, boundaryAfter = false)
        )
        assertEquals(listOf("的事"), stripDuetMarkerWords(markerOnly).map { it.text })
        // 无标记词表返回输入实例。
        val plain = listOf(LyricWord("的事", "", 0L, 100L, boundaryAfter = false))
        assertSame(plain, stripDuetMarkerWords(plain))
    }

    @Test
    fun markerIdentityFeedsNoTypeAlignment() {
        // 标记 → 身份(男/女 交替,合 不参与):与元数据身份同走 resolveDuetAlignment。
        val lines = listOf(
            line(duetMarkerAgentId(parseDuetMarker("（女） 男共女的事")!!)),
            line(duetMarkerAgentId(parseDuetMarker("（男） 男共女的事深究")!!)),
            line(duetMarkerAgentId(parseDuetMarker("（合） 如讲起恋爱")!!)),
            line(duetMarkerAgentId(parseDuetMarker("（男） 谁亦有本事")!!))
        )
        assertEquals(listOf(false, true, false, true), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun chorusMarkerProducesNoIdentity() {
        assertNull(duetMarkerAgentId(DuetMarker("合", "如讲起恋爱这课题")))
        assertEquals("男", duetMarkerAgentId(DuetMarker("男", "其实有些事")))
    }

    // --- 段落标记(（副歌）/（间奏）等)识别 ---

    @Test
    fun sectionMarkerStripsButProducesNoIdentity() {
        // 段落标记只作显示剥离:不产出演唱者身份,对唱分侧零参与。
        assertEquals(DuetMarker("副歌", "爱你一万年"), parseDuetMarker("（副歌）爱你一万年"))
        assertNull(duetMarkerAgentId(parseDuetMarker("（副歌）爱你一万年")!!))
        assertNull(duetMarkerAgentId(parseDuetMarker("（间奏）轻快地弹奏")!!))
        assertEquals("爱你一万年", stripDuetMarker("（副歌）爱你一万年"))
    }

    @Test
    fun sectionMarkerVocabularyCoversCommonLabels() {
        for (token in listOf("前奏", "间奏", "尾奏", "主歌", "副歌", "桥段", "说唱", "旁白", "RAP", "Chorus")) {
            assertEquals(DuetMarker(token, "词"), parseDuetMarker("（$token）词"))
        }
        // ASCII 词大小写不敏感。
        assertEquals("词", stripDuetMarker("（rap）词"))
    }

    @Test
    fun sectionMarkerDoesNotDisturbSingerAlternation() {
        // 无类型分支:身份表只收演唱者标记,段落行无身份 → 保持源值且不进身份表。
        // 纯标记行「（间奏）」parseDuetMarker 按既有规则返回 null(保留原样显示)→ 同样无身份。
        val lines = listOf(
            line(duetMarkerAgentId(parseDuetMarker("（女） 男共女的事")!!)),
            line(duetMarkerAgentId(parseDuetMarker("（副歌） 合唱段落")!!)),
            line(duetMarkerAgentId(parseDuetMarker("（男） 男共女的事深究")!!)),
            line()
        )
        // 身份只有女/男:女(先现)居左、男居右;两行无身份保持源值(居左)。
        assertEquals(listOf(false, false, true, false), resolveDuetAlignment(lines, enabled = true))
    }

    @Test
    fun compositeAndStackedMarkersResolveAsOneRun() {
        // 组内复合(男·RAP)与连写(男)(副歌)都剥成一串;身份取首个演唱者词。
        assertEquals(DuetMarker("男", "说唱段"), parseDuetMarker("（男·RAP）说唱段"))
        assertEquals("男", duetMarkerAgentId(parseDuetMarker("（男·RAP）说唱段")!!))
        assertEquals(DuetMarker("男", "词"), parseDuetMarker("（男）（副歌）词"))
        assertEquals("词", stripDuetMarker("（男）（副歌）词"))
        // 序号粘合:副歌2 仍归一为副歌。
        assertEquals(DuetMarker("副歌", "词"), parseDuetMarker("（副歌2）词"))
    }

    @Test
    fun nonMarkerParenthesizedContentStaysRaw() {
        // 词表外括号内容是歌词本身,不得误剥。
        assertNull(parseDuetMarker("（爱你）一万年"))
        assertEquals("（爱你）一万年", stripDuetMarker("（爱你）一万年"))
        assertNull(parseDuetMarker("（男·爱你）一万年"))
        // 纯标记行保留原样(不产出空行),段落标记同规则。
        assertNull(parseDuetMarker("（副歌）"))
        assertNull(parseDuetMarker("（男）（副歌）"))
    }

    @Test
    fun pureSectionMarkerLineCountsZeroSingChars() {
        // 纯标记行(（间奏）等)剥成空串:可唱估时 0 字,长间奏行窗不被误判成损坏行。
        assertEquals("", stripDuetMarkerRun("（间奏）"))
        assertEquals("", stripDuetMarkerRun("（男）（副歌）"))
        assertEquals("爱你一万年", stripDuetMarkerRun("（副歌）爱你一万年"))
    }
}
