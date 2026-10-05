package com.example.hyperglow.aitranslation

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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 一次翻译所需的配置快照（读取后不可变，便于纯函数决策与测试）。 */
internal data class TranslationConfig(
    val targetLanguage: String,
    val skipLanguages: Set<String>,
    val skipExisting: Boolean,
    val forceOverride: Boolean,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val prompt: String,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
)

/**
 * AI 翻译处理器：把当前歌词送给用户配置的 OpenAI 兼容接口翻译，按行回写 `translation`。
 *
 * 管线：`TRANSLATION_ENHANCEMENT` 阶段、`PATCH` 模式（行数不变，只声明 TRANSLATION）——
 * 不触碰 text/words/roma，也不产出 translationWords（接口输出是行级译文，无词级时间轴）。
 *
 * 与官方插件（hyperlyric.ai.translation 1.0.0）的语义对齐点：跳过语言（字符集启发式识别）、
 * 跳过已有翻译 / 强制翻译（conflictsWith 互斥）、行级「无需翻译」过滤、按曲目缓存。
 * 上游内置化后的修复（「自动跳过语言为空时不生效」）在重写实现里天然正确：空集合=不过滤。
 *
 * 失败处理：请求失败**不写缓存**，改由内存失败节流退避（同一曲目+配置 10 分钟内不重试），
 * 避免逐行源的 15s 链调度反复打网络；用户也可在缓存页删除条目强制重翻。
 */
internal class AiTranslationProcessor(
    private val context: PluginContext,
    private val cache: TranslationCache,
    private val clientFactory: (String) -> OpenAiClient = { base -> OpenAiClient(base) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LyricProcessorExtension {

    override val id: String = "aitranslation.processor"

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

        val meta = trackMeta(song, processingContext)
        val ckey = cacheKey(meta, config)

        // 缓存：所有待翻译行都能按行文本命中才采用（部分命中则整首重翻，简单可预期）。
        cache.get(ckey)?.let { outcome ->
            val byText = (outcome as CachedOutcome.Hit).pairs.toMap()
            if (need.all { (_, row) -> byText.containsKey(row.text) }) {
                return patch(song, need, byText)
            }
        }

        // 失败退避：同一曲目+配置在冷却期内不再请求。
        val lastFail = failCooldown[ckey] ?: 0L
        if (nowMs() - lastFail < FAIL_COOLDOWN_MS) return null

        val translations = runCatching {
            clientFactory(config.baseUrl).translate(
                config = config,
                meta = meta,
                lines = need.map { (index, row) -> LineToTranslate(index, row.text!!) },
            )
        }.getOrNull()

        if (translations.isNullOrEmpty()) {
            failCooldown[ckey] = nowMs()
            context.logger.warn("translation request failed for '${meta.title ?: "?"}' (cooldown ${FAIL_COOLDOWN_MS / 60_000}min)")
            return null
        }

        val pairs = need.mapNotNull { (index, row) ->
            translations[index]?.takeIf { it.isNotBlank() }?.let { row.text!! to it }
        }
        if (pairs.isEmpty()) {
            failCooldown[ckey] = nowMs()
            return null
        }
        cache.put(ckey, CachedOutcome.Hit(pairs), displayTitle(meta), hitSummary(config, pairs.size))
        context.logger.info("translated ${pairs.size} line(s) for '${meta.title ?: "?"}' -> ${config.targetLanguage}")

        return patch(song, need, pairs.toMap())
    }

    // ---- 回写 -------------------------------------------------------------

    /** PATCH：仅覆盖待翻译行的 translation（缓存命中与线上结果共用，按行文本查找译文）。 */
    private fun patch(
        song: PluginSong,
        need: List<IndexedValue<PluginLyricLine>>,
        byText: Map<String, String>,
    ): PluginSongResult {
        val targets = need.map { it.index }.toSet()
        val patched = song.lyrics.orEmpty().mapIndexed { index, row ->
            if (index in targets) {
                val translation = row.text?.let { byText[it] }
                translation?.takeIf { it.isNotBlank() }?.let { row.copy(translation = it) } ?: row
            } else {
                row
            }
        }
        return PluginSongResult(
            song = song.copy(lyrics = patched),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = setOf(PluginLyricField.TRANSLATION),
        )
    }

    // ---- 配置与键 ---------------------------------------------------------

    private fun readConfig(): TranslationConfig? {
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
        return TranslationConfig(
            targetLanguage = config.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET)?.trim().orEmpty()
                .ifEmpty { DEFAULT_TARGET },
            skipLanguages = config.getStringSet(KEY_SKIP_LANGUAGES, emptySet()),
            skipExisting = config.getBoolean(KEY_SKIP_EXISTING, false),
            forceOverride = config.getBoolean(KEY_FORCE_OVERRIDE, false),
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

    /** 缓存键 = 曲目身份 + 全部影响结果的配置（含提示词与采样参数）的 SHA-256。 */
    internal fun cacheKey(meta: TrackMeta, config: TranslationConfig): String {
        val raw = buildString {
            append(normalizeIdentity(meta.title))
            append('\u0000').append(normalizeIdentity(meta.artist))
            append('\u0000').append(config.targetLanguage)
            append('\u0000').append(config.model)
            append('\u0000').append(config.baseUrl)
            append('\u0000').append(config.prompt)
            append('\u0000').append(config.temperature)
            append('\u0000').append(config.topP)
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

    private fun hitSummary(config: TranslationConfig, lineCount: Int): String =
        "→ ${config.targetLanguage} · ${config.model} · $lineCount 行"

    private companion object {
        const val KEY_SKIP_LANGUAGES = "skip_languages"
        const val KEY_SKIP_EXISTING = "skip_existing"
        const val KEY_FORCE_OVERRIDE = "force_override"
        const val KEY_TARGET_LANGUAGE = "target_language"
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
