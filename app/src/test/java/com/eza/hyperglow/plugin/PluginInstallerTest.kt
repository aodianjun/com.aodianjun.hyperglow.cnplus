package com.eza.hyperglow.plugin

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP 安装/解压的两条硬约束（2.9）：
 * - 累计未压缩总量超预算拒绝（压缩包上限不约束解压总量）；
 * - 单条目超过上限判非法，绝不静默截断成半截 dex 落盘。
 *
 * 预算参数可注入，因此用小样本（几十到几百字节）即可覆盖超限路径，默认上限不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginInstallerTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private val manifestJson = """
        { "id": "com.example.testplugin", "name": "Test", "entry": "com.example.TestPlugin", "apiVersion": 1 }
    """.trimIndent()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun bytes(size: Int) = ByteArray(size) { 'x'.code.toByte() }

    /** Windows 主机上只读文件会挡住 Robolectric 临时目录清理：交还前恢复可写。 */
    private fun makeWritable(root: File) {
        root.walkBottomUp().forEach { it.setWritable(true) }
    }

    @Test
    fun installsSmallArchiveWithManifestAndDex() {
        val zip = zipOf(
            "manifest.json" to manifestJson.toByteArray(),
            "classes.dex" to bytes(64),
            "classes2.dex" to bytes(128),
        )
        val (manifest, reason) = PluginInstaller.install(context(), zip)
        assertEquals("", reason)
        assertNotNull(manifest)
        val installed = manifest!!
        assertEquals("com.example.testplugin", installed.id)

        val dir = PluginInstaller.pluginDir(context(), installed.id)
        assertEquals(64L, File(dir, "classes1.dex").length())
        assertEquals(128L, File(dir, "classes2.dex").length())
        assertEquals(manifestJson, File(dir, "manifest.json").readText())
        // Android 14+ 的加载约束：落盘的 dex 必须只读。
        assertFalse(File(dir, "classes1.dex").canWrite())

        // 安装临时目录不得残留（旧目录替换是「写临时目录 + 原子换名」）。
        val leftovers = PluginInstaller.pluginsRoot(context()).listFiles().orEmpty()
            .filter { it.name.startsWith(".staging-") }
        assertTrue("staging leftovers: $leftovers", leftovers.isEmpty())

        makeWritable(dir)
    }

    @Test
    fun rejectsWhenUncompressedTotalExceedsBudget() {
        // 每个条目都在单条目上限内，但累计超过总量预算：必须拒绝，理由可辨识。
        val zip = zipOf(
            "manifest.json" to manifestJson.toByteArray(),
            "classes.dex" to bytes(80),
            "classes2.dex" to bytes(80),
        )
        val (manifest, reason) = PluginInstaller.install(
            context(),
            zip,
            maxEntryBytes = 1024,
            maxTotalBytes = 150,
        )
        assertNull(manifest)
        assertTrue("reason=$reason", reason.contains("exceeds extraction limits"))
        assertTrue("reason=$reason", reason.contains("uncompressed total"))
        assertFalse(
            "拒绝时不得创建插件目录",
            PluginInstaller.pluginDir(context(), "com.example.testplugin").exists(),
        )
    }

    @Test
    fun rejectsOversizedEntryInsteadOfTruncating() {
        // 单条目内容多于上限：判非法（不是截断成半截 dex 落盘）。
        val zip = zipOf(
            "manifest.json" to manifestJson.toByteArray(),
            "classes.dex" to bytes(300),
        )
        val (manifest, reason) = PluginInstaller.install(
            context(),
            zip,
            maxEntryBytes = 160,
            maxTotalBytes = 4096,
        )
        assertNull(manifest)
        assertTrue("reason=$reason", reason.contains("exceeds extraction limits"))
        assertTrue("reason=$reason", reason.contains("classes.dex"))
        assertFalse(
            "拒绝时不得创建插件目录",
            PluginInstaller.pluginDir(context(), "com.example.testplugin").exists(),
        )
    }

    @Test
    fun acceptsEntryExactlyAtLimit() {
        // 边界：条目恰好等于上限且没有更多数据 = 合法，不得误判为超长。
        val zip = zipOf(
            "manifest.json" to manifestJson.toByteArray(),
            "classes.dex" to bytes(128),
        )
        val (manifest, reason) = PluginInstaller.install(
            context(),
            zip,
            maxEntryBytes = 128,
            maxTotalBytes = 4096,
        )
        assertEquals("", reason)
        assertNotNull(manifest)
        val dir = PluginInstaller.pluginDir(context(), manifest!!.id)
        assertEquals(128L, File(dir, "classes1.dex").length())
        makeWritable(dir)
    }

    @Test
    fun stagingLeftoversAreIgnoredByRescan() {
        // 安装中途崩溃可能留下 .staging-* 目录：冷启动恢复不得把它当成已安装插件。
        val staging = File(PluginInstaller.pluginsRoot(context()), ".staging-com.example.testplugin")
        staging.mkdirs()
        File(staging, "manifest.json").writeText(manifestJson)

        assertTrue(PluginInstaller.installed(context()).isEmpty())

        staging.deleteRecursively()
    }
}
