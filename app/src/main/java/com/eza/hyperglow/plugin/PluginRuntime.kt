package com.eza.hyperglow.plugin

import android.content.Context
import android.os.Build
import com.eza.hyperglow.AppLog
import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer

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
 */
object PluginRuntime {
    /** 与 HyperLyric 一致的单处理器宿主等待上限。 */
    const val PROCESSOR_TIMEOUT_MS = 40_000L

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

    /** App 冷启动时恢复磁盘上已安装的插件并执行 onLoad。幂等。 */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext === app) return
            appContext = app
        }
        reloadAll()
    }

    /** 重新扫描插件目录并加载（安装/卸载后调用；旧 ClassLoader 无法卸载，进程重启后彻底清理）。 */
    fun reloadAll() {
        val context = appContext ?: return
        val manifests = PluginInstaller.installed(context)
        synchronized(lock) {
            loaded.clear()
            manifests.forEach { manifest ->
                var plugin = loadPlugin(context, manifest)
                val instance = plugin.plugin
                val hostContext = plugin.hostContext
                if (instance != null && hostContext != null) {
                    runCatching { instance.onLoad(hostContext) }
                        .onFailure { error ->
                            AppLog.w(TAG, "onLoad failed for ${manifest.id}", error)
                            // 初始化失败必须显式可见：插件类已实例化但 registerExtension
                            // 未必完成，按「已加载 · 无处理器」展示会被误读为插件设计
                            // 如此（issue #65）。置 plugin=null 让 UI 走 load failed 分支。
                            plugin = LoadedPlugin(
                                manifest,
                                null,
                                hostContext,
                                "onLoad: ${error.message ?: error.javaClass.simpleName}"
                            )
                        }
                }
                loaded += plugin
            }
        }
        if (manifests.isNotEmpty()) {
            AppLog.i(TAG, "plugins loaded: ${manifests.joinToString { "${it.id}:${it.version}" }}")
        }
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
        val plugin = installed(pluginId) ?: return
        val instance = plugin.plugin ?: return
        val context = appContext ?: return
        runCatching {
            instance.onConfigChanged(PluginSettingsStore.asPluginConfig(context, pluginId))
        }.onFailure {
            AppLog.w(TAG, "onConfigChanged failed for $pluginId", it)
        }
    }

    /** 激活开关从关到开时补发 onEnable（HyperLyric:宿主允许其参与处理时调用）。 */
    fun notifyEnabled(pluginId: String, enabled: Boolean) {
        val plugin = installed(pluginId) ?: return
        if (!enabled) return
        val instance = plugin.plugin ?: return
        runCatching { instance.onEnable() }.onFailure {
            AppLog.w(TAG, "onEnable failed for $pluginId", it)
        }
    }

    fun clearCache(pluginId: String) {
        installed(pluginId)?.hostContext?.let {
            val extension = it.cacheExtension()
            if (extension != null) {
                runCatching { extension.clearAll() }
                    .onFailure { error -> AppLog.w(TAG, "clearAll failed for $pluginId", error) }
            }
            it.clearCache()
        }
    }

    fun cacheSizeBytes(pluginId: String): Long =
        installed(pluginId)?.hostContext?.cacheSizeBytes() ?: 0L

    /**
     * 处理器链入口：按阶段顺序执行所有已激活插件的处理器并合并结果。
     * 返回最终快照；无插件/全部无结果时返回原始 [song]。
     */
    suspend fun processChain(
        song: PluginSong,
        mediaInfo: PluginMediaInfo?
    ): PluginSong {
        var current = song
        val context = appContext ?: return song
        val processingContext = PluginProcessingContext(mediaInfo = mediaInfo)
        val plugins = installed()
        for (plugin in plugins) {
            if (!plugin.isActivated(context)) continue
            for (processor in plugin.processors) {
                val result = runProcessorSafely(processor, current, processingContext)
                    ?: continue
                val merged = runCatching { PluginChainMerger.merge(current, result) }
                    .getOrNull()
                if (merged == null) {
                    AppLog.w(
                        TAG,
                        "rejected result from ${plugin.manifest.id}/${processor.id}: " +
                            "merge validation failed"
                    )
                    continue
                }
                current = merged
            }
        }
        return current
    }

    private suspend fun runProcessorSafely(
        processor: LyricProcessorExtension,
        song: PluginSong,
        processingContext: PluginProcessingContext
    ): PluginSongResult? = try {
        withTimeoutOrNull(PROCESSOR_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) {
                processor.processResult(song, processingContext)
            }
        }
    } catch (error: Throwable) {
        AppLog.w(TAG, "processor ${processor.id} threw", error)
        null
    }

    private fun loadPlugin(context: Context, manifest: PluginManifest): LoadedPlugin {
        val dir = PluginInstaller.pluginDir(context, manifest.id)
        val dexFiles = dir.listFiles { file -> file.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (dexFiles.isEmpty()) {
            return LoadedPlugin(manifest, null, null, "no dex files")
        }
        val result = runCatching {
            val entryClass = loadEntryClass(dexFiles, manifest)
            val instance = entryClass.getDeclaredConstructor().newInstance() as HyperLyricPlugin
            val hostContext = HostPluginContext(
                context = context,
                manifest = manifest,
                hasCacheScope = manifest.cacheScopes.isNotEmpty()
            )
            LoadedPlugin(manifest, instance, hostContext, null)
        }.getOrElse { error ->
            AppLog.w(TAG, "load failed for ${manifest.id}: ${error.message}")
            LoadedPlugin(manifest, null, null, error.message ?: "load failed")
        }
        logLoadDiagnostics(manifest.id, dexFiles, result)
        return result
    }

    /**
     * 构造可加载插件入口类的 ClassLoader。优先级：
     * 1. 磁盘 dex + [PathClassLoader] —— 正常路径，保留 odex 缓存；
     * 2. 若主路径被系统以「可写 dex」拒绝（构造或加载阶段抛出，Android 14+/targetSdk≥34
     *    的防篡改检查），降级 [InMemoryDexClassLoader] 从字节加载，完全绕开可写路径校验——
     *    适用于只读设置在某 ROM 上仍不被接受的情形。
     *
     * in-memory 路径下插件类由独立 ClassLoader 定义，访问宿主 Kotlin 运行时中任何
     * 运行期可见性被收紧的类（如 R8 access-modification 产物）会抛 IllegalAccessError
     * （issue #65），因此它只作最后手段，且必须记录双亲链便于定位可见性问题。
     *
     * 加载前先把仍可写的 dex 自愈为只读：安装时已置只读，但旧版本安装残留/备份恢复
     * 可能丢失该属性（issue #65 现场 dex 即为 rw），自愈后 PathClassLoader 主路径可用。
     */
    private fun loadEntryClass(dexFiles: List<File>, manifest: PluginManifest): Class<*> {
        ensureDexFilesReadOnly(dexFiles, manifest.id)
        val dexPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
        val pathResult = runCatching {
            PathClassLoader(dexPath, javaClass.classLoader).loadClass(manifest.entry)
        }
        pathResult.getOrNull()?.let { return it }
        AppLog.w(
            TAG,
            "path class-loading rejected for ${manifest.id} (writable dex?) → in-memory fallback",
            pathResult.exceptionOrNull()
        )
        val parent = javaClass.classLoader
        AppLog.w(
            TAG,
            "in-memory fallback for ${manifest.id}, parent chain: ${describeClassLoaderChain(parent)}"
        )
        val memoryClass = try {
            val buffers = dexFiles.map { ByteBuffer.wrap(it.readBytes()) }.toTypedArray()
            InMemoryDexClassLoader(buffers, parent).loadClass(manifest.entry)
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

    /** 加载诊断：记录 dex 实际路径可见信息（可写性）、API 级别与结果，便于区分平台限制与真正损坏。 */
    private fun logLoadDiagnostics(id: String, dexFiles: List<File>, result: LoadedPlugin) {
        val dex = dexFiles.joinToString(", ") {
            "${it.name}(${if (it.canWrite()) "rw" else "ro"})"
        }
        AppLog.i(
            TAG,
            "plugin $id loaded=${result.plugin != null} " +
                "dex=[$dex] sdk=${Build.VERSION.SDK_INT} error=${result.loadError ?: "-"}"
        )
    }

    private const val TAG = "PluginRuntime"
}
