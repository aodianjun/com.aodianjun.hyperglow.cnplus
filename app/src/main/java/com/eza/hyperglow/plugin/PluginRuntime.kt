package com.eza.hyperglow.plugin

import android.content.Context
import com.eza.hyperglow.AppLog
import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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
        /** 加载失败原因；plugin==null 时非空。 */
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
                val plugin = loadPlugin(context, manifest)
                val instance = plugin.plugin
                val hostContext = plugin.hostContext
                if (instance != null && hostContext != null) {
                    runCatching { instance.onLoad(hostContext) }
                        .onFailure {
                            AppLog.w(TAG, "onLoad failed for ${manifest.id}", it)
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
        return runCatching {
            val dexPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
            val loader = PathClassLoader(dexPath, javaClass.classLoader)
            val entryClass = loader.loadClass(manifest.entry)
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
    }

    private const val TAG = "PluginRuntime"
}
