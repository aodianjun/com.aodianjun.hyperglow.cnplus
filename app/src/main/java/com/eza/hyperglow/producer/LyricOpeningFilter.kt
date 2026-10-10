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
 * 制作名单行识别(「不显示非歌词内容」开关的判定核心)。
 *
 * 与 [LyricOpeningFilter] 的关系必须说清,否则极易误改:
 * - 移植自上游/Bridge 的开场清理词表**刻意不含**「作词/作曲」——这类制作名单常被源摆
 *   在开头当曲目介绍,Bridge 选择保守保留;[LyricOpeningFilterTest] 已把该边界固化成
 *   断言(isProductionCreditLine("作词：张三") == false)。
 * - 但用户并不想在歌词字幕里看到「作词：张三」。本对象是为此新增的**用户可选**能力,
 *   默认关闭时不参与任何链路,因此不改变上述保守语义。
 *
 * 判据(都要求「标签 + 分隔符」或整句短语,避免吞掉正文):
 * 1. 标签 + 分隔符(`:：=／/`):标签等于角色词,或**以角色词结尾**(复合标签)。
 *    后缀匹配是 2026-10-10 真机实测补上的:网易云《乐鸣东方》开场 15 行名单里
 *    「弦乐监制」「民族伴唱监制」「音乐总监」这类复合标签占多数,只做整词匹配会漏掉
 *    大半名单(用户反馈「开关无效」的根因,判据取自设备 diagnostic-trace.log 原文)。
 * 2. 空格分隔的英文短语:`Lyrics by Bob`、`Composed by John`;
 * 3. 括号标签型:`【作词】 张三`、`(作曲) 李四`(只认无歧义的创作角色)。
 *
 * 保守原则与 LyricOpeningFilter 一致:宁可漏判(照常显示),不可错判(吞掉真歌词)。
 * 因此单字角色(词/曲/鼓/琴…)只做**整标签**匹配(否则「歌词：」「插曲：」会被吞),
 * 英文按**词边界**匹配(否则 `sp` 会命中 `space:`),标签长度设上限防长句误判。
 */
internal object LyricCreditLineFilter {

    /**
     * 角色词:标签**等于**它,或**以它结尾**。
     * 词表 = 上游 HyperLyric LEADING_MUSIC_INFO_PATTERN + CN+ 既有开场清理的乐器词表
     * + 真机实测名单补录(调校/音乐总监/吉他/和音/弦乐/弦乐监制/民族伴唱监制/混音母带…)。
     */
    private val ROLE_TOKENS = listOf(
        // 创作/制作(简繁)
        "作词", "作詞", "作词人", "作詞人", "作词者", "作詞者", "作曲", "作曲人", "作曲者",
        "编曲", "編曲", "编曲人", "词曲", "詞曲", "填词", "填詞", "谱曲", "譜曲", "配器",
        "调校", "調校", "校对", "校對", "配唱", "监唱", "監唱",
        "制作人", "製作人", "出品人", "发行人", "發行人", "监制", "監製", "总监", "總監",
        "制作", "製作", "统筹", "統籌", "企划", "企劃", "策划", "策劃", "导演", "導演",
        "录音", "錄音", "录音师", "錄音師", "录音室", "錄音室", "录音棚", "錄音棚",
        "混音", "混音师", "混音師", "混音室", "缩混", "縮混", "混缩", "混縮",
        "母带", "母帶", "母带师", "母帶師", "母版", "调音师", "調音師", "处理", "處理",
        "后期", "後期", "工作室", "协力", "協力", "指导", "指導",
        "和声", "和聲", "和音", "伴唱", "合唱", "童声", "童聲", "人声", "人聲",
        "演奏", "演奏者", "演唱", "演唱者",
        "人声编辑", "人聲編輯", "音频编辑", "音頻編輯", "编辑", "編輯", "编写", "編寫",
        "工程师", "工程師", "助理",
        // 乐器(带「琴/笛/箫/鼓/胡/筝」等字的乐器名多数由下面的后缀规则兜住)
        "吉他", "木吉他", "电吉他", "電吉他", "贝斯", "貝斯", "贝司", "鼓", "打击乐", "打擊樂",
        "键盘", "鍵盤", "钢琴", "鋼琴", "合成器", "电子琴", "電子琴",
        "弦乐", "弦樂", "管弦乐", "管弦樂", "交响乐", "交響樂", "乐团", "樂團", "乐队", "樂隊",
        "指挥", "指揮", "小提琴", "中提琴", "大提琴", "低音提琴",
        "长笛", "長笛", "萨克斯", "薩克斯", "小号", "小號", "长号", "長號", "圆号", "圓號",
        "二胡", "琵琶", "古筝", "古箏", "笛子", "唢呐", "嗩吶", "马头琴", "馬頭琴",
        "口琴", "竖琴", "豎琴", "手风琴", "手風琴", "拍板", "三弦", "箜篌", "木鱼", "木魚",
        "扬琴", "柳琴", "月琴", "古琴", "天琴",
        // 视觉/发行
        "封面", "设计", "設計", "视觉", "視覺", "文案", "宣发", "宣發", "发行", "發行",
        "出品", "版权", "版權", "公司", "插画", "插畫", "摄影", "攝影"
    )

