package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏相关行为的纯函数测试:
 *  - 「横屏自动隐藏系统息屏内容、回落竖屏恢复」的判定 [shouldHideStockAodContent];
 *  - 横屏全屏化「自动铺满不越界」的放缩比 [fullscreenAutoScale]。
 */
class AodLandscapeBehaviorTest {

    // ---- shouldHideStockAodContent ----

    @Test
    fun globalSuppressAlwaysHides() {
        assertTrue(
            shouldHideStockAodContent(
                suppressBase = true,
                landscapeStep = false,
                rotateWithDevice = false,
                landscapeHideStock = false
            )
        )
    }

    @Test
    fun landscapeHideRequiresBothLandscapeAndRotationEnabled() {
        // 开关开启、横屏、随设备旋转 → 隐藏。
        assertTrue(
            shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = true,
                rotateWithDevice = true,
                landscapeHideStock = true
            )
        )
        // 竖屏回落 → 恢复(不隐藏)。
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = false,
                rotateWithDevice = true,
                landscapeHideStock = true
            )
        )
        // 虽横屏但未开启随设备旋转 → 不隐藏。
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = true,
                rotateWithDevice = false,
                landscapeHideStock = true
            )
        )
        // 开关未开启 → 不隐藏。
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = true,
                rotateWithDevice = true,
                landscapeHideStock = false
            )
        )
    }

    @Test
    fun fullyOffYieldsNoSuppression() {
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = false,
                rotateWithDevice = false,
                landscapeHideStock = false
            )
        )
    }

    // ---- fullscreenAutoScale ----

    @Test
    fun fillsAvailableHeightWhenRoomAllows() {
        // 可用高 1080,内容高 540,fill 0.85 → 1.7,命中上限 1.7。
        assertEquals(
            1.7f,
            fullscreenAutoScale(540f, 1080f, 0.85f, 1.0f, 1.7f),
            0.001f
        )
        // 相同输入、更宽上限 2.0 → 1080/540*0.85 = 1.7。
        assertEquals(
            1.7f,
            fullscreenAutoScale(540f, 1080f, 0.85f, 1.0f, 2.0f),
            0.001f
        )
    }

    @Test
    fun neverBelowMinimumFromShrinking() {
        // 内容比可用高更高 → 原始倍数 <1,钳制到最小 1.0(不缩小)。
        assertEquals(
            1.0f,
            fullscreenAutoScale(2000f, 1080f, 0.85f, 1.0f, 1.7f),
            0.001f
        )
    }

    @Test
    fun proportionalInterpolationRespected() {
        // 可用 1080 / 内容 800 * 0.85 = 1.1475,落于 [1.0, 1.7] 内。
        assertEquals(
            1.1475f,
            fullscreenAutoScale(800f, 1080f, 0.85f, 1.0f, 1.7f),
            0.001f
        )
    }

    @Test
    fun degenerateInputFallsBackToMinimum() {
        assertEquals(1.0f, fullscreenAutoScale(0f, 1080f, 0.85f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.0f, fullscreenAutoScale(540f, 0f, 0.85f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.0f, fullscreenAutoScale(540f, 1080f, -0.2f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.0f, fullscreenAutoScale(540f, 1080f, Float.NaN, 1.0f, 1.7f), 0.001f)
    }
}