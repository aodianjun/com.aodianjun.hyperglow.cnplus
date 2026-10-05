package com.example.hyperglow.aitranslation

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationProtocolTest {

    private val lines = listOf(
        LineToTranslate(0, "你说你来到这世界的那天"),
        LineToTranslate(2, "hello world"),
    )

    @Test
    fun systemPromptWithoutCustomIsPureProtocol() {
        val prompt = TranslationProtocol.buildSystemPrompt("")
        assertTrue(prompt.contains("仅输出一个原始 JSON object"))
        assertTrue(prompt.contains("禁止重新编号"))
        assertTrue(!prompt.contains("[用户自定义风格提示词]"))
    }

    @Test
    fun systemPromptWithCustomKeepsProtocolAboveIt() {
        val prompt = TranslationProtocol.buildSystemPrompt("信雅达，贴合旋律")
        assertTrue(prompt.contains("仅输出一个原始 JSON object"))
        val idxCustom = prompt.indexOf("信雅达，贴合旋律")
        val idxRule = prompt.indexOf("不得覆盖上面的核心协议")
        assertTrue(idxCustom > 0 && idxRule > idxCustom, "风格提示词须在协议之后，且带不得覆盖声明")
    }

    @Test
    fun userPayloadCarriesMetaAndLines() {
        val payload = TranslationProtocol.buildUserPayload(
            TrackMeta("蝴蝶", "洛天依Official"), "中文", lines,
        )
        val obj = JSONObject(payload)
        assertEquals("蝴蝶", obj.getString("title"))
        assertEquals("洛天依Official", obj.getString("artist"))
        assertEquals("中文", obj.getString("targetLanguage"))
        val arr = obj.getJSONArray("lines")
        assertEquals(2, arr.length())
        assertEquals(0, arr.getJSONObject(0).getInt("index"))
        assertEquals("你说你来到这世界的那天", arr.getJSONObject(0).getString("text"))
        assertEquals(2, arr.getJSONObject(1).getInt("index"))
    }

    @Test
    fun userPayloadOmitsBlankMeta() {
        val payload = TranslationProtocol.buildUserPayload(TrackMeta(null, "  "), "English", lines)
        val obj = JSONObject(payload)
        assertTrue(!obj.has("title"))
        assertTrue(!obj.has("artist"))
    }

    @Test
    fun parsesPlainIndexMap() {
        val map = TranslationProtocol.parseResponse(
            """{"0":"你来到这世界的那天","2":"你好世界"}""",
            setOf(0, 2),
        )
        assertEquals("你来到这世界的那天", map?.get(0))
        assertEquals("你好世界", map?.get(2))
    }

    @Test
    fun ignoresOutOfRangeAndInvalidIndexes() {
        val map = TranslationProtocol.parseResponse(
            """{"0":"a","9":"越界","x":"非数字"}""",
            setOf(0),
        )
        assertEquals(mapOf(0 to "a"), map)
    }

    @Test
    fun stripsMarkdownFences() {
        val map = TranslationProtocol.parseResponse(
            "```json\n{\"0\":\"译文\"}\n```",
            setOf(0),
        )
        assertEquals("译文", map?.get(0))
        assertEquals("{\"0\":\"x\"}", TranslationProtocol.stripCodeFences("```\n{\"0\":\"x\"}\n```"))
    }

    @Test
    fun parsesArrayForm() {
        val map = TranslationProtocol.parseResponse(
            """[{"index":0,"text":"译文A"},{"index":2,"text":"译文B"}]""",
            setOf(0, 2),
        )
        assertEquals("译文A", map?.get(0))
        assertEquals("译文B", map?.get(2))
    }

    @Test
    fun parsesWrappedArrayForm() {
        val map = TranslationProtocol.parseResponse(
            """{"译文":[{"index":0,"text":"译文A"}]}""",
            setOf(0),
        )
        assertEquals("译文A", map?.get(0))
    }

    @Test
    fun blankAndGarbageYieldNull() {
        assertNull(TranslationProtocol.parseResponse("", setOf(0)))
        assertNull(TranslationProtocol.parseResponse("抱歉，我无法翻译。", setOf(0)))
        assertNull(TranslationProtocol.parseResponse("{}", setOf(0)))
        assertNull(TranslationProtocol.parseResponse("""{"0":"  "}""", setOf(0)))
    }

    @Test
    fun firstOccurrenceWinsWhenMapAndArrayCoexist() {
        // collect 用 putIfAbsent：同一 index 在多处出现时保留先解析到的（map 先于数组字段）。
        val map = TranslationProtocol.parseResponse(
            """{"0":"来自map","译文":[{"index":0,"text":"来自数组"}]}""",
            setOf(0),
        )
        assertEquals("来自map", map?.get(0))
    }
}
