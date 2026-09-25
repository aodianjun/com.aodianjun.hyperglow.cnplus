package com.example.hyperglow.template

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
 * HyperGlow 插件模板：一个可编译、默认直通（不改任何内容）的最小歌词处理器。
 *
 * 使用步骤见 plugins/template/README.md；完整 API 用法（缓存、多设置项、变更协议）
 * 参考 plugins/demo 的 DemoPlugin。
 *
 * 处理协议要点（与宿主 PluginChainMerger 对应）：
 * - PATCH 模式：行数不变，仅覆盖声明过的行字段（这里声明 TEXT）；
 * - 返回 null 表示"本次不做修改"，宿主保留原快照继续后续处理器。
 */
class TemplatePlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("template plugin loaded (host api ${context.hostApiVersion})")
        context.registerExtension(TemplateProcessor(context))
    }
}

private class TemplateProcessor(private val context: PluginContext) : LyricProcessorExtension {

    override val id: String = "template.processor"

    // 翻译增强阶段：在原始歌词之后、显示之前运行。
    override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

    override fun processResult(song: PluginSong): PluginSongResult? {
        val rows = song.lyrics ?: return null
        if (rows.isEmpty()) return null

        // 示例：读取宿主设置页渲染的开关（manifest.json 中 settings 声明）。
        val enabled = context.config.getBoolean("template_enabled", false)
        if (!enabled) return null

        // TODO: 在这里实现你的歌词变换。下面保持原样返回 null（直通），
        //  需要修改歌词时参考 DemoPlugin：row.copy(...) + PluginSongResult(PATCH)。

        if (rows == song.lyrics) return null

        return PluginSongResult(
            song = song.copy(lyrics = rows),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = setOf(PluginLyricField.TEXT)
        )
    }
}
