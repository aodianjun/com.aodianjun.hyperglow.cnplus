package com.example.hyperglow.lyricfetch

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import com.lidesheng.hyperlyric.plugin.api.PluginWord

/**
 * 在线歌词增强插件：把 accompanist-lyrics-core 的**多格式解析**与
 * Lyricify-Lyrics-Helper 的**在线取词**能力合体，做成 HyperLyric 插件。
 *
 * 为什么是这个形态：插件 API 只暴露歌词处理器（拿到的是已结构化的 PluginSong），
 * 所以"解析能力"要有输入，只能由插件自己去在线取原始歌词——取回后解析成行/词，
 * 以 REPLACE 回写宿主。这正是两个库能力的可达形态。
 *
 * 运行位置与约束：
 * - 阶段 LYRIC_REPLACEMENT（整表替换，先于翻译增强类插件）；
 * - 宿主按会话调度插件链（逐行源每 15s 重跑），因此取词结果**必须缓存**；
 * - 每处理器 40s 上限：三个来源串行、单请求 4s 连接 + 6s 读超时，且命中即停。
 *
 * 缓存：走宿主 `PluginCache`（声明 `cacheScopes` 才不是 Noop；见 manifest），
 * 并按 [PluginCacheExtension] 契约暴露给宿主的缓存管理页——用户能看到取过哪些歌、
 * 删单条或清空（匹配错了不必重启 App）。见 [LyricCache]。
 */
class LyricFetchPlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("lyric fetch plugin loaded (host api ${context.hostApiVersion})")
        val cache = LyricCache(context.cache)
        context.registerExtension(LyricFetchProcessor(context, cache))
        context.registerExtension(LyricCacheExtension(cache))
    }
}

/** 自动模式下按"逐字优先"排序；显式选择只用一个来源。 */
internal fun defaultProviders(pref: String): List<LyricProvider> = when (pref) {
    "netease" -> listOf(NeteaseProvider)
    "qq" -> listOf(QqMusicProvider)
    "lrclib" -> listOf(LrclibProvider)
    // 自动：网易云优先（唯一免鉴权的逐字来源），其次 QQ（行级），最后 LRCLIB（行级）
    else -> listOf(NeteaseProvider, QqMusicProvider, LrclibProvider)
}

/**
 * 缓存管理扩展：宿主缓存页据此列出/删除本插件的取词缓存。
 * `id` 与 manifest 的 `cacheScopes[0].id` 一致（宿主按插件取第一个缓存扩展，
 * 但保持一致便于日志与后续按 scope 匹配）。
 */
internal class LyricCacheExtension(private val cache: LyricCache) : PluginCacheExtension {

    override val id: String = SCOPE_ID

    override fun listEntries(): List<PluginCacheEntry> = cache.entries()

    override fun clearAll() = cache.clear()

    override fun clearEntry(entryId: String): Boolean = cache.clearEntry(entryId)

    internal companion object {
        const val SCOPE_ID = "lyricfetch_songs"
    }
}

