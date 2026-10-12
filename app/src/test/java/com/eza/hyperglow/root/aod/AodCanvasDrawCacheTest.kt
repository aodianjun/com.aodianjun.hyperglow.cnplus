package com.eza.hyperglow.root.aod

import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3 画布逐帧分配削减的机器门:派生缓存的键等价性与有界性。
 *
 * 覆盖三类新缓存:
 * 1. [BoundedDrawCache] —— 逐字合成几何缓存与扫光块行集合缓存的共用机制
 *    (同键命中同一实例、键变即 miss、容量到达清表保持有界);
 * 2. [SyntheticKaraokeKey] —— 逐字合成几何的缓存键必须覆盖合成分支的全部输入
 *    (任一输入变化即 miss,杜绝几何漂移);
 * 3. 两个共享渲染核心的扫光渐变缓存 —— 键=构造全部输入(颜色;stops 常量、位置/边界
 *    经 localMatrix 逐帧表达),同色命中同一 shader 实例、异色 miss。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AodCanvasDrawCacheTest {

    @Test
    fun boundedCacheHitsIdenticalKeyAndMissesChangedKey() {
        val cache = BoundedDrawCache<String, String>(capacity = 4)
        var computes = 0
        val first = cache.getOrCompute("line-a") {
            computes++
            "unit-a"
        }
        val again = cache.getOrCompute("line-a") {
            computes++
            "unit-a-again"
        }
        assertSame("同键必须命中已缓存实例,不再重算", first, again)
        assertEquals(1, computes)

        val other = cache.getOrCompute("line-b") {
            computes++
            "unit-b"
        }
        assertEquals("键变化必须重算", 2, computes)
        assertNotSame(first, other)
    }

    @Test
    fun boundedCacheStaysBoundedAndClearsOnOverflow() {
        val cache = BoundedDrawCache<Int, Int>(capacity = 2)
        cache.getOrCompute(1) { 10 }
        cache.getOrCompute(2) { 20 }
        assertEquals(2, cache.size)
        cache.getOrCompute(3) { 30 }
        assertTrue("容量到达即清表,缓存有界", cache.size <= 2)
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun syntheticKaraokeKeyEqualityCoversEveryGeometryInput() {
        val base = SyntheticKaraokeKey(
            text = "夜空中最亮的星",
            textSize = 27f,
            typeface = Typeface.create("sans-serif", Typeface.NORMAL),
            windowStartMs = 1_000L,
            windowEndMs = 5_000L,
            totalWidth = 420f,
            precedingWidth = 40f,
            lineStartX = 12f
        )
        val same = base.copy()
        assertEquals("同参数必须相等(命中)", base, same)
        assertEquals(base.hashCode(), same.hashCode())

        val variants = mapOf(
            "文本" to base.copy(text = "夜空中最亮的星。"),
            "字号" to base.copy(textSize = 27.5f),
            "字型" to base.copy(typeface = Typeface.create("sans-serif", Typeface.BOLD)),
            "窗口起点" to base.copy(windowStartMs = 1_001L),
            "窗口终点" to base.copy(windowEndMs = 5_001L),
            "总宽" to base.copy(totalWidth = 420.5f),
            "行前累计宽" to base.copy(precedingWidth = 40.5f),
            "行起始 x" to base.copy(lineStartX = 12.5f)
        )
        for ((name, variant) in variants) {
            assertNotEquals("$name 变化必须视为 miss(几何随之重算)", base, variant)
        }
    }

    @Test
    fun sweepLocalMatrixMapsCanonicalGradientToBakedGeometry() {
        // 等价性:规范化渐变 (0→1) + localMatrix(scale=band, translate=start) 的 device
        // 端点必须等于旧式「按 start/start+band 新建 LinearGradient」的几何端点 ——
        // 扫光带位置由共享纯函数 sweepGradientStart/sweepBandWidth 唯一给出。
        val row = LyricGlowRow(left = 30f, width = 100f, baseline = 200f, drawText = { _, _ -> })
        val progress = 0.42f
        val band = sweepBandWidth(row.width)
        val start = sweepGradientStart(row.left, row.width, progress)
        val matrix = android.graphics.Matrix()
        matrix.setScale(band, 1f)
        matrix.postTranslate(start, 0f)
        val points = floatArrayOf(0f, 0f, 1f, 0f)
        matrix.mapPoints(points)
        assertEquals("渐变起点", start, points[0], 0.0001f)
        assertEquals("渐变终点 = start + band", start + band, points[2], 0.0001f)
    }

    @Test
    fun glowSweepShaderCacheHitsSameColorAndMissesDifferentColor() {
        val sung = 0xFF33AAFF.toInt()
        val first = LyricGlowRenderer.sweepShaderForTest(sung)
        val again = LyricGlowRenderer.sweepShaderForTest(sung)
        assertSame("同 sung 色必须复用同一 shader 实例(每帧零新建)", first, again)

        val other = LyricGlowRenderer.sweepShaderForTest(0xFFFFAA33.toInt())
        assertNotSame("颜色变化必须 miss(键覆盖构造全部输入)", first, other)
    }

    @Test
    fun karaokeSweepShaderCacheHitsSameColorAndMissesDifferentColor() {
        val sung = 0xFF33AAFF.toInt()
        val first = LyricWordKaraokeRenderer.sweepShaderForTest(sung)
        val again = LyricWordKaraokeRenderer.sweepShaderForTest(sung)
        assertSame("同 sung 色必须复用同一 shader 实例(逐字档每帧 O(字数) 次新建归零)", first, again)

        val other = LyricWordKaraokeRenderer.sweepShaderForTest(0xFFFFAA33.toInt())
        assertNotSame("颜色变化必须 miss", first, other)
    }

    @Test
    fun sweepShaderCachesStayBoundedAcrossManyColors() {
        // 颜色键远超容量(8)时缓存整表清空,不随色值种类无界增长。
        val colors = (0 until 40).map { 0xFF000000.toInt() or (it * 0x010203) or 0x00040506 }
        val first = LyricGlowRenderer.sweepShaderForTest(colors.first())
        for (color in colors) LyricGlowRenderer.sweepShaderForTest(color)
        val recreated = LyricGlowRenderer.sweepShaderForTest(colors.first())
        assertNotSame("超容量后清表:最早条目应被重建(仍可用,只是重新分配)", first, recreated)

        val karaokeFirst = LyricWordKaraokeRenderer.sweepShaderForTest(colors.first())
        for (color in colors) LyricWordKaraokeRenderer.sweepShaderForTest(color)
        val karaokeRecreated = LyricWordKaraokeRenderer.sweepShaderForTest(colors.first())
        assertNotSame("逐字档缓存同样有界", karaokeFirst, karaokeRecreated)
    }
}
