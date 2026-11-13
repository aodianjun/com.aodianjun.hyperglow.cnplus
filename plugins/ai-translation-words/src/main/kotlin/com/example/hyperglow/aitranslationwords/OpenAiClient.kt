package com.example.hyperglow.aitranslationwords

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容 chat/completions 客户端。
 *
 * 兼容性取舍：**不带 `response_format`**（很多第三方兼容端点不支持它，且 prompt 已用
 * 严格 JSON 协议约束输出）；请求失败（非 2xx / 超时 / 解析不出内容）一律返回 null，
 * 由调用方做失败节流。base URL 由用户配置（默认官方示例端点），key 只随请求头发往
 * 用户自己配置的服务。
 */
internal class OpenAiClient(private val baseUrl: String) {

    fun translate(
        config: TranslationWordsConfig,
        meta: TrackMeta,
        lines: List<LineToTranslate>,
        expectedTokenCounts: Map<Int, Int>,
        budgetMs: Int = Http.DEFAULT_BUDGET_MS,
    ): Map<Int, ParsedLine>? {
        val body = buildRequestBody(config, meta, lines)
        val response = Http.postJson(
            url = chatCompletionsUrl(baseUrl),
            jsonBody = body,
            headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
            budgetMs = budgetMs,
        ) ?: return null
        val content = parseChoiceContent(response) ?: return null
        return WordAlignmentProtocol.parseResponse(
            content = content,
            validIndexes = lines.map { it.index }.toSet(),
            expectedTokenCounts = expectedTokenCounts,
        )
    }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"

        /** 规范化用户填写的 API 地址：去尾斜杠；空/非法回退默认。 */
        fun normalizeBaseUrl(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_BASE_URL
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return DEFAULT_BASE_URL
            return trimmed
        }

        fun chatCompletionsUrl(baseUrl: String): String = "$baseUrl/chat/completions"

        /** 请求体：model/messages/temperature/top_p；max_tokens=0 表示不传（不限）。 */
        internal fun buildRequestBody(
            config: TranslationWordsConfig,
            meta: TrackMeta,
            lines: List<LineToTranslate>,
        ): String {
            val body = JSONObject()
            body.put("model", config.model)
            val messages = JSONArray()
            messages.put(
                JSONObject().put("role", "system")
                    .put("content", WordAlignmentProtocol.buildSystemPrompt(config.prompt))
            )
            messages.put(
                JSONObject().put("role", "user")
                    .put("content", WordAlignmentProtocol.buildUserPayload(meta, config.targetLanguage, lines))
            )
            body.put("messages", messages)
            body.put("temperature", config.temperature.toDouble())
            body.put("top_p", config.topP.toDouble())
            if (config.maxTokens > 0) body.put("max_tokens", config.maxTokens)
            return body.toString()
        }

        /** 从 chat/completions 响应提取 `choices[0].message.content`。 */
        internal fun parseChoiceContent(response: String): String? = runCatching {
            val choices = JSONObject(response).optJSONArray("choices") ?: return@runCatching null
            val first = choices.optJSONObject(0) ?: return@runCatching null
            val message = first.optJSONObject("message") ?: return@runCatching null
            message.optString("content").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
