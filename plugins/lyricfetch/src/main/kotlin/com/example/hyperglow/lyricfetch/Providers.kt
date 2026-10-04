package com.example.hyperglow.lyricfetch

/** 取词结果：原文 + 可选翻译，都是各 provider 的原始文本，交给解析管线处理。 */
internal data class RawLyrics(
    val providerId: String,
    val content: String,
    val translation: String? = null,
    val matchedTitle: String = "",
    val matchedArtists: String = "",
    val matchedDurationMs: Long? = null,
)

/** 在线歌词来源。实现必须自我收敛：任何失败返回 null，不抛异常。 */
internal interface LyricProvider {
    val id: String
    val displayName: String

    /**
     * 取词。[budgetMs] 是本来源可用的总时间预算（含搜索 + 取词两个请求），
     * 由处理器按剩余总预算下发——宿主每处理器只有 40s，绝不能把预算花光在超时上。
     */
    fun fetch(query: TrackQuery, budgetMs: Int): RawLyrics?

    /** 在候选里挑最高分；低于阈值或时长硬否决的丢弃。 */
    fun pickBest(query: TrackQuery, candidates: List<TrackMatch.Candidate>): TrackMatch.Candidate? =
        candidates
            .mapNotNull { candidate ->
                TrackMatch.score(query, candidate)?.let { score -> candidate to score }
            }
            .filter { it.second >= TrackMatch.MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
}
