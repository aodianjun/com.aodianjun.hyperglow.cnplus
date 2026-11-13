package com.example.hyperglow.aitranslationwords

import org.json.JSONArray
import org.json.JSONObject

/** 待翻译的一行（index 为原行下标，协议里必须原样往返）；tokens 仅逐字时间行携带。 */
internal data class LineToTranslate(
    val index: Int,
    val text: String,
    val tokens: List<String>? = null,
)

/** 送入模型的曲目元数据（语境用；缺失字段不进 payload）。 */
internal data class TrackMeta(val title: String?, val artist: String?)

/**
 * 一行的解析结果：[translation] 是权威整行译文（片段通过校验时由片段拼合而来），
 * [fragments] 是通过校验的逐字片段，未通过/未提供时为 null。
 */
internal data class ParsedLine(val translation: String, val fragments: List<String>?)

/**
 * 翻译 + 逐字对齐的输入输出协议与解析（纯函数，全部可单测）。
 *
 * 协议语义与官方 HyperLyric 翻译插件对齐（**提示词为独立撰写**），并在此基础上扩展
 * 逐字片段：核心协议负责 JSON/index 与 segments 规则（不可被用户提示词覆盖），用户
 * 自定义提示词只影响风格；输入是带元数据与 `lines[{index,text,tokens?}]` 的 JSON object，
 * 输出是 `{index: {translation, segments?}}` 的 JSON object。解析器对常见模型输出偏差
 * 容错：Markdown 围栏、数组形式 `[{index,text}]`、外层包裹 `{"译文": [...]}` /
 * `{"translations": [...]}`、多余或非法 index 一律忽略。
 *
 * 片段校验是**长度精确匹配**：模型给的 segments 数量与输入 tokens 数量不一致、
 * 或含非字符串元素时，整组片段作废、只保留整行译文——宁可没有逐字效果，也不能把
 * 片段错位映射到词时间轴上。
 */
internal object WordAlignmentProtocol {

    /** 核心协议：索引规则、JSON 格式与 segments 规则（不可被用户提示词覆盖）。 */
    private const val CORE_PROTOCOL = """你是专业的歌词翻译引擎，同时负责把译文按原歌词的逐字时间对齐到原词。

[输入输出规范]
输入是一个 JSON object：{"title": 歌曲名或省略, "artist": 艺术家或省略, "targetLanguage": 目标语言, "lines": [{"index": 行号, "text": 原文, "tokens": ["原词1", "原词2", ...]}, ...]}。
tokens 为可选字段：出现时表示这一行带逐字时间（原文按 tokens 顺序逐个演唱），该行必须同时输出 segments。
输出必须是一个 JSON object：键是输入中该行的原始 index（数字的字符串形式），值是一个 object，形如 {"translation": "整行译文", "segments": ["片段1", "片段2", ...]}。

[逐字对齐规则]
- 只有带 tokens 的行需要输出 segments；不带 tokens 的行省略 segments（或给 null）。
- segments 的长度必须恰好等于该行 tokens 的数量，第 i 个片段是「唱第 i 个 token 时应当显示的译文片段」。
- 允许空串（""）：该 token 没有对应的译文片段时（例如纯语气词、标点，或译文已在相邻片段中表达）。
- 片段必须按演唱顺序排列；直接拼接所有片段（不加任何分隔符）必须逐字符等于 translation。西文词之间的空格写在片段文本内部（片段以空格开头或结尾），中文不加空格。
- 译文要贴合旋律与断句，不要为了凑片段数把词硬拆成无意义的碎片。

[翻译规则]
- 只需翻译输入中给出的行；未出现在输入中的 index 禁止输出。
- 严格要求：仅输出一个原始 JSON object，禁止使用 Markdown 代码块、前言或注释。
- 必须使用输入中的原始 index，禁止重新编号。同一个 index 最多输出一次。
质量要求：译文自然流畅，禁止添加括号注释。"""

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