    /**
     * 只做**整标签**匹配的短词:作后缀会误伤真歌词
     * (「歌词：」「插曲：」会被 `词`/`曲` 后缀吞掉)。
     * 乐器字(琴/笛/箫/鼓/胡/筝…)反过来必须能作后缀——中文乐器名是长尾
     * (古琴/天琴/骨笛/南音洞箫/二胡/扬琴…),逐个列举列不全。
     */
    private val EXACT_LABELS = listOf("词", "詞", "曲")

    /** 可作后缀的乐器字:命中「以该字结尾的标签」,如 古琴/天琴/骨笛/洞箫/二胡/扬琴。 */
    private val INSTRUMENT_SUFFIX_CHARS = listOf(
        "琴", "笛", "箫", "簫", "鼓", "胡", "筝", "箏", "笙", "阮", "埙", "塤", "锣", "鑼", "钹", "鈸"
    )

    /** 英文角色词:按词边界匹配(整标签 / `X guitar` / `guitar X`),避免 sp 命中 space。 */
    private val EN_ROLE_TOKENS = listOf(
        "lyricist", "composer", "arranger", "songwriter", "producer", "produced",
        "lyrics", "music", "written", "composed", "arranged",
        "recorded", "recording", "mixed", "mixing", "mastered", "mastering", "engineer",
        "vocals", "vocal", "backing vocal", "background vocal", "choir", "conductor",
        "guitar", "bass", "drum", "drums", "keyboard", "piano", "strings", "violin",
        "cello", "viola", "flute", "saxophone", "trumpet", "orchestra", "synth",
        "programming", "production", "op", "sp", "publisher", "label", "artwork", "design"
    )

    /** 空格分隔的英文短语(无冒号的整句形态)。 */
    private val EN_PHRASES = listOf(
        "lyrics by", "music by", "written by", "composed by", "arranged by",
        "produced by", "mixed by", "mastered by", "recorded by", "performed by",
        "vocals recorded", "background vocal", "backing vocal", "mixed in dolby atmos"
    )

    /**
     * 括号标签型只认无歧义的创作角色:和声/演唱/乐器类可以作行首标记
     * (如「(和声) 歌词正文」),不能当名单吞掉。
     */
    private val BRACKET_LABELS = listOf(
        "作词", "作詞", "作曲", "编曲", "編曲", "词曲", "詞曲", "填词", "填詞", "谱曲", "譜曲",
        "制作人", "製作人", "监制", "監製", "出品人", "调校", "調校",
        "lyricist", "composer", "arranger", "producer", "lyrics"
    )

    /** 装饰性左括号族:【…】/ (…) / 「…」等。 */
    private val LEAD_DECORATIONS = charArrayOf(
        '[', '【', '(', '（', '「', '『', '♪', '♫', '♬', '·', '•'
    )

    /** 角色词后的右括号族:仅这些收尾才构成「标签」形态(裸文本如「作曲家的梦想」不算)。 */
    private val LEAD_CLOSERS = charArrayOf('】', ']', ')', '）', '』', '」')

    private val SEPARATORS = charArrayOf(':', '/', '=')

    /** 标签长度上限:比这更长的前缀不可能是制作名单标签,防长句误判。 */
    private const val MAX_LABEL_LENGTH = 12

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

        // 1) 标签 + 分隔符:取第一个分隔符之前的标签做角色判定。
        //    「混音/母带：罗文Rown」这类把斜杠当分隔的写法,标签即「混音」。
        val separator = text.indexOfFirst { it in SEPARATORS }
        if (separator > 0) {
            val label = text.substring(0, separator).trim()
            if (label.isNotEmpty() && label.length <= MAX_LABEL_LENGTH && isRoleLabel(label)) {
                return true
            }
        }

        // 2) 空格分隔的英文短语:`Lyrics by Bob` / `Composed by John`
        val lower = text.lowercase()
        if (EN_PHRASES.any { lower.startsWith(it) }) return true

        // 3) 括号标签型:`【作词】 张三` / `(作曲) 李四` / 裸标签 `【作词】`
        //    必须紧跟右括号才算标签——否则「作曲家的梦想」这类真歌词会被误吞。
        val bare = text.trimStart(*LEAD_DECORATIONS)
        for (label in BRACKET_LABELS) {
            if (!bare.startsWith(label)) continue
            val next = bare.getOrNull(label.length) ?: continue
            if (next in LEAD_CLOSERS) return true
        }
        return false
    }

    /** 标签是否为制作名单角色:整标签、以角色词结尾(复合标签)、或英文按词边界。 */
    private fun isRoleLabel(label: String): Boolean {
        if (label in EXACT_LABELS) return true
        for (token in ROLE_TOKENS) {
            if (label == token || label.endsWith(token)) return true
        }
        for (ch in INSTRUMENT_SUFFIX_CHARS) {
            if (label.length >= 2 && label.endsWith(ch)) return true
        }
        val lower = label.lowercase()
        for (token in EN_ROLE_TOKENS) {
            if (lower == token ||
                lower.endsWith(" " + token) ||
                lower.startsWith(token + " ")
            ) {
                return true
            }
        }
        return false
    }
}

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
