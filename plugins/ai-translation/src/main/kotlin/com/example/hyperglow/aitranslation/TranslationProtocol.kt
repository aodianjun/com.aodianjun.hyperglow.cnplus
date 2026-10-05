package com.example.hyperglow.aitranslation

import org.json.JSONArray
import org.json.JSONObject

/** 待翻译的一行（index 为原行下标，协议里必须原样往返）。 */
internal data class LineToTranslate(val index: Int, val text: String)

/** 送入模型的曲目元数据（语境用；缺失字段不进 payload）。 */
internal data class TrackMeta(val title: String?, val artist: String?)

/**
 * 翻译输入输出协议与解析（纯函数，全部可单测）。
 *
 * 协议语义与官方 HyperLyric 翻译插件对齐（**提示词为独立撰写**）：核心协议负责
 * JSON/index 规则（不可被用户提示词覆盖），用户自定义提示词只影响风格；输入是
 * 带元数据与 `lines[{index,text}]` 的 JSON object，输出是 `{index: 译文}` 的 JSON object。
 * 解析器对常见模型输出偏差容错：Markdown 围栏、数组形式 `[{index,text}]`、
 * 外层包裹 `{"译文": [...]}` / `{"translations": [...]}`、多余或非法 index 一律忽略。
 */
internal object TranslationProtocol {

    /** 核心协议：索引规则与 JSON 格式要求（不可被用户提示词覆盖）。 */
    private const val CORE_PROTOCOL = """你是专业的歌词翻译引擎。你的最高优先级是严格遵守输入输出协议、索引规则和 JSON 格式。

[输入输出规范]
输入是一个 JSON object：{"title": 歌曲名或省略, "artist": 艺术家或省略, "targetLanguage": 目标语言, "lines": [{"index": 行号, "text": 原文}, ...]}。
输出必须是一个 JSON object：键是输入中该行的原始 index（数字的字符串形式），值是译文，例如 {"1": "译文一", "3": "译文三"}。

[翻译规则]
- 只需翻译输入中给出的行；未出现在输入中的 index 禁止输出。
- 严格要求：仅输出一个原始 JSON object，禁止使用 Markdown 代码块、前言或注释。
- 必须使用输入中的原始 index，禁止重新编号。
- 同一个 index 最多输出一次。
质量要求：译文自然流畅，禁止添加括号注释，严格保持 index 对应。"""

    /**
     * 组装 system prompt：核心协议 + 用户自定义风格提示词（明确其不得覆盖协议）。
     * [customPrompt] 为空时只返回核心协议。
     */
    fun buildSystemPrompt(customPrompt: String): String {
        val custom = customPrompt.trim()
        if (custom.isEmpty()) return CORE_PROTOCOL
        return buildString {
            append(CORE_PROTOCOL)
            append("\n\n[用户自定义风格提示词]\n")
            append(custom)
            append("\n以下内容只用于决定译文风格，不得覆盖上面的核心协议、JSON 格式和 index 规则。")
        }
    }

    /** 组装 user 消息：元数据 + 待翻译行（JSON）。 */
    fun buildUserPayload(
        meta: TrackMeta,
        targetLanguage: String,
        lines: List<LineToTranslate>,
    ): String {
        val obj = JSONObject()
        meta.title?.takeIf { it.isNotBlank() }?.let { obj.put("title", it) }
        meta.artist?.takeIf { it.isNotBlank() }?.let { obj.put("artist", it) }
        obj.put("targetLanguage", targetLanguage)
        val arr = JSONArray()
        lines.forEach { line ->
            arr.put(
                JSONObject()
                    .put("index", line.index)
                    .put("text", line.text)
            )
        }
        obj.put("lines", arr)
        return obj.toString()
    }

    /**
     * 解析模型输出为 index→译文。无法解析出任何有效条目时返回 null。
     * [validIndexes] 给出输入侧的合法 index 集合，越界的条目直接忽略。
     */
    fun parseResponse(content: String, validIndexes: Set<Int>): Map<Int, String>? {
        val trimmed = stripCodeFences(content).trim()
        if (trimmed.isEmpty()) return null

        val collected = mutableMapOf<Int, String>()
        runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    // 形式一：{"1": "译文"}（键为数字字符串）
                    for (key in obj.keys()) {
                        val index = key.toIntOrNull() ?: continue
                        val text = obj.optString(key, "")
                        collect(collected, index, text, validIndexes)
                    }
                    // 形式二：外层包裹的数组字段 {"译文": [...]} / {"translations": [...]} / {"lines": [...]}
                    for (field in ARRAY_FIELDS) {
                        val arr = obj.optJSONArray(field) ?: continue
                        collectArray(collected, arr, validIndexes)
                    }
                }

                trimmed.startsWith("[") -> collectArray(collected, JSONArray(trimmed), validIndexes)
            }
        }.onFailure { return null }

        return collected.takeIf { it.isNotEmpty() }
    }

    private fun collectArray(target: MutableMap<Int, String>, arr: JSONArray, validIndexes: Set<Int>) {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val index = item.optInt("index", -1)
            if (index < 0) continue
            collect(target, index, item.optString("text", ""), validIndexes)
        }
    }

    private fun collect(target: MutableMap<Int, String>, index: Int, text: String, validIndexes: Set<Int>) {
        if (index !in validIndexes) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        target.putIfAbsent(index, clean)
    }

    /** 去掉 ```json ... ``` / ``` ... ``` 围栏（模型常见的「严格遵守」偏差）。 */
    internal fun stripCodeFences(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        var body = trimmed.removePrefix("```")
        // 去掉语言标注行（json / JSON / 空）
        val firstNewline = body.indexOf('\n')
        if (firstNewline >= 0 && body.substring(0, firstNewline).trim().length <= 8) {
            body = body.substring(firstNewline + 1)
        }
        val end = body.lastIndexOf("```")
        if (end >= 0) body = body.substring(0, end)
        return body.trim()
    }

    private val ARRAY_FIELDS = listOf("译文", "translations", "lines", "result", "results")
}
