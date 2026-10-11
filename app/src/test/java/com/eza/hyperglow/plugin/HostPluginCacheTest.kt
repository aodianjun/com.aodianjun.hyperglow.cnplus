package com.eza.hyperglow.plugin

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2.12「宿主缓存配额静默丢弃」修复的回归测试：
 * - 覆盖写按「旧值大小差」计费（同键大→小不再被误拒）；
 * - 超配额拒绝保留旧值，并在宿主侧留下可观察统计（不改 plugins/api 公开签名）；
 * - 总量口径与磁盘一致；首次使用从磁盘一次遍历建立，之后增量维护。
 *
 * 配额经构造参数注入（默认 16 MiB 不变），用小样本覆盖边界，无需写满真实配额。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HostPluginCacheTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    /** 每个用例用独立 pluginId：目录状态按路径在进程内共享，避免跨用例串状态。 */
    private fun cache(pluginId: String, maxTotalBytes: Long = 100L) =
        HostPluginCache(context(), pluginId, maxTotalBytes)

    private fun diskBytes(pluginId: String): Long {
        val dir = File(context().filesDir, "plugin_cache/$pluginId")
        return dir.takeIf { it.isDirectory }
            ?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    @Test
    fun overwritingSameKeyIsBilledBySizeDeltaNotSum() {
        val pluginId = "com.example.cache.delta"
        val cache = cache(pluginId)

        cache.putBytes("k", ByteArray(80))
        // 旧实现按 used+new 计费（80+30=110 > 100）会误拒这次缩小的覆盖写。
        cache.putBytes("k", ByteArray(30))

        assertEquals(0L, cache.stats().quotaRejections)
        assertEquals(30, cache.getBytes("k")?.size)
        assertEquals(30L, cache.totalSizeBytes())
        assertEquals(30L, diskBytes(pluginId))
    }

    @Test
    fun oversizedReplacementIsRejectedAndKeepsOldValue() {
        val pluginId = "com.example.cache.reject"
        val cache = cache(pluginId)

        cache.putBytes("k", ByteArray(60) { 1 })
        cache.putBytes("k", ByteArray(200) { 2 })

        val stats = cache.stats()
        assertEquals(1L, stats.quotaRejections)
        assertEquals(200L, stats.lastRejectedBytes)
        assertTrue(stats.lastRejectionAtEpochMs > 0L)
        val kept = cache.getBytes("k")
        assertEquals(60, kept?.size)
        assertTrue(kept!!.all { it == 1.toByte() })
        assertEquals(60L, cache.totalSizeBytes())
        assertEquals(60L, diskBytes(pluginId))
    }

    @Test
    fun quotaRejectionIsObservableToHostWithoutChangingPluginApi() {
        val pluginId = "com.example.cache.signal"
        val first = cache(pluginId)
        first.putBytes("k", ByteArray(120))

        // 宿主 reload 后重建的同目录实例共享统计：失败信号可被宿主观察。
        val second = cache(pluginId)
        val stats = second.stats()
        assertEquals(1L, stats.quotaRejections)
        assertEquals(120L, stats.lastRejectedBytes)
        assertEquals(0L, stats.writeFailures)
        assertNull(second.getBytes("k"))
        assertEquals(0L, second.totalSizeBytes())
    }

    @Test
    fun totalSizeStaysOnDiskAfterPutOverwriteRemoveAndClear() {
        val pluginId = "com.example.cache.total"
        val cache = cache(pluginId, maxTotalBytes = 1_000L)

        cache.putBytes("a", ByteArray(30))
        cache.putBytes("b", ByteArray(40))
        assertEquals(70L, cache.totalSizeBytes())
        assertEquals(diskBytes(pluginId), cache.totalSizeBytes())

        cache.putBytes("a", ByteArray(10))
        assertEquals(50L, cache.totalSizeBytes())
        assertEquals(diskBytes(pluginId), cache.totalSizeBytes())

        cache.remove("a")
        assertEquals(40L, cache.totalSizeBytes())
        assertEquals(diskBytes(pluginId), cache.totalSizeBytes())

        cache.clear()
        assertEquals(0L, cache.totalSizeBytes())
        assertEquals(0L, diskBytes(pluginId))
    }

    @Test
    fun firstUseCountsExistingFilesFromDisk() {
        val pluginId = "com.example.cache.prewarmed"
        val dir = File(context().filesDir, "plugin_cache/$pluginId")
        dir.mkdirs()
        File(dir, "prewarmed").writeBytes(ByteArray(25))

        val cache = cache(pluginId)
        assertEquals(25L, cache.totalSizeBytes())

        // 配额判断基于既有文件：25 + 80 > 100 → 拒绝，旧文件不受影响。
        cache.putBytes("k", ByteArray(80))
        assertEquals(1L, cache.stats().quotaRejections)
        assertEquals(25L, diskBytes(pluginId))
    }
}