    /** 组装 user 消息：元数据 + 待翻译行（JSON）；tokens 只出现在逐字时间行上。 */
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
            val item = JSONObject()
                .put("index", line.index)
                .put("text", line.text)
            line.tokens?.takeIf { it.isNotEmpty() }?.let { tokens ->
                item.put("tokens", JSONArray(tokens))
            }
            arr.put(item)
        }
        obj.put("lines", arr)
        return obj.toString()
    }

    /**
     * 解析模型输出为 index→[ParsedLine]。无法解析出任何有效条目时返回 null。
     * [validIndexes] 给出输入侧的合法 index 集合，越界的条目直接忽略；
     * [expectedTokenCounts] 给出逐字时间行的 tokens 数量（片段长度必须精确等于它）。
     */
    fun parseResponse(
        content: String,
        validIndexes: Set<Int>,
        expectedTokenCounts: Map<Int, Int>,
    ): Map<Int, ParsedLine>? {
        val trimmed = stripCodeFences(content).trim()
        if (trimmed.isEmpty()) return null

        val collected = LinkedHashMap<Int, ParsedLine>()
        runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    // 形式一：{"1": "译文"} / {"1": {"translation": ..., "segments": [...]}}
                    collectIndexMap(collected, obj, validIndexes, expectedTokenCounts)
                    // 形式二：外层包裹 {"译文": [...]} / {"translations": [...]} / {"lines": [...]}
                    for (field in WRAPPER_FIELDS) {
                        obj.optJSONArray(field)?.let {
                            collectArray(collected, it, validIndexes, expectedTokenCounts)
                        }
                        obj.optJSONObject(field)?.let {
                            collectIndexMap(collected, it, validIndexes, expectedTokenCounts)
                        }
                    }
                }

                trimmed.startsWith("[") ->
                    collectArray(collected, JSONArray(trimmed), validIndexes, expectedTokenCounts)
            }
        }.onFailure { return null }

        return collected.takeIf { it.isNotEmpty() }
    }

    private fun collectIndexMap(
        target: MutableMap<Int, ParsedLine>,
        obj: JSONObject,
        validIndexes: Set<Int>,
        expectedTokenCounts: Map<Int, Int>,
    ) {
        for (key in obj.keys()) {
            val index = key.toIntOrNull() ?: continue
            collectValue(target, index, obj.opt(key), validIndexes, expectedTokenCounts)
        }
    }

    private fun collectArray(
        target: MutableMap<Int, ParsedLine>,
        arr: JSONArray,
        validIndexes: Set<Int>,
        expectedTokenCounts: Map<Int, Int>,
    ) {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val index = item.optInt("index", -1)
            if (index < 0) continue
            collectValue(target, index, item, validIndexes, expectedTokenCounts)
        }
    }

    private fun collectValue(
        target: MutableMap<Int, ParsedLine>,
        index: Int,
        value: Any?,
        validIndexes: Set<Int>,
        expectedTokenCounts: Map<Int, Int>,
    ) {
        if (index !in validIndexes) return
        if (target.containsKey(index)) return
        val parsed = when (value) {
            // 形式一（简写）：值直接是整行译文，没有逐字片段。
            is String -> value.trim().takeIf { it.isNotEmpty() }?.let { ParsedLine(it, null) }
            is JSONObject -> parseLineObject(value, expectedTokenCounts[index])
            else -> null
        } ?: return
        target[index] = parsed
    }

    /**
     * 解析单行 object。片段通过校验时以 `fragments.joinToString("")` 为权威译文
     * （模型另给的 translation 可能与片段拼合有细微出入，不采信、不比对）；
     * 校验失败只丢片段，保留整行译文。
     */
    private fun parseLineObject(obj: JSONObject, expectedTokenCount: Int?): ParsedLine? {
        val translation = firstNonBlank(
            obj.optString("translation", ""),
            // 数组形式里模型常沿用兄弟插件的 "text" 键。
            obj.optString("text", ""),
        )
        val segments = obj.opt("segments")
        val fragments = (segments as? JSONArray)?.let { extractFragments(it, expectedTokenCount) }
        if (fragments == null) {
            return translation?.let { ParsedLine(it, null) }
        }
        val normalized = normalizeFragmentEdges(fragments)
        val joined = normalized.joinToString("").trim()
        if (joined.isEmpty()) return null
        return ParsedLine(joined, normalized)
    }

    /** 片段校验：长度必须等于该行的 tokens 数量，且每个元素都是 JSON 字符串。 */
    private fun extractFragments(segments: JSONArray, expectedTokenCount: Int?): List<String>? {
        val expected = expectedTokenCount ?: return null
        if (segments.length() != expected) return null
        val result = ArrayList<String>(expected)
        for (i in 0 until segments.length()) {
            val element = segments.opt(i)
            if (element !is String) return null
            result.add(element)
        }
        return result
    }

    private fun firstNonBlank(vararg candidates: String): String? =
        candidates.firstOrNull { it.isNotBlank() }?.trim()

    /**
     * 把拼合文本两端被 trim 掉的空白从首/末个非空片段里移除，使
     * `normalizeFragmentEdges(f).joinToString("") == f.joinToString("").trim()`。
     * 逐字渲染按片段显示，若把首尾空白留在片段里，辅助文字行会出现无意义的缩进。
     */
    internal fun normalizeFragmentEdges(fragments: List<String>): List<String> {
        val joined = fragments.joinToString("")
        val trimmed = joined.trim()
        if (trimmed == joined) return fragments

        var leading = joined.length - joined.trimStart().length
        var trailing = joined.length - joined.trimEnd().length
        val result = fragments.toMutableList()

        var i = 0
        while (leading > 0 && i < result.size) {
            val fragment = result[i]
            if (fragment.isNotEmpty()) {
                val take = minOf(leading, fragment.length)
                result[i] = fragment.substring(take)
                leading -= take
            }
            i++
        }

        var j = result.size - 1
        while (trailing > 0 && j >= 0) {
            val fragment = result[j]
            if (fragment.isNotEmpty()) {
                val take = minOf(trailing, fragment.length)
                result[j] = fragment.substring(0, fragment.length - take)
                trailing -= take
            }
            j--
        }
        return result
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

    private val WRAPPER_FIELDS = listOf("译文", "translations", "lines", "result", "results")
}
