package com.example.hyperglow.aitranslation

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext

/**
 * OpenAI 兼容 AI 翻译插件：用用户配置的接口（地址/模型/Key）翻译当前歌词。
 *
 * 与官方插件（hyperlyric.ai.translation 1.0.0，HyperLyric 7.3-7.5 时期）的关系：
 * 官方插件随 7.6「移除插件系统」下线、功能收回内置；上游内置版后续修掉了
 * 「自动跳过语言为空时不生效」的问题。本插件为独立重写（协议语义对齐、提示词独立撰写），
 * 并带上该修复的正确语义（空集合=不过滤）。**模型搜索不在本插件内**：它需要
 * 「按钮 → 异步拉取列表 → 选择」的宿主 UI，而插件契约未暴露对应控件（README 已列明）。
 *
 * 缓存：走宿主 `PluginCache`（声明 `cacheScopes` 才不是 Noop；见 manifest），
 * 并按 [PluginCacheExtension] 契约暴露给宿主的缓存管理页。见 [TranslationCache]。
 */
class AiTranslationPlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("AI translation plugin loaded (host api ${context.hostApiVersion})")
        val cache = TranslationCache(context.cache)
        context.registerExtension(AiTranslationProcessor(context, cache))
        context.registerExtension(AiTranslationCacheExtension(cache))
    }
}

/**
 * 缓存管理扩展：宿主缓存页据此列出/删除本插件的翻译缓存。
 * `id` 与 manifest 的 `cacheScopes[0].id` 一致。
 */
internal class AiTranslationCacheExtension(private val cache: TranslationCache) : PluginCacheExtension {

    override val id: String = SCOPE_ID

    override fun listEntries(): List<PluginCacheEntry> = cache.entries()

    override fun clearAll() = cache.clear()

    override fun clearEntry(entryId: String): Boolean = cache.clearEntry(entryId)

    internal companion object {
        const val SCOPE_ID = "ai_translation_songs"
    }
}
