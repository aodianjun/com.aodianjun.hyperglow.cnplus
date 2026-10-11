package com.eza.hyperglow.plugin

import android.content.Context
import com.eza.hyperglow.AppLog
import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.HYPERLYRIC_PLUGIN_API_VERSION
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginStorage
import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import java.io.File
import java.security.MessageDigest

/**
 * 宿主提供给插件的四类服务实现（logger / storage / cache / PluginContext 装配）。
 *
 * 边界原则照抄 HyperLyric：任何 Android 对象（Context/文件句柄）都不越过插件 API，
 * 缓存键做文件名安全化，所有插件触发的 IO 异常在宿主侧吞掉并留日志——插件绝不能
 * 通过 cache/storage 抛异常影响歌词管线。
 */
internal class HostPluginLogger(pluginId: String) : PluginLogger {
    private val area = "Plugin:$pluginId"

    override fun debug(message: String) {
        AppLog.i(area, message)
    }

    override fun info(message: String) {
        AppLog.i(area, message)
    }

    override fun warn(message: String, throwable: Throwable?) {
        AppLog.w(area, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        AppLog.e(area, message, throwable)
    }
}

internal class HostPluginStorage(context: Context, pluginId: String) : PluginStorage {
    private val prefs = context.getSharedPreferences("plugin_storage_$pluginId", Context.MODE_PRIVATE)
    private val area = "PluginStorage:$pluginId"

    override fun getString(key: String, defaultValue: String?): String? =
        runCatching { prefs.getString(key, defaultValue) }.getOrDefault(defaultValue)

    override fun putString(key: String, value: String) {
        runCatching { prefs.edit().putString(key, value).apply() }
            .onFailure { AppLog.w(area, "putString failed key=$key", it) }
    }

    override fun remove(key: String) {
        runCatching { prefs.edit().remove(key).apply() }
            .onFailure { AppLog.w(area, "remove failed key=$key", it) }
    }

