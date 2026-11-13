package com.example.hyperglow.aisegment

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

/**
 * AI 切词处理器:`LYRIC_REPLACEMENT` 阶段、`REPLACE` 模式——把**超长**的歌词行交给
 * 用户配置的 OpenAI 兼容接口按语义切分成均衡的子行,整表替换行列表(可拆可并)。
 *
 * 设计要点:
 * - 只切 `text.length > max_line_chars` 的行;短行原样保留(它们不需要切,卡拉OK
 *   词表也不会被动过);
 * - 每行独立缓存(键 = 起始时间 + 行文本 + 配置哈希):流式源(逐行推送)每 15s 重跑
 *   一次链,行级缓存保证只有**新出现的长行**才触发 AI 请求,已切过的行直接复用;
 * - 校验:AI 输出的子行按序拼接(忽略空白)必须等于源行(忽略空白),不一致的源行
 *   整行回落不拆(缓存 `[原文]` 防止同一行反复请求);
 * - 子行时间窗按显示字符数占比切分源行窗口,词按字符区间归入子行并钳制时间戳
 *   (源行没有词表时按 CJK 逐字 / 西文按词合成),声明 WORDS 让宿主把行窗口
 *   对齐到词跨度(`normalizeMergedTimeline`);
 * - 失败处理:请求失败不写缓存,10 分钟内不重试(流式源 15s 一次的调度节奏,
 *   不加冷却会反复打网络)。
 */
