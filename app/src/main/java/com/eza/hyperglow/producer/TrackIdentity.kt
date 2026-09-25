package com.eza.hyperglow.producer

/**
 * 曲目身份判定(Bridge TrackIdentity 的最小移植,issue #68 #15):
 * 歌词源对同一首歌的标题写法会变——加 "(Live)"/"(Explicit)" 尾缀、中译括号后缀
 * 「原名 (译名)」、feat 拆分变化——按原始字符串比较会把它们误判成切歌(generation++,
 * 外推状态与门控全部重置)。这里在归一化后做身份相等判定。
 *
 * 规则(保守:宁可漏判"同曲"判成"变化",不可反向——漏切歌会让旧歌词滞留):
 * - 标题归一:trim、连续空白折叠、引号统一、小写;
 * - 尾缀剥离仅限两类可证实的噪声:①内容命中噪声词表(live/explicit/伴奏等,
 *   "Part 1/Part 2" 这类区分性尾缀**不**剥);②译名对——括号内容与剩余标题的
 *   文字族不同(假名↔汉字↔拉丁,如「嘘つき… (谎言是恋爱的伊始)」);
 * - feat 归一:按 feat/ft/featuring 切分后集合比较,顺序不敏感;
 * - 歌手:任一侧为空=未知放行,两侧都有则分隔符切分集合相等才算同曲。
 * 有一侧标题为空白 → 不是同曲(保持"变化",首次出现必须触发 generation++)。
 */
internal fun isSameTrackIdentity(
    oldTitle: String,
    oldArtist: String,
    newTitle: String,
    newArtist: String
): Boolean {
    if (oldTitle.isBlank() || newTitle.isBlank()) return false
    if (!titleMatches(stripDecorations(oldTitle), stripDecorations(newTitle))) return false
    val oldArtists = splitArtists(oldArtist)
    val newArtists = splitArtists(newArtist)
    if (oldArtists.isEmpty() || newArtists.isEmpty()) return true
    return oldArtists == newArtists
}

private fun titleMatches(old: String, new: String): Boolean =
    old == new || splitFeatured(old) == splitFeatured(new)

/** 反复剥离尾部的噪声/译名括号组;全剥空则放弃(返回原文)。 */
private fun stripDecorations(title: String): String {
    var current = normalizeTitle(title)
    while (true) {
        val stripped = stripNoiseBracketGroup(current) ?: break
        if (stripped.isBlank()) break
        current = stripped
    }
    return current.ifBlank { normalizeTitle(title) }
}

private val BRACKET_NOISE_WORDS = setOf(
    "live", "explicit", "clean", "remaster", "remastered", "deluxe", "acoustic",
    "instrumental", "inst", "ver", "version", "tv", "short", "off vocal", "offvocal",
    "karaoke", "cover",
    // 中文噪声词(归一化后小写无关,直接精确比较)
    "伴奏", "纯音乐", "演奏版", "翻唱", "重制版", "现场版", "电视版"
)

/** 返回剥掉单个尾部括号组后的标题;不是噪声/译名形态则返回 null。 */
private fun stripNoiseBracketGroup(title: String): String? {
    val closes = ")]）】〕》"
    if (title.isEmpty() || !closes.contains(title.last())) return null
    val opens = "([（【〔〈《"
    val openChar = opens.getOrNull(closes.indexOf(title.last())) ?: return null
    val openIndex = title.lastIndexOf(openChar)
    if (openIndex <= 0) return null
    val head = title.substring(0, openIndex).trim()
    if (head.isEmpty()) return null
    val content = title.substring(openIndex + 1, title.length - 1).trim().lowercase()
    val isNoise = content in BRACKET_NOISE_WORDS || content.replace(" ", "") in BRACKET_NOISE_WORDS
    val isTranslationPair = isTranslationPair(head, content)
    return if (isNoise || isTranslationPair) head else null
}

/** 译名对:括号内容与剩余标题属于不同文字族(假名/汉字/拉丁)。 */
private fun isTranslationPair(head: String, bracketContent: String): Boolean {
    if (bracketContent.isEmpty()) return false
    val headKana = containsKana(head)
    val headHan = containsHan(head)
    val headLatin = containsLatin(head)
    val innerKana = containsKana(bracketContent)
    val innerHan = containsHan(bracketContent)
    val innerLatin = containsLatin(bracketContent)
    // 假名标题 + 汉字译名;拉丁标题 + 假名/汉字译名;汉字标题 + 拉丁译名。
    return (headKana && !innerKana && innerHan) ||
        (headLatin && !headHan && (innerKana || innerHan)) ||
        (headHan && !headKana && innerLatin && !innerHan)
}

private fun containsKana(value: String): Boolean =
    value.any { it.code in 0x3040..0x30FF || it.code in 0xFF66..0xFF9F }

private fun containsHan(value: String): Boolean =
    value.any { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }

private fun containsLatin(value: String): Boolean = value.any { it.code in 0x41..0x7A || it.code in 0xC0..0x24F }

private fun splitFeatured(title: String): Set<String> {
    val head = title.split(Regex("(?i)\\s*(?:\\(|（)?\\b(?:feat|ft|featuring)\\.?\\s+")).first()
    return normalizeTitle(head).split(Regex("\\s*/\\s*|\\s*、\\s*")).filterTo(HashSet()) { it.isNotBlank() }
}

private fun splitArtists(artist: String): Set<String> =
    normalizeTitle(artist)
        .split(Regex("\\s*/\\s*|\\s*、\\s*|\\s*,\\s*|\\s*;\\s*"))
        .filterTo(HashSet()) { it.isNotBlank() }

private fun normalizeTitle(value: String): String =
    value.replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()
