package com.example.hyperglow.amllttml

import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtmlMapperTest {

    private fun butterfly(): String =
        javaClass.getResourceAsStream("/butterfly.ttml")!!.readBytes().decodeToString()

    /** 真实样本（AMLL TTML DataBase 蝴蝶，39 行单演唱者）：解析鲁棒性与逐字覆盖。 */
    @Test
    fun butterflyMapsAllLinesWithWordLevel() {
        val rows = TtmlMapper.map(butterfly(), duet = true, translation = true, background = true)

        assertEquals(39, rows.size)
        val first = rows.first()
        assertEquals("你说你来到这世界的那天", first.text)
        assertTrue((first.words?.size ?: 0) >= 2, "首行应带逐字词表")
        assertEquals("你", first.words!!.first().text)
        assertEquals(TtmlMapper.ROLE_LEAD, first.metadata?.values?.get(TtmlMapper.META_ROLE))
        // 单 agent（v1）不产生右侧行
        assertTrue(rows.none { it.isAlignedRight })
        // 时间轴单调非负
        assertTrue(rows.all { it.begin >= 0L && it.end >= it.begin })
        assertTrue(TtmlMapper.hasWordLevel(rows))
    }

    private val duetTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
        <head><metadata>
        <ttm:agent type="person" xml:id="v1"/>
        <ttm:agent type="person" xml:id="v2"/>
        </metadata></head>
        <body>
        <div>
        <p begin="00:01.000" end="00:02.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">甲</span></p>
        <p begin="00:03.000" end="00:04.000" ttm:agent="v2"><span begin="00:03.000" end="00:04.000">乙</span></p>
        <p begin="00:05.000" end="00:06.000" ttm:agent="v1"><span begin="00:05.000" end="00:06.000">丙</span></p>
        </div>
        </body></tt>
    """.trimIndent()

    @Test
    fun duetSwitchControlsAlignedRight() {
        val on = TtmlMapper.map(duetTtml, duet = true, translation = true, background = true)
        assertEquals(3, on.size)
        assertFalse(on[0].isAlignedRight, "v1 起始在左")
        assertTrue(on[1].isAlignedRight, "换到 v2 应翻转到右")
        assertFalse(on[2].isAlignedRight, "换回 v1 应翻回左")

        val off = TtmlMapper.map(duetTtml, duet = false, translation = true, background = true)
        assertTrue(off.none { it.isAlignedRight }, "关闭对唱后不得分侧")
    }

    private val richTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
        <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body>
        <div>
        <p begin="00:01.000" end="00:03.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">主</span><span ttm:role="x-bg" begin="00:01.500" end="00:02.500"><span begin="00:01.500" end="00:02.500">和</span></span><span ttm:role="x-translation">译</span></p>
        </div>
        </body></tt>
    """.trimIndent()

    @Test
    fun backgroundAndTranslationSwitches() {
        val full = TtmlMapper.map(richTtml, duet = true, translation = true, background = true)
        assertEquals(2, full.size, "主行 + 和声行")
        val lead = full.first { it.metadata?.values?.get(TtmlMapper.META_ROLE) == TtmlMapper.ROLE_LEAD }
        val bg = full.first { it.metadata?.values?.get(TtmlMapper.META_ROLE) == TtmlMapper.ROLE_BG }
        assertEquals("主", lead.text)
        assertEquals("译", lead.translation)
        assertEquals("和", bg.text)
        assertFalse(bg.isAlignedRight)

        val noBg = TtmlMapper.map(richTtml, duet = true, translation = true, background = false)
        assertEquals(1, noBg.size, "关闭和声后只保留主行")

        val noTranslation = TtmlMapper.map(richTtml, duet = true, translation = false, background = true)
        assertTrue(noTranslation.all { it.translation.isNullOrBlank() }, "关闭翻译后不得挂载翻译")
    }

    @Test
    fun emptyOrBrokenInputYieldsEmpty() {
        assertTrue(TtmlMapper.map("", duet = true, translation = true, background = true).isEmpty())
        assertTrue(TtmlMapper.map("<not-ttml", duet = true, translation = true, background = true).isEmpty())
        assertFalse(TtmlMapper.hasWordLevel(emptyList()))
    }

    // ---- 行级 x-bg 与 x-bg 内嵌 x-roman（1.0.2 原文回扫兜底）---------------

    private fun role(line: PluginLyricLine): String? =
        line.metadata?.values?.get(TtmlMapper.META_ROLE)

    private fun wrap(body: String): String = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
        <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body><div>
        $body
        </div></body></tt>
    """.trimIndent()

    /** 规范 §5/§7.3 的行级 x-bg（纯文本，内层无时轴）：库会整条丢弃。 */
    private val inlineBgTtml = wrap(
        """<p begin="00:05.000" end="00:09.000" ttm:agent="v1">一行歌词<span ttm:role="x-bg">(伴唱)</span></p>"""
    )

    @Test
    fun inlineBackgroundWithoutNestedTimingIsSynthesized() {
        val rows = TtmlMapper.map(inlineBgTtml, duet = true, translation = true, background = true)

        assertEquals(2, rows.size, "行级 x-bg 必须补出独立 BG 行")
        val lead = rows.first { role(it) == TtmlMapper.ROLE_LEAD }
        val bg = rows.first { role(it) == TtmlMapper.ROLE_BG }
        assertEquals("一行歌词", lead.text)
        assertEquals("(伴唱)", bg.text)
        // span 无自带窗口时回退父 <p> 的窗口
        assertEquals(5_000L, bg.begin)
        assertEquals(9_000L, bg.end)
        assertEquals(4_000L, bg.duration)
        assertFalse(bg.isAlignedRight)
        assertNull(bg.words, "行级 x-bg 没有逐字词表，宿主走行级渲染路径")
        assertNull(bg.translation)
        assertNull(bg.roma)
    }

    @Test
    fun inlineBackgroundPrefersItsOwnWindow() {
        val ttml = wrap(
            """<p begin="00:05.000" end="00:09.000" ttm:agent="v1">一行歌词<span ttm:role="x-bg" begin="00:06.000" end="00:08.000">(伴唱)</span></p>"""
        )

        val rows = TtmlMapper.map(ttml, duet = true, translation = true, background = true)

        assertEquals(2, rows.size)
        val bg = rows.first { role(it) == TtmlMapper.ROLE_BG }
        assertEquals(6_000L, bg.begin, "span 自带 begin 时优先于父 <p> 窗口")
        assertEquals(8_000L, bg.end)
        assertEquals(2_000L, bg.duration)
    }

    /** 逐字主行 + 行级 x-bg：主行是 MainKaraokeLine，和声行同样要补。 */
    @Test
    fun wordLevelMainLineGetsInlineBackgroundRow() {
        val ttml = wrap(
            """<p begin="00:05.000" end="00:09.000" ttm:agent="v1"><span begin="00:05.000" end="00:07.000">主唱</span><span ttm:role="x-bg">(伴唱)</span></p>"""
        )

        val rows = TtmlMapper.map(ttml, duet = true, translation = true, background = true)

        assertEquals(2, rows.size)
        assertEquals("主唱", rows.first { role(it) == TtmlMapper.ROLE_LEAD }.text)
        val bg = rows.first { role(it) == TtmlMapper.ROLE_BG }
        assertEquals("(伴唱)", bg.text)
        assertEquals(5_000L, bg.begin)
        assertEquals(9_000L, bg.end)
    }

    /** 规范 §7.3 的 x-bg + 内嵌 x-roman：库解析出和声行（行本身不变），音译由回扫补上。 */
    private val timedBgTtml = wrap(
        """<p begin="00:05.000" end="00:09.000" ttm:agent="v1">""" +
            """<span begin="00:05.000" end="00:07.000">主唱</span>""" +
            """<span ttm:role="x-bg" begin="00:06.000" end="00:08.000">""" +
            """<span begin="00:06.000" end="00:07.000">(伴</span>""" +
            """<span begin="00:07.000" end="00:08.000">唱)</span>""" +
            """<span ttm:role="x-roman">ban chang</span></span></p>"""
    )

    @Test
    fun timedBackgroundKeepsLibraryRowsAndGetsRoman() {
        val rows = TtmlMapper.map(timedBgTtml, duet = true, translation = true, background = true)

        assertEquals(2, rows.size, "库已解析的和声行不多不少")
        val bg = rows.first { role(it) == TtmlMapper.ROLE_BG }
        assertEquals("伴唱", bg.text, "库会剥掉包裹括号")
        assertEquals(6_000L, bg.begin)
        assertEquals(8_000L, bg.end)
        assertEquals(listOf("伴", "唱"), bg.words?.map { it.text })
        assertEquals(6_000L, bg.words!!.first().begin)
        assertEquals("ban chang", bg.roma, "x-bg 内嵌的 x-roman 必须兜底挂上")
    }

    @Test
    fun timedBackgroundWithoutRomanStaysNull() {
        val ttml = timedBgTtml.replace("""<span ttm:role="x-roman">ban chang</span>""", "")

        val rows = TtmlMapper.map(ttml, duet = true, translation = true, background = true)

        assertEquals(2, rows.size, "去掉 x-roman 后行本身不变")
        assertNull(rows.first { role(it) == TtmlMapper.ROLE_BG }.roma, "库与原文都没有音译时不得凭空补")
    }

    @Test
    fun blankBackgroundSpansAreNotEmitted() {
        val ttml = wrap(
            """<p begin="00:05.000" end="00:09.000" ttm:agent="v1">一行歌词<span ttm:role="x-bg">  </span></p>""" +
                """<p begin="00:10.000" end="00:12.000" ttm:agent="v1">另一行<span ttm:role="x-bg"></span></p>"""
        )

        val rows = TtmlMapper.map(ttml, duet = true, translation = true, background = true)

        assertEquals(2, rows.size, "空白 x-bg 不得补出空行（宿主与审核细则都禁止空白行）")
        assertTrue(rows.none { role(it) == TtmlMapper.ROLE_BG })
    }

    @Test
    fun backgroundSwitchAlsoDropsInlineBackgroundRows() {
        val rows = TtmlMapper.map(inlineBgTtml, duet = true, translation = true, background = false)

        assertEquals(1, rows.size, "关闭和声后行级 x-bg 同样不得输出")
        assertTrue(rows.none { role(it) == TtmlMapper.ROLE_BG })
    }

    /** 守卫：库给出了和声行时保持既有行为，不因为同一行还有行级 x-bg 而追加。 */
    @Test
    fun parserProvidedAccompanimentSuppressesSynthesis() {
        val ttml = wrap(
            """<p begin="00:05.000" end="00:09.000" ttm:agent="v1">""" +
                """<span begin="00:05.000" end="00:07.000">主唱</span>""" +
                """<span ttm:role="x-bg"><span begin="00:06.000" end="00:07.000">(和)</span></span>""" +
                """<span ttm:role="x-bg">(行级和声)</span></p>"""
        )

        val rows = TtmlMapper.map(ttml, duet = true, translation = true, background = true)

        assertEquals(2, rows.size, "库已给出和声行时不追加")
        assertEquals("和", rows.first { role(it) == TtmlMapper.ROLE_BG }.text)
    }

    /** 回扫函数本身：库丢弃的内容（文本/音译/时轴信号/窗口）必须被完整读出。 */
    @Test
    fun rawScanExposesDroppedBackgroundContent() {
        val timed = scanRawBgLines(timedBgTtml).single().spans.single()
        assertEquals("(伴唱)", timed.text)
        assertEquals("ban chang", timed.roman)
        assertEquals(2, timed.syllables.size, "内层时轴音节是「库会自行解析」的信号")
        assertEquals(6_000L, timed.begin)
        assertEquals(8_000L, timed.end)

        val inline = scanRawBgLines(inlineBgTtml).single().spans.single()
        assertEquals("(伴唱)", inline.text)
        assertNull(inline.roman)
        assertTrue(inline.syllables.isEmpty(), "无内层时轴 ⇒ 库会整条丢弃，正是兜底的场景")
        assertNull(inline.begin)
        assertNull(inline.end)
    }

    private fun serialize(rows: List<PluginLyricLine>): String = rows.joinToString("\n") { row ->
        listOf(
            row.begin, row.end, row.duration, row.isAlignedRight,
            role(row) ?: "-",
            row.text,
            row.words?.joinToString(",") { "${it.begin}-${it.end}=${it.text}" } ?: "-",
            row.translation ?: "-",
            row.roma ?: "-",
        ).joinToString("|")
    }

    /** 回归金样：既有样本的映射输出必须与 1.0.2 修复前逐字段一致（修复是纯增量）。 */
    @Test
    fun butterflyMappingMatchesPreFixBaseline() {
        val rows = TtmlMapper.map(butterfly(), duet = true, translation = true, background = true)
        val baseline = javaClass.getResourceAsStream("/butterfly.rows.txt")!!
            .readBytes().decodeToString().replace("\r\n", "\n").trimEnd('\n')

        assertEquals(baseline, serialize(rows))
    }
}
