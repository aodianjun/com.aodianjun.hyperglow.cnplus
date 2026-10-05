package com.example.hyperglow.amllttml

import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult

/**
 * AMLL TTML 取词处理器：媒体信息 → 搜索匹配 → 取 TTML → 解析映射 → REPLACE 回写。
 *
 * 运行位置与约束（与 plugins/lyricfetch 同构）：
 * - 阶段 LYRIC_REPLACEMENT（整表替换，先于翻译增强类插件）；
 * - 宿主按会话调度插件链（逐行源每 15s 重跑），取词结果**必须缓存**；
 * - 每处理器 40s 上限：搜索 + 取词两个请求共享 30s 总预算，宁可不取词也不能超时。
 *
 * 缓存策略（与 lyricfetch 的差异）：缓存键**只含曲目身份 + API 地址**，不含渲染开关
 * （对唱/翻译/和声）——TTML 原文与开关无关，切开关应即时重映射而不是重新联网；
 * 「仅升级为逐字」的接受判断在取到之后统一做，被拒绝的命中同样入库（关掉开关立即生效）。
 */
internal class AmllTtmlProcessor(
    private val context: PluginContext,
    private val cache: AmllTtmlCache,
    private val clientFactory: (String) -> AmllTtmlClient = { base -> AmllTtmlClient(base) },
) : LyricProcessorExtension {

    override val id: String = "amllttml.processor"

    override val stage: PluginProcessorStage = PluginProcessorStage.LYRIC_REPLACEMENT

    override fun processResult(
        song: PluginSong,
        processingContext: PluginProcessingContext,
    ): PluginSongResult? {
        val query = buildQuery(song, processingContext) ?: return null

        val duet = context.config.getBoolean(KEY_DUET, true)
        val translation = context.config.getBoolean(KEY_TRANSLATION, true)
        val background = context.config.getBoolean(KEY_BACKGROUND, true)
        val upgradeOnly = context.config.getBoolean(KEY_UPGRADE_ONLY, true)
        val baseUrl = AmllTtmlClient.normalizeBaseUrl(
            context.config.getString(KEY_API_BASE, null)
        )

        // 只做升级时：当前已是逐字歌词就没有收益，直接透传（省一次网络往返）。
        if (upgradeOnly && currentHasWordLevel(song)) return null

        val key = query.cacheKey(baseUrl)
        val cached = cache.get(key)
        val (outcome, rows) = if (cached != null) {
            materialize(cached, duet, translation, background)
        } else {
            fetchAndCache(query, baseUrl, duet, translation, background)
        }

        val hit = outcome as? CachedOutcome.Hit ?: return null
        if (rows.isEmpty()) return null
        // 库中版本没有逐字覆盖而当前要求「仅升级」：不采用（命中已入库，切开关即时生效）。
        if (upgradeOnly && !TtmlMapper.hasWordLevel(rows)) return null

        context.logger.info(
            "fetched ${rows.size} lines from AMLL for '${query.title}' (${hit.matchedArtists})"
        )
        return PluginSongResult(
            song = song.copy(lyrics = rows),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.REPLACE,
            changedLyricFields = buildSet {
                add(PluginLyricField.TEXT)
                if (rows.any { !it.words.isNullOrEmpty() }) add(PluginLyricField.WORDS)
                if (rows.any { !it.translation.isNullOrBlank() }) add(PluginLyricField.TRANSLATION)
                if (rows.any { !it.roma.isNullOrBlank() }) add(PluginLyricField.ROMA)
            },
        )
    }

    // ---- 查询构造 ---------------------------------------------------------

    private fun buildQuery(song: PluginSong, processingContext: PluginProcessingContext): AmllQuery? {
        val media = processingContext.mediaInfo
        val title = (media?.title ?: song.name)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val artist = (media?.artist ?: song.artist).orEmpty()
        val album = (media?.album ?: song.album)?.trim()?.takeIf { it.isNotEmpty() }
        return AmllQuery(
            title = title,
            artists = splitArtists(artist),
            album = album,
        ).takeIf { it.isUsable }
    }

    /** 媒体会话的艺人是拼接串（"A/B"、"A、B"、"A; B"），拆开逐段参与匹配。 */
    private fun splitArtists(artist: String): List<String> =
        artist.split('/', ';', ',', '、', '&')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ---- 取词与缓存 -------------------------------------------------------

    /** 缓存命中后重建（映射很便宜，存 TTML 原文比存映射结果更小、更能容忍后续改进）。 */
    private fun materialize(
        outcome: CachedOutcome,
        duet: Boolean,
        translation: Boolean,
        background: Boolean,
    ): Pair<CachedOutcome?, List<PluginLyricLine>> = when (outcome) {
        is CachedOutcome.Miss -> null to emptyList()
        is CachedOutcome.Hit ->
            outcome to TtmlMapper.map(outcome.ttml, duet, translation, background)
    }

    private fun fetchAndCache(
        query: AmllQuery,
        baseUrl: String,
        duet: Boolean,
        translation: Boolean,
        background: Boolean,
    ): Pair<CachedOutcome?, List<PluginLyricLine>> {
        val key = query.cacheKey(baseUrl)
        val client = clientFactory(baseUrl)
        // 宿主每处理器 40s 上限：这里自留 30s 总预算（搜索 10s + 取词 16s 封顶），
        // 宁可不取词也不能把宿主链拖到超时被丢弃。
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS

        val candidates = runCatching {
            client.search(query, budgetFor(deadline, SEARCH_BUDGET_MS))
        }.getOrNull()
        val best = candidates?.let { AmllMatch.pickBest(query, it) }
        if (best == null) {
            cache.put(key, CachedOutcome.Miss, missTitle(query), MISS_SUMMARY)
            context.logger.info("no AMLL match for '${query.title}'")
            return null to emptyList()
        }

        val ttml = runCatching {
            client.fetchTtml(best.id, budgetFor(deadline, FETCH_BUDGET_MS))
        }.getOrNull()
        if (ttml.isNullOrBlank()) {
            cache.put(key, CachedOutcome.Miss, missTitle(query), MISS_SUMMARY)
            context.logger.warn("AMLL fetch failed for '${query.title}' (id=${best.id})")
            return null to emptyList()
        }

        val outcome = CachedOutcome.Hit(
            ttml = ttml,
            matchedTitle = best.musicNames.firstOrNull().orEmpty(),
            matchedArtists = best.artistNames.joinToString("/"),
        )
        cache.put(key, outcome, displayTitle(query), hitSummary(best))
        return outcome to TtmlMapper.map(ttml, duet, translation, background)
    }

    /** 剩余预算（至少 500ms），并受单个请求的封顶约束。 */
    private fun budgetFor(deadline: Long, capMs: Int): Int =
        (deadline - System.currentTimeMillis())
            .coerceAtLeast(500L)
            .coerceAtMost(capMs.toLong())
            .toInt()

    private fun currentHasWordLevel(song: PluginSong): Boolean =
        song.lyrics.orEmpty().any { row -> row.words?.size ?: 0 >= 2 }

    /** 缓存页展示用：标题带艺人，未命中加后缀。 */
    private fun displayTitle(query: AmllQuery): String {
        val artist = query.artists.firstOrNull()
        return if (artist.isNullOrBlank()) query.title else "${query.title} — $artist"
    }

    private fun missTitle(query: AmllQuery): String = displayTitle(query) + MISS_SUFFIX

    private fun hitSummary(best: AmllMatch.Candidate): String = buildString {
        append("AMLL TTML DataBase")
        best.artistNames.firstOrNull()?.let { append(" · ").append(it) }
        append(" · id=").append(best.id)
    }

    private companion object {
        const val KEY_API_BASE = "amll_ttml_api_base_url"
        const val KEY_DUET = "amll_ttml_duet"
        const val KEY_TRANSLATION = "amll_ttml_translation"
        const val KEY_BACKGROUND = "amll_ttml_background"
        const val KEY_UPGRADE_ONLY = "amll_ttml_upgrade_only"

        /** 全部请求共享的总预算；宿主上限 40s，留 10s 余量给解析与宿主合并。 */
        const val TOTAL_BUDGET_MS = 30_000L
        const val SEARCH_BUDGET_MS = 10_000
        const val FETCH_BUDGET_MS = 16_000

        const val MISS_SUFFIX = "（未命中）"
        const val MISS_SUMMARY = "AMLL 歌词库未找到可用歌词；重新取词请在缓存页删除本条目"
    }
}
