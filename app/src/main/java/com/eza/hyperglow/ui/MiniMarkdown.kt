package com.eza.hyperglow.ui

/**
 * 轻量行级 Markdown 解析,用于内置帮助(FAQ)与更新日志(release notes)的只读渲染。
 * 只覆盖这两类文本实际使用的结构:井号标题、无序/有序列表、围栏代码块、行内 **加粗**
 * 与 `等宽`;表格、图片、嵌套列表等未支持语法按普通段落降级输出,不抛错。
 */
internal enum class MarkdownLineKind { HEADING, BULLET, ORDERED, CODE, PARAGRAPH }

internal data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false
)

internal data class MarkdownLine(
    val kind: MarkdownLineKind,
    /** HEADING 的层级(1-3,更深的标题折叠到 3);其他 kind 恒为 0。 */
    val level: Int = 0,
    /** ORDERED 的序号前缀(如 "1.");其他 kind 恒为 null。 */
    val orderedPrefix: String? = null,
    val spans: List<MarkdownSpan> = emptyList()
)

private val ORDERED_PREFIX = Regex("^(\\d{1,3})[.)]\\s+")

internal fun parseMarkdownLines(raw: String): List<MarkdownLine> {
    if (raw.isBlank()) return emptyList()
    val lines = mutableListOf<MarkdownLine>()
    var inFence = false
    for (rawLine in raw.lines()) {
        if (inFence) {
            if (rawLine.trimStart().startsWith("```")) {
                inFence = false
            } else {
                lines += MarkdownLine(
                    MarkdownLineKind.CODE,
                    spans = listOf(MarkdownSpan(rawLine, code = true))
                )
            }
            continue
        }
        val trimmed = rawLine.trim()
        if (trimmed.startsWith("```")) {
            inFence = true
            continue
        }
        if (trimmed.isEmpty()) continue
        val headingPrefix = headingPrefixLength(trimmed)
        if (headingPrefix != null) {
            val text = trimmed.substring(headingPrefix).trim()
            if (text.isNotEmpty()) {
                lines += MarkdownLine(
                    MarkdownLineKind.HEADING,
                    level = minOf(headingPrefix, 3),
                    spans = parseInlineSpans(text)
                )
            }
            continue
        }
        val bullet = bulletText(trimmed)
        if (bullet != null) {
            if (bullet.isNotEmpty()) {
                lines += MarkdownLine(MarkdownLineKind.BULLET, spans = parseInlineSpans(bullet))
            }
            continue
        }
        val ordered = ORDERED_PREFIX.find(trimmed)
        if (ordered != null) {
            val text = trimmed.substring(ordered.value.length)
            if (text.isNotEmpty()) {
                lines += MarkdownLine(
                    MarkdownLineKind.ORDERED,
                    orderedPrefix = ordered.groupValues[1] + ".",
                    spans = parseInlineSpans(text)
                )
            }
            continue
        }
        if (trimmed.startsWith(">")) {
            // 引用语义在本仓库文档中不承担结构信息,降级为普通段落并剥掉前缀。
            val quoted = trimmed.trimStart('>', ' ')
            if (quoted.isNotEmpty()) {
                lines += MarkdownLine(
                    MarkdownLineKind.PARAGRAPH,
                    spans = parseInlineSpans(quoted)
                )
            }
            continue
        }
        lines += MarkdownLine(MarkdownLineKind.PARAGRAPH, spans = parseInlineSpans(trimmed))
    }
    return lines
}

/**
 * 行内解析:先按 ** 加粗段拆分,再对每段按 ` 等宽段拆分。
 * 不成对的分隔符(奇数个)会让最末一段回到普通文本,避免把剩余全文误标为加粗/等宽。
 */
internal fun parseInlineSpans(text: String): List<MarkdownSpan> {
    if (text.isEmpty()) return emptyList()
    if (!text.contains("**") && !text.contains('`')) {
        return listOf(MarkdownSpan(text))
    }
    val spans = mutableListOf<MarkdownSpan>()
    val boldParts = text.split("**")
    for (i in boldParts.indices) {
        val part = boldParts[i]
        if (part.isEmpty()) continue
        val bold = i % 2 == 1 && !(i == boldParts.lastIndex && boldParts.size % 2 == 0)
        appendCodeSpans(spans, part, bold)
    }
    return spans.ifEmpty { listOf(MarkdownSpan(text)) }
}

private fun appendCodeSpans(spans: MutableList<MarkdownSpan>, text: String, bold: Boolean) {
    if (!text.contains('`')) {
        spans += MarkdownSpan(text, bold)
        return
    }
    val parts = text.split('`')
    for (i in parts.indices) {
        val part = parts[i]
        if (part.isEmpty()) continue
        val code = i % 2 == 1 && !(i == parts.lastIndex && parts.size % 2 == 0)
        spans += MarkdownSpan(part, bold, code)
    }
}

/** 返回井号前缀长度(井号后必须跟空格),非标题返回 null。 */
private fun headingPrefixLength(line: String): Int? {
    var i = 0
    while (i < line.length && line[i] == '#') i++
    if (i == 0 || i > 6) return null
    if (i >= line.length || line[i] != ' ') return null
    return i
}

/** 返回剥掉 "- "/"* "/" + " 标记后的内容;非无序列表返回 null。 */
private fun bulletText(line: String): String? {
    if (line.length < 2 || line[1] != ' ') return null
    if (line[0] != '-' && line[0] != '*' && line[0] != '+') return null
    return line.substring(2).trim()
}
