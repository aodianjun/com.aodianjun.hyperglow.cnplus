package com.example.hyperglow.lyricfetch

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/** 解析后的一个词（音节）。 */
internal data class ParsedWord(val text: String, val beginMs: Long, val endMs: Long)

/** 解析后的一行。 */
internal data class ParsedLine(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<ParsedWord>,
    val translation: String?,
    val phonetic: String?,
    val isAccompaniment: Boolean,
)

/**
 * 原始歌词文本 → 结构化行/词。
 *
 * 解析交给 accompanist-lyrics-core 的 [AutoParser]（LRC / Enhanced LRC / YRC / KRC /
 * TTML / Lyricify Syllable 全格式自动识别），本管线只补三件它不做或不做全的事：
 * 1. **翻译合并**：翻译是独立 LRC 文档，按行起始时间对齐挂回（对齐语义取自
 *    Lyricify 的 Netease 翻译处理：同一时间戳视为同一行）；
 * 2. **音节合并**：移植 Lyricify-Lyrics-Helper 的 SyllableWordMerger——相邻的
 *    非 CJK 音节（拉丁字母/数字，且无空白边界）合并为整词，避免逐字母高亮；
 * 3. **规范化**：去空行、时间轴钳制（begin≥0、end≥begin）、按开始时间排序。
 */
internal object LyricsPipeline {

    /** 翻译行与原文行的最大对齐误差（实测网易云 YRC↔tlyric 可差 400ms 上下）。 */
    private const val TRANSLATION_TOLERANCE_MS = 800L

    private val autoParser = AutoParser()

    fun parse(raw: RawLyrics, mergeSyllables: Boolean, wantTranslation: Boolean): List<ParsedLine> {
        val synced = runCatching { autoParser.parse(raw.content) }.getOrNull() ?: return emptyList()
        if (synced.lines.isEmpty()) return emptyList()

        val lines = ArrayList<ParsedLine>(synced.lines.size + 8)
        for (line in synced.lines) {
            when (line) {
                is KaraokeLine.MainKaraokeLine -> {
                    lines += karaokeLine(line, isAccompaniment = false)
                    line.accompanimentLines?.forEach { accompaniment ->
                        lines += karaokeLine(accompaniment, isAccompaniment = true)
                    }
                }

                is KaraokeLine.AccompanimentKaraokeLine -> lines += karaokeLine(line, isAccompaniment = true)

                is SyncedLine -> lines += ParsedLine(
                    beginMs = line.start.toLong(),
                    endMs = line.end.toLong(),
                    text = line.content,
                    words = emptyList(),
                    translation = line.translation,
                    phonetic = null,
                    isAccompaniment = false,
                )

                else -> Unit // 未知行型：跳过而不是崩溃（插件链里任何异常都会被宿主吞掉）
            }
        }

        val withTranslation = if (wantTranslation) {
            mergeTranslation(lines, raw.translation)
        } else {
            lines
        }
        val merged = if (mergeSyllables) withTranslation.map { it.copy(words = mergeSyllableWords(it.words)) } else withTranslation
        return sanitize(merged)
    }

    private fun karaokeLine(line: KaraokeLine, isAccompaniment: Boolean): ParsedLine {
        val words = line.syllables.map {
            ParsedWord(it.content, it.start.toLong(), it.end.toLong())
        }
        return ParsedLine(
            beginMs = line.start.toLong(),
            endMs = line.end.toLong(),
            text = line.syllables.joinToString("") { it.content },
            words = words,
            translation = line.translation,
            phonetic = line.phonetic,
            isAccompaniment = isAccompaniment,
        )
    }

