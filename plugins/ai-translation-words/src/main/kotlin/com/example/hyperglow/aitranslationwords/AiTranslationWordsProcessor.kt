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
 * AI 逐字翻译处理器：把当前歌词送给用户配置的 OpenAI 兼容接口翻译，按行回写
 * `translation`；对带词时间轴的行，同时把译文片段按源词窗口回写 `translationWords`
 * （宿主用 `PluginLyricField.TRANSLATION_WORDS` 点亮辅助文字行的逐字效果）。
 *
 * 管线：`TRANSLATION_ENHANCEMENT` 阶段、`PATCH` 模式——不触碰 text/words/roma/secondary，
 * 也不改动行数。行没有可用词时间轴（逐行源、或词文本全空）时只写 `translation`。
 *
 * 与兄弟插件 `plugins/ai-translation` 的差异只在「逐字」：请求多带 tokens、解析多读
 * segments、回写多一项 translationWords；跳过语言/跳过已有翻译/强制翻译/按曲目缓存
 * 的语义完全一致（空 skip_languages=不过滤）。
 *
 * 失败处理：请求失败**不写缓存**，改由内存失败节流退避（同一曲目+配置 10 分钟内不重试），
 * 避免逐行源的 15s 链调度反复打网络；用户也可在缓存页删除条目强制重翻。
 */
internal class AiTranslationWordsProcessor(
    private val context: PluginContext,
    private val cache: TranslationWordsCache,
    private val clientFactory: (String) -> OpenAiClient = { base -> OpenAiClient(base) },
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
        if (config.skipLanguages.isNotEmpty()) {
            val lang = LanguageDetect.detect(rows.mapNotNull { it.text }.joinToString("\n"))
            if (lang.code in config.skipLanguages) {
                context.logger.debug("skip song: detected ${lang.code} in skip_languages")
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

        // 缓存：所有待翻译行都能按行文本命中，且逐字行的片段数量与当前词时间轴一致才采用
        //（部分命中/片段数量对不上则整首重翻，简单可预期）。
        cache.get(ckey)?.let { outcome ->
            val byText = (outcome as CachedOutcome.Hit).lines.associateBy { it.text }
            if (cacheComplete(need, byText, expectedTokenCounts)) {
                val resolved = need.associate { (index, row) -> index to byText.getValue(row.text!!) }
                return patch(song, resolved, alignedWords(need, resolved, tokensByIndex))
            }
        }

        // 失败退避：同一曲目+配置在冷却期内不再请求。
        val lastFail = failCooldown[ckey] ?: 0L
        if (nowMs() - lastFail < FAIL_COOLDOWN_MS) return null

        val parsed = runCatching {
            clientFactory(config.baseUrl).translate(
                config = config,
                meta = meta,
                lines = need.map { (index, row) ->
                    LineToTranslate(index, row.text!!, tokensByIndex[index]?.map { it.text })
                },
                expectedTokenCounts = expectedTokenCounts,
            )
        }.getOrNull()

        if (parsed.isNullOrEmpty()) {
            failCooldown[ckey] = nowMs()
            context.logger.warn("translation request failed for '${meta.title ?: "?"}' (cooldown ${FAIL_COOLDOWN_MS / 60_000}min)")
            return null
        }

        val resolved = need.mapNotNull { (index, row) ->
            parsed[index]?.let { index to CachedLine(row.text!!, it.translation, it.fragments) }
        }.toMap()
        if (resolved.isEmpty()) {
            failCooldown[ckey] = nowMs()
            return null
        }

        cache.put(
            ckey,
            resolved.values.toList(),
            displayTitle(meta),
            hitSummary(config, resolved.size),
        )
        val wordsByIndex = alignedWords(need, resolved, tokensByIndex)
        context.logger.info(
            "translated ${resolved.size} line(s) (${wordsByIndex.size} with word timing) " +
                "for '${meta.title ?: "?"}' -> ${config.targetLanguage}"
        )

        return patch(song, resolved, wordsByIndex)
    }

    // ---- 回写 -------------------------------------------------------------

    /**
     * 缓存命中条件：每个待翻译行都有条目；逐字行若条目带片段，片段数必须恰好等于
     * 当前词时间轴的词数（词时间轴变过就重翻，避免片段错位）。
     */
    private fun cacheComplete(
        need: List<IndexedValue<PluginLyricLine>>,
        byText: Map<String, CachedLine>,
        expectedTokenCounts: Map<Int, Int>,
    ): Boolean = need.all { (index, row) ->
        val text = row.text ?: return@all false
        val cached = byText[text] ?: return@all false
        val expected = expectedTokenCounts[index] ?: return@all true
        val fragments = cached.fragments ?: return@all true
        fragments.size == expected
    }

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

    private fun hitSummary(config: TranslationWordsConfig, lineCount: Int): String =
        "→ ${config.targetLanguage} · ${config.model} · $lineCount 行" +
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

        /** 失败退避：翻译失败多为暂时性（限流/超时），10 分钟后允许重试。 */
        const val FAIL_COOLDOWN_MS = 10L * 60 * 1000
    }
}
