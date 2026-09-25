package com.eza.hyperglow.producer

/**
 * 歌词开头元数据清理·内置三类(Bridge LyricOpeningCleanup + LyricMetadataFilter
 * 的内置部分移植,issue #68 #4;学习规则/逐曲修正不在本次范围)。
 *
 * 只处理前 [OPENING_MAX_LINES] 行且 startMs ≤ [OPENING_MAX_TIME_MS] 的候选行,
 * 越界绝不误伤正文。三类判定逐字段对齐 Bridge:
 * 1. 版权与权利声明(©/copyright/版权所有…);
 * 2. 制作明细(30s 内「角色: 内容」形态,角色词表为乐器/职务/发行方——注意
 *    「作词/作曲」不在 Bridge 词表,同样**不**隐藏,测试固化该事实);
 * 3. 标题歌手头(自带 0..15s 窗口,「空格-空格」七种破折族分隔,两侧 ≥2 字符
 *    且含字母,无句末标点,总长 ≤96)及其 2s 内、紧随其后的短续行。
 *
 * 保守原则:宁可漏隐藏,不可错藏正文——区分性尾缀/含句末标点/超窗一律保留。
 */
internal object LyricOpeningFilter {
    internal const val OPENING_MAX_LINES = 32
    internal const val OPENING_MAX_TIME_MS = 30_000L

    fun filterOpeningMetadata(lines: List<ElrcParser.TimedLine>): List<ElrcParser.TimedLine> {
        if (lines.isEmpty()) return lines
        val kept = ArrayList<ElrcParser.TimedLine>(lines.size)
        var previousWasCredit = false
        var previousTimeMs = Long.MIN_VALUE
        var hidden = 0
        for ((index, line) in lines.withIndex()) {
            val isCandidate = index < OPENING_MAX_LINES && line.startMs in 0..OPENING_MAX_TIME_MS
            if (isCandidate && !line.text.isBlank()) {
                val isCreditLead = isTitleArtistLead(line.text, line.startMs)
                if (isCopyrightLine(line.text) ||
                    isProductionCreditLine(line.text, line.startMs) ||
                    isCreditLead
                ) {
                    hidden++
                    // 短续行只跟随标题歌手头(与 Bridge 的 index-1 reason 语义一致)。
                    previousWasCredit = isCreditLead
                    previousTimeMs = line.startMs
                    continue
                }
                if (previousWasCredit &&
                    line.startMs - previousTimeMs in 0..ARTIST_CONTINUATION_GAP_MS &&
                    isLikelyArtistContinuation(line.text)
                ) {
                    hidden++
                    previousWasCredit = false
                    previousTimeMs = line.startMs
                    continue
                }
            }
            kept.add(line)
            previousWasCredit = false
            previousTimeMs = line.startMs
        }
        return if (hidden == 0) lines else kept
    }

    internal fun isCopyrightLine(text: String): Boolean {
        val normalized = normalizeText(text)
        if (normalized.isEmpty()) return false
        if (normalized.startsWith("©") || normalized.startsWith("℗")) return true
        val lower = normalized.lowercase()
        if (lower.contains("copyright") || lower.contains("all rights reserved") ||
            lower.contains("used by permission")
        ) {
            return true
        }
        return normalized.contains("版权所有") || normalized.contains("著作权") ||
            normalized.contains("未经许可") || normalized.contains("未经授权") ||
            normalized.contains("翻译作品")
    }

    internal fun isProductionCreditLine(text: String, timeMillis: Long): Boolean {
        val normalized = normalizeText(text)
        if (normalized.isEmpty()) return false
        if (timeMillis < 0L || timeMillis > OPENING_MAX_TIME_MS) return false
        val lower = normalized.lowercase().replace('：', ':')
        val separator = lower.indexOf(':')
        val label = (if (separator >= 0) lower.substring(0, separator) else lower).trim()
        if (label.isEmpty() || label.length > 120) return false
        val isRole = PRODUCTION_ROLE_STARTS.any { label.startsWith(it) }
        if (!isRole) return false
        return separator >= 0 || lower.contains(" by") || lower.contains(" recorded by")
    }

