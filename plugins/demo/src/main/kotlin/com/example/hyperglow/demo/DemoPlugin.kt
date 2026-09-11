package com.example.hyperglow.demo

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult

/**
 * HyperGlow 演示插件：装饰每行歌词（前缀/大写），完整演示插件 API 的用法——
 * onLoad 注册扩展、经 PluginConfig 读宿主设置页渲染的配置、用 PluginCache
 * 记录已处理歌曲、按"显式声明变更"协议返回 [PluginSongResult]。
 *
 * 处理协议要点（与宿主 PluginChainMerger 对应）：
 * - PATCH 模式：行数不变，仅覆盖声明过的行字段（这里只声明 TEXT）；
 * - 无可变更（全部行无文本）时返回 null，宿主保留原快照继续后续处理器。
 */
class DemoPlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("demo plugin loaded (host api ${context.hostApiVersion})")
        context.registerExtension(DemoDecorator(context))
    }
}

private class DemoDecorator(private val context: PluginContext) : LyricProcessorExtension {

    override val id: String = "demo.decorator"

    // 翻译增强阶段：在原始歌词之后、显示之前运行。
    override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

    override fun processResult(song: PluginSong): PluginSongResult? {
        val rows = song.lyrics ?: return null
        if (rows.isEmpty()) return null

        val config = context.config
        val mode = config.getString("demo_mode", "prefix") ?: "prefix"
        val prefix = config.getString("demo_prefix", "♪ ") ?: "♪ "

        val decorated = rows.map { row ->
            val text = row.text
            if (text.isNullOrEmpty()) {
                row
            } else {
                row.copy(text = decorate(text, mode, prefix))
            }
        }
        if (decorated == rows) return null

        // 演示缓存 API：记录本首歌已处理（宿主按插件隔离，16 MiB 配额）。
        song.name?.let { name ->
            runCatching {
                context.cache.putString("processed:$name", "1")
            }
        }
        context.logger.debug("decorated ${decorated.size} lines for '${song.name ?: "?"}'")

        return PluginSongResult(
            song = song.copy(lyrics = decorated),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = setOf(PluginLyricField.TEXT)
        )
    }

    private fun decorate(text: String, mode: String, prefix: String): String = when (mode) {
        "uppercase" -> text.uppercase()
        "both" -> prefix + text.uppercase()
        else -> prefix + text
    }
}
