package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画布内部 normalizeAodAnimation 与 aod/AodRenderPreferences 同名函数的漂移守卫。
 *
 * 画布(root.aod 包)按同包解析调用本包副本,不会走 aod 包版本;两处曾因不同步
 * (副本只认 Minimal)导致 BetterLyrics 档在画布被静默压回 Gradient——表现即
 * 「设置只在预览生效、实机不变」。新增动画档时本测试会先红。
 */
class AodCanvasTextMetricsTest {
    @Test
    fun canvasNormalizeAnimationMatchesAodPreferences() {
        val values = listOf("Minimal", "Gradient", "BetterLyrics", "Karaoke fill", "bogus", "")
        for (value in values) {
            assertEquals(
                "root.aod copy must match aod/AodRenderPreferences for '$value'",
                com.eza.hyperglow.aod.normalizeAodAnimation(value),
                normalizeAodAnimation(value)
            )
        }
    }
}
