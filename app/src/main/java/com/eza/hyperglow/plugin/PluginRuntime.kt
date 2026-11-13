package com.eza.hyperglow.plugin

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * HyperLyric 兼容插件运行时（宿主 = App 进程）。
 *
 * 生命周期（HyperLyric 语义在 App 进程下的映射）：
 * - 安装/卸载/升级代码 → 下次 App 启动生效（HyperLyric 在 SystemUI 里需要重启 SystemUI，
 *   这里只需重启 App——比上游更轻量，因为插件不在 SystemUI 进程里跑）；
 * - 启用/禁用/普通配置修改 → 实时同步（onConfigChanged + 激活判定每次处理时读取），
 *   从下一首歌开始生效，当前歌不自动重跑；
 * - 处理器按 LYRIC_REPLACEMENT → TRANSLATION_ENHANCEMENT 顺序执行，后一个收到
 *   前一个合并后的结果；单处理器上限 [PROCESSOR_TIMEOUT_MS]。
 *
 * 插件异常隔离：每个处理器的加载与执行都包在 runCatching 里，任何插件抛错/超时/
 * 结果非法都只跳过该结果，绝不影响原始歌词与其它插件。
 *
 * 线程模型（2.6）：
 * - **加载通道**：manifest 扫描 / ClassLoader / 反射实例化 / onLoad / dex 诊断全部在专用
 *   单线程通道上执行，`bootstrap` 只挂载 context 后立即返回，`Application.onCreate` 与 UI
 *   线程不再被插件 IO 阻塞；两次并发 reload 经 [reloadMutex] 串行，不交错出半截状态。
 * - **执行通道**：处理器在专用有界通道（[PROCESSOR_MAX_CONCURRENCY] 条线程）上跑，
 *   不占用共享的 Dispatchers.IO。
 * - **快照原子性**：加载在锁外完成，替换整表在 [lock] 内一次完成——`installed()` 与处理器
 *   枚举要么看到完整旧表、要么看到完整新表，绝不会看到半截；插件代码（onLoad/onUnload）
 *   也绝不运行在 runtime 锁内。
 *
 * 超时语义（2.7）：超时只做协作式中断（`Thread.interrupt`），**不能强杀 JVM 线程**——
 * 忽略中断的阻塞会一直占住执行通道线程直到自然返回；因此超时判定走显式捕获
 * [TimeoutCancellationException]，外层取消（切歌/换会话）继续传播，并对连续超时的
 * (pluginId, processorId) 在本轮加载会话内熔断跳过（见 [processorTimeoutCircuitThreshold]）。
 *
 * 卸载语义（2.12）：reload / 卸载 / onLoad 抛错三条路径都对**旧实例** best-effort 调
 * [HyperLyricPlugin.onUnload]（包 runCatching + 日志），并丢弃旧链引用（ClassLoader 本身
 * 无法卸载，进程重启后彻底清理）。
 */
object PluginRuntime {
    /** 与 HyperLyric 一致的单处理器宿主等待上限。 */
    const val PROCESSOR_TIMEOUT_MS = 40_000L

    /**
     * 连续超时熔断阈值（2.7）：同一 (pluginId, processorId) 连续超时达到该次数后，
     * 本轮加载会话内直接跳过该处理器（不再等满 [processorTimeoutMs]）；reload 时归零。
     */
    internal const val TIMEOUT_CIRCUIT_THRESHOLD = 3

    /** 处理器专用有界通道的并发上限：链本身串行，留一条给「已取消但忽略中断」的残留调用。 */
    private const val PROCESSOR_MAX_CONCURRENCY = 2

    /** 加载通道线程名（单测据此断言加载不在调用线程上执行）。 */
    internal const val RELOAD_THREAD_NAME = "hyperglow-plugin-reload"

    /** 处理器执行通道线程名。 */
    internal const val PROCESSOR_THREAD_NAME = "hyperglow-plugin-processor"

    class LoadedPlugin(
        val manifest: PluginManifest,
        val plugin: HyperLyricPlugin?,
        val hostContext: HostPluginContext?,
        /** 加载/初始化失败原因；plugin==null 时非空。 */
        val loadError: String?
    ) {
        val processors: List<LyricProcessorExtension>
            get() = hostContext?.registeredExtensions
                ?.filterIsInstance<LyricProcessorExtension>()
                ?.sortedBy { it.stage.ordinal }
                .orEmpty()

        /** 激活判定：activationSettingKey 开（或未声明）+ 代码加载成功。 */
        fun isActivated(context: Context): Boolean =
            plugin != null && PluginSettingsStore.isActivated(context, manifest)
    }

