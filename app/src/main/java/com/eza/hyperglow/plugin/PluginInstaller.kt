package com.eza.hyperglow.plugin

import android.content.Context
import com.eza.hyperglow.AppLog
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 插件 ZIP 安装/卸载（HyperLyric 打包格式）。
 *
 * ZIP 结构：`manifest.json` + 一个或多个 `classes.dex`。安装时把 dex 抽到
 * `files/plugins/<id>/` 供 [PluginRuntime] 用 DexClassLoader 加载。manifest 只解析
 * 校验不落盘重复副本——解析结果由运行时持有，磁盘上的原始 manifest.json 保留用于
 * App 重启后恢复插件列表。
 */
object PluginInstaller {
    /** ZIP 大小上限 32 MiB：翻译插件带缓存逻辑也不该把巨包塞进宿主。 */
    const val MAX_ZIP_BYTES = 32L * 1024 * 1024

    /** 解压后未压缩总量上限 64 MiB：压缩包大小上限不约束解压总量，须单独设预算。 */
    private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024
    private const val MAX_DEX_COUNT = 8

    /** 安装临时目录前缀：先写这里、成功后换名成正式目录；残留（崩溃）不被当成插件。 */
    private const val STAGING_PREFIX = ".staging-"

    fun pluginsRoot(context: Context): File = File(context.filesDir, "plugins")

    fun pluginDir(context: Context, pluginId: String): File =
        File(pluginsRoot(context), pluginId)

