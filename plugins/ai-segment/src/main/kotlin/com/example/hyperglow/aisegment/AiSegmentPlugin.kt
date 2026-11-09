package com.example.hyperglow.aisegment

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext

/**
 * OpenAI 兼容 AI 切词插件:用用户配置的接口(地址/模型/Key)把**超长**的歌词行
 * 按语义切分成均衡的子行,播放时对所有歌生效。
 *
 * 管线:`LYRIC_REPLACEMENT` 阶段、`REPLACE` 模式(行数可变 = 拆/并的唯一通道)——
 * 源行窗口按子行显示字符数占比切分,词按字符区间归入子行并钳制时间戳,
 * 声明 TEXT/WORDS 让宿主把行窗口对齐到词跨度。
 *
 * 缓存:行级(键 = 起始时间 + 行文本 + 配置哈希),走宿主 `PluginCache`
 * (manifest 声明 `cacheScopes` 才不是 Noop),并按 [PluginCacheExtension] 契约
 * 暴露给宿主的缓存管理页。见 [SegmentationCache]。
 */
class AiSegmentPlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("AI segment plugin loaded (host api ${context.hostApiVersion})")
        val cache = SegmentationCache(context.cache)
        context.registerExtension(AiSegmentProcessor(context, cache))
        context.registerExtension(AiSegmentCacheExtension(cache))
    }
}

/**
 * 缓存管理扩展:宿主缓存页据此列出/删除本插件的切词缓存。
 * `id` 与 manifest 的 `cacheScopes[0].id` 一致。
 */
internal class AiSegmentCacheExtension(private val cache: SegmentationCache) : PluginCacheExtension {

    override val id: String = SCOPE_ID

    override fun listEntries(): List<PluginCacheEntry> = cache.entries()

    override fun clearAll() = cache.clear()

    override fun clearEntry(entryId: String): Boolean = cache.clearEntry(entryId)

    internal companion object {
        const val SCOPE_ID = "ai_segment_rows"
    }
}