    private val lock = Any()
    private var appContext: Context? = null
    private val loaded = mutableListOf<LoadedPlugin>()

    // --- 加载通道（2.6）：manifest 扫描 / ClassLoader / 实例化 / onLoad / dex 诊断
    // 全部在这条专用单线程通道上执行，调用线程（onCreate / UI）只提交不等待。
    private val reloadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, RELOAD_THREAD_NAME).apply { isDaemon = true }
    }
    private val reloadDispatcher: CoroutineDispatcher = reloadExecutor.asCoroutineDispatcher()
    private val reloadScope = CoroutineScope(SupervisorJob())

    /** 显式互斥：即使加载通道被换成多线程 dispatcher，两次 reload 也必须顺序完成。 */
    private val reloadMutex = Mutex()

    /** 每完成一次 reload 自增；UI 据此在冷启动异步加载完成后刷新插件列表。 */
    private val reloadGenerationState = MutableStateFlow(0)
    internal val reloadGeneration: StateFlow<Int> = reloadGenerationState.asStateFlow()

    // --- 处理器执行通道与连续超时熔断（2.7）。
    private val processorExecutor =
        Executors.newFixedThreadPool(PROCESSOR_MAX_CONCURRENCY) { runnable ->
            Thread(runnable, PROCESSOR_THREAD_NAME).apply { isDaemon = true }
        }
    private val processorDispatcher: CoroutineDispatcher = processorExecutor.asCoroutineDispatcher()

    /** 连续超时计数，键 = "pluginId/processorId"；reload（新加载会话）时清空。 */
    private val timeoutStreaks = ConcurrentHashMap<String, Int>()

    /** 单处理器超时上限；生产默认 [PROCESSOR_TIMEOUT_MS]，单测注入小值以免等满 40s。 */
    @Volatile
    internal var processorTimeoutMs: Long = PROCESSOR_TIMEOUT_MS

    /** 连续超时熔断阈值；生产默认 [TIMEOUT_CIRCUIT_THRESHOLD]。 */
    @Volatile
    internal var processorTimeoutCircuitThreshold: Int = TIMEOUT_CIRCUIT_THRESHOLD

    /**
     * 单测注入缝：替换「按 manifest 从磁盘 dex 加载入口实例」这一步（生产恒为 null）。
     * 返回 null 视作加载失败；返回实例后仍走与生产完全相同的 onLoad / 失败清理路径。
     */
    @Volatile
    internal var entryLoaderOverride: ((Context, PluginManifest) -> HyperLyricPlugin?)? = null

    /**
     * App 冷启动时挂载 app context 并**异步**恢复磁盘上已安装的插件。幂等。
     *
     * 只做 context 挂载（同步、廉价），加载交给 [reloadAll] 的后台通道——onCreate 不再被
     * manifest/dex/onLoad 阻塞（2.6）。加载完成前 [installed] 返回空表，链查询照常拿到
     * 「一致的空快照」而不是半截状态。
     */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext === app) return
            appContext = app
        }
        AppLog.i(TAG, "bootstrap: runtime attached to app context")
        reloadAll()
    }

    /**
     * 重新扫描插件目录并加载（安装/卸载后调用；旧 ClassLoader 无法卸载，进程重启后彻底清理）。
     *
     * 调用线程立即返回：加载在专用单线程通道上执行，两次并发调用按提交顺序串行，
     * 不会交错出半截状态。需要等新快照就绪的调用方用 [reloadAllAndAwait]。
     */
    fun reloadAll(): Job = reloadScope.launch(reloadDispatcher) { reloadLocked() }

    /** [reloadAll] 的挂起版本：在加载通道上执行并等待完成；调用线程只挂起、不阻塞。 */
    suspend fun reloadAllAndAwait() {
        withContext(reloadDispatcher) { reloadLocked() }
    }

    private suspend fun reloadLocked() {
        reloadMutex.withLock { reloadNow() }
    }

    /**
     * 一次完整 reload 的顺序（原子性与 onUnload 时序的设计，见 2.6 / 2.12）：
     * 1. **锁外**读 manifest 并逐个加载新实例（含 onLoad）——期间链查询继续看到上一份完整快照；
     * 2. **锁内**整表原子替换——查询者要么看到完整旧表、要么看到完整新表，绝无半截；
     * 3. **锁外**对旧实例 best-effort 调 onUnload——先摘旧再调，任何查询者拿到的实例都处于
     *    「已完整初始化」状态；插件代码也绝不运行在 runtime 锁内。
     */
    private fun reloadNow() {
        val context = appContext
        if (context == null) {
            AppLog.w(TAG, "reloadAll skipped: runtime not bootstrapped")
            return
        }
        val manifests = PluginInstaller.installed(context)
        AppLog.i(TAG, "reloadAll: ${manifests.size} manifest(s) to load")
        val fresh = manifests.map { manifest -> loadOne(context, manifest) }
        val replaced: List<LoadedPlugin>
        synchronized(lock) {
            replaced = loaded.toList()
            loaded.clear()
            loaded.addAll(fresh)
        }
        replaced.forEach { unloadQuietly(it) }
        // 熔断计数属于「本轮加载会话」：新会话从零开始。
        timeoutStreaks.clear()
        reloadGenerationState.value = reloadGenerationState.value + 1
        if (manifests.isNotEmpty()) {
            AppLog.i(TAG, "plugins loaded: ${manifests.joinToString { "${it.id}:${it.version}" }}")
        }
    }

    /** 加载单个插件并记录结果（锁外调用）。 */
    private fun loadOne(context: Context, manifest: PluginManifest): LoadedPlugin {
        AppLog.i(
            TAG,
            "loading ${manifest.id} v${manifest.version} entry=${manifest.entry} " +
                "apiVersion=${manifest.apiVersion} cacheScopes=${manifest.cacheScopes.size}"
        )
        val plugin = loadPlugin(context, manifest)
        if (plugin.plugin != null) {
            AppLog.i(
                TAG,
                "loaded ${manifest.id}: processors=${plugin.processors.size} " +
                    "extensions=${describeExtensions(plugin)}"
            )
        } else {
            AppLog.w(
                TAG,
                "not loaded ${manifest.id}: ${plugin.loadError ?: "unknown"} " +
                    "(extensions=${describeExtensions(plugin)})"
            )
        }
        return plugin
    }

    /** best-effort onUnload：reload / 卸载 / 初始化失败三条路径共用；插件异常绝不影响宿主。 */
    private fun unloadQuietly(plugin: LoadedPlugin) {
        val instance = plugin.plugin ?: return
        unloadQuietly(plugin.manifest.id, instance)
    }

    private fun unloadQuietly(pluginId: String, instance: HyperLyricPlugin) {
        runCatching { instance.onUnload() }
            .onSuccess { AppLog.i(TAG, "onUnload delivered to $pluginId") }
            .onFailure { error -> AppLog.w(TAG, "onUnload failed for $pluginId", error) }
    }

    fun installed(): List<LoadedPlugin> = synchronized(lock) { loaded.toList() }

    fun installed(pluginId: String): LoadedPlugin? =
        synchronized(lock) { loaded.firstOrNull { it.manifest.id == pluginId } }

    fun findProcessorOwner(processor: LyricProcessorExtension): LoadedPlugin? =
        synchronized(lock) {
            loaded.firstOrNull { plugin ->
                plugin.hostContext?.registeredExtensions?.contains(processor) == true
            }
        }

    /** 设置页保存某项配置后实时同步 onConfigChanged（下一首歌生效）。 */
    fun notifyConfigChanged(pluginId: String) {
        val plugin = installed(pluginId)
        if (plugin == null) {
            AppLog.w(TAG, "onConfigChanged ignored: $pluginId not loaded")
            return
        }
        val instance = plugin.plugin
        if (instance == null) {
            AppLog.w(
                TAG,
                "onConfigChanged ignored: $pluginId load failed (${plugin.loadError ?: "-"})"
            )
            return
        }
        val context = appContext
        if (context == null) {
            AppLog.w(TAG, "onConfigChanged ignored: $pluginId runtime not bootstrapped")
            return
        }
        runCatching {
            instance.onConfigChanged(PluginSettingsStore.asPluginConfig(context, pluginId))
        }
            .onSuccess { AppLog.i(TAG, "onConfigChanged delivered to $pluginId") }
            .onFailure { AppLog.w(TAG, "onConfigChanged failed for $pluginId", it) }
    }

    /** 激活开关从关到开时补发 onEnable（HyperLyric:宿主允许其参与处理时调用）。 */
    fun notifyEnabled(pluginId: String, enabled: Boolean) {
        val plugin = installed(pluginId)
        if (plugin == null) {
            AppLog.w(TAG, "onEnable ignored: $pluginId not loaded (enabled=$enabled)")
            return
        }
        if (!enabled) {
            AppLog.i(TAG, "onEnable skipped: $pluginId disabled")
            return
        }
        val instance = plugin.plugin
        if (instance == null) {
            AppLog.w(TAG, "onEnable ignored: $pluginId load failed (${plugin.loadError ?: "-"})")
            return
        }
        runCatching { instance.onEnable() }
            .onSuccess { AppLog.i(TAG, "onEnable delivered to $pluginId") }
            .onFailure { AppLog.w(TAG, "onEnable failed for $pluginId", it) }
    }

    fun clearCache(pluginId: String) {
        val hostContext = installed(pluginId)?.hostContext
        if (hostContext == null) {
            AppLog.w(TAG, "clearCache ignored: $pluginId not loaded")
            return
        }
        val sizeBefore = hostContext.cacheSizeBytes()
        val extension = hostContext.cacheExtension()
        if (extension != null) {
            runCatching { extension.clearAll() }
                .onSuccess { AppLog.i(TAG, "plugin cache extension clearAll for $pluginId") }
                .onFailure { error -> AppLog.w(TAG, "clearAll failed for $pluginId", error) }
        } else {
            AppLog.i(TAG, "no plugin cache extension for $pluginId; clearing host cache only")
        }
        hostContext.clearCache()
        AppLog.i(TAG, "cache cleared for $pluginId (size before=${sizeBefore}B)")
    }

    fun cacheSizeBytes(pluginId: String): Long {
        val size = installed(pluginId)?.hostContext?.cacheSizeBytes() ?: 0L
        AppLog.i(TAG, "cache size for $pluginId = ${size}B")
        return size
    }

    /**
     * 读取插件缓存条目的元数据(标题/摘要/大小/更新时间)。
     * 插件未实现 [PluginCacheExtension] 时返回 null,由界面呈现"未提供缓存管理"。
     * 调用方应在 IO 线程调用(插件侧会读缓存索引文件)。
     */
    fun listCacheEntries(pluginId: String): List<PluginCacheEntry>? {
        val extension = installed(pluginId)?.hostContext?.cacheExtension() ?: return null
        return runCatching { extension.listEntries() }
            .onSuccess { AppLog.i(TAG, "listEntries for $pluginId: ${it.size} entry(ies)") }
            .onFailure { error -> AppLog.w(TAG, "listEntries failed for $pluginId", error) }
            .getOrNull()
    }

    /**
     * 删除单条缓存条目,返回条目是否存在并已删除。
     * 插件未实现 [PluginCacheExtension] 或条目不存在时返回 false。
     */
    fun clearCacheEntry(pluginId: String, entryId: String): Boolean {
        val extension = installed(pluginId)?.hostContext?.cacheExtension() ?: return false
        return runCatching { extension.clearEntry(entryId) }
            .onSuccess { removed ->
                AppLog.i(TAG, "clearEntry for $pluginId removed=$removed")
            }
            .onFailure { error -> AppLog.w(TAG, "clearEntry failed for $pluginId", error) }
            .getOrDefault(false)
    }

    /**
     * 处理器链入口：按阶段顺序执行所有已激活插件的处理器并合并结果。
     * 返回最终快照；无插件/全部无结果时返回原始 [song]。
     */
    suspend fun processChain(
        song: PluginSong,
        mediaInfo: PluginMediaInfo?
    ): PluginSong {
        var current = song
        val context = appContext
        if (context == null) {
            AppLog.w(TAG, "processChain skipped: runtime not bootstrapped")
            return song
        }
        val processingContext = PluginProcessingContext(mediaInfo = mediaInfo)
        val plugins = installed()
        val startedAtMs = SystemClock.elapsedRealtime()
        var processorRuns = 0
        var acceptedResults = 0
        var circuitSkips = 0
        var pluginDeclaredWords = false
        AppLog.i(
            TAG,
            "processChain begin song=${describeSong(song)} rows=${song.lyrics?.size ?: 0} " +
                "plugins=${plugins.size}"
        )
        for (plugin in plugins) {
            val activated = plugin.isActivated(context)
            AppLog.i(
                TAG,
                "processChain plugin ${plugin.manifest.id}: activated=$activated " +
                    "processors=${plugin.processors.size} loadError=${plugin.loadError ?: "-"}"
            )
            if (!activated) continue
            for (processor in plugin.processors) {
                if (isTimeoutCircuitOpen(plugin.manifest.id, processor.id)) {
                    circuitSkips++
                    AppLog.w(
                        TAG,
                        "processor ${plugin.manifest.id}/${processor.id} skipped: " +
                            "timeout circuit open " +
                            "(${processorTimeoutCircuitThreshold} consecutive timeout(s))"
                    )
                    continue
                }
                processorRuns++
                val result = runProcessorSafely(
                    plugin.manifest.id,
                    processor,
                    current,
                    processingContext
                ) ?: continue
                val outcome = runCatching { PluginChainMerger.mergeWithReason(current, result) }
                    .getOrElse { error ->
                        AppLog.w(
                            TAG,
                            "merge threw for ${plugin.manifest.id}/${processor.id}",
                            error
                        )
                        PluginChainMerger.MergeOutcome(
                            null,
                            "merge threw: ${error.message ?: error.javaClass.simpleName}"
                        )
                    }
                val merged = outcome.song
                if (merged == null) {
                    AppLog.w(
                        TAG,
                        "rejected result from ${plugin.manifest.id}/${processor.id}: " +
                            "${outcome.reason}"
                    )
                    continue
                }
                acceptedResults++
                if (PluginLyricField.WORDS in result.changedLyricFields) {
                    pluginDeclaredWords = true
                }
                AppLog.i(
                    TAG,
                    "accepted result from ${plugin.manifest.id}/${processor.id}: " +
                        "changedFields=${result.changedFields} " +
                        "changedLyricFields=${result.changedLyricFields} " +
                        "mode=${result.lyricsUpdateMode}"
                )
                current = merged
            }
        }
        AppLog.i(
            TAG,
            "processChain end runs=$processorRuns accepted=$acceptedResults " +
                "circuitSkipped=$circuitSkips " +
                "changed=${current != song} elapsed=${SystemClock.elapsedRealtime() - startedAtMs}ms"
        )
        // 合并结果的单一收尾:插件声明 WORDS 时其词表(文本 + 时间戳)整份生效、词窗即最终值,
        // 合并文档的行窗在此过 ingest 同一套归一(见 [PluginChainMerger.normalizeMergedTimeline]);
        // 未声明 WORDS(宿主词表已在 ingest 归一)或全部行未动时原样返回。
        val normalized = PluginChainMerger.normalizeMergedTimeline(current, pluginDeclaredWords)
        if (normalized !== current) {
            AppLog.i(TAG, "processChain normalized merged timeline (plugin-declared words)")
        }
        return normalized
    }

    /**
     * 单次处理器执行的结局（内部可见，单测据此区分「超时」与「插件返回 null」两条路径）。
     */
    internal sealed interface ProcessorOutcome {
        /** 插件正常返回；result 为 null = 插件声明无结果。 */
        data class Completed(val result: PluginSongResult?) : ProcessorOutcome

        /** 超过 [processorTimeoutMs] 未返回。 */
        data object TimedOut : ProcessorOutcome

        /** 插件抛错（非超时、非取消）。 */
        data class Failed(val error: Throwable) : ProcessorOutcome
    }

    /**
     * 执行一次处理器调用（internal：单测直接驱动）。三条路径显式区分：
     * - 超时捕获 [TimeoutCancellationException]——旧实现用 withTimeoutOrNull + 耗时猜上限，
     *   「超时」与「插件返回 null」不可区分；
     * - 外层取消（切歌 / 换会话）继续传播，**绝不吞成 null**（对照
     *   PluginPipeline 对 CancellationException 的处理）；
     * - 忽略中断的阻塞只能等它自然返回：专用有界通道只隔离资源，不能强杀 JVM 线程。
     */
    internal suspend fun executeProcessor(
        pluginId: String,
        processor: LyricProcessorExtension,
        song: PluginSong,
        processingContext: PluginProcessingContext
    ): ProcessorOutcome {
        val startedAtMs = SystemClock.elapsedRealtime()
        return try {
            val result = withTimeout(processorTimeoutMs) {
                runInterruptible(processorDispatcher) {
                    processor.processResult(song, processingContext)
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            if (result != null) {
                AppLog.i(
                    TAG,
                    "processor $pluginId/${processor.id} (stage=${processor.stage}) " +
                        "produced result in ${elapsedMs}ms"
                )
            } else {
                AppLog.i(
                    TAG,
                    "processor $pluginId/${processor.id} (stage=${processor.stage}) " +
                        "returned no result in ${elapsedMs}ms"
                )
            }
            ProcessorOutcome.Completed(result)
        } catch (timeout: TimeoutCancellationException) {
            AppLog.w(
                TAG,
                "processor $pluginId/${processor.id} (stage=${processor.stage}) " +
                    "timed out after ${SystemClock.elapsedRealtime() - startedAtMs}ms " +
                    "(limit=${processorTimeoutMs}ms)"
            )
            ProcessorOutcome.TimedOut
        } catch (cancelled: CancellationException) {
            // 取消是正常控制流（切歌取消旧链），必须传播：吞成 null 会让调用方把
            // 「已取消」误当「插件无结果」继续合并。
            AppLog.i(TAG, "processor $pluginId/${processor.id} cancelled (outer scope cancelled)")
            throw cancelled
        } catch (error: Throwable) {
            AppLog.w(
                TAG,
                "processor $pluginId/${processor.id} (stage=${processor.stage}) threw after " +
                    "${SystemClock.elapsedRealtime() - startedAtMs}ms",
                error
            )
            ProcessorOutcome.Failed(error)
        }
    }

    /**
     * 执行一次处理器并归账熔断计数。返回 null = 无结果（含超时/抛错），与旧签名一致；
     * 区别在 [executeProcessor] 已把「超时 / 返回 null / 抛错」三条路径显式分开。
     */
    private suspend fun runProcessorSafely(
        pluginId: String,
        processor: LyricProcessorExtension,
        song: PluginSong,
        processingContext: PluginProcessingContext
    ): PluginSongResult? =
        when (val outcome = executeProcessor(pluginId, processor, song, processingContext)) {
            is ProcessorOutcome.Completed -> {
                resetTimeoutStreak(pluginId, processor.id)
                outcome.result
            }
            ProcessorOutcome.TimedOut -> {
                recordTimeout(pluginId, processor.id)
                null
            }
            is ProcessorOutcome.Failed -> {
                // 抛错不是超时：「连续超时」计数按口径归零。
                resetTimeoutStreak(pluginId, processor.id)
                null
            }
        }

    // --- 连续超时熔断（2.7）：键 = "pluginId/processorId"。 ---

    private fun circuitKey(pluginId: String, processorId: String): String = "$pluginId/$processorId"

    private fun isTimeoutCircuitOpen(pluginId: String, processorId: String): Boolean =
        (timeoutStreaks[circuitKey(pluginId, processorId)] ?: 0) >=
            processorTimeoutCircuitThreshold

    private fun recordTimeout(pluginId: String, processorId: String) {
        val key = circuitKey(pluginId, processorId)
        val streak = timeoutStreaks.merge(key, 1) { current, increment -> current + increment } ?: 1
        if (streak == processorTimeoutCircuitThreshold) {
            AppLog.w(
                TAG,
                "timeout circuit opened for $key after $streak consecutive timeout(s); " +
                    "processor disabled for this plugin session"
            )
        }
    }

    private fun resetTimeoutStreak(pluginId: String, processorId: String) {
        if (timeoutStreaks.remove(circuitKey(pluginId, processorId)) != null) {
            AppLog.i(TAG, "timeout streak reset for $pluginId/$processorId")
        }
    }

    private fun loadPlugin(context: Context, manifest: PluginManifest): LoadedPlugin {
        // 单测注入缝：跳过磁盘 dex / ClassLoader，直接提供入口实例（生产恒为 null）。
        // 之后的 onLoad 与失败清理与生产路径完全一致。
        entryLoaderOverride?.let { loader ->
            val instance = runCatching { loader(context, manifest) }.getOrNull()
            if (instance == null) {
                AppLog.w(TAG, "injected loader produced no instance for ${manifest.id}")
                return LoadedPlugin(manifest, null, null, "injected loader: no instance")
            }
            return initializePlugin(
                manifest,
                instance,
                HostPluginContext(context, manifest, manifest.cacheScopes.isNotEmpty())
            )
        }
        val dir = PluginInstaller.pluginDir(context, manifest.id)
        val dexFiles = dir.listFiles { file -> file.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (dexFiles.isEmpty()) {
            AppLog.w(TAG, "no dex files for ${manifest.id} in ${dir.absolutePath}")
            return LoadedPlugin(manifest, null, null, "no dex files")
        }
        val instance = runCatching {
            val entryClass = loadEntryClass(dexFiles, manifest)
            AppLog.i(
                TAG,
                "entry ${manifest.entry} resolved for ${manifest.id} via " +
                    "${entryClass.classLoader?.javaClass?.simpleName ?: "?"}"
            )
            entryClass.getDeclaredConstructor().newInstance() as HyperLyricPlugin
        }.getOrElse { error ->
            AppLog.w(TAG, "load failed for ${manifest.id}: ${error.message}", error)
            val failed = LoadedPlugin(manifest, null, null, error.message ?: "load failed")
            logLoadDiagnostics(manifest.id, dexFiles, failed)
            return failed
        }
        AppLog.i(TAG, "instantiated ${instance.javaClass.name} for ${manifest.id}")
        val result = initializePlugin(
            manifest,
            instance,
            HostPluginContext(context, manifest, manifest.cacheScopes.isNotEmpty())
        )
        logLoadDiagnostics(manifest.id, dexFiles, result)
        return result
    }

    /**
     * 实例化后的统一初始化：跑 onLoad；抛错时走与 reload 相同的清理路径——
     * best-effort onUnload（实例可能已建资源 / 已注册部分扩展，2.12）+ 显式 loadError。
     *
     * 初始化失败必须显式可见：插件类已实例化但 registerExtension 未必完成，按
     * 「已加载 · 无处理器」展示会被误读为插件设计如此（issue #65），置 plugin=null
     * 让 UI 走 load failed 分支。
     */
    private fun initializePlugin(
        manifest: PluginManifest,
        instance: HyperLyricPlugin,
        hostContext: HostPluginContext
    ): LoadedPlugin {
        val outcome = runCatching { instance.onLoad(hostContext) }
        outcome.exceptionOrNull()?.let { error ->
            AppLog.w(TAG, "onLoad failed for ${manifest.id}", error)
            unloadQuietly(manifest.id, instance)
            return LoadedPlugin(
                manifest,
                null,
                hostContext,
                "onLoad: ${error.message ?: error.javaClass.simpleName}"
            )
        }
        return LoadedPlugin(manifest, instance, hostContext, null)
    }

    /**
     * 构造可加载插件入口类的 ClassLoader。优先级：
     * 1. 磁盘 dex + [PluginPathClassLoader] —— 正常路径，保留 odex 缓存；
     * 2. 若主路径被系统以「可写 dex」拒绝（构造或加载阶段抛出，Android 14+/targetSdk≥34
     *    的防篡改检查），降级 [PluginInMemoryDexClassLoader] 从字节加载，完全绕开可写
     *    路径校验——适用于只读设置在某 ROM 上仍不被接受的情形。该路径只作最后手段，
     *    且必须记录双亲链便于定位可见性问题。
     *
     * 两条路径的 ClassLoader 都对非共享类子优先（issue #65 建议 ①，见
     * [PluginClassLoaderPolicy]）：插件 dex 自带的 kotlin 与 kotlinx 副本由插件
     * 自己的 ClassLoader 定义，不会被宿主 R8 收紧后的同名类顶掉，从根上避免
     * 跨 ClassLoader 访问收紧类的 IllegalAccessError。
     *
     * 加载前先把仍可写的 dex 自愈为只读：安装时已置只读，但旧版本安装残留/备份恢复
     * 可能丢失该属性（issue #65 现场 dex 即为 rw），自愈后按路径加载的主路径可用。
     */
    private fun loadEntryClass(dexFiles: List<File>, manifest: PluginManifest): Class<*> {
        ensureDexFilesReadOnly(dexFiles, manifest.id)
        val parent = javaClass.classLoader
        val dexPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
        // 两条路径都走子优先 ClassLoader（issue #65 建议 ①）：插件 dex 自带的类
        // （如 AMLL 插件的 kotlin.collections.SetsKt__SetsKt）若双亲优先会解析成
        // 宿主 R8 收紧后的同名类，跨 ClassLoader 访问抛 IllegalAccessError；
        // 子优先只作用于非共享类，API 契约类仍双亲优先。
        val pathResult = runCatching {
            PluginPathClassLoader(dexPath, parent).loadClass(manifest.entry)
        }
        pathResult.getOrNull()?.let { return it }
        AppLog.w(
            TAG,
            "path class-loading rejected for ${manifest.id} (writable dex?) → in-memory fallback",
            pathResult.exceptionOrNull()
        )
        AppLog.w(
            TAG,
            "in-memory fallback for ${manifest.id}, parent chain: ${describeClassLoaderChain(parent)}"
        )
        val memoryClass = try {
            val buffers = dexFiles.map { ByteBuffer.wrap(it.readBytes()) }.toTypedArray()
            PluginInMemoryDexClassLoader(buffers, parent).loadClass(manifest.entry)
        } catch (fallback: Throwable) {
            AppLog.w(TAG, "in-memory fallback failed for ${manifest.id}: ${fallback.message}")
            throw fallback
        }
        return memoryClass
    }

    /**
     * Android 14+/targetSdk≥34 拒绝按路径加载可写 dex。安装时已置只读，但旧版本安装
     * 残留或备份恢复可能丢失该属性（issue #65 现场即为 rw）；加载前自愈一次，让
     * PathClassLoader 主路径可用，避免落入 in-memory 回退。
     */
    private fun ensureDexFilesReadOnly(dexFiles: List<File>, pluginId: String) {
        dexFiles.forEach { dex ->
            if (!dex.canWrite()) return@forEach
            val healed = runCatching { dex.setReadOnly() }.getOrDefault(false) && !dex.canWrite()
            if (healed) {
                AppLog.w(TAG, "dex ${dex.name} of $pluginId was writable; re-applied read-only")
            } else {
                AppLog.w(TAG, "dex ${dex.name} of $pluginId still writable after setReadOnly")
            }
        }
    }

    /** 诊断用：打印 ClassLoader 双亲链（issue #65 建议，定位 in-memory 可见性问题）。 */
    private fun describeClassLoaderChain(loader: ClassLoader?): String =
        generateSequence(loader) { it.parent }
            .joinToString(" <- ") { it.javaClass.name }

    /** 诊断用：列出插件注册的扩展（类型 + id + 处理器阶段），定位「已加载但无处理器」。 */
    private fun describeExtensions(plugin: LoadedPlugin): String {
        val extensions = plugin.hostContext?.registeredExtensions.orEmpty()
        if (extensions.isEmpty()) return "[]"
        return extensions.joinToString(prefix = "[", postfix = "]") { extension ->
            when (extension) {
                is LyricProcessorExtension -> "${extension.id}@${extension.stage}"
                else -> "${extension.id}:${extension.javaClass.simpleName}"
            }
        }
    }

    /** 诊断用：歌曲身份摘要（不打印歌词正文，避免日志膨胀）。 */
    private fun describeSong(song: PluginSong): String =
        "id=${song.id ?: "-"} name=${song.name ?: "-"} artist=${song.artist ?: "-"}"

    /**
     * 加载诊断：记录 dex 可写性、API 级别、结果。dex 自带的 kotlin/kotlinx/androidx 类数量
     * （issue #65 建议 ③）**只在加载失败时按需计算**——正常加载时把每份 dex 全量读进堆只为
     * 打一行日志是纯浪费（2.6「dex 统计改按需」）；失败时它是排障的关键线索。
     */
    private fun logLoadDiagnostics(id: String, dexFiles: List<File>, result: LoadedPlugin) {
        val dex = dexFiles.joinToString(", ") {
            "${it.name}(${if (it.canWrite()) "rw" else "ro"})"
        }
        val overlap = if (result.plugin == null) {
            var total = 0
            var kotlin = 0
            var kotlinx = 0
            var androidx = 0
            dexFiles.forEach { file ->
                val summary = runCatching { file.readBytes() }.getOrNull()
                    ?.let { PluginClassLoaderPolicy.summarizeDexOverlap(it) }
                if (summary != null) {
                    total += summary.totalClasses
                    kotlin += summary.kotlinClasses
                    kotlinx += summary.kotlinxClasses
                    androidx += summary.androidxClasses
                }
            }
            "classes=$total bundled(kotlin=$kotlin kotlinx=$kotlinx androidx=$androidx)"
        } else {
            "class-overlap=skipped(load ok)"
        }
        AppLog.i(
            TAG,
            "plugin $id loaded=${result.plugin != null} " +
                "dex=[$dex] sdk=${Build.VERSION.SDK_INT} error=${result.loadError ?: "-"} " +
                overlap
        )
    }

    private const val TAG = "PluginRuntime"
}
