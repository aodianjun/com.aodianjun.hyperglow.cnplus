package com.example.hyperglow.amllttml

import java.util.Locale

/** 在线取词用的曲目查询信息（来自宿主 PluginProcessingContext.mediaInfo，回落到 PluginSong 字段）。 */
internal data class AmllQuery(
    val title: String,
    val artists: List<String>,
    val album: String?,
) {
    val isUsable: Boolean get() = title.isNotBlank()

    /** 会话内缓存键：同一首歌（标题/艺人）只取一次；album 不参与（部分来源缺失）。 */
    fun cacheKey(baseUrl: String): String = buildString {
        append(AmllMatch.normalize(title))
        append('|')
        append(artists.joinToString(",") { AmllMatch.normalize(it) })
        append('|')
        append(AmllMatch.normalize(baseUrl))
    }
}

/**
 * 曲目匹配打分：标题 + 艺人两维度（AMLL TTML DataBase 的检索结果不含时长，
 * 无法像 lyricfetch 那样用时长硬否决）。
 *
 * 语义与 plugins/lyricfetch 的 TrackMatch 同源（其又对齐 Lyricify-Lyrics-Helper 的
 * CompareHelper）：标题权重最高、**双方都有艺人却毫无交集时硬否决**——实测教训是
 * 同名翻唱/伴奏的标题归一化后与原文完全相等，只靠标题会误选。
 */
internal object AmllMatch {

    /** 低于该分不采信，宁可不出词也不塞错词。 */
    const val MIN_SCORE = 0.62

    /** 单条检索结果（DataBase 的 SongItem 子集）。 */
    data class Candidate(
        val id: Long,
        val musicNames: List<String>,
        val artistNames: List<String>,
        val albumNames: List<String>,
    )

    fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        // 去掉括号内容（版本注记 Live/Remastered/伴奏 等）
        s = s.replace(Regex("""[\(（\[【].*?[\)）\]】]"""), "")
        // 去掉 feat./ft. 及其后内容
        s = s.replace(Regex("""\b(feat|ft|featuring)\b.*"""), "")
        // 只保留字母、数字与 CJK，其余（空格/标点）全部剔除
        s = s.filter { it.isLetterOrDigit() }
        return s
    }

    /**
     * 只剥括号内容与 feat. 从句，**保留空格与标点**（搜索变体 2）。
     *
     * AMLL 库检索近乎精确匹配：库里存了带空格标点的拉丁标题（如 "Orchelia's vox"）时，
     * 播放器标题多一个 "(feat. ...)" 后缀也会 0 结果；该变体正是为这种形态准备的。
     */
    internal fun stripDecorations(raw: String): String {
        var s = raw.trim()
        // 先剥括号（含括号内的 feat. 从句），再剥括号外的 feat. 从句
        s = s.replace(Regex("""[\(（\[【].*?[\)）\]】]"""), " ")
        s = s.replace(Regex("""(?i)\b(feat|ft|featuring)\b.*"""), "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 搜索标题变体，按序尝试（去空去重）：
     * 1. 原文（trim 后）——库里存了带后缀/带标点的版本时，原文才能区分 Live/Remastered；
     * 2. [stripDecorations]——只剥括号与 feat. 从句，保留空格与标点；
     * 3. [normalize]——全量规范化（仅字母数字 CJK）。
     *
     * 起因：播放器元数据标题带版本后缀（如「蝴蝶 (Cocoon Broken)」）时，原文检索在
     * AMLL 库 0 结果，必须逐级回退；但反过来库里若真有带后缀的版本，只有原文能区分。
     */
    internal fun searchTitleVariants(raw: String): List<String> =
        listOf(raw.trim(), stripDecorations(raw), normalize(raw))
            .filter { it.isNotEmpty() }
            .distinct()

    /** 标题对多个候选名的最高分（DataBase 里同一首歌可能有多个别名）。 */
    fun titleScore(query: String, candidates: List<String>): Double =
        candidates.maxOfOrNull { titleScoreOne(query, it) } ?: 0.0

    private fun titleScoreOne(query: String, candidate: String): Double {
        val a = normalize(query)
        val b = normalize(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) {
            val shorter = minOf(a.length, b.length)
            val longer = maxOf(a.length, b.length)
            // 短串至少占长串 60% 才算包含关系，避免 "hello" 命中 "hello goodbye world"
            if (shorter * 10 >= longer * 6) return 0.75
        }
        return 0.0
    }

    fun artistScore(query: List<String>, candidate: List<String>): Double {
        val q = query.map { normalize(it) }.filter { it.isNotEmpty() }
        val c = candidate.map { normalize(it) }.filter { it.isNotEmpty() }
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q.any { it in c }) return 1.0
        if (c.any { cand -> q.any { it.contains(cand) || cand.contains(it) } }) return 0.6
        return 0.0
    }

    /** 综合打分；标题不匹配或**艺人完全不符**时返回 null（调用方丢弃该候选）。 */
    fun score(query: AmllQuery, candidate: Candidate): Double? {
        val title = titleScore(query.title, candidate.musicNames)
        if (title <= 0.0) return null
        val artist = artistScore(query.artists, candidate.artistNames)
        // 双方都给了艺人却毫无交集：这是翻唱/伴奏/同名异曲，直接否决。
        if (query.artists.any { it.isNotBlank() } && candidate.artistNames.any { it.isNotBlank() } &&
            artist <= 0.0
        ) {
            return null
        }
        return title * 0.65 + artist * 0.35
    }

    /** 在候选里挑最高分；低于阈值或硬否决的丢弃。 */
    fun pickBest(query: AmllQuery, candidates: List<Candidate>): Candidate? =
        candidates
            .mapNotNull { candidate ->
                score(query, candidate)?.let { score -> candidate to score }
            }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
}
