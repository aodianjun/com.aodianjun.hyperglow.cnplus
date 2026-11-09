package com.example.hyperglow.aisegment

import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginWord

/** 一条切分后的子行:文本 + 源行窗口内的时间片 + 已分配的词。 */
internal data class ChildLine(
    val text: String,
    val begin: Long,
    val end: Long,
    val words: List<PluginWord>,
)

/**
 * 源行 → 子行的时间窗与词分配。
 *
 * - 子行时间窗:按子行显示字符数占源行显示字符数的比例切分源行 [begin, end]
 *   (首子行对齐源行 begin、末子行强制收口到 row.end,避免浮点累计截短末行);
 * - 词:源行带可用词表(逐词在源文本里按顺序定位得到字符区间)时,按词首字符落在
 *   哪个子行的字符区间就归入该子行,时间戳钳制进子行窗口(卡拉OK逐字时间不丢);
 *   源行没有可用词表时,按 CJK 逐字 / 西文按词合成子行的词块,时间按显示长度占比均分。
 */
internal object LineWindows {

    private const val META_ROLE = "role"
    private const val ROLE_LEAD = "LEAD"

    fun splitRow(row: PluginLyricLine, children: List<String>): List<ChildLine> {
        val sourceText = row.text.orEmpty()
        val window = (row.end - row.begin).coerceAtLeast(0L)
        val totalChildLen = children.sumOf { displayLength(it) }.coerceAtLeast(1)
        val sourceLen = codePointLength(sourceText).coerceAtLeast(1)
        val locatedWords = locateWords(sourceText, row.words.orEmpty())

        // 每个子行的源字符区间:[startChar, endCharExclusive)(按显示长度占比切源字符轴)
        val ranges = mutableListOf<Pair<Int, Int>>()
        var ratioCursor = 0.0
        children.forEachIndexed { index, childText ->
            val childLen = displayLength(childText).coerceAtLeast(1)
            val startRatio = ratioCursor
            ratioCursor += childLen.toDouble() / totalChildLen
            val endRatio = if (index == children.lastIndex) 1.0 else ratioCursor
            val startChar = (startRatio * sourceLen).toInt()
            val endChar = if (index == children.lastIndex) sourceLen else (endRatio * sourceLen).toInt()
            ranges += startChar to maxOf(endChar, startChar + 1)
        }

        val out = mutableListOf<ChildLine>()
        children.forEachIndexed { index, childText ->
            val (startChar, endChar) = ranges[index]
            val begin = if (index == 0) row.begin else out.last().end
            val end = if (index == children.lastIndex) {
                row.end
            } else {
                (row.begin + window * endChar / sourceLen).coerceIn(begin + 1, row.end)
            }
            val childWords = if (locatedWords.isNotEmpty()) {
                wordsInRange(locatedWords, startChar, endChar, begin, end)
            } else {
                syntheticWords(childText, begin, end)
            }
            out += ChildLine(childText, begin, end, childWords)
        }
        return out
    }

    /** 源行子行列表 → REPLACE 表用的 PluginLyricLine(角色 LEAD,继承源行的分侧)。 */
    fun toPluginLines(row: PluginLyricLine, children: List<ChildLine>): List<PluginLyricLine> =
        children.map { child ->
            PluginLyricLine(
                begin = child.begin,
                end = child.end,
                duration = (child.end - child.begin).coerceAtLeast(0L),
                isAlignedRight = row.isAlignedRight,
                metadata = PluginMetadata(
                    values = (row.metadata?.values ?: emptyMap()) + (META_ROLE to ROLE_LEAD)
                ),
                text = child.text,
                words = child.words.takeIf { it.isNotEmpty() },
            )
        }

    // ---- 源词定位 ----------------------------------------------------------

    /** 逐词按顺序在源文本里定位字符区间;任何一个词找不到 → 视为整体不可用(空表)。 */
    private fun locateWords(
        sourceText: String,
        words: List<PluginWord>,
    ): List<Triple<Int, Int, PluginWord>> {
        if (words.isEmpty()) return emptyList()
        val located = mutableListOf<Triple<Int, Int, PluginWord>>()
        var cursor = 0
        for (word in words) {
            val text = word.text
            if (text.isNullOrEmpty()) continue
            val start = sourceText.indexOf(text, cursor)
            if (start < 0) return emptyList()
            val end = (start + text.length).coerceAtMost(sourceText.length)
            located += Triple(start, end, word)
            cursor = end
        }
        return located
    }

    /** 词首字符落在哪个子行的字符区间就归哪个子行;时间戳钳制进该子行窗口。 */
    private fun wordsInRange(
        located: List<Triple<Int, Int, PluginWord>>,
        startChar: Int,
        endChar: Int,
        childBegin: Long,
        childEnd: Long,
    ): List<PluginWord> {
        val out = mutableListOf<PluginWord>()
        for ((start, end, word) in located) {
            if (start < startChar || start >= endChar) continue
            val begin = word.begin.coerceIn(childBegin, childEnd)
            var wordEnd = word.end.coerceIn(begin, childEnd)
            if (wordEnd <= begin) wordEnd = (begin + 1).coerceAtMost(childEnd)
            out += PluginWord(
                begin = begin,
                end = wordEnd,
                duration = (wordEnd - begin).coerceAtLeast(0L),
                text = word.text,
            )
        }
        return out
    }

    // ---- 合成词(源行没有可用词表时) ---------------------------------------

    /** CJK 逐字、西文按词合成词块;时间按显示长度占比在子行窗口内均分。 */
    private fun syntheticWords(text: String, begin: Long, end: Long): List<PluginWord> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val window = (end - begin).coerceAtLeast(0L)
        val totalLen = tokens.sumOf { displayLength(it) }.coerceAtLeast(1)
        val out = mutableListOf<PluginWord>()
        var cursor = begin
        var lenCursor = 0
        for (token in tokens) {
            val tokenLen = displayLength(token).coerceAtLeast(1)
            val tokenEnd = begin + window * (lenCursor + tokenLen) / totalLen
            val tokenBegin = cursor
            out += PluginWord(
                begin = tokenBegin,
                end = tokenEnd,
                duration = (tokenEnd - tokenBegin).coerceAtLeast(0L),
                text = token,
            )
            cursor = tokenEnd
            lenCursor += tokenLen
        }
        return out
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val latin = StringBuilder()
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (latin.isNotEmpty()) {
                    tokens += latin.toString()
                    latin.clear()
                }
                continue
            }
            if (isCjk(ch)) {
                if (latin.isNotEmpty()) {
                    tokens += latin.toString()
                    latin.clear()
                }
                tokens += ch.toString()
            } else {
                latin.append(ch)
            }
        }
        if (latin.isNotEmpty()) tokens += latin.toString()
        return tokens
    }

    private fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA
    }

    private fun displayLength(text: String): Int = text.codePointCount(0, text.length)

    private fun codePointLength(text: String): Int = text.codePointCount(0, text.length)
}
