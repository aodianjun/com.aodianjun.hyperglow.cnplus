package com.example.hyperglow.aitranslationwords

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext

/**
 * OpenAI 兼容 AI 逐字翻译插件：用用户配置的接口（地址/模型/Key）翻译当前歌词，
 * 并在歌词自带词时间轴时把译文按源词对齐成 `translationWords`。
 *
 * 与兄弟插件 `plugins/ai-translation` 的关系：同一套行级翻译语义与缓存策略，
 * 本插件是其第二代变体，额外产出逐字片段（宿主「辅助文字逐字效果」据此逐字点亮）。
 * 逐字片段依赖原文的词时间轴：逐行源（每行只有一个整行词）自然退化为纯翻译。
 *
 * 缓存：走宿主 `PluginCache`（声明 `cacheScopes` 才不是 Noop；见 manifest），
 * 并按 [PluginCacheExtension] 契约暴露给宿主的缓存管理页。见 [TranslationWordsCache]。
 */
class AiTranslationWordsPlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("AI word-level translation plugin loaded (host api ${context.hostApiVersion})")
        val cache = TranslationWordsCache(context.cache)
        context.registerExtension(AiTranslationWordsProcessor(context, cache))
        context.registerExtension(AiTranslationWordsCacheExtension(cache))
    }
}

/**
 * 缓存管理扩展：宿主缓存页据此列出/删除本插件的逐字翻译缓存。
 * `id` 与 manifest 的 `cacheScopes[0].id` 一致。
 */
internal class AiTranslationWordsCacheExtension(
    private val cache: TranslationWordsCache,
) : PluginCacheExtension {

    override val id: String = SCOPE_ID

    override fun listEntries(): List<PluginCacheEntry> = cache.entries()

    override fun clearAll() = cache.clear()

    override fun clearEntry(entryId: String): Boolean = cache.clearEntry(entryId)

    internal companion object {
        const val SCOPE_ID = "ai_translation_words_songs"
    }
}
