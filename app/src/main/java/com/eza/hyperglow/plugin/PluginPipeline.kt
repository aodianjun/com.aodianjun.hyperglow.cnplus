package com.eza.hyperglow.plugin

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeDocumentStore
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.producer.LyricSongSnapshot
import com.eza.hyperglow.producer.LyricSource
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 插件处理管线：把 HyperLyric 插件链接进 HyperGlow 的歌词投影链。
 *
 * 位置：仲裁者(arbiter.active) → **[enrich]** → AodProjectionEngine.project → SystemUI。
 * 富化在 project() 内同步查表（无插件结果时原样返回，保持引擎引用相等校验），
 * 链本身在换歌时异步执行（每处理器 40s 上限，切歌取消旧任务——插件必须响应中断）。
 *
 * 数据边界（v2）：三条输入路径汇入同一条插件链——
 * - Spicy 整首文档（SpicyBridgeDocumentStore，v1 起有，语义不变）；
 * - 内存持有整首行数组的生产者（Lyricon/LyricInfo）经 fullSongSnapshot 直读，每会话一次；
 * - 纯逐行源（SuperLyric）经 [LineStreamAggregator] 边播边聚合，首行立即跑链、
 *   之后按 [STREAM_CHAIN_MIN_INTERVAL_MS] 节流补跑（控制增量 LLM 调用频率）。
 * 三条路径的产物统一走 [PluginSongBridge.enrichState] 富化，行内容字段覆盖、
 * 时间轴字段保留生产者权威值。
 */
object PluginPipeline {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var appContext: Context? = null
    private var collector: Job? = null
    @Volatile private var chainJob: Job? = null

    /** 当前会话已处理好的插件结果；null = 无结果（透传）。 */
    @Volatile
    private var patched: PatchedSong? = null

    // 以下字段会被 collector / retry / 链完成回调多个协程读写:可见性一律 @Volatile,
    // 复合状态收敛进 LineStreamAggregator(内部 @Synchronized)。
    @Volatile private var processedSessionKey: String? = null
    private var processedDocument: Any? = null

    /** 逐行源聚合器（SuperLyric 等无整首数据的源）。内部 @Synchronized,可多协程安全调用。 */
    private val aggregator = LineStreamAggregator()

    /** 上次调度的快照内容指纹（行数 + 末行 endMs），供逐行路径去重。 */
    @Volatile private var snapshotFingerprint: Pair<Int, Long>? = null

    /** 本会话上次链启动时刻（SystemClock.elapsedRealtime），逐行节流窗口的基准。 */
    @Volatile private var streamLastChainStartElapsedMs = 0L

    /** 链运行期间到来了新行：链完成后按节流窗口补评一次。 */
    @Volatile private var streamDirty = false
    @Volatile private var streamRetryJob: Job? = null

