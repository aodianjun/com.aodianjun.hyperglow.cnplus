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

/**
 * `fetch` 的结局。三类必须分开上报：处理器只在「确定无匹配」时写 7 天负缓存，
 * 传输失败 / 预算耗尽只能写短退避——把瞬时网络故障写成长期 Miss 会毒化缓存键。
 */
internal sealed interface ProviderFetch {
    /** 取到可用歌词（内容是否满足升级条件由处理器再判）。 */
    data class Hit(val raw: RawLyrics) : ProviderFetch

    /** 请求成功返回、但确无可用结果：可以安全写长期负缓存。 */
    data object NoMatch : ProviderFetch

    /** 传输失败 / 预算耗尽 / 响应无法解析：结局未知，只能短退避后重试。 */
    data object Failure : ProviderFetch
}

/** 在线歌词来源。实现必须自我收敛：任何失败收敛为 [ProviderFetch.Failure]，不抛异常。 */
internal interface LyricProvider {
    val id: String
    val displayName: String

    /**
     * 取词。[budgetMs] 是本来源可用的总时间预算（含搜索 + 取词两个请求），
     * 由处理器按剩余总预算下发——宿主每处理器只有 40s，绝不能把预算花光在超时上。
     *
     * 返回值必须区分三类结局（见 [ProviderFetch]）：网络失败绝不能报成「无匹配」。
     */
    fun fetch(query: TrackQuery, budgetMs: Int): ProviderFetch

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