    /**
     * 翻译文档按行起始时间挂回原文；已有翻译（如 TTML 内嵌）不覆盖。
     *
     * 实测网易云的 YRC 与 tlyric 时间戳并不严格相等（同一句可差 400ms 上下），
     * 因此用"就近且不重复消费"匹配：每条原文行取窗口内最近的、尚未被占用的翻译行，
     * 窗口外的行不挂翻译（宁缺毋滥）。
     */
    private fun mergeTranslation(lines: List<ParsedLine>, translation: String?): List<ParsedLine> {
        if (translation.isNullOrBlank() || lines.isEmpty()) return lines
        val parsed = runCatching { autoParser.parse(translation) }.getOrNull() ?: return lines
        if (parsed.lines.isEmpty()) return lines

        data class Entry(val start: Long, val text: String, var used: Boolean = false)

        val entries = parsed.lines.mapNotNull { line ->
            val text = when (line) {
                is KaraokeLine -> line.syllables.joinToString("") { it.content }
                is SyncedLine -> line.content
                else -> null
            }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(line.start.toLong(), text)
        }.sortedBy { it.start }
        if (entries.isEmpty()) return lines

        return lines.map { line ->
            if (!line.translation.isNullOrBlank()) return@map line
            var best: Entry? = null
            var bestDelta = Long.MAX_VALUE
            for (entry in entries) {
                if (entry.used) continue
                val delta = kotlin.math.abs(entry.start - line.beginMs)
                if (delta <= TRANSLATION_TOLERANCE_MS && delta < bestDelta) {
                    best = entry
                    bestDelta = delta
                }
            }
            val match = best ?: return@map line
            match.used = true
            line.copy(translation = match.text)
        }
    }

    /**
     * 相邻音节合并（Lyricify SyllableWordMerger 语义移植）：
     * 前词尾/后词首无空白、两侧都不含 CJK/假名、且都含字母或数字时合并为整词。
     * 逐字母切分的拉丁歌词（QRC/KRC 常见）由此还原成单词，避免"一个字母一个块"的
     * 逐字高亮；CJK 侧不合并，保持逐字卡拉OK语义。
     */
    internal fun mergeSyllableWords(words: List<ParsedWord>): List<ParsedWord> {
        if (words.size < 2) return words
        val merged = ArrayList<ParsedWord>(words.size)
        for (word in words) {
            val previous = merged.lastOrNull()
            if (previous != null && shouldMerge(previous.text, word.text)) {
                merged[merged.size - 1] = ParsedWord(
                    text = previous.text + word.text,
                    beginMs = previous.beginMs,
                    endMs = maxOf(previous.endMs, word.endMs),
                )
            } else {
                merged += word
            }
        }
        return merged
    }

    private fun shouldMerge(previous: String, current: String): Boolean {
        if (previous.isEmpty() || current.isEmpty()) return false
        if (previous.last().isWhitespace() || current.first().isWhitespace()) return false
        if (previous.any(::isCjkOrKana) || current.any(::isCjkOrKana)) return false
        return previous.any { it.isLetterOrDigit() } && current.any { it.isLetterOrDigit() }
    }

    private fun isCjkOrKana(ch: Char): Boolean = when (ch.code) {
        in 0x4E00..0x9FFF -> true   // CJK 统一表意
        in 0x3400..0x4DBF -> true   // 扩展 A
        in 0x3040..0x309F -> true   // 平假名
        in 0x30A0..0x30FF -> true   // 片假名
        in 0x31F0..0x31FF -> true   // 片假名扩展
        else -> false
    }

    private fun sanitize(lines: List<ParsedLine>): List<ParsedLine> =
        lines.asSequence()
            .filter { it.text.isNotBlank() }
            .map { line ->
                val begin = line.beginMs.coerceAtLeast(0L)
                val end = line.endMs.coerceAtLeast(begin)
                line.copy(
                    beginMs = begin,
                    endMs = end,
                    words = line.words.filter { it.text.isNotEmpty() }
                        .map { word ->
                            val wb = word.beginMs.coerceAtLeast(begin)
                            ParsedWord(word.text, wb, word.endMs.coerceAtLeast(wb))
                        },
                )
            }
            .sortedBy { it.beginMs }
            .toList()

    /** 逐字覆盖度：有词级且多数行含 ≥2 个词时才算"逐字歌词"。 */
    fun hasWordLevel(lines: List<ParsedLine>): Boolean {
        if (lines.isEmpty()) return false
        val withWords = lines.count { it.words.size >= 2 }
        return withWords * 2 >= lines.size
    }
}
