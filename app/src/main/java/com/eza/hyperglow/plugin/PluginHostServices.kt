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
 * 总量封顶 [MAX_TOTAL_BYTES]（写入时超限即拒绝并留痕），键名做 sha256 防路径穿越。
 */
internal class HostPluginCache(context: Context, private val pluginId: String) : PluginCache {
    private val dir = File(context.filesDir, "plugin_cache/$pluginId")

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
        runCatching {
            val sizeBefore = dir.totalSize()
            if (sizeBefore + value.size > MAX_TOTAL_BYTES) {
                AppLog.w(
                    TAG,
                    "cache quota exceeded for $pluginId key=$key " +
                        "(used=${sizeBefore}B + ${value.size}B > ${MAX_TOTAL_BYTES}B)"
                )
                return
            }
            dir.mkdirs()
            fileFor(key).writeBytes(value)
            AppLog.i(TAG, "cache put $pluginId bytes=${value.size} total=${dir.totalSize()}B")
        }.onFailure { AppLog.w(TAG, "cache write failed for $pluginId key=$key", it) }
    }

    override fun contains(key: String): Boolean =
        runCatching { fileFor(key).isFile }.getOrDefault(false)

    override fun remove(key: String) {
        val removed = runCatching { fileFor(key).delete() }.getOrDefault(false)
        if (removed) AppLog.i(TAG, "cache remove $pluginId")
    }

    override fun clear() {
        val sizeBefore = runCatching { dir.totalSize() }.getOrDefault(0L)
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        AppLog.i(TAG, "cache clear $pluginId removed=$removed freed=${sizeBefore}B")
    }

    fun totalSizeBytes(): Long = runCatching { dir.totalSize() }.getOrDefault(0L)

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val safeName = digest.joinToString("") { "%02x".format(it) }
        return File(dir, safeName)
    }

    private fun File.totalSize(): Long =
        takeIf { it.isDirectory }?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    companion object {
        private const val TAG = "PluginCache"

        /** 每插件 16 MiB 缓存配额（HyperLyric 由宿主掌握大小策略，此处取保守值）。 */
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
    }
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
