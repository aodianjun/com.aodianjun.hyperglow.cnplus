package com.example.hyperglow.aitranslationwords

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordAlignmentProtocolTest {

    private val lines = listOf(
        LineToTranslate(0, "你说你来到这世界的那天"),
        LineToTranslate(2, "hello world", listOf("hello", "world")),
    )

    @Test
    fun systemPromptWithoutCustomIsPureProtocol() {
        val prompt = WordAlignmentProtocol.buildSystemPrompt("")
        assertTrue(prompt.contains("仅输出一个原始 JSON object"))
        assertTrue(prompt.contains("禁止重新编号"))
        assertTrue(prompt.contains("segments 的长度必须恰好等于该行 tokens 的数量"))
        assertTrue(!prompt.contains("[用户自定义风格提示词]"))
    }

    @Test
    fun systemPromptWithCustomKeepsProtocolAboveIt() {
        val prompt = WordAlignmentProtocol.buildSystemPrompt("信雅达，贴合旋律")
        assertTrue(prompt.contains("仅输出一个原始 JSON object"))
        val idxCustom = prompt.indexOf("信雅达，贴合旋律")
        val idxRule = prompt.indexOf("不得覆盖上面的核心协议")
        assertTrue(idxCustom > 0 && idxRule > idxCustom, "风格提示词须在协议之后，且带不得覆盖声明")
    }

    @Test
    fun userPayloadCarriesTokensOnlyForWordTimedLines() {
        val payload = WordAlignmentProtocol.buildUserPayload(
            TrackMeta("蝴蝶", "洛天依Official"), "中文", lines,
        )
        val obj = JSONObject(payload)
        assertEquals("蝴蝶", obj.getString("title"))
        assertEquals("中文", obj.getString("targetLanguage"))
        val arr = obj.getJSONArray("lines")
        assertEquals(2, arr.length())
        assertEquals(0, arr.getJSONObject(0).getInt("index"))
        assertTrue(!arr.getJSONObject(0).has("tokens"))
        val timed = arr.getJSONObject(1)
        assertEquals(listOf("hello", "world"), timed.getJSONArray("tokens").let { t ->
            (0 until t.length()).map { t.getString(it) }
        })
    }

    @Test
    fun userPayloadOmitsBlankMeta() {
        val payload = WordAlignmentProtocol.buildUserPayload(TrackMeta(null, "  "), "English", lines)
        val obj = JSONObject(payload)
        assertTrue(!obj.has("title"))
        assertTrue(!obj.has("artist"))
    }

    @Test
    fun parsesPlainIndexMap() {
        val map = WordAlignmentProtocol.parseResponse(
            """{"0":"你来到这世界的那天","2":"你好世界"}""",
            setOf(0, 2),
            emptyMap(),
        )
        assertEquals(ParsedLine("你来到这世界的那天", null), map?.get(0))
        assertEquals(ParsedLine("你好世界", null), map?.get(2))
    }

    @Test
    fun parsesObjectValueWithSegments() {
        val map = WordAlignmentProtocol.parseResponse(
            """{"2":{"translation":"你好 世界","segments":["你好 ","世界"]}}""",
            setOf(2),
            mapOf(2 to 2),
        )
        assertEquals(ParsedLine("你好 世界", listOf("你好 ", "世界")), map?.get(2))
    }

    @Test
    fun segmentsAreAuthoritativeOverTranslationField() {
        // 模型给的 translation 与片段拼合有细微出入时以片段为准（片段才是逐字渲染的输入）。
        val map = WordAlignmentProtocol.parseResponse(
            """{"2":{"translation":"你好世界！","segments":["你好","世界"]}}""",
            setOf(2),
            mapOf(2 to 2),
        )
        assertEquals(ParsedLine("你好世界", listOf("你好", "世界")), map?.get(2))
    }

    @Test
    fun segmentCountMismatchKeepsTranslationOnly() {
        val map = WordAlignmentProtocol.parseResponse(
            """{"2":{"translation":"你好世界","segments":["你好"]}}""",
            setOf(2),
            mapOf(2 to 2),
        )
        assertEquals(ParsedLine("你好世界", null), map?.get(2))
    }

    @Test
    fun nonStringSegmentElementDropsFragments() {
        val map = WordAlignmentProtocol.parseResponse(
            """{"2":{"translation":"你好世界","segments":["你好",5]}}""",
            setOf(2),
            mapOf(2 to 2),
        )
        assertEquals(ParsedLine("你好世界", null), map?.get(2))
    }

    @Test
    fun segmentsWithoutExpectedTokenCountAreDropped() {
        // 该行没有词时间轴（输入没给 tokens）：片段没有可映射的窗口，只能丢弃。
        val map = WordAlignmentProtocol.parseResponse(
            """{"0":{"translation":"你来到","segments":["你","来到"]}}""",
            setOf(0),
            emptyMap(),
        )
        assertEquals(ParsedLine("你来到", null), map?.get(0))
    }

    @Test
    fun blankTranslationAndNoUsableFragmentsIsIgnored() {
        assertNull(
            WordAlignmentProtocol.parseResponse(
                """{"2":{"translation":"","segments":[]}}""",
                setOf(2),
                mapOf(2 to 2),
            )
        )
        assertNull(
            WordAlignmentProtocol.parseResponse(
                """{"2":{"translation":"","segments":[" ",""]}}""",
                setOf(2),
                mapOf(2 to 2),
            )
        )
    }

    @Test
    fun ignoresOutOfRangeAndInvalidIndexes() {
        val map = WordAlignmentProtocol.parseResponse(
            """{"0":"a","9":"越界","x":"非数字"}""",
            setOf(0),
            emptyMap(),
        )
        assertEquals(mapOf(0 to ParsedLine("a", null)), map)
    }

    @Test
    fun stripsMarkdownFences() {
        val map = WordAlignmentProtocol.parseResponse(
            "```json\n{\"0\":\"译文\"}\n```",
            setOf(0),
            emptyMap(),
        )
        assertEquals("译文", map?.get(0)?.translation)
        assertEquals("{\"0\":\"x\"}", WordAlignmentProtocol.stripCodeFences("```\n{\"0\":\"x\"}\n```"))
    }

    @Test
    fun parsesArrayForm() {
        val map = WordAlignmentProtocol.parseResponse(
            """[{"index":0,"text":"译文A"},{"index":2,"text":"译文B"}]""",
            setOf(0, 2),
            emptyMap(),
        )
        assertEquals("译文A", map?.get(0)?.translation)
        assertEquals("译文B", map?.get(2)?.translation)
    }

    @Test
    fun parsesArrayFormWithSegments() {
        val map = WordAlignmentProtocol.parseResponse(
            """[{"index":2,"translation":"你好世界","segments":["你好","世界"]}]""",
            setOf(2),
            mapOf(2 to 2),
        )
        assertEquals(ParsedLine("你好世界", listOf("你好", "世界")), map?.get(2))
    }

    @Test
    fun parsesWrappedArrayAndObjectForms() {
        val wrappedArray = WordAlignmentProtocol.parseResponse(
            """{"译文":[{"index":0,"text":"译文A"}]}""",
            setOf(0),
            emptyMap(),
        )
        assertEquals("译文A", wrappedArray?.get(0)?.translation)

        val wrappedObject = WordAlignmentProtocol.parseResponse(
            """{"result":{"0":"译文A"}}""",
            setOf(0),
            emptyMap(),
        )
        assertEquals("译文A", wrappedObject?.get(0)?.translation)
    }

    @Test
    fun blankAndGarbageYieldNull() {
        assertNull(WordAlignmentProtocol.parseResponse("", setOf(0), emptyMap()))
        assertNull(WordAlignmentProtocol.parseResponse("抱歉，我无法翻译。", setOf(0), emptyMap()))
        assertNull(WordAlignmentProtocol.parseResponse("{}", setOf(0), emptyMap()))
        assertNull(WordAlignmentProtocol.parseResponse("""{"0":"  "}""", setOf(0), emptyMap()))
    }

    @Test
    fun firstOccurrenceWinsWhenMapAndArrayCoexist() {
        // collect 用 putIfAbsent 语义：同一 index 在多处出现时保留先解析到的（map 先于数组字段）。
        val map = WordAlignmentProtocol.parseResponse(
            """{"0":"来自map","译文":[{"index":0,"text":"来自数组"}]}""",
            setOf(0),
            emptyMap(),
        )
        assertEquals("来自map", map?.get(0)?.translation)
    }

    @Test
    fun normalizeFragmentEdgesMovesOuterWhitespace() {
        // 无外层空白：原样返回
        assertEquals(
            listOf("你好 ", " 世界"),
            WordAlignmentProtocol.normalizeFragmentEdges(listOf("你好 ", " 世界")),
        )
        // 首片段前导空白：从首个非空片段里去掉
        assertEquals(
            listOf("你好", " 世界"),
            WordAlignmentProtocol.normalizeFragmentEdges(listOf("  你好", " 世界")),
        )
        // 末片段尾随空白：从末个非空片段里去掉
        assertEquals(
            listOf("你好", "世界"),
            WordAlignmentProtocol.normalizeFragmentEdges(listOf("你好", "世界  ")),
        )
        // 空片段不消耗待去除的字符
        assertEquals(
            listOf("", "你好", "世界"),
            WordAlignmentProtocol.normalizeFragmentEdges(listOf("", " 你好", "世界  ")),
        )
        // 全空白：拼合为空白，两侧都吃掉
        assertEquals(
            listOf("", ""),
            WordAlignmentProtocol.normalizeFragmentEdges(listOf("  ", " ")),
        )
    }
}
