package com.eza.hyperglow.plugin

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricSongRow
import com.eza.hyperglow.producer.LyricSongSnapshot

/**
 * 纯逐行源（SuperLyric 等，无整首歌词数据）的行流聚合器。
 *
 * 把按行推送的歌词累积成整首 [LyricSongSnapshot] 供插件链处理：行以 startMs 为键去重——
 * 位置驱动的源会对同一行高频重复 emit，只有新 startMs（或同 startMs 但文本变化，即提供方
 * 修正）才落行；seek 回放命中已有行不重复计、也不算新内容。会话语义与插件链一致
 * （sessionKey 变化 = 换歌）：换歌即重置缓冲。SuperLyric 的 generation 恒 0，trackUri 里
 * 的歌名变化就是换歌信号——与现状语义一致。
 *
 * 行窗口用生产者上报的真实 lineStartMs/lineEndMs（SuperLyric 每行都有），没有真实时间轴
 * 的行（占位 / metadata-only）不聚合。state.durationMs 在 SuperLyric 上被挪用为「当前行
 * 结束时间」，因此快照时长取已见行的最大 endMs，不读该字段。
 *
 * 线程模型：只在 PluginPipeline 的单一 collector 协程上调用，自身不加锁。
 */
internal class LineStreamAggregator {
    data class Accumulation(
        val snapshot: LyricSongSnapshot?,
        /** 本次调用是否落了新行（或修正了已有行）：节流触发与补跑判定的依据。 */
        val addedNewRow: Boolean
    )

    private var sessionKey: String? = null
    private val rows = sortedMapOf<Long, MutableRow>()
    private var durationMs = 0L

    private class MutableRow(
        var endMs: Long,
        var text: String,
        var translation: String,
        var roma: String
    )

    // 可从多个协程进入(collector/retry/链完成补评):onState 的「读-改-写 rows 与
    // 会话状态」必须原子,否则 TreeMap 并发修改会让 collector 被永久杀死。
    @Synchronized
    fun onState(state: LyricProducerState): Accumulation {
        val key = PluginSongBridge.sessionKey(state)
        if (key != sessionKey) {
            sessionKey = key
            rows.clear()
            durationMs = 0L
        }
        var added = false
        val start = state.lineStartMs
        val end = state.lineEndMs
        val text = state.line
        if (text.isNotBlank() && end > start) {
            val existing = rows[start]
            when {
                existing == null -> {
                    rows[start] = MutableRow(end, text, state.translatedLine, state.romanizedLine)
                    added = true
                }
                existing.text != text -> {
                    // 同一时间的行被提供方修正：覆盖，并视为新内容。
                    existing.endMs = end
                    existing.text = text
                    existing.translation = state.translatedLine
                    existing.roma = state.romanizedLine
                    added = true
                }
                existing.translation.isEmpty() && state.translatedLine.isNotEmpty() -> {
                    // 翻译 lane 晚于原文到达：补翻译不视为新内容，避免多余的重跑。
                    existing.translation = state.translatedLine
                }
            }
            if (end > durationMs) durationMs = end
        }
        val snapshot = rows.takeIf { it.isNotEmpty() }?.let { buffered ->
            LyricSongSnapshot(
                producerId = state.producerId,
                generation = state.generation,
                trackUri = state.trackUri,
                durationMs = durationMs,
                rows = buffered.map { (startMs, row) ->
                    LyricSongRow(
                        startMs = startMs,
                        endMs = row.endMs,
                        text = row.text,
                        translation = row.translation,
                        roma = row.roma
                    )
                }
            )
        }
        return Accumulation(snapshot, added)
    }

    /** 丢弃缓冲（总开关关闭等场景）；下次 onState 按会话变化重新开始。 */
    @Synchronized
    fun reset() {
        sessionKey = null
        rows.clear()
        durationMs = 0L
    }
}
