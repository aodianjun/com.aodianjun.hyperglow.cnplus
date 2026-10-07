package com.example.hyperglow.aitranslationwords

import java.util.Locale

/** 启发式语言归类（与设置项 skip_languages 的取值域一致）。 */
internal enum class Lang(val code: String) {
    ZH("zh"),
    EN("en"),
    JA("ja"),
    KO("ko"),
    ES("es"),
    OTHER("other"),
}

/**
 * 语言检测与「无需翻译」判断（纯函数，全部可单测）。
 *
 * 检测是**字符集启发式**（与官方插件的「歌词语言识别」同类做法）：按 谚文 → 假名 →
 * 汉字占比 → 西语标点 → 拉丁字母 的优先级归类；日文歌纯汉字歌词会被归为中文，
 * 属该方法的已知边界（README 已列出）。
 */
internal object LanguageDetect {

    /** 汉字在「字母 + 汉字」中的占比达到该值即视为中文（无假名时）。 */
    private const val HAN_RATIO_ZH = 0.20

    /** 拉丁字母占比达到该值即视为英文（无西语标点时）。 */
    private const val LATIN_RATIO_EN = 0.50

    fun detect(text: String): Lang {
        if (text.isBlank()) return Lang.OTHER
        // 剥掉括号注记（"(Cocoon Broken)" 之类的版本/副标题）：它们不该左右主体语言判断。
        val body = text.replace(BRACKETED, " ")
        if (body.isBlank()) return Lang.OTHER
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        var spanishMark = false
        for (ch in body) {
            when {
                isHangul(ch) -> hangul++
                isKana(ch) -> kana++
                isHan(ch) -> han++
                ch.isLetter() && ch.code < 0x250 -> latin++
                ch in SPANISH_MARKS -> spanishMark = true
            }
        }
        if (hangul > 0) return Lang.KO
        if (kana > 0) return Lang.JA
        val letters = han + latin
        if (letters == 0) return Lang.OTHER
        if (han.toDouble() / letters >= HAN_RATIO_ZH) return Lang.ZH
        if (spanishMark) return Lang.ES
        if (latin.toDouble() / letters >= LATIN_RATIO_EN) return Lang.EN
        return Lang.OTHER
    }

    /**
     * 把用户填写的目标语言（自由文本）归一化到枚举；识别不出时返回 null
     * （此时仍会进 prompt 原样使用，只是「行已是目标语言」的跳过判断不启用）。
     */
    fun normalizeTarget(raw: String): Lang? {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        return when {
            s.contains("中文") || s.contains("汉语") || s.contains("漢語") ||
                s.contains("简体") || s.contains("繁體") || s.contains("繁体") ||
                s.contains("chinese") || s == "zh" || s.startsWith("zh-") ||
                s.contains("mandarin") -> Lang.ZH

            s.contains("英文") || s.contains("英语") || s.contains("英語") ||
                s.contains("english") || s == "en" || s.startsWith("en-") -> Lang.EN

            s.contains("日文") || s.contains("日语") || s.contains("日本語") ||
                s.contains("japanese") || s == "ja" || s.startsWith("ja-") -> Lang.JA

            s.contains("韩文") || s.contains("韩语") || s.contains("韓語") ||
                s.contains("korean") || s.contains("한국어") || s == "ko" || s.startsWith("ko-") -> Lang.KO

            s.contains("西班牙") || s.contains("spanish") || s.contains("español") ||
                s == "es" || s.startsWith("es-") -> Lang.ES

            else -> null
        }
    }

    /**
     * 该行是否无需翻译：空白、纯数字/标点/符号（无任何字母与表意文字）、
     * 或已经是目标语言（目标语言可识别时）。
     */
    fun isSkippable(text: String, target: Lang?): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.none { it.isLetter() }) return true
        if (target != null && target != Lang.OTHER && detect(trimmed) == target) return true
        return false
    }

    private fun isHangul(ch: Char): Boolean = when (ch.code) {
        in 0xAC00..0xD7AF -> true // 谚文音节
        in 0x1100..0x11FF -> true // 谚文字母
        in 0x3130..0x318F -> true // 谚文兼容字母
        else -> false
    }

    private fun isKana(ch: Char): Boolean = when (ch.code) {
        in 0x3040..0x309F -> true // 平假名
        in 0x30A0..0x30FF -> true // 片假名
        in 0x31F0..0x31FF -> true // 片假名扩展
        else -> false
    }

    private fun isHan(ch: Char): Boolean = when (ch.code) {
        in 0x4E00..0x9FFF -> true // CJK 统一表意
        in 0x3400..0x4DBF -> true // 扩展 A
        in 0xF900..0xFAFF -> true // 兼容表意
        else -> false
    }

    private val SPANISH_MARKS = setOf('¿', '¡', 'ñ', 'Ñ', 'á', 'é', 'í', 'ó', 'ú', 'ü', 'Á', 'É', 'Í', 'Ó', 'Ú', 'Ü')

    /** 括号注记（中英文括号/方括号/书名号内的内容）在语言判断前剥除。 */
    private val BRACKETED = Regex("""[\(（\[【].*?[\)）\]】]""")
}