    /**
     * 校验并安装 ZIP。成功返回解析出的 manifest；失败返回 null，[reason] 写入拒绝原因。
     * 同 id 插件重复安装 = 覆盖升级（解压校验通过后，写临时目录再原子换名替换旧目录）。
     *
     * [maxEntryBytes] / [maxTotalBytes] 是解压预算，默认即生产上限；单测注入小值以便用
     * 小样本覆盖超限路径，默认行为不变。
     */
    fun install(
        context: Context,
        zipBytes: ByteArray,
        maxEntryBytes: Long = MAX_ZIP_BYTES,
        maxTotalBytes: Long = MAX_UNCOMPRESSED_BYTES,
    ): Pair<PluginManifest?, String> {
        AppLog.i(TAG, "install begin bytes=${zipBytes.size}")
        if (zipBytes.isEmpty()) return reject("empty archive")
        if (zipBytes.size > MAX_ZIP_BYTES) {
            return reject("archive too large: ${zipBytes.size}B > ${MAX_ZIP_BYTES}B")
        }
        val extracted = runCatching { extract(zipBytes, maxEntryBytes, maxTotalBytes) }.getOrElse { error ->
            return when (error) {
                is ExtractionLimitException -> reject("archive exceeds extraction limits: ${error.message}")
                else -> reject("malformed archive: ${error.message}")
            }
        }
        val manifestText = extracted.manifest
            ?: return reject("missing $MANIFEST_ENTRY")
        if (extracted.dexEntries.isEmpty()) return reject("no classes.dex in archive")
        val manifest = PluginManifestCodec.decode(manifestText.toString(Charsets.UTF_8))
            ?: return reject("unparseable $MANIFEST_ENTRY")
        manifest.validate()?.let { return reject("invalid manifest: $it") }

        val dir = pluginDir(context, manifest.id)
        // 先写临时目录、成功后再原子换名替换旧目录：中途失败（磁盘满/进程被杀）不会
        // 留下半截的插件目录，旧版本在换名成功前一直可用。
        val staging = File(pluginsRoot(context), STAGING_PREFIX + manifest.id)
        runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            extracted.dexEntries.forEachIndexed { index, bytes ->
                val dexFile = File(staging, "classes${index + 1}.dex")
                dexFile.writeBytes(bytes)
                // Android 14+ 拒绝通过 DexClassLoader 加载「位于可写位置、且自身可写」的
                // dex（防篡改）。已固化的插件 dex 须置只读，否则 PathClassLoader 在
                // targetSdk≥34 时抛 "Writable dex file … is not allowed"。
                // 失败仅记日志：PluginRuntime 加载前会再做一次只读自愈（issue #65）。
                val readOnly = dexFile.isFile && !dexFile.canWrite()
                if (dexFile.isFile && !dexFile.setReadOnly() && !readOnly) {
                    AppLog.w(TAG, "setReadOnly rejected for ${manifest.id}/${dexFile.name}")
                }
                AppLog.i(
                    TAG,
                    "wrote ${manifest.id}/${dexFile.name} bytes=${bytes.size} " +
                        "readOnly=${if (dexFile.canWrite()) "no" else "yes"}"
                )
            }
            File(staging, MANIFEST_ENTRY).writeText(manifestText.toString(Charsets.UTF_8))
            // 换名是原子操作：删除旧目录与换名之间只剩两次元数据操作，
            // 失败窗口远小于「逐文件落盘」的旧流程。
            if (!dir.deleteRecursively()) {
                throw IOException("cannot replace existing ${dir.absolutePath}")
            }
            if (!staging.renameTo(dir)) {
                throw IOException("staging rename failed: ${staging.absolutePath} -> ${dir.absolutePath}")
            }
        }.getOrElse { error ->
            runCatching { staging.deleteRecursively() }
            return reject("install io failed: ${error.message}")
        }
        AppLog.i(
            TAG,
            "install ok ${manifest.id} v${manifest.version} dex=${extracted.dexEntries.size} " +
                "dir=${dir.absolutePath}"
        )
        return manifest to ""
    }

    /** 卸载：删除插件目录。已加载的 ClassLoader 无法真正卸载，进程重启后彻底移除。 */
    fun uninstall(context: Context, pluginId: String): Boolean {
        val dir = pluginDir(context, pluginId)
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (removed) {
            AppLog.i(TAG, "uninstall $pluginId removed dir=${dir.absolutePath}")
        } else {
            AppLog.w(TAG, "uninstall $pluginId failed dir=${dir.absolutePath}")
        }
        return removed
    }

    /** 枚举磁盘上已安装插件的 manifest（App 冷启动恢复用）。 */
    fun installed(context: Context): List<PluginManifest> {
        val root = pluginsRoot(context)
        if (!root.isDirectory) {
            AppLog.i(TAG, "rescan: plugin root absent (${root.absolutePath})")
            return emptyList()
        }
        // 安装临时目录（.staging-*）不是插件：崩溃残留不得被当成已安装插件加载。
        val dirs = root.listFiles { file -> file.isDirectory && !file.name.startsWith(STAGING_PREFIX) }
            .orEmpty().sortedBy { it.name }
        val manifests = dirs.mapNotNull { dir ->
            val manifestFile = File(dir, MANIFEST_ENTRY)
            if (!manifestFile.isFile) {
                AppLog.w(TAG, "skip ${dir.name}: missing $MANIFEST_ENTRY")
                return@mapNotNull null
            }
            val manifest = PluginManifestCodec.decode(manifestFile.readText())
            when {
                manifest == null -> {
                    AppLog.w(TAG, "skip ${dir.name}: manifest unreadable")
                    null
                }
                manifest.validate() != null -> {
                    AppLog.w(TAG, "skip ${dir.name}: manifest invalid on rescan")
                    null
                }
                else -> manifest
            }
        }
        AppLog.i(
            TAG,
            "rescan: ${dirs.size} dir(s) → ${manifests.size} valid manifest(s) " +
                manifests.joinToString(prefix = "[", postfix = "]") { "${it.id}:${it.version}" }
        )
        return manifests
    }

    private fun reject(reason: String): Pair<PluginManifest?, String> {
        AppLog.w(TAG, "install rejected: $reason")
        return null to reason
    }

    private class Extracted(
        val manifest: ByteArray?,
        val dexEntries: List<ByteArray>
    )

    /** 解压超限（单条目超长 / 未压缩总量超预算）：独立类型，拒绝理由与「ZIP 损坏」区分。 */
    private class ExtractionLimitException(message: String) : IOException(message)

    /**
     * 解压 ZIP 里的 manifest 与 dex。两条硬约束（防解压炸弹与静默截断）：
     * - 单条目读满 [maxEntryBytes] 后仍能读到数据 = 条目超长，判非法——绝不静默截断成半截 dex；
     * - 累计未压缩字节超过 [maxTotalBytes] 立即拒绝——ZIP 大小上限不约束解压总量，
     *   只靠单条目上限时 manifest + 8 份 dex 最坏可在堆上同时驻留数百 MiB。
     */
    private fun extract(zipBytes: ByteArray, maxEntryBytes: Long, maxTotalBytes: Long): Extracted {
        var manifest: ByteArray? = null
        val dexEntries = mutableListOf<ByteArray>()
        var totalBytes = 0L
        ZipInputStream(zipBytes.inputStream()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                val wanted = (name == MANIFEST_ENTRY && manifest == null) ||
                    (name.startsWith("classes") && name.endsWith(".dex") &&
                        dexEntries.size < MAX_DEX_COUNT)
                if (wanted) {
                    val bytes = readEntryBytes(stream, name, maxEntryBytes)
                    totalBytes += bytes.size
                    if (totalBytes > maxTotalBytes) {
                        throw ExtractionLimitException(
                            "uncompressed total ${totalBytes}B > ${maxTotalBytes}B (after $name)"
                        )
                    }
                    if (name == MANIFEST_ENTRY) manifest = bytes else dexEntries += bytes
                }
                stream.closeEntry()
            }
        }
        return Extracted(manifest, dexEntries)
    }

    /** 读取单个条目：读满上限后还能读到数据即条目超长（判非法），而不是截断落盘。 */
    private fun readEntryBytes(stream: ZipInputStream, name: String, limit: Long): ByteArray {
        val cap = limit.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val bytes = stream.readNBytes(cap)
        // readNBytes 读满 cap 只说明「至少这么多」：再探一个字节，还有数据就是超长条目。
        if (bytes.size == cap && stream.read() >= 0) {
            throw ExtractionLimitException("entry $name exceeds ${limit}B")
        }
        return bytes
    }

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val TAG = "PluginInstaller"
}