    override fun clear() {
        val before = prefs.all.size
        runCatching { prefs.edit().clear().apply() }
            .onSuccess { AppLog.i(area, "storage cleared (keys before=$before)") }
            .onFailure { AppLog.w(area, "clear failed", it) }
    }
}

/**
 * 宿主侧持久缓存：`files/plugin_cache/<pluginId>/` 下每键一个文件。
 *
 * 总量封顶 [maxTotalBytes]（默认 [MAX_TOTAL_BYTES]）。配额语义（2026-10 修复）：
 * - 覆盖写按「旧值大小差」计费：先扣掉被替换文件占用的空间，同键大→小不再被误拒
 *   （旧实现把旧文件大小与新值相加，覆盖场景会误判超限）；
 * - 目录总量是缓存值：首次使用时一次遍历建立，之后由写入/删除/清空增量维护，
 *   不再每次 put 走两遍全目录遍历；
 * - 超配额拒绝时保留旧值，并把拒绝计数与最近一次拒绝记录在 [stats]（宿主侧可观察）。
 *   `PluginCache.putBytes` 的 Unit 签名是 plugins/api v1 公开契约，不能改；插件侧要
 *   判定是否落盘可用 `contains`/`getBytes` 回读；
 * - 配额检查与写入在同一临界区完成，多线程并发写入不会绕过配额。
 *
 * 键名做 sha256 防路径穿越。
 */
internal class HostPluginCache(
    context: Context,
    private val pluginId: String,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES
) : PluginCache {
    private val dir = File(context.filesDir, "plugin_cache/$pluginId")

    /** 目录级共享状态（总量缓存 + 统计 + 临界区），见 [directoryState]。 */
    private val state = directoryState(dir)

    override fun getString(key: String): String? =
        getBytes(key)?.toString(Charsets.UTF_8)

    override fun putString(key: String, value: String) {
        putBytes(key, value.toByteArray(Charsets.UTF_8))
    }

    override fun getBytes(key: String): ByteArray? = runCatching {
        val file = fileFor(key)
        if (file.isFile) file.readBytes() else null
    }.getOrNull()

    override fun putBytes(key: String, value: ByteArray) {
        // 外层 runCatching 保留原有边界承诺：插件触发的任何 IO/算法异常都在宿主侧吞掉。
        runCatching {
            synchronized(state) {
                val sizeBefore = state.totalSizeBytes(dir)
                val file = fileFor(key)
                val replaced = if (file.isFile) file.length() else 0L
                val projected = sizeBefore - replaced + value.size
                if (projected > maxTotalBytes) {
                    state.quotaRejections++
                    state.lastRejectedBytes = value.size.toLong()
                    state.lastRejectionAtEpochMs = System.currentTimeMillis()
                    AppLog.w(
                        TAG,
                        "cache quota exceeded for $pluginId key=$key " +
                            "(used=${sizeBefore}B - replaced=${replaced}B + ${value.size}B " +
                            "= ${projected}B > ${maxTotalBytes}B; old value kept)"
                    )
                    return
                }
                val failure = runCatching {
                    dir.mkdirs()
                    file.writeBytes(value)
                }.exceptionOrNull()
                if (failure == null) {
                    state.totalBytes = projected
                    AppLog.i(TAG, "cache put $pluginId bytes=${value.size} total=${projected}B")
                } else {
                    state.writeFailures++
                    // 写入可能留下半截文件：总量缓存作废，下次使用时重新遍历。
                    state.totalBytes = null
                    AppLog.w(TAG, "cache write failed for $pluginId key=$key", failure)
                }
            }
        }.onFailure { AppLog.w(TAG, "cache putBytes aborted for $pluginId key=$key", it) }
    }

    override fun contains(key: String): Boolean =
        runCatching { fileFor(key).isFile }.getOrDefault(false)

    override fun remove(key: String) {
        runCatching {
            synchronized(state) {
                val file = fileFor(key)
                val size = if (file.isFile) file.length() else 0L
                if (file.delete()) {
                    state.totalBytes = state.totalBytes?.let { (it - size).coerceAtLeast(0L) }
                    AppLog.i(TAG, "cache remove $pluginId")
                }
            }
        }.onFailure { AppLog.w(TAG, "cache remove failed for $pluginId key=$key", it) }
    }

    override fun clear() {
        runCatching {
            synchronized(state) {
                val sizeBefore = state.totalSizeBytes(dir)
                val removed = dir.deleteRecursively()
                state.totalBytes = if (removed) 0L else null
                AppLog.i(TAG, "cache clear $pluginId removed=$removed freed=${sizeBefore}B")
            }
        }.onFailure { AppLog.w(TAG, "cache clear failed for $pluginId", it) }
    }

    fun totalSizeBytes(): Long = runCatching {
        synchronized(state) { state.totalSizeBytes(dir) }
    }.getOrDefault(0L)

    /** 宿主侧缓存统计（总量 / 配额拒绝 / 写入失败）；不暴露缓存键与内容。 */
    fun stats(): HostPluginCacheStats = runCatching {
        synchronized(state) {
            HostPluginCacheStats(
                totalBytes = state.totalSizeBytes(dir),
                quotaRejections = state.quotaRejections,
                writeFailures = state.writeFailures,
                lastRejectedBytes = state.lastRejectedBytes,
                lastRejectionAtEpochMs = state.lastRejectionAtEpochMs
            )
        }
    }.getOrDefault(HostPluginCacheStats(0L, 0L, 0L, null, 0L))

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val safeName = digest.joinToString("") { "%02x".format(it) }
        return File(dir, safeName)
    }

    companion object {
        private const val TAG = "PluginCache"

        /** 每插件 16 MiB 缓存配额（HyperLyric 由宿主掌握大小策略，此处取保守值）。 */
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

        /**
         * 按目录路径共享的缓存状态。同一插件的 HostPluginContext 在 reload 时会重建，
         * 旧实例的后台写入线程仍可能落盘——共享总量/统计保证多实例口径一致；
         * 条目数 = 已出现过的插件缓存目录数，有界。
         */
        private val directoryStates = HashMap<String, CacheDirectoryState>()

        private fun directoryState(dir: File): CacheDirectoryState =
            synchronized(directoryStates) {
                directoryStates.getOrPut(dir.absolutePath) { CacheDirectoryState() }
            }
    }
}

