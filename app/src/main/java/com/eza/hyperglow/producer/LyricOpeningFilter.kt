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

/**
 * 制作名单行识别(「隐藏非歌词内容」开关的判定核心)。
 *
 * 与 [LyricOpeningFilter] 的关系必须说清,否则极易误改:
 * - 移植自上游/Bridge 的开场清理词表**刻意不含**「作词/作曲」——这类制作名单常被源摆
 *   在开头当曲目介绍,Bridge 选择保守保留;[LyricOpeningFilterTest] 已把该边界固化成
 *   断言(isProductionCreditLine("作词：张三") == false)。
 * - 但用户并不想在歌词字幕里看到「作词：张三」。本对象是为此新增的**用户可选**能力,
 *   默认关闭时不参与任何链路,因此不改变上述保守语义。
 *
 * 判据(均要求「角色词 + 分隔/收尾」,避免吞掉正文):
 * 1. 中英日角色 + 分隔符(`:：=／/`):如 `作词：周杰伦`、`Composer: John`;
 * 2. 空格分隔的英文短语:`Lyrics by Bob`、`Composed by John`;
 * 3. 括号装饰型:`【作词】 张三`、`(作曲) 李四`。
 *
 * 保守原则与 LyricOpeningFilter 一致:宁可漏判(照常显示),不可错判(吞掉真歌词)。
 * 句中出现的角色词不算,英语词还要求左侧非字母(避开 composed/composer 内部命中)。
 */
internal object LyricCreditLineFilter {

    /** 创作/制作角色词(每个角色独立扫描,顺序不影响结果)。 */
    private val CREDIT_ROLE_WORDS = listOf(
        // 简体
        "作词", "作曲", "编曲", "词曲", "填词", "谱曲",
        // 繁体(zh-Hant 歌词源同样常见)
        "作詞", "編曲", "詞曲", "填詞", "譜曲",
        "lyricist", "composer", "arranger", "songwriter",
        "written", "composed", "arranged", "produced", "lyrics"
    )

    /** 整句短语前缀(常以空格而非冒号引出人名的分工写明)。 */
    private val CREDIT_PHRASE_PREFIXES = listOf(
        "vocals recorded", "background vocal", "backing vocal",
        "mixed in dolby atmos", "recorded by", "performed by"
    )

    /** 装饰性左括号族:【…】/ (…) / 「…」等。 */
    private val LEAD_DECORATIONS = charArrayOf(
        '[', '【', '(', '（', '「', '『', '♪', '♫', '♬', '·', '•'
    )

    /** 角色词后的右括号族:仅这些收尾才构成「标签」形态(裸文本如「作曲家的梦想」不算)。 */
    private val LEAD_CLOSERS = charArrayOf('】', ']', ')', '）', '』', '」')

    /**
     * 归一化:全角标点统一到半角、连续空白压平、去掉行首装饰符号。
     * 归一集中在入口做一次,判定各分支不再各自解释字符串。
     */
    private fun normalize(value: String): String {
        val folded = buildString(value.length) {
            for (ch in value) {
                when {
                    ch == '：' -> append(':')
                    ch == '／' -> append('/')
                    ch == '　' -> append(' ')
                    ch in 'Ａ'..'Ｚ' -> append((ch.code - 0xFEE0).toChar())
                    ch in 'ａ'..'ｚ' -> append((ch.code - 0xFEE0).toChar())
                    else -> append(ch)
                }
            }
        }
        return folded.trim()
            .trimStart(*LEAD_DECORATIONS)
            .replace(Regex("\\s+"), " ")
    }

    /**
     * 是否为制作名单行:true → 开关打开时隐藏该行。
     *
     * 空白/空行一律返回 false(不是歌词也不是名单,交给投影层既有逻辑处理)。
     */
    fun isCreditLine(rawText: String): Boolean {
        val text = normalize(rawText)
        if (text.isEmpty()) return false
        val lower = text.lowercase()

        // 1) 角色 + 分隔符:`作词：张三` / `Composer: John` / `编曲/李四`
        for (role in CREDIT_ROLE_WORDS) {
            var from = 0
            while (true) {
                val index = lower.indexOf(role, from)
                if (index < 0) break
                from = index + 1
                if (!isWordHead(text, index)) continue
                var cursor = index + role.length
                while (cursor < lower.length && (lower[cursor] == ' ' || lower[cursor] == '\t')) {
                    cursor++
                }
                if (cursor < lower.length && lower[cursor] in charArrayOf(':', '/', '=')) {
                    return true
                }
            }
        }

        // 2) 空格分隔的英文短语:`Lyrics by Bob` / `Composed by John`
        if (CREDIT_PHRASE_PREFIXES.any { lower.startsWith(it) }) return true
        if (lower.startsWith("lyrics by") || lower.startsWith("music by") ||
            lower.startsWith("written by") || lower.startsWith("composed by") ||
            lower.startsWith("arranged by") || lower.startsWith("produced by")
        ) {
            return true
        }

        // 3) 括号标签型:`【作词】 张三` / `(作曲) 李四` / 裸标签 `【作词】`
        //    必须紧跟右括号才算标签——否则「作曲家的梦想」这类真歌词会被误吞。
        val bare = text.trimStart(*LEAD_DECORATIONS)
        for (role in CREDIT_ROLE_WORDS) {
            if (!bare.startsWith(role)) continue
            val next = bare.getOrNull(role.length) ?: continue
            if (next in LEAD_CLOSERS) return true
        }
        return false
    }

    /** [index] 处是否为一个词的左边界(行首,或左侧非字母数字)。 */
    private fun isWordHead(text: String, index: Int): Boolean {
        if (index == 0) return true
        return !text[index - 1].isLetterOrDigit()
    }
}

/**
 * 主行/下一行的名单归类结果(「不显示非歌词内容」)。
 *
 * 分类只做一次、两处消费:实机投影([com.eza.hyperglow.aod.projectToDisplay])与 App 内
 * 预览(ProducerCollectors.toPreviewSnapshot)共用本函数,避免两边各写一份判定而在
 * 「预览即实机」上失守。**如何呈现**(占位符/空档预览/窗口)由各消费方按自己的既有
 * 门控决定,不在这里。
 */
internal data class CreditLineFlags(
    val lineIsCredit: Boolean,
    val nextLineIsCredit: Boolean
)

internal fun classifyCreditLines(
    line: String,
    nextLine: String,
    hideCredits: Boolean
): CreditLineFlags {
    if (!hideCredits) return CreditLineFlags(false, false)
    return CreditLineFlags(
        lineIsCredit = line.isNotBlank() && LyricCreditLineFilter.isCreditLine(line),
        nextLineIsCredit = nextLine.isNotBlank() && LyricCreditLineFilter.isCreditLine(nextLine)
    )
}
