package com.example.hyperglow.amllttml

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext

/**
 * AMLL TTML 逐字歌词插件：从 AMLL TTML DataBase（api.amll.dev）取词，
 * 命中时以逐字歌词（含翻译/音译/和声/对唱）替换当前歌词。
 *
 * 与官方插件（hyperlyric.amll.ttml 1.1.0，HyperLyric 7.3-7.5 时期）的关系：
 * 官方插件用的是旧版 API 参数名（title/artist），当前 API 已不接受——本插件按官方
 * OpenAPI 现行契约（musicName/artistName）重新实现，并补上上游内置版后来才有的能力：
 * **歌词 API 地址可配**、**启用对唱表演**（ttm:agent → 左右分侧）、和声行开关。
 *
 * 缓存：走宿主 `PluginCache`（声明 `cacheScopes` 才不是 Noop；见 manifest），
 * 并按 [PluginCacheExtension] 契约暴露给宿主的缓存管理页——用户能看到取过哪些歌、
 * 删单条或清空（匹配错了不必重启 App）。见 [AmllTtmlCache]。
 */
class AmllTtmlPlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("AMLL TTML plugin loaded (host api ${context.hostApiVersion})")
        val cache = AmllTtmlCache(context.cache)
        context.registerExtension(AmllTtmlProcessor(context, cache))
        context.registerExtension(AmllTtmlCacheExtension(cache))
    }
}

/**
 * 缓存管理扩展：宿主缓存页据此列出/删除本插件的取词缓存。
 * `id` 与 manifest 的 `cacheScopes[0].id` 一致（宿主按插件取第一个缓存扩展，
 * 但保持一致便于日志与后续按 scope 匹配）。
 */
internal class AmllTtmlCacheExtension(private val cache: AmllTtmlCache) : PluginCacheExtension {

    override val id: String = SCOPE_ID

    override fun listEntries(): List<PluginCacheEntry> = cache.entries()

    override fun clearAll() = cache.clear()

    override fun clearEntry(entryId: String): Boolean = cache.clearEntry(entryId)

    internal companion object {
        const val SCOPE_ID = "amll_ttml_songs"
    }
}