internal class AiSegmentProcessor(
    private val context: PluginContext,
    private val cache: SegmentationCache,
    private val clientFactory: (String) -> SegmentClient = { base -> SegmentClient(base) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LyricProcessorExtension {

    override val id: String = "aisegment.processor"

    override val stage: PluginProcessorStage = PluginProcessorStage.LYRIC_REPLACEMENT

    /** 失败退避表(ckey → 最近失败时刻);插件实例常驻进程,内存即可。 */
    private val failCooldown = ConcurrentHashMap<String, Long>()

    private var missingConfigLogged = false

    override fun processResult(
        song: PluginSong,
        processingContext: PluginProcessingContext,
    ): PluginSongResult? {
        val config = readConfig() ?: return null
        val rows = song.lyrics ?: return null
        if (rows.isEmpty()) return null

        // 只挑「需要切」的行:超长行。短行原样(不拆的行不进缓存/不进 AI)。
        val targets = rows.withIndex().filter { (_, row) ->
            val text = row.text
            !text.isNullOrBlank() && text.length > config.maxChars
        }
        if (targets.isEmpty()) return null

        val meta = trackMeta(song, processingContext)
        val displayTitle = displayTitle(meta)

        // 行级缓存:命中直接复用切分结果;缺失的行才走 AI。
        val keys = LinkedHashMap<String, String>()           // rowKey → 行文本(保序去重)
        val keyByIndex = mutableMapOf<Int, String>()
        val childrenByKey = mutableMapOf<String, List<String>>()
        for ((index, row) in targets) {
            val text = row.text!!
            val key = rowKey(row, config)
            keys[key] = text
            keyByIndex[index] = key
            cache.get(key)?.let { childrenByKey[key] = it }
        }
        val missing = keys.filterKeys { it !in childrenByKey }
        val missingTargets = targets.filter { keyByIndex[it.index]!! in missing }

        if (missingTargets.isNotEmpty()) {
            // 只在有失败记录时退避:无记录(包括首次运行)绝不拦截
            val lastFail = failCooldown[ckey(config)]
            if (lastFail != null && nowMs() - lastFail < FAIL_COOLDOWN_MS) return null

            val numbered = missingTargets.mapIndexed { index, (_, row) ->
                RowToSegment(number = index + 1, text = row.text!!, maxChars = config.maxChars)
            }
            val parsed = runCatching {
                clientFactory(config.baseUrl).segment(config, meta, numbered)
            }.getOrNull()

            if (parsed == null) {
                failCooldown[ckey(config)] = nowMs()
                context.logger.warn(
                    "segmentation request failed for '${displayTitle}' " +
                        "(cooldown ${FAIL_COOLDOWN_MS / 60_000}min)"
                )
                return null
            }

            var usable = 0
            for (row in numbered) {
                val key = keys.entries.first { it.value == row.text }.key
                val validated = SegmentProtocol.validate(
                    row.number, row.text, parsed.bySource[row.number].orEmpty()
                )
                childrenByKey[key] = validated.children
                if (!validated.passthrough) usable++
                cache.put(
                    key,
                    validated.children,
                    title = displayTitle,
                    summary = segmentSummary(config, validated.children.size, validated.passthrough),
                )
            }
            if (usable == 0) {
                // AI 全部拒绝拆分:与失败同策略退避,避免 15s 一轮的重复请求。
                failCooldown[ckey(config)] = nowMs()
                return null
            }
            context.logger.info(
                "segmented $usable line(s) for '${displayTitle}' -> ${config.model}"
            )
        }

        return rebuild(song, targets, keyByIndex, childrenByKey)
    }

    // ---- 整表重建 ----------------------------------------------------------

    /**
     * REPLACE:源行表 → 切分后的行表。顺序保持;被切的行展开为子行,其余原样。
     * 翻译/注音/次级文本跟随**首个子行**(宿主 diff 按「新行携带的内容字段」记账)。
     */
    private fun rebuild(
        song: PluginSong,
        targets: List<IndexedValue<PluginLyricLine>>,
        keyByIndex: Map<Int, String>,
        childrenByKey: Map<String, List<String>>,
    ): PluginSongResult? {
        val childByIndex = targets.associate { (index, row) ->
            index to (childrenByKey[keyByIndex[index]!!] ?: listOf(row.text!!))
        }

        var anyWords = false
        var anyTranslation = false
        var anyRoma = false
        var anySecondary = false

        val rebuilt = song.lyrics.orEmpty().flatMapIndexed { index, row ->
            val childrenTexts = childByIndex[index]
                ?: return@flatMapIndexed listOf(row)
            val children = LineWindows.splitRow(row, childrenTexts)
            val lines = LineWindows.toPluginLines(row, children)
            lines.mapIndexed { childIndex, line ->
                if (!line.words.isNullOrEmpty()) anyWords = true
                if (childIndex != 0) return@mapIndexed line
                // 源行携带的歌词字段跟随首个子行(宿主 diff 只记新行携带的内容字段)
                var patched = line
                row.translation?.takeIf { it.isNotBlank() }?.let {
                    anyTranslation = true
                    patched = patched.copy(translation = it)
                }
                row.roma?.takeIf { it.isNotBlank() }?.let {
                    anyRoma = true
                    patched = patched.copy(roma = it)
                }
                row.secondary?.takeIf { it.isNotBlank() }?.let {
                    anySecondary = true
                    patched = patched.copy(secondary = it)
                }
                patched
            }
        }

        // 目标行全部回落不拆时表无变化 → null(不产生无意义 REPLACE)
        if (rebuilt == song.lyrics.orEmpty()) return null

        val changedLyricFields = buildSet {
            add(PluginLyricField.TEXT)
            if (anyWords) add(PluginLyricField.WORDS)
            if (anyTranslation) add(PluginLyricField.TRANSLATION)
            if (anyRoma) add(PluginLyricField.ROMA)
            if (anySecondary) add(PluginLyricField.SECONDARY)
        }

        return PluginSongResult(
            song = song.copy(lyrics = rebuilt),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.REPLACE,
            changedLyricFields = changedLyricFields,
        )
    }

    // ---- 配置与键 ----------------------------------------------------------

    private fun readConfig(): SegmentConfig? {
        val config = context.config
        val apiKey = config.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val model = config.getString(KEY_MODEL, "")?.trim().orEmpty()
        if (apiKey.isEmpty() || model.isEmpty()) {
            if (!missingConfigLogged) {
                missingConfigLogged = true
                context.logger.info("segmentation idle: API key or model not configured")
            }
            return null
        }
        return SegmentConfig(
            apiKey = apiKey,
            model = model,
            baseUrl = SegmentClient.normalizeBaseUrl(config.getString(KEY_BASE_URL, null)),
            maxChars = config.getLong(KEY_MAX_CHARS, DEFAULT_MAX_CHARS.toLong())
                .toInt()
                .coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS),
            temperature = config.getFloat(KEY_TEMPERATURE, 0.4f),
            topP = config.getFloat(KEY_TOP_P, 1.0f),
            maxTokens = config.getLong(KEY_MAX_TOKENS, 0L)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt(),
        )
    }

    private fun trackMeta(
        song: PluginSong,
        processingContext: PluginProcessingContext,
    ): TrackMeta {
        val media = processingContext.mediaInfo
        return TrackMeta(
            title = (media?.title ?: song.name)?.trim()?.takeIf { it.isNotEmpty() },
            artist = (media?.artist ?: song.artist)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** 行级缓存键 = 起始时间 + 行文本 + 影响切分的配置(模型/地址/每行字数)的 SHA-256。 */
    internal fun rowKey(row: PluginLyricLine, config: SegmentConfig): String {
        val raw = buildString {
            append(row.begin)
            append('\u0000')
            append(row.text.orEmpty())
            append('\u0000').append(config.model)
            append('\u0000').append(config.baseUrl)
            append('\u0000').append(config.maxChars)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 全局失败退避键 = 影响请求成败的配置(模型/地址)。 */
    internal fun ckey(config: SegmentConfig): String {
        val raw = "${config.model}\u0000${config.baseUrl}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun displayTitle(meta: TrackMeta): String {
        val title = meta.title ?: "?"
        return if (meta.artist.isNullOrBlank()) title else "$title — ${meta.artist}"
    }

    private fun segmentSummary(config: SegmentConfig, childCount: Int, passthrough: Boolean): String =
        "→ ${config.model} · " + if (passthrough) "未拆分" else "切分为 $childCount 子行"

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MAX_CHARS = "max_line_chars"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_MAX_TOKENS = "max_tokens"

        const val DEFAULT_MAX_CHARS = 12
        const val MIN_MAX_CHARS = 6
        const val MAX_MAX_CHARS = 20

        /** 失败退避:切词失败多为暂时性(限流/超时),10 分钟后允许重试。 */
        const val FAIL_COOLDOWN_MS = 10L * 60 * 1000
    }
}
