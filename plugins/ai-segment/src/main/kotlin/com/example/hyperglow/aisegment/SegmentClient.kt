package com.example.hyperglow.aisegment

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容 chat/completions 客户端(切词)。
 *
 * 兼容性取舍:不带 `response_format`(很多第三方兼容端点不支持,输出协议由
 * `SegmentProtocol` 的行格式约束);请求失败(非 2xx / 超时 / 解析不出内容)一律
 * 返回 null,由处理器做失败节流。base URL 用户配置,key 只随请求头发往用户自己的服务。
 */
internal open class SegmentClient(private val baseUrl: String) {

    open fun segment(
        config: SegmentConfig,
        meta: TrackMeta,
        rows: List<RowToSegment>,
        budgetMs: Int = Http.DEFAULT_BUDGET_MS,
    ): SegmentationParsed? {
        val body = SegmentProtocol.buildRequestBody(config, meta, rows)
        val response = Http.postJson(
            url = chatCompletionsUrl(baseUrl),
            jsonBody = body,
            headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
            budgetMs = budgetMs,
        ) ?: return null
        val content = SegmentProtocol.parseChoiceContent(response) ?: return null
        return SegmentProtocol.parseResponse(content, rows.map { it.number }.toSet())
    }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"

        /** 规范化用户填写的 API 地址:去尾斜杠;空/非法回退默认。 */
        fun normalizeBaseUrl(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_BASE_URL
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return DEFAULT_BASE_URL
            return trimmed
        }

        fun chatCompletionsUrl(baseUrl: String): String = "$baseUrl/chat/completions"
    }
}

/** 一次切词所需的配置快照(读取后不可变,便于纯函数决策与测试)。 */
internal data class SegmentConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val maxChars: Int,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
)

/** 曲目身份(缓存键与展示用)。 */
internal data class TrackMeta(
    val title: String?,
    val artist: String?,
)
