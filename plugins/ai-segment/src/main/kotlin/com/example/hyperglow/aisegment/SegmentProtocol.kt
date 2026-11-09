package com.example.hyperglow.aisegment

import org.json.JSONArray
import org.json.JSONObject

/** 一条待切分的歌词行(编号用于让 AI 输出能对回源行)。 */
internal data class RowToSegment(val number: Int, val text: String, val maxChars: Int)

/** 切词结果:源行编号 → 子行文本列表(按原行文字顺序)。 */
internal data class SegmentationParsed(val bySource: Map<Int, List<String>>)

/**
 * 切词协议:提示词、用户载荷与响应解析(`n|子行文本` 行协议)。
 *
 * 选行协议而不是 JSON:LLM 对「逐行前缀」格式的遵循度更高,解析也允许混入
 * 少量不匹配行(跳过即可,不至于整份作废);校验交给 [validate]——子行按序拼接
 * (忽略空白)必须与源行(忽略空白)一致,不一致的源行整行回落不拆。
 */
internal object SegmentProtocol {

    /** 校验结果:children = 切分后的子行(已验证);passthrough=true 表示该行不拆。 */
    data class Validated(val number: Int, val children: List<String>, val passthrough: Boolean)

    fun buildSystemPrompt(): String = listOf(
        "你是歌词切词助手。输入为带编号的歌词行,格式:`[n] 文本`。",
        "任务:把每行按语义和气口重新切分成 1..k 个子行,适合逐行卡拉OK显示。",
        "规则:",
        "1. 绝对不增删改任何文字(空白可以挪动);",
        "2. 每个源行可以不拆(输出 `n|原文`)或拆成多个子行,子行按原顺序拼接必须等于原行(忽略空白);",
        "3. 子行目标长度不超过给定的每行最大字数,尽量均衡;标点留在行尾;",
        "4. 输出每行格式:`n|子行文本`(n 为原行编号,半角竖线分隔);",
        "5. 只输出结果行,不要解释、序号、代码块或空行。",
    ).joinToString("\n")

    fun buildUserPayload(rows: List<RowToSegment>, maxChars: Int): String = buildString {
        append("每行最大字数:").append(maxChars).append("。\n")
        rows.forEach { row ->
            append('[').append(row.number).append("] ").append(row.text).append('\n')
        }
    }.trimEnd()

    /** 请求体:model/messages/temperature;max_tokens=0 表示不传(不限)。 */
    fun buildRequestBody(
        config: SegmentConfig,
        meta: TrackMeta,
        rows: List<RowToSegment>,
    ): String {
        val body = JSONObject()
        body.put("model", config.model)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", buildSystemPrompt()))
        messages.put(JSONObject().put("role", "user").put("content", buildUserPayload(rows, config.maxChars)))
        body.put("messages", messages)
        body.put("temperature", config.temperature.toDouble())
        body.put("top_p", config.topP.toDouble())
        if (config.maxTokens > 0) body.put("max_tokens", config.maxTokens)
        return body.toString()
    }

    /** 从 chat/completions 响应提取 `choices[0].message.content`。 */
    fun parseChoiceContent(response: String): String? = runCatching {
        val choices = JSONObject(response).optJSONArray("choices") ?: return@runCatching null
        val first = choices.optJSONObject(0) ?: return@runCatching null
        val message = first.optJSONObject("message") ?: return@runCatching null
        message.optString("content").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 解析 AI 输出:`n|子行文本` 行协议。
     * 只接受 [allowed] 里的编号;混入的说明行/空行/代码块围栏一律跳过。
     */
    fun parseResponse(content: String, allowed: Set<Int>): SegmentationParsed? {
        val bySource = LinkedHashMap<Int, MutableList<String>>()
        val fenceStart = Regex("^```[^`]*$")
        val fenced = content.replace(fenceStart, "").replace("```", "").trim()
        for (rawLine in fenced.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = Regex("^\\((\\d{1,3})\\)\\s*\\|(.*)$").find(line)
                ?: Regex("^(\\d{1,3})\\s*\\|(.*)$").find(line)
                ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (number !in allowed) continue
            val text = match.groupValues[2].trim()
            if (text.isEmpty()) continue
            bySource.getOrPut(number) { mutableListOf() }.add(text)
        }
        if (bySource.isEmpty()) return null
        return SegmentationParsed(bySource)
    }

    /**
     * 校验单个源行:子行按序拼接(忽略空白)必须等于源行(忽略空白)。
     * 一致 → 返回切分结果;不一致/没有子行 → 整行回落不拆(passthrough)。
     */
    fun validate(number: Int, sourceText: String, children: List<String>): Validated {
        val cleaned = children.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return Validated(number, listOf(sourceText), true)
        val joined = cleaned.joinToString("") { it.filter { ch -> !ch.isWhitespace() } }
        val original = sourceText.filter { ch -> !ch.isWhitespace() }
        return if (joined == original) {
            Validated(number, cleaned, false)
        } else {
            Validated(number, listOf(sourceText), true)
        }
    }
}
