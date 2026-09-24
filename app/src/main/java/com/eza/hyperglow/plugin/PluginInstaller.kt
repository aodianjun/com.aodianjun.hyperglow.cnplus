package com.eza.hyperglow.plugin

import android.content.Context
import com.eza.hyperglow.AppLog
import java.io.File
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
    private const val MAX_DEX_COUNT = 8

    fun pluginsRoot(context: Context): File = File(context.filesDir, "plugins")

    fun pluginDir(context: Context, pluginId: String): File =
        File(pluginsRoot(context), pluginId)

    /**
     * 校验并安装 ZIP。成功返回解析出的 manifest；失败返回 null，[reason] 写入拒绝原因。
     * 同 id 插件重复安装 = 覆盖升级（先卸载旧目录再落盘新版本）。
     */
    fun install(context: Context, zipBytes: ByteArray): Pair<PluginManifest?, String> {
        AppLog.i(TAG, "install begin bytes=${zipBytes.size}")
        if (zipBytes.isEmpty()) return reject("empty archive")
        if (zipBytes.size > MAX_ZIP_BYTES) {
            return reject("archive too large: ${zipBytes.size}B > ${MAX_ZIP_BYTES}B")
        }
        val extracted = runCatching { extract(zipBytes) }.getOrElse {
            return reject("malformed archive: ${it.message}")
        }
        val manifestText = extracted.manifest
            ?: return reject("missing $MANIFEST_ENTRY")
        if (extracted.dexEntries.isEmpty()) return reject("no classes.dex in archive")
        val manifest = PluginManifestCodec.decode(manifestText.toString(Charsets.UTF_8))
            ?: return reject("unparseable $MANIFEST_ENTRY")
        manifest.validate()?.let { return reject("invalid manifest: $it") }

        val dir = pluginDir(context, manifest.id)
        runCatching {
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            extracted.dexEntries.forEachIndexed { index, bytes ->
                val dexFile = File(dir, "classes${index + 1}.dex")
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
            File(dir, MANIFEST_ENTRY).writeText(manifestText.toString(Charsets.UTF_8))
        }.getOrElse { return reject("install io failed: ${it.message}") }
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
        val dirs = root.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
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

    private fun extract(zipBytes: ByteArray): Extracted {
        var manifest: ByteArray? = null
        val dexEntries = mutableListOf<ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                when {
                    name == MANIFEST_ENTRY && manifest == null -> {
                        manifest = stream.readNBytes(MAX_ZIP_BYTES.toInt())
                    }
                    name.startsWith("classes") && name.endsWith(".dex") &&
                        dexEntries.size < MAX_DEX_COUNT -> {
                        dexEntries += stream.readNBytes(MAX_ZIP_BYTES.toInt())
                    }
                }
                stream.closeEntry()
            }
        }
        return Extracted(manifest, dexEntries)
    }

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val TAG = "PluginInstaller"
}
