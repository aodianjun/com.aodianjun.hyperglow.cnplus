package com.example.hyperglow.amllttml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