/** 宿主侧插件缓存统计；见 [HostPluginContext.cacheStats]。 */
data class HostPluginCacheStats(
    val totalBytes: Long,
    /** 超配额被拒的写入次数（静默丢弃修复后的可观察计数）。 */
    val quotaRejections: Long,
    /** IO 写入失败次数。 */
    val writeFailures: Long,
    /** 最近一次被拒写入的请求字节数；无拒绝为 null。 */
    val lastRejectedBytes: Long?,
    /** 最近一次拒绝的 epoch 毫秒；无拒绝为 0。 */
    val lastRejectionAtEpochMs: Long
)

/**
 * 单个插件缓存目录的共享状态；实例本身兼作并发锁（配额检查 + 写入 + 计数原子完成）。
 */
private class CacheDirectoryState {
    /** 已落盘总量缓存；null = 尚未统计（下次使用时一次遍历建立）。 */
    var totalBytes: Long? = null
    var quotaRejections: Long = 0L
    var writeFailures: Long = 0L
    var lastRejectedBytes: Long? = null
    var lastRejectionAtEpochMs: Long = 0L

    fun totalSizeBytes(dir: File): Long =
        totalBytes ?: dir.totalSizeOnDisk().also { totalBytes = it }

    private fun File.totalSizeOnDisk(): Long =
        takeIf { it.isDirectory }?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}

/**
 * [PluginContext] 的宿主装配：onLoad 时交给插件，插件用它拿 config/logger/cache/storage
 * 并注册扩展。注册结果由 [PluginRuntime] 消费——这里只做记录，不做生命周期裁决。
 *
 * public：[PluginRuntime.LoadedPlugin] 是对外类型，其构造参数引用本类，
 * internal 会让 public API 暴露 internal 类型而编译失败。
 */
class HostPluginContext(
    private val context: Context,
    private val manifest: PluginManifest,
    private val hasCacheScope: Boolean
) : PluginContext {
    private val cacheBackend = HostPluginCache(context, manifest.id)

    override val pluginId: String = manifest.id
    override val hostApiVersion: Int = HYPERLYRIC_PLUGIN_API_VERSION
    override val config: PluginConfig = PluginSettingsStore.asPluginConfig(context, manifest.id)
    override val logger: PluginLogger = HostPluginLogger(manifest.id)
    override val storage: PluginStorage = HostPluginStorage(context, manifest.id)
    override val cache: PluginCache = if (hasCacheScope) cacheBackend else NoopPluginCache

    val registeredExtensions = mutableListOf<HyperLyricExtension>()
    private val area = "Plugin:${manifest.id}"

    override fun registerExtension(extension: HyperLyricExtension) {
        registeredExtensions += extension
        val detail = if (extension is LyricProcessorExtension) {
            "${extension.id} stage=${extension.stage}"
        } else {
            extension.id
        }
        AppLog.i(
            area,
            "registerExtension $detail (type=${extension.javaClass.simpleName}) " +
                "total=${registeredExtensions.size}"
        )
    }

    fun cacheExtension(): PluginCacheExtension? =
        registeredExtensions.filterIsInstance<PluginCacheExtension>().firstOrNull()

    fun clearCache() {
        cacheBackend.clear()
    }

    fun cacheSizeBytes(): Long = cacheBackend.totalSizeBytes()

    /**
     * 宿主侧缓存统计：总量、配额拒绝/写入失败计数与最近一次拒绝记录。
     * 配额丢弃不再只留日志：宿主（管理界面/诊断）可经此观察，插件 API 签名不变。
     */
    fun cacheStats(): HostPluginCacheStats = cacheBackend.stats()
}

private object NoopPluginCache : PluginCache {
    override fun getString(key: String): String? = null
    override fun putString(key: String, value: String) = Unit
    override fun getBytes(key: String): ByteArray? = null
    override fun putBytes(key: String, value: ByteArray) = Unit
    override fun contains(key: String): Boolean = false
    override fun remove(key: String) = Unit
    override fun clear() = Unit
}
