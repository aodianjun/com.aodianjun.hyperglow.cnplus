package com.eza.hyperglow.plugin

import android.content.Context
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
        if (zipBytes.isEmpty()) return null to "empty archive"
        if (zipBytes.size > MAX_ZIP_BYTES) return null to "archive too large"
        val extracted = runCatching { extract(zipBytes) }.getOrElse {
            return null to "malformed archive: ${it.message}"
        }
        val manifestText = extracted.manifest
            ?: return null to "missing $MANIFEST_ENTRY"
        if (extracted.dexEntries.isEmpty()) return null to "no classes.dex in archive"
        val manifest = PluginManifestCodec.decode(manifestText.toString(Charsets.UTF_8))
            ?: return null to "unparseable $MANIFEST_ENTRY"
        manifest.validate()?.let { return null to "invalid manifest: $it" }

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
                if (dexFile.isFile) dexFile.setReadOnly()
            }
            File(dir, MANIFEST_ENTRY).writeText(manifestText.toString(Charsets.UTF_8))
        }.getOrElse { return null to "install io failed: ${it.message}" }
        return manifest to ""
    }

    /** 卸载：删除插件目录。已加载的 ClassLoader 无法真正卸载，进程重启后彻底移除。 */
    fun uninstall(context: Context, pluginId: String): Boolean {
        val dir = pluginDir(context, pluginId)
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 枚举磁盘上已安装插件的 manifest（App 冷启动恢复用）。 */
    fun installed(context: Context): List<PluginManifest> =
        pluginsRoot(context).takeIf { it.isDirectory }
            ?.listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { dir ->
                val manifestFile = File(dir, MANIFEST_ENTRY)
                if (!manifestFile.isFile) return@mapNotNull null
                val manifest = PluginManifestCodec.decode(manifestFile.readText())
                when {
                    manifest == null -> {
                        AppLogInstall(dir.name, "manifest unreadable")
                        null
                    }
                    manifest.validate() != null -> {
                        AppLogInstall(dir.name, "manifest invalid on rescan")
                        null
                    }
                    else -> manifest
                }
            }

    private fun AppLogInstall(pluginDirName: String, reason: String) {
        com.eza.hyperglow.AppLog.w("PluginInstaller", "skip $pluginDirName: $reason")
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
}