    internal fun isTitleArtistLead(text: String, timeMillis: Long): Boolean {
        val normalized = normalizeText(text)
        if (normalized.isEmpty() || timeMillis < 0L || timeMillis > TITLE_ARTIST_MAX_TIME_MS) {
            return false
        }
        if (normalized.length > 96) return false
        if (containsSentenceEndingPunctuation(normalized)) return false
        val separator = findSpacedTitleArtistSeparator(normalized)
        if (separator <= 0) return false
        val title = normalized.substring(0, separator).trim()
        val artist = normalized.substring(separator + 1).trim()
        return title.length >= 2 && artist.length >= 2 &&
            containsLetter(title) && containsLetter(artist)
    }

    private fun isLikelyArtistContinuation(text: String): Boolean {
        val normalized = normalizeText(text)
        if (normalized.length < 2 || normalized.length > 48) return false
        if (looksLikeLyricContent(normalized)) return false
        if (normalized.indexOf(':') >= 0 || normalized.indexOf('：') >= 0 ||
            normalized.indexOf(',') >= 0 || normalized.indexOf('，') >= 0 ||
            normalized.endsWith(".") || normalized.endsWith("!") || normalized.endsWith("?")
        ) {
            return false
        }
        return normalized.any { it.isLetter() }
    }

    private fun looksLikeLyricContent(value: String): Boolean {
        var tokens = 0
        var cjkChars = 0
        var inToken = false
        val codePoints = value.codePoints().iterator()
        while (codePoints.hasNext()) {
            val codePoint = codePoints.next()
            if (Character.isWhitespace(codePoint)) {
                inToken = false
                continue
            }
            if (!inToken) {
                tokens++
                inToken = true
            }
            if (isCjkCodePoint(codePoint)) cjkChars++
        }
        return tokens > 5 || cjkChars >= 8
    }

    private fun isCjkCodePoint(codePoint: Int): Boolean =
        (codePoint in 0x3400..0x9FFF) || (codePoint in 0xF900..0xFAFF) ||
            (codePoint in 0x3040..0x30FF) || (codePoint in 0xAC00..0xD7AF)

    internal fun isTitleArtistSeparator(ch: Char): Boolean =
        ch == '-' || ch == '‐' || ch == '‑' || ch == '‒' || ch == '–' || ch == '—' || ch == '−'

    private fun findSpacedTitleArtistSeparator(text: String): Int {
        for (index in 1 until text.length - 1) {
            val ch = text[index]
            if (isTitleArtistSeparator(ch) &&
                Character.isWhitespace(text[index - 1]) &&
                Character.isWhitespace(text[index + 1])
            ) {
                return index
            }
        }
        return -1
    }

    private fun containsSentenceEndingPunctuation(text: String): Boolean =
        text.any { it == '.' || it == '?' || it == '!' || it == '。' || it == '？' || it == '！' }

    private fun containsLetter(value: String): Boolean = value.any { it.isLetter() }

    private fun normalizeText(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    private val PRODUCTION_ROLE_STARTS = arrayOf(
        "vocals recorded", "background vocal", "background vocals",
        "backing vocal", "backing vocals", "orchestration", "percussion",
        "synth", "synthesizer",
        "viola", "violin", "piano", "acoustic guitar", "electric guitar",
        "drum", "drums", "drum programming", "digital edited", "digital editing",
        "mixed in dolby atmos", "orchestra", "band", "choir", "conductor",
        "accordion", "strings", "guitar", "bass", "cello",
        "original publisher", "original publishers", "sub-publisher", "sub-publishers",
        "publisher", "乐队", "樂隊", "管弦乐", "管弦樂", "交响乐团",
        "交響樂團", "合唱", "指挥", "指揮", "手风琴", "手風琴",
        "钢琴", "鋼琴", "大提琴", "小提琴", "弦乐", "弦樂"
    )

    private const val TITLE_ARTIST_MAX_TIME_MS = 15_000L
    private const val ARTIST_CONTINUATION_GAP_MS = 2_000L
}
