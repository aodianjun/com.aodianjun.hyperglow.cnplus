package com.eza.hyperglow.plugin

import android.content.Context
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.bridge.SpicyBridgeDocumentStore
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 插件处理管线：把 HyperLyric 插件链接进 HyperGlow 的歌词投影链。
 *
 * 位置：仲裁者(arbiter.active) → **[enrich]** → AodProjectionEngine.project → SystemUI。
 * 富化在 project() 内同步查表（无插件结果时原样返回，保持引擎引用相等校验），
 * 链本身在换歌时异步执行（每处理器 40s 上限，切歌取消旧任务——插件必须响应中断）。
 *
 * 数据边界：v1 只有 Spicy 路径提供整首文档（SpicyBridgeDocumentStore），
 * Lyricon/SuperLyric/LyricInfo 源暂无整首歌快照，插件不参与（透传原始状态）。
 */
object PluginPipeline {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var appContext: Context? = null
    private var collector: Job? = null
    private var chainJob: Job? = null

    /** 当前会话已处理好的插件结果；null = 无结果（透传）。 */
    @Volatile
    private var patched: PatchedSong? = null

    private var processedSessionKey: String? = null
    private var processedDocument: Any? = null

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (appContext === app) return
            appContext = app
            PluginRuntime.bootstrap(app)
            collector = scope.launch {
                launch {
                    LyricProducers.arbiter.active.collect { maybeProcess() }
                }
                launch {
                    SpicyBridgeDocumentStore.state.collect { maybeProcess() }
                }
            }
        }
    }

    /** 引擎 project() 的同步富化入口——绝不能抛异常、绝不能阻塞。 */
    fun enrich(state: LyricProducerState): LyricProducerState {
        val current = patched ?: return state
        return runCatching { PluginSongBridge.enrichState(state, current) }.getOrDefault(state)
    }

    fun processingEnabled(): Boolean =
        appContext?.let { AodRenderPreferences.read(it).pluginProcessingEnabled } ?: false

    /**
     * 设置页把总开关从关到开时调用：立即处理当前歌曲（若有）。
     * maybeProcess 自带会话/文档去重，重复调用安全。
     */
    fun requestProcess() {
        maybeProcess()
    }

    /** 总开关关闭或插件全部卸载时清空缓存，让富化立即回到透传。 */
    fun invalidate() {
        chainJob?.cancel()
        chainJob = null
        patched = null
        processedSessionKey = null
        processedDocument = null
    }

    private fun maybeProcess() {
        val context = appContext ?: return
        if (!processingEnabled()) {
            if (patched != null) invalidate()
            return
        }
        val state = LyricProducers.arbiter.active.value ?: return
        val document = SpicyBridgeDocumentStore.state.value ?: return
        if (!documentMatches(document, state)) return
        val sessionKey = PluginSongBridge.sessionKey(state)
        if (sessionKey == processedSessionKey && document === processedDocument) return

        chainJob?.cancel()
        processedSessionKey = sessionKey
        processedDocument = document
        patched = null
        chainJob = scope.launch {
            val result = runCatching {
                val original = PluginSongBridge.fromDocument(document, state)
                val processed = PluginRuntime.processChain(
                    original,
                    PluginSongBridge.mediaInfo(state)
                )
                if (processed === original || processed == original) {
                    null
                } else {
                    val (songFields, lyricFields) = diff(original, processed)
                    if (songFields.isEmpty() && lyricFields.isEmpty()) null
                    else PatchedSong(sessionKey, processed, songFields, lyricFields)
                }
            }.getOrElse { error ->
                AppLog.w(TAG, "plugin chain failed for $sessionKey", error)
                null
            }
            patched = result
            if (result != null) {
                AppLog.i(TAG, "plugin chain patched $sessionKey (fields=${result.changedLyricFields})")
            }
        }
    }

    private fun documentMatches(
        document: com.eza.hyperglow.bridge.SpicyBridgeDocument,
        state: LyricProducerState
    ): Boolean = document.producerId == state.producerId &&
        document.generation == state.generation &&
        document.trackUri == state.trackUri

    /** 比较链前/链后快照，得出真正发生变化的字段集合（宿主权威，不信插件声明）。 */
    internal fun diff(
        original: PluginSong,
        final: PluginSong
    ): Pair<Set<PluginSongField>, Set<PluginLyricField>> {
        val songFields = buildSet {
            if (original.id != final.id) add(PluginSongField.ID)
            if (original.name != final.name) add(PluginSongField.NAME)
            if (original.artist != final.artist) add(PluginSongField.ARTIST)
            if (original.album != final.album) add(PluginSongField.ALBUM)
            if (original.duration != final.duration) add(PluginSongField.DURATION)
            if (original.metadata != final.metadata) add(PluginSongField.METADATA)
            if (original.lyrics != final.lyrics) add(PluginSongField.LYRICS)
        }
        val lyricFields = if (PluginSongField.LYRICS !in songFields) {
            emptySet()
        } else {
            diffLyricFields(original.lyrics.orEmpty(), final.lyrics.orEmpty())
        }
        return songFields.filter { it != PluginSongField.LYRICS }.toSet() to lyricFields
    }

    private fun diffLyricFields(
        originalRows: List<com.lidesheng.hyperlyric.plugin.api.PluginLyricLine>,
        finalRows: List<com.lidesheng.hyperlyric.plugin.api.PluginLyricLine>
    ): Set<PluginLyricField> {
        if (originalRows.size != finalRows.size) {
            // REPLACE 整表替换（行数变化）：内容字段全量按新表覆盖显示。
            return setOf(
                PluginLyricField.TEXT,
                PluginLyricField.TRANSLATION,
                PluginLyricField.ROMA,
                PluginLyricField.WORDS
            )
        }
        val changed = mutableSetOf<PluginLyricField>()
        for ((base, next) in originalRows.zip(finalRows)) {
            if (PluginLyricField.BEGIN !in changed && base.begin != next.begin) {
                changed += PluginLyricField.BEGIN
            }
            if (PluginLyricField.END !in changed && base.end != next.end) {
                changed += PluginLyricField.END
            }
            if (PluginLyricField.DURATION !in changed && base.duration != next.duration) {
                changed += PluginLyricField.DURATION
            }
            if (PluginLyricField.IS_ALIGNED_RIGHT !in changed &&
                base.isAlignedRight != next.isAlignedRight
            ) {
                changed += PluginLyricField.IS_ALIGNED_RIGHT
            }
            if (PluginLyricField.METADATA !in changed && base.metadata != next.metadata) {
                changed += PluginLyricField.METADATA
            }
            if (PluginLyricField.TEXT !in changed && base.text != next.text) {
                changed += PluginLyricField.TEXT
            }
            if (PluginLyricField.WORDS !in changed && base.words != next.words) {
                changed += PluginLyricField.WORDS
            }
            if (PluginLyricField.SECONDARY !in changed && base.secondary != next.secondary) {
                changed += PluginLyricField.SECONDARY
            }
            if (PluginLyricField.SECONDARY_WORDS !in changed &&
                base.secondaryWords != next.secondaryWords
            ) {
                changed += PluginLyricField.SECONDARY_WORDS
            }
            if (PluginLyricField.TRANSLATION !in changed && base.translation != next.translation) {
                changed += PluginLyricField.TRANSLATION
            }
            if (PluginLyricField.TRANSLATION_WORDS !in changed &&
                base.translationWords != next.translationWords
            ) {
                changed += PluginLyricField.TRANSLATION_WORDS
            }
            if (PluginLyricField.ROMA !in changed && base.roma != next.roma) {
                changed += PluginLyricField.ROMA
            }
        }
        return changed
    }

    private const val TAG = "PluginPipeline"
}
