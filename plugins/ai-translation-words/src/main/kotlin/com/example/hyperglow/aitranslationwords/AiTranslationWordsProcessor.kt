package com.example.hyperglow.aitranslationwords

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
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 一次翻译所需的配置快照（读取后不可变，便于纯函数决策与测试）。 */
internal data class TranslationWordsConfig(
    val targetLanguage: String,
    val skipLanguages: Set<String>,
    val skipExisting: Boolean,
    val forceOverride: Boolean,
    val wordTiming: Boolean,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val prompt: String,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
)

/**
 * 一次批量请求的发送口：生产实现走 [OpenAiClient]（真网络），测试注入假实现（可逐批成功/失败、
 * 可计数、不需要网络）。返回 null 表示该批失败（超时 / 非 2xx / 解析不出内容）。
 */
internal fun interface BatchSender {
    fun send(
        config: TranslationWordsConfig,
        meta: TrackMeta,
        lines: List<LineToTranslate>,
        expectedTokenCounts: Map<Int, Int>,
        budgetMs: Int,
    ): Map<Int, ParsedLine>?
}

/**
 * AI 逐字翻译处理器：把当前歌词送给用户配置的 OpenAI 兼容接口翻译，按行回写
 * `translation`；对带词时间轴的行，同时把译文片段按源词窗口回写 `translationWords`
 * （宿主用 `PluginLyricField.TRANSLATION_WORDS` 点亮辅助文字行的逐字效果）。
 *
 * 管线：`TRANSLATION_ENHANCEMENT` 阶段、`PATCH` 模式——不触碰 text/words/roma/secondary，
 * 也不改动行数。行没有可用词时间轴（逐行源、或词文本全空）时只写 `translation`。
 *
 * 与兄弟插件 `plugins/ai-translation` 的差异除「逐字」外还有**分批 + 渐进**（真机实测教训：
 * 整首一次发时，几十行的歌会让 DeepSeek 端到端超过单次预算而整首失败，日志只有
 * `translation request failed ... (cooldown 10min)`，看起来就像插件没在工作）：
 * 待译行按 [BATCH_LINES] 切批、逐批请求，单批预算 [PER_BATCH_BUDGET_MS]、总预算
 * [TOTAL_BUDGET_MS]（宿主每处理器 40s 上限，留出合并余量）；**缓存允许部分命中、也存部分结果**
 * ——已完成的批落盘后，下一次链轮次只补剩下的行，长歌因此可以跨轮次逐批完成。
 *
 * 失败处理：**只有整轮一条都没成功**才进入内存失败退避（[FAIL_COOLDOWN_MS]，短退避：
 * 切歌取消也会走这条路径，长退避会让那首歌十分钟内不再重试）；部分成功不设退避，
 * 下一轮继续补缺。用户也可在缓存页删除条目强制重翻。
 */
