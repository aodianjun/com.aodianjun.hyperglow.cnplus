package com.example.hyperglow.lyricfetch

import java.util.Locale

/** 在线取词用的曲目查询信息（来自宿主 PluginProcessingContext.mediaInfo，回落到 PluginSong 字段）。 */
internal data class TrackQuery(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
) {
    val isUsable: Boolean get() = title.isNotBlank()

    /**
     * 缓存键：同一首歌（标题/艺人）只取一次。
     *
     * **刻意不含时长**：宿主给的时长在逐行源（SuperLyric）上是「当前行的结束时间」
     * （见宿主 `LineStreamAggregator` 的说明），每次链重跑都不同——写进键里等于
     * 每 15s 给同一首歌新建一条缓存、永不命中（真机实证：一首歌堆了 21 条，
     * 每条都重新联网）。时长仍参与 [TrackMatch.score] 的候选打分与硬否决，
     * 只是不构成缓存身份。
     */
    val cacheKey: String
        get() = buildString {
            append(TrackMatch.normalize(title))
            append('|')
            append(artists.joinToString(",") { TrackMatch.normalize(it) })
        }
}

/**
 * 曲目匹配打分：标题/艺人/时长三维度，语义对齐 Lyricify-Lyrics-Helper 的
 * CompareHelper（NameMatch + ArtistMatch + DurationMatch）与 accompanist 的搜索匹配思路，
 * 按歌词场景收窄：标题权重最高，时长用于排除同名异曲。
 */
internal object TrackMatch {

    /** 候选（各 provider 的搜索结果）统一表示。 */
    data class Candidate(
        val id: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    /** 低于该分不采信，宁可不出词也不塞错词。 */
    const val MIN_SCORE = 0.62

    /** 双方都有时长且差超过该值：硬否决（同名翻唱/现场版必须靠时长分开）。 */
    const val HARD_REJECT_MS = 15_000L

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

    fun titleScore(query: String, candidate: String): Double {
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

    /** 返回 null 表示时长信息不足，该项不参与打分（权重重新归一化）。 */
    fun durationScore(queryMs: Long?, candidateMs: Long?): Double? {
        if (queryMs == null || candidateMs == null || queryMs <= 0L || candidateMs <= 0L) return null
        val delta = kotlin.math.abs(queryMs - candidateMs)
        return when {
            delta <= 3_000L -> 1.0
            delta <= 5_000L -> 0.7
            delta <= 8_000L -> 0.35
            delta <= HARD_REJECT_MS -> 0.1
            else -> -1.0 // 硬否决
        }
    }

    /** 综合打分；标题不匹配、时长硬否决、或**艺人完全不符**时返回 null（调用方丢弃该候选）。 */
    fun score(query: TrackQuery, candidate: Candidate): Double? {
        val title = titleScore(query.title, candidate.title)
        if (title <= 0.0) return null
        val artist = artistScore(query.artists, candidate.artists)
        // 双方都给了艺人却毫无交集：这是翻唱/伴奏/同名异曲，直接否决。
        // 实测教训：网易云搜索 "晴天 周杰伦" 首位是翻唱版 "晴天 (原唱 周杰伦) - RyaVocal"，
        // 标题归一化后与原文完全相等，仅靠标题+时长会误选。
        if (query.artists.any { it.isNotBlank() } && candidate.artists.any { it.isNotBlank() } &&
            artist <= 0.0
        ) {
            return null
        }
        val duration = durationScore(query.durationMs, candidate.durationMs)
        if (duration != null && duration < 0.0) return null

        return if (duration == null) {
            title * 0.65 + artist * 0.35
        } else {
            title * 0.55 + artist * 0.30 + duration * 0.15
        }
    }
}