    /** 上一次记录的跳过原因；仅用于去重，避免 collector 高频回调刷屏。 */
    private var skipReason: String? = null

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
            AppLog.i(TAG, "pipeline started: observing arbiter.active + SpicyBridgeDocumentStore")
        }
    }

    /** 引擎 project() 的同步富化入口——绝不能抛异常、绝不能阻塞。 */
    fun enrich(state: LyricProducerState): LyricProducerState {
        val current = patched ?: return state
        return runCatching { PluginSongBridge.enrichState(state, current) }
            .onFailure { AppLog.w(TAG, "enrich failed for ${current.sessionKey}", it) }
            .getOrDefault(state)
    }

    fun processingEnabled(): Boolean =
        appContext?.let { AodRenderPreferences.read(it).pluginProcessingEnabled } ?: false

    /**
     * 设置页把总开关从关到开时调用：立即处理当前歌曲（若有）。
     * maybeProcess 自带会话/文档去重，重复调用安全。
     */
    fun requestProcess() {
        AppLog.i(TAG, "requestProcess: manual re-process requested")
        maybeProcess()
    }

    /** 总开关关闭或插件全部卸载时清空缓存，让富化立即回到透传。 */
    fun invalidate() {
        val hadPatch = patched != null
        chainJob?.cancel()
        chainJob = null
        patched = null
        processedSessionKey = null
        processedDocument = null
        skipReason = null
        snapshotFingerprint = null
        streamDirty = false
        streamLastChainStartElapsedMs = 0L
        streamRetryJob?.cancel()
        streamRetryJob = null
        aggregator.reset()
        AppLog.i(TAG, "invalidate: cleared patched cache (hadPatch=$hadPatch)")
    }

    private fun maybeProcess() {
        if (appContext == null) return
        if (!processingEnabled()) {
            if (patched != null) {
                AppLog.i(TAG, "processing disabled; discarding patched cache")
                invalidate()
            } else {
                logSkipOnce("processing disabled (plugin master switch off)")
            }
            return
        }
        val state = LyricProducers.arbiter.active.value ?: return
        val document = SpicyBridgeDocumentStore.state.value
        if (document != null && documentMatches(document, state)) {
            // Spicy 路径（v1 语义不变）：整首文档 → 插件链，按会话 + 文档身份去重。
            val sessionKey = PluginSongBridge.sessionKey(state)
            if (sessionKey == processedSessionKey && document === processedDocument) return
            processedDocument = document
            scheduleChain(state, sessionKey, document.rows.size) {
                PluginSongBridge.fromDocument(document, state)
            }
            return
        }
        val source = LyricProducers.arbiter.activeSource.value
        if (source == null || source == LyricSource.SPICY) {
            // Spicy 状态但文档缺失/不匹配：文档路径的过渡态。不落入逐行路径
            // （Spicy 的行不该进聚合器），保留 skip 日志便于现场定位。
            logSkipOnce(
                if (document == null) {
                    "no spicy document; plugin chain idle for source=${state.producerId}"
                } else {
                    "document/state mismatch: document=[${document.producerId} " +
                        "gen=${document.generation} uri=${document.trackUri}] " +
                        "state=[${state.producerId} gen=${state.generation} " +
                        "uri=${state.trackUri}]"
                }
            )
            return
        }
        maybeProcessLineStream(state, source)
    }

    /**
     * 逐行源（Lyricon/LyricInfo/SuperLyric）的插件链入口：
     * - 内存里有整首行数组的生产者（Lyricon/LyricInfo）：fullSongSnapshot 直读，
     *   每会话一次（内容指纹去重）；
     * - 纯逐行源（SuperLyric）：聚合器累积，本会话首行立即跑链，之后按
     *   [STREAM_CHAIN_MIN_INTERVAL_MS] 节流补跑——链运行中不重排（半截 LLM 调用
     *   是浪费），期间到来的新行记 dirty，链完成后按剩余窗口补评。
     */
    private fun maybeProcessLineStream(state: LyricProducerState, source: LyricSource) {
        val fullSnapshot = LyricProducers.arbiter.fullSongSnapshot(source)
        if (fullSnapshot != null && fullSnapshot.matches(state)) {
            val sessionKey = PluginSongBridge.sessionKey(state)
            val fingerprint = fingerprintOf(fullSnapshot)
            if (sessionKey == processedSessionKey && fingerprint == snapshotFingerprint) return
            snapshotFingerprint = fingerprint
            scheduleChain(state, sessionKey, fullSnapshot.rows.size) {
                PluginSongBridge.fromSnapshot(state, fullSnapshot)
            }
            return
        }
        val accumulation = aggregator.onState(state)
        val aggregated = accumulation.snapshot ?: return
        if (!aggregated.matches(state)) return
        val sessionKey = PluginSongBridge.sessionKey(state)
        val fingerprint = fingerprintOf(aggregated)
        // 判活用 isActive 而非 null:Job 完成后引用仍在,判 null 会让补评/节流
        // 在第一条链完成后永久失效(issue #75 评审修复)。
        if (chainJob?.isActive == true) {
            // 链运行中：不打断；新行记 dirty，链完成后按剩余节流窗口补评。
            if (accumulation.addedNewRow) streamDirty = true
            return
        }
        if (sessionKey == processedSessionKey && fingerprint == snapshotFingerprint && !streamDirty) {
            return
        }
        if (sessionKey != processedSessionKey) {
            // 新会话:节流窗口不跨歌,首行立即跑链(与本函数文档一致)。
            streamLastChainStartElapsedMs = 0L
        }
        val elapsedSinceLastChain = SystemClock.elapsedRealtime() - streamLastChainStartElapsedMs
        if (streamLastChainStartElapsedMs != 0L &&
            elapsedSinceLastChain < STREAM_CHAIN_MIN_INTERVAL_MS
        ) {
            scheduleStreamRetry(STREAM_CHAIN_MIN_INTERVAL_MS - elapsedSinceLastChain)
            return
        }
        streamDirty = false
        snapshotFingerprint = fingerprint
        scheduleChain(state, sessionKey, aggregated.rows.size) {
            PluginSongBridge.fromSnapshot(state, aggregated)
        }
    }

    /** 节流窗口到点后的重评：窗口期间错过的新行在此补跑（指纹去重保证幂等）。 */
    private fun scheduleStreamRetry(delayMs: Long) {
        streamRetryJob?.cancel()
        streamRetryJob = scope.launch {
            delay(delayMs.coerceAtLeast(MIN_STREAM_RETRY_DELAY_MS))
            streamRetryJob = null
            // 重试也可能落在总开关关闭之后:与 maybeProcess 同一道门。
            if (!processingEnabled()) return@launch
            val retryState = LyricProducers.arbiter.active.value ?: return@launch
            val retrySource = LyricProducers.arbiter.activeSource.value ?: return@launch
            if (retrySource != LyricSource.SPICY) maybeProcessLineStream(retryState, retrySource)
        }
    }

    /**
     * 统一的链调度：取消旧任务、登记会话、清空 patched 后异步执行插件链。会话身份里
     * 的文档引用 / 快照指纹由调用方在调用前登记（document 身份 / snapshotFingerprint）。
     */
    private fun scheduleChain(
        state: LyricProducerState,
        sessionKey: String,
        rowsCount: Int,
        buildSong: () -> PluginSong
    ) {
        chainJob?.cancel()
        processedSessionKey = sessionKey
        patched = null
        skipReason = null
        streamLastChainStartElapsedMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "chain scheduled: session=$sessionKey rows=$rowsCount")
        chainJob = scope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            val result = try {
                val original = buildSong()
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
            } catch (cancelled: CancellationException) {
                // 切歌取消旧链是正常控制流，不可与真正的处理失败混为一谈。
                AppLog.i(TAG, "chain cancelled for $sessionKey (superseded by newer session)")
                throw cancelled
            } catch (error: Throwable) {
                AppLog.w(TAG, "chain failed for $sessionKey", error)
                null
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            patched = result
            if (result != null) {
                AppLog.i(
                    TAG,
                    "chain applied session=$sessionKey songFields=${result.changedSongFields} " +
                        "lyricFields=${result.changedLyricFields} elapsed=${elapsedMs}ms"
                )
            } else {
                AppLog.i(
                    TAG,
                    "chain produced no change for $sessionKey elapsed=${elapsedMs}ms (passthrough)"
                )
            }
            // 逐行聚合路径：链运行期间到来的新行在节流窗口后补评（文档路径 streamDirty 恒 false）。
            if (streamDirty) {
                streamDirty = false
                scheduleStreamRetry(
                    STREAM_CHAIN_MIN_INTERVAL_MS -
                        (SystemClock.elapsedRealtime() - streamLastChainStartElapsedMs)
                )
            }
        }.also { job ->
            // 链结束后释放引用:判活改用 isActive(issue #75 评审修复),
            // 但不长期挂着已完成的 Job;若已被更新的链替换则不动。
            job.invokeOnCompletion {
                synchronized(this@PluginPipeline) { if (chainJob === job) chainJob = null }
            }
        }
    }

    /** 快照内容指纹：行数 + 末行 endMs——足以区分「聚合器又积累了几行」。 */
    private fun fingerprintOf(snapshot: LyricSongSnapshot): Pair<Int, Long> =
        snapshot.rows.size to (snapshot.rows.lastOrNull()?.endMs ?: 0L)

    /** 只在跳过原因发生变化时记一次，避免 collector 高频回调刷屏。 */
    private fun logSkipOnce(reason: String) {
        if (skipReason == reason) return
        skipReason = reason
        AppLog.i(TAG, "chain skipped: $reason")
    }

    /** 管线输入状态，供插件管理页展示「为什么配好了插件却没有动静」。 */
    internal enum class PipelineInputState {
        /** 没有活动歌词源（当前没有媒体在播）。 */
        IDLE_NO_SOURCE,
        /** 有活动源但没有整首文档：Spicy 的过渡态；逐行源（SuperLyric/Lyricon/LyricInfo）在 v2 起走聚合/快照路径，不会停留在此。 */
        IDLE_NO_DOCUMENT,
        /** 文档与活动源不匹配（换歌瞬间的过渡态，等下一次调度）。 */
        SOURCE_MISMATCH,
        /** 文档与源匹配，插件链已具备处理输入。 */
        READY
    }

    /** 纯函数：按当前活动源与文档快照分类管线输入状态，判定与 [maybeProcess] 保持一致。 */
    internal fun pipelineInputState(
        state: LyricProducerState?,
        document: SpicyBridgeDocument?
    ): PipelineInputState = when {
        state == null -> PipelineInputState.IDLE_NO_SOURCE
        document == null -> PipelineInputState.IDLE_NO_DOCUMENT
        documentMatches(document, state) -> PipelineInputState.READY
        else -> PipelineInputState.SOURCE_MISMATCH
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
            // REPLACE 整表替换（行数变化）：按行比较失去意义，但行数变化 ≠ 正文变化——
            // 全量标记 TEXT/TRANSLATION/ROMA/WORDS 会让空表把正在显示的歌词抹掉。
            // 只标记新表里实际携带内容的字段，回向只覆盖有内容可投的行字段。
            return buildSet {
                if (finalRows.any { !it.text.isNullOrBlank() }) add(PluginLyricField.TEXT)
                if (finalRows.any { !it.translation.isNullOrBlank() }) add(PluginLyricField.TRANSLATION)
                if (finalRows.any { !it.roma.isNullOrBlank() }) add(PluginLyricField.ROMA)
                if (finalRows.any { !it.words.isNullOrEmpty() }) add(PluginLyricField.WORDS)
            }
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

    /**
     * 逐行聚合链的最小重跑间隔：控制增量 LLM 调用频率。插件自身的缓存扩展会去重
     * 已翻译行，重复行成本趋近于零；若实测费用仍高，调大此值或改为偏好设置。
     */
    internal const val STREAM_CHAIN_MIN_INTERVAL_MS = 15_000L

    /** 节流重评任务的最小延迟下限，避免 0 延迟忙转。 */
    private const val MIN_STREAM_RETRY_DELAY_MS = 50L
}