/** 处理器（internal 以便离线单测注入来源）。 */
internal class LyricFetchProcessor(
    private val context: PluginContext,
    private val cache: LyricCache,
    private val providers: (String) -> List<LyricProvider> = ::defaultProviders,
) : LyricProcessorExtension {

    override val id: String = "lyricfetch.processor"

    override val stage: PluginProcessorStage = PluginProcessorStage.LYRIC_REPLACEMENT

    override fun processResult(song: PluginSong, processingContext: PluginProcessingContext): PluginSongResult? {
        val query = buildQuery(song, processingContext) ?: return null

        val upgradeOnly = context.config.getBoolean(KEY_UPGRADE_ONLY, true)
        val wantTranslation = context.config.getBoolean(KEY_TRANSLATION, true)
        val mergeSyllables = context.config.getBoolean(KEY_MERGE_SYLLABLES, true)
        val providerPref = context.config.getString(KEY_PROVIDER, VALUE_AUTO) ?: VALUE_AUTO

        // 只做升级时：当前已是逐字歌词就没有收益，直接透传（省一次网络往返）。
        if (upgradeOnly && currentHasWordLevel(song)) return null

        val (raw, lines) = resolve(query, providerPref, upgradeOnly, wantTranslation, mergeSyllables)
        if (raw == null || lines.isEmpty()) return null

        val rows = mapRows(lines, song.lyrics.orEmpty())
        if (rows.isEmpty()) return null

        context.logger.info(
            "fetched ${rows.size} lines from ${raw.providerId} for '${query.title}' " +
                "(${raw.matchedArtists})"
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

    private fun buildQuery(song: PluginSong, processingContext: PluginProcessingContext): TrackQuery? {
        val media = processingContext.mediaInfo
        val title = (media?.title ?: song.name)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val artist = (media?.artist ?: song.artist).orEmpty()
        val album = (media?.album ?: song.album)?.trim()?.takeIf { it.isNotEmpty() }
        val duration = (media?.duration ?: song.duration).takeIf { it > 0L }
        return TrackQuery(
            title = title,
            artists = splitArtists(artist),
            album = album,
            durationMs = duration,
        ).takeIf { it.isUsable }
    }

    /** 媒体会话的艺人是拼接串（"A/B"、"A、B"、"A; B"），拆开逐段参与匹配。 */
    private fun splitArtists(artist: String): List<String> =
        artist.split('/', ';', ',', '、', '&')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ---- 取词与缓存 -------------------------------------------------------

    /**
     * 取词（带缓存）：先查缓存，未命中再按来源顺序取，命中即停并把结果写回缓存。
     * 未命中同样缓存（负缓存），避免逐行源每 15s 重跑链时反复打网络。
     */
    private fun resolve(
        query: TrackQuery,
        providerPref: String,
        upgradeOnly: Boolean,
        wantTranslation: Boolean,
        mergeSyllables: Boolean,
    ): Pair<RawLyrics?, List<ParsedLine>> {
        val key = cacheKeyOf(query, providerPref, upgradeOnly, wantTranslation, mergeSyllables)
        cache.get(key)?.let { return materialize(it, mergeSyllables, wantTranslation) }

        val accept: (List<ParsedLine>) -> Boolean = { lines ->
            lines.isNotEmpty() && (!upgradeOnly || LyricsPipeline.hasWordLevel(lines))
        }
        var stored: CachedOutcome = CachedOutcome.Miss
        // 宿主每处理器 40s 上限：这里自留 30s 总预算，按剩余时间逐来源下发，
        // 宁可不取词也不能把宿主链拖到超时被丢弃。
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        for (provider in providers(providerPref)) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining < MIN_PROVIDER_BUDGET_MS) break
            val raw = runCatching { provider.fetch(query, remaining) }.getOrNull() ?: continue
            val lines = LyricsPipeline.parse(raw, mergeSyllables, wantTranslation)
            if (accept(lines)) {
                stored = CachedOutcome.Hit(raw)
                cache.put(
                    key = key,
                    outcome = stored,
                    title = displayTitle(query),
                    summary = hitSummary(provider, lines),
                )
                return stored.raw() to lines
            }
        }
        cache.put(
            key = key,
            outcome = stored,
            title = displayTitle(query) + MISS_SUFFIX,
            summary = MISS_SUMMARY,
        )
        return null to emptyList()
    }

    /** 缓存命中后重建（解析很便宜，存原文比存解析结果更小、更能容忍后续解析改进）。 */
    private fun materialize(
        outcome: CachedOutcome,
        mergeSyllables: Boolean,
        wantTranslation: Boolean,
    ): Pair<RawLyrics?, List<ParsedLine>> = when (outcome) {
        is CachedOutcome.Miss -> null to emptyList()
        is CachedOutcome.Hit -> outcome.raw to
            LyricsPipeline.parse(outcome.raw, mergeSyllables, wantTranslation)
    }

    private fun CachedOutcome.raw(): RawLyrics? = (this as? CachedOutcome.Hit)?.raw

    private fun cacheKeyOf(
        query: TrackQuery,
        providerPref: String,
        upgradeOnly: Boolean,
        wantTranslation: Boolean,
        mergeSyllables: Boolean,
    ): String = query.cacheKey + "|" + providerPref + "|" + upgradeOnly + "|" +
        wantTranslation + "|" + mergeSyllables

    /** 缓存页展示用：标题带艺人，未命中加后缀。 */
    private fun displayTitle(query: TrackQuery): String {
        val artist = query.artists.firstOrNull()
        return if (artist.isNullOrBlank()) query.title else "${query.title} — $artist"
    }

    private fun hitSummary(provider: LyricProvider, lines: List<ParsedLine>): String = buildString {
        append(provider.displayName)
        append(" · ").append(lines.size).append(" 行")
        if (LyricsPipeline.hasWordLevel(lines)) append(" · 逐字")
        if (lines.any { !it.translation.isNullOrBlank() }) append(" · 含翻译")
    }

    private fun currentHasWordLevel(song: PluginSong): Boolean =
        song.lyrics.orEmpty().any { row -> row.words?.size ?: 0 >= 2 }

    // ---- 回写 -------------------------------------------------------------

    /**
     * 解析结果 → 插件行表。两处刻意对齐宿主语义：
     * - 行角色写进 metadata 的 "role"（LEAD/BG），与 Spicy 文档桥同一约定，
     *   宿主 selectActiveRow/nextLeadRow 依赖它；
     * - 对唱分侧（isAlignedRight）按时间就近从原行继承——REPLACE 换表不该丢掉
     *   生产者推导出的左右分侧（宿主 diff 会把它当成变化覆盖回去）。
     */
    private fun mapRows(lines: List<ParsedLine>, previous: List<PluginLyricLine>): List<PluginLyricLine> =
        lines.map { line ->
            PluginLyricLine(
                begin = line.beginMs,
                end = line.endMs,
                duration = (line.endMs - line.beginMs).coerceAtLeast(0L),
                isAlignedRight = inheritAlignedRight(line.beginMs, previous),
                metadata = PluginMetadata(
                    values = mapOf(META_ROLE to if (line.isAccompaniment) ROLE_BG else ROLE_LEAD)
                ),
                text = line.text,
                words = line.words.takeIf { it.isNotEmpty() }?.map { word ->
                    PluginWord(
                        begin = word.beginMs,
                        end = word.endMs,
                        duration = (word.endMs - word.beginMs).coerceAtLeast(0L),
                        text = word.text,
                    )
                },
                translation = line.translation,
                roma = line.phonetic,
            )
        }

    private fun inheritAlignedRight(beginMs: Long, previous: List<PluginLyricLine>): Boolean {
        var best: PluginLyricLine? = null
        var bestDelta = Long.MAX_VALUE
        for (row in previous) {
            val delta = kotlin.math.abs(row.begin - beginMs)
            if (delta < bestDelta) {
                bestDelta = delta
                best = row
            }
        }
        return best != null && bestDelta <= ALIGNMENT_INHERIT_TOLERANCE_MS && best.isAlignedRight
    }

    private companion object {
        const val KEY_UPGRADE_ONLY = "lyricfetch_upgrade_only"
        const val KEY_TRANSLATION = "lyricfetch_translation"
        const val KEY_MERGE_SYLLABLES = "lyricfetch_merge_syllables"
        const val KEY_PROVIDER = "lyricfetch_provider"

        const val VALUE_AUTO = "auto"

        const val META_ROLE = "role"
        const val ROLE_LEAD = "LEAD"
        const val ROLE_BG = "BG"

        const val ALIGNMENT_INHERIT_TOLERANCE_MS = 700L

        /** 全部来源共享的总预算；宿主上限 40s，留 10s 余量给解析与宿主合并。 */
        const val TOTAL_BUDGET_MS = 30_000L

        /** 剩余预算低于该值时不再尝试后续来源。 */
        const val MIN_PROVIDER_BUDGET_MS = 1_500

        const val MISS_SUFFIX = "（未命中）"
        const val MISS_SUMMARY = "在线来源未找到可用歌词；重新取词请在缓存页删除本条目"
    }
}