internal class AiTranslationWordsProcessor(
    private val context: PluginContext,
    private val cache: TranslationWordsCache,
    private val sender: BatchSender = BatchSender { config, meta, lines, expected, budget ->
        OpenAiClient(config.baseUrl).translate(config, meta, lines, expected, budget)
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LyricProcessorExtension {

    override val id: String = "aitranslationwords.processor"

    override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

    /** 失败退避表（ckey → 最近失败时刻）；插件实例常驻进程，内存即可。 */
    private val failCooldown = ConcurrentHashMap<String, Long>()

    private var missingConfigLogged = false

    override fun processResult(
        song: PluginSong,
        processingContext: PluginProcessingContext,
    ): PluginSongResult? {
        val config = readConfig() ?: return null
        val rows = song.lyrics ?: return null
        if (rows.isEmpty()) return null

        // 已有翻译的歌曲：skip_existing 开启且未强制覆盖 → 整首跳过。
        if (config.skipExisting && !config.forceOverride &&
            rows.any { !it.translation.isNullOrBlank() }
        ) {
            return null
        }

        // 源语言跳过：识别出的主导语言在勾选列表里 → 整首跳过（空列表=不过滤）。
        // 记 info 级：这是用户自己的设置造成的「整首没动静」，debug 级在默认日志级别下
        // 完全看不见，真机上就表现为「插件没在用」。
        if (config.skipLanguages.isNotEmpty()) {
            val lang = LanguageDetect.detect(rows.mapNotNull { it.text }.joinToString("\n"))
            if (lang.code in config.skipLanguages) {
                context.logger.info("skip song: detected ${lang.code} in skip_languages")
                return null
            }
        }

        val target = LanguageDetect.normalizeTarget(config.targetLanguage)
        val need = rows.withIndex().filter { (_, row) ->
            val text = row.text
            !text.isNullOrBlank() &&
                !LanguageDetect.isSkippable(text, target) &&
                (config.forceOverride || row.translation.isNullOrBlank())
        }
        if (need.isEmpty()) return null

        // 逐字时间行：只有开关打开且行确有可用词时间轴时才带 tokens 请求。
        val tokensByIndex: Map<Int, List<TimedToken>> = if (config.wordTiming) {
            need.mapNotNull { (index, row) -> timedTokens(row)?.let { index to it } }.toMap()
        } else {
            emptyMap()
        }
        val expectedTokenCounts = tokensByIndex.mapValues { (_, tokens) -> tokens.size }

        val meta = trackMeta(song, processingContext)
        val ckey = cacheKey(meta, config)

        // 缓存逐行取用（允许部分命中）：命中行直接回填，只对缺失行发请求——长歌跨轮次逐批完成。
        val cachedByText = cache.get(ckey)
            ?.let { (it as CachedOutcome.Hit).lines }
            .orEmpty()
            .associateBy { it.text }
        val resolved = LinkedHashMap<Int, CachedLine>()
        val missing = ArrayList<IndexedValue<PluginLyricLine>>()
        need.forEach { (index, row) ->
            val hit = cachedByText[row.text!!]
            if (hit != null && cachedLineUsable(hit, expectedTokenCounts[index])) {
                resolved[index] = hit
            } else {
                missing += IndexedValue(index, row)
            }
        }

        var freshLines = 0
        if (missing.isNotEmpty() && !inFailCooldown(ckey)) {
            val fresh = translateInBatches(config, meta, missing, tokensByIndex, expectedTokenCounts)
            freshLines = fresh.size
            resolved.putAll(fresh)
            if (fresh.isEmpty()) {
                // 整轮一条都没成功（超时/端点异常/链被切歌取消）→ 短退避，下一轮再试。
                failCooldown[ckey] = nowMs()
                context.logger.warn(
                    "translation request failed for '${meta.title ?: "?"}' " +
                        "(${missing.size} line(s) pending, cooldown ${FAIL_COOLDOWN_MS / 60_000}min)"
                )
            }
        }
        if (resolved.isEmpty()) return null

        val wordsByIndex = alignedWords(need, resolved, tokensByIndex)
        if (freshLines > 0) {
            // 只缓存并集（已命中的 + 本轮新译的）：下一次链轮次据此只补缺。
            cache.put(
                ckey,
                resolved.values.toList(),
                displayTitle(meta),
                hitSummary(config, resolved.size, missing.size - freshLines),
            )
            context.logger.info(
                "translated ${freshLines} line(s) this run (${resolved.size} ready, " +
                    "${missing.size - freshLines} pending, ${wordsByIndex.size} with word timing) " +
                    "for '${meta.title ?: "?"}' -> ${config.targetLanguage}"
            )
        }

        return patch(song, resolved, wordsByIndex)
    }

    /**
     * 分批翻译：按 [BATCH_LINES] 切批、逐批请求，单批预算 [PER_BATCH_BUDGET_MS]、
     * 总预算 [TOTAL_BUDGET_MS]（宿主每处理器 40s 上限）。某批失败不终止其余批（毒批不会
     * 卡住后面的行：成功的批照常落缓存，下一轮从缺的那批继续），连续
     * [MAX_CONSECUTIVE_BATCH_FAILURES] 批失败则提前收手，不把预算耗在不可用的端点上。
     */
    private fun translateInBatches(
        config: TranslationWordsConfig,
        meta: TrackMeta,
        missing: List<IndexedValue<PluginLyricLine>>,
        tokensByIndex: Map<Int, List<TimedToken>>,
        expectedTokenCounts: Map<Int, Int>,
    ): Map<Int, CachedLine> {
        val out = LinkedHashMap<Int, CachedLine>()
        val startedAt = nowMs()
        var offset = 0
        var consecutiveFailures = 0
        while (offset < missing.size && consecutiveFailures < MAX_CONSECUTIVE_BATCH_FAILURES) {
            val remainingMs = TOTAL_BUDGET_MS - (nowMs() - startedAt)
            if (remainingMs < MIN_BATCH_BUDGET_MS) break
            val batch = missing.subList(offset, minOf(offset + BATCH_LINES, missing.size))
            val parsed = runCatching {
                sender.send(
                    config = config,
                    meta = meta,
                    lines = batch.map { (index, row) ->
                        LineToTranslate(index, row.text!!, tokensByIndex[index]?.map { it.text })
                    },
                    expectedTokenCounts = batch
                        .mapNotNull { (index, _) -> expectedTokenCounts[index]?.let { index to it } }
                        .toMap(),
                    budgetMs = minOf(remainingMs, PER_BATCH_BUDGET_MS).toInt(),
                )
            }.getOrNull()
            if (parsed.isNullOrEmpty()) {
                consecutiveFailures++
            } else {
                consecutiveFailures = 0
                batch.forEach { (index, row) ->
                    parsed[index]?.let { out[index] = CachedLine(row.text!!, it.translation, it.fragments) }
                }
            }
            offset += batch.size
        }
        return out
    }

    /** 缓存行可用性：无片段（行级译文）恒可用；带片段时片段数必须等于当前词时间轴的词数。 */
    private fun cachedLineUsable(line: CachedLine, expectedTokenCount: Int?): Boolean {
        val fragments = line.fragments ?: return true
        return expectedTokenCount != null && fragments.size == expectedTokenCount
    }

    /**
     * 失败退避：同一曲目+配置在冷却期内不再请求。**没有失败记录即不在冷却中**——不要用
     * `0L` 当「从未失败」的哨兵（注入时钟从 0 起时 `nowMs() - 0 < 冷却` 恒成立，
     * 会把从未失败过的歌判成冷却中；生产时钟下只是被掩盖）。
     */
    private fun inFailCooldown(cacheKey: String): Boolean {
        val lastFail = failCooldown[cacheKey] ?: return false
        return nowMs() - lastFail < FAIL_COOLDOWN_MS
    }

    // ---- 回写 -------------------------------------------------------------

    /** 逐字片段 → 词级时间轴；行无词时间轴、条目无片段或对齐不出内容时不含该行。 */
    private fun alignedWords(
        need: List<IndexedValue<PluginLyricLine>>,
        resolved: Map<Int, CachedLine>,
        tokensByIndex: Map<Int, List<TimedToken>>,
    ): Map<Int, List<PluginWord>> = need.mapNotNull { (index, row) ->
        val cached = resolved[index] ?: return@mapNotNull null
        val fragments = cached.fragments ?: return@mapNotNull null
        val windows = tokensByIndex[index]?.map { it.window } ?: return@mapNotNull null
        val aligned = alignFragmentsToTokens(fragments, windows, row.begin, row.end)
        if (aligned.isEmpty()) return@mapNotNull null
        index to aligned.map { fragment ->
            PluginWord(
                begin = fragment.startMs,
                end = fragment.endMs,
                duration = fragment.endMs - fragment.startMs,
                text = fragment.text,
            )
        }
    }.toMap()

    /**
     * PATCH：仅覆盖待翻译行的 translation（缓存命中与线上结果共用）；
     * translationWords 只在本次真的对齐出片段时写入，否则保留行上已有的值。
     */
    private fun patch(
        song: PluginSong,
        resolved: Map<Int, CachedLine>,
        wordsByIndex: Map<Int, List<PluginWord>>,
    ): PluginSongResult {
        val patched = song.lyrics.orEmpty().mapIndexed { index, row ->
            val cached = resolved[index] ?: return@mapIndexed row
            if (cached.translation.isBlank()) return@mapIndexed row
            row.copy(
                translation = cached.translation,
                translationWords = wordsByIndex[index] ?: row.translationWords,
            )
        }
        val changedLyricFields = buildSet {
            add(PluginLyricField.TRANSLATION)
            if (wordsByIndex.isNotEmpty()) add(PluginLyricField.TRANSLATION_WORDS)
        }
        return PluginSongResult(
            song = song.copy(lyrics = patched),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = changedLyricFields,
        )
    }

    // ---- 配置与键 ---------------------------------------------------------

    private fun readConfig(): TranslationWordsConfig? {
        val config = context.config
        val apiKey = config.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val model = config.getString(KEY_MODEL, "")?.trim().orEmpty()
        if (apiKey.isEmpty() || model.isEmpty()) {
            if (!missingConfigLogged) {
                missingConfigLogged = true
                context.logger.info("translation idle: API key or model not configured")
            }
            return null
        }
        return TranslationWordsConfig(
            targetLanguage = config.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET)?.trim().orEmpty()
                .ifEmpty { DEFAULT_TARGET },
            skipLanguages = config.getStringSet(KEY_SKIP_LANGUAGES, emptySet()),
            skipExisting = config.getBoolean(KEY_SKIP_EXISTING, false),
            forceOverride = config.getBoolean(KEY_FORCE_OVERRIDE, false),
            wordTiming = config.getBoolean(KEY_WORD_TIMING, true),
            apiKey = apiKey,
            model = model,
            baseUrl = OpenAiClient.normalizeBaseUrl(config.getString(KEY_BASE_URL, null)),
            prompt = config.getString(KEY_PROMPT, "") ?: "",
            temperature = config.getFloat(KEY_TEMPERATURE, 1.0f),
            topP = config.getFloat(KEY_TOP_P, 1.0f),
            maxTokens = config.getLong(KEY_MAX_TOKENS, 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    private fun trackMeta(song: PluginSong, processingContext: PluginProcessingContext): TrackMeta {
        val media = processingContext.mediaInfo
        return TrackMeta(
            title = (media?.title ?: song.name)?.trim()?.takeIf { it.isNotEmpty() },
            artist = (media?.artist ?: song.artist)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 缓存键 = 曲目身份 + 全部影响结果的配置（含提示词、采样参数与逐字开关）的 SHA-256。
     * 逐字开关入键：关掉开关产生的纯译文与开着的逐字结果不是同一份数据。
     */
    internal fun cacheKey(meta: TrackMeta, config: TranslationWordsConfig): String {
        val raw = buildString {
            append(normalizeIdentity(meta.title))
            append('\u0000').append(normalizeIdentity(meta.artist))
            append('\u0000').append(config.targetLanguage)
            append('\u0000').append(config.model)
            append('\u0000').append(config.baseUrl)
            append('\u0000').append(config.prompt)
            append('\u0000').append(config.temperature)
            append('\u0000').append(config.topP)
            append('\u0000').append(config.wordTiming)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeIdentity(raw: String?): String =
        raw.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    private fun displayTitle(meta: TrackMeta): String {
        val title = meta.title ?: "?"
        return if (meta.artist.isNullOrBlank()) title else "$title — ${meta.artist}"
    }

    private fun hitSummary(config: TranslationWordsConfig, lineCount: Int, pendingCount: Int): String =
        "→ ${config.targetLanguage} · ${config.model} · $lineCount 行" +
            (if (pendingCount > 0) "（待补 $pendingCount）" else "") +
            if (config.wordTiming) " · 逐字" else ""

    private companion object {
        const val KEY_SKIP_LANGUAGES = "skip_languages"
        const val KEY_SKIP_EXISTING = "skip_existing"
        const val KEY_FORCE_OVERRIDE = "force_override"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_WORD_TIMING = "word_timing"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_BASE_URL = "base_url"
        const val KEY_PROMPT = "prompt"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_MAX_TOKENS = "max_tokens"

        const val DEFAULT_TARGET = "中文"

        /** 单批行数：整首一次发时几十行的歌会让端到端超过单次预算（真机实测整首失败）。 */
        const val BATCH_LINES = 12

        /** 单批预算：12 行级别的响应通常几秒内返回，12s 留足余量。 */
        const val PER_BATCH_BUDGET_MS = 12_000L

        /** 一轮处理器调用的总预算：宿主每处理器 40s 上限，留出合并余量。 */
        const val TOTAL_BUDGET_MS = 30_000L

        /** 剩余预算低于此值不再开新批（半截批的等待会撞宿主上限）。 */
        const val MIN_BATCH_BUDGET_MS = 4_000L

        /** 连续这么多批失败就收手，不把预算耗在不可用的端点上。 */
        const val MAX_CONSECUTIVE_BATCH_FAILURES = 2

        /**
         * 失败退避：**短**退避（原为 10 分钟）——切歌取消也会落到这条路径上，长退避会让
         * 那首歌十分钟内不再重试，观感就是「插件没在用」。整轮全败时 2 分钟内不重试，
         * 既挡住逐行源 15s 链调度的反复打网络，又不至于把一首歌闷死。
         */
        const val FAIL_COOLDOWN_MS = 2L * 60 * 1000
    }
}
