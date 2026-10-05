package com.example.hyperglow.aitranslation

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiClientTest {

    private val config = TranslationConfig(
        targetLanguage = "中文",
        skipLanguages = emptySet(),
        skipExisting = false,
        forceOverride = false,
        apiKey = "sk-test",
        model = "mimo-v2.5",
        baseUrl = "https://api.xiaomimimo.com/v1",
        prompt = "信雅达",
        temperature = 1.0f,
        topP = 1.0f,
        maxTokens = 0,
    )

    private val lines = listOf(LineToTranslate(0, "hello"))

    @Test
    fun normalizeBaseUrlHandlesUserInput() {
        assertEquals("https://api.xiaomimimo.com/v1", OpenAiClient.normalizeBaseUrl(null))
        assertEquals("https://api.xiaomimimo.com/v1", OpenAiClient.normalizeBaseUrl("  "))
        assertEquals("https://api.xiaomimimo.com/v1", OpenAiClient.normalizeBaseUrl("https://api.xiaomimimo.com/v1/"))
        assertEquals("https://api.xiaomimimo.com/v1", OpenAiClient.normalizeBaseUrl("https://api.xiaomimimo.com/v1"))
        // 无 scheme 回退默认
        assertEquals("https://api.xiaomimimo.com/v1", OpenAiClient.normalizeBaseUrl("api.xiaomimimo.com"))
        assertEquals("http://192.168.1.10:8080/v1", OpenAiClient.normalizeBaseUrl("http://192.168.1.10:8080/v1/"))
    }

    @Test
    fun chatCompletionsUrlJoinsPath() {
        assertEquals(
            "https://api.xiaomimimo.com/v1/chat/completions",
            OpenAiClient.chatCompletionsUrl("https://api.xiaomimimo.com/v1"),
        )
    }

    @Test
    fun requestBodyCarriesModelMessagesAndSampling() {
        val body = JSONObject(OpenAiClient.buildRequestBody(config, TrackMeta("蝴蝶", null), lines))
        assertEquals("mimo-v2.5", body.getString("model"))
        assertEquals(1.0, body.getDouble("temperature"), 1e-6)
        assertEquals(1.0, body.getDouble("top_p"), 1e-6)
        // max_tokens=0 → 不传（表示不限制）
        assertFalse(body.has("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("仅输出一个原始 JSON object"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("信雅达"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("\"lines\""))
    }

    @Test
    fun requestBodyIncludesMaxTokensWhenPositive() {
        val body = JSONObject(
            OpenAiClient.buildRequestBody(config.copy(maxTokens = 4096), TrackMeta(null, null), lines)
        )
        assertEquals(4096, body.getInt("max_tokens"))
    }

    @Test
    fun parsesChoiceContent() {
        val response = """{"choices":[{"message":{"role":"assistant","content":"{\"0\":\"你好\"}"}}]}"""
        assertEquals("{\"0\":\"你好\"}", OpenAiClient.parseChoiceContent(response))
    }

    @Test
    fun choiceContentGarbageYieldsNull() {
        assertNull(OpenAiClient.parseChoiceContent("not json"))
        assertNull(OpenAiClient.parseChoiceContent("""{"error":{"message":"bad key"}}"""))
        assertNull(OpenAiClient.parseChoiceContent("""{"choices":[]}"""))
        assertNull(OpenAiClient.parseChoiceContent("""{"choices":[{"message":{"content":"  "}}]}"""))
    }
}
