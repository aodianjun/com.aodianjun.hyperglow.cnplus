package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE_REVERSE
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_PORTRAIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodOrientationMonitorTest {

    // ---- resolveAodRotationStep: 纯函数判定 ----

    @Test
    fun `portrait mode never rotates`() {
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_PORTRAIT, 9.8f, 0f))
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_PORTRAIT, -9.8f, 0f))
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_PORTRAIT, 0f, 9.8f))
    }

    @Test
    fun `portrait orientation gravity resolves to null`() {
        // 竖直(gY 主导)在任意非 auto 取景下都应保持竖屏。
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_LANDSCAPE, 0.5f, 9.7f))
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_LANDSCAPE_REVERSE, 0.5f, 9.7f))
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_AUTO, 0.5f, 9.7f))
    }

    @Test
    fun `landscape mode rotates to landscape regardless of side`() {
        assertEquals(
            AodOrientationStep.LANDSCAPE,
            resolveAodRotationStep(AOD_ROTATION_MODE_LANDSCAPE, 9.8f, 0.4f)
        )
        assertEquals(
            AodOrientationStep.LANDSCAPE,
            resolveAodRotationStep(AOD_ROTATION_MODE_LANDSCAPE, -9.8f, 0.4f)
        )
    }

    @Test
    fun `landscape reverse mode rotates to fixed reverse landscape`() {
        assertEquals(
            AodOrientationStep.REVERSE_LANDSCAPE,
            resolveAodRotationStep(AOD_ROTATION_MODE_LANDSCAPE_REVERSE, 9.8f, 0.4f)
        )
        assertEquals(
            AodOrientationStep.REVERSE_LANDSCAPE,
            resolveAodRotationStep(AOD_ROTATION_MODE_LANDSCAPE_REVERSE, -9.8f, 0.4f)
        )
    }

    @Test
    fun `auto mode distinguishes landscape sides and keeps portrait when upright`() {
        assertEquals(
            AodOrientationStep.LANDSCAPE,
            resolveAodRotationStep(AOD_ROTATION_MODE_AUTO, 9.8f, 0.4f)
        )
        assertEquals(
            AodOrientationStep.REVERSE_LANDSCAPE,
            resolveAodRotationStep(AOD_ROTATION_MODE_AUTO, -9.8f, 0.4f)
        )
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_AUTO, 0.4f, 9.8f))
    }

    @Test
    fun `near-zero tie keeps portrait and non-finite input returns null`() {
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_AUTO, 0f, 0f))
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_AUTO, Float.NaN, 9.8f))
        assertNull(resolveAodRotationStep(AOD_ROTATION_MODE_AUTO, Float.POSITIVE_INFINITY, 0f))
    }

    @Test
    fun `unknown mode never rotates`() {
        assertNull(resolveAodRotationStep("tilted", 9.8f, 0.4f))
    }

    // ---- shouldEmitRotationStep: 稳定性判定 ----

    @Test
    fun `emits only when candidate differs from current and meets stability`() {
        // 候选 == 当前:不发射。
        assertFalse(shouldEmitRotationStep(AodOrientationStep.LANDSCAPE, AodOrientationStep.LANDSCAPE, 5, 5))
        // 候选为空:不发射(返回竖屏由监听器自行决定,避免抖动)。
        assertFalse(shouldEmitRotationStep(AodOrientationStep.LANDSCAPE, null, 10, 1))
        // 稳定帧不足:不发射。
        assertFalse(shouldEmitRotationStep(null, AodOrientationStep.LANDSCAPE, 2, 5))
        // 满足条件:发射。
        assertTrue(shouldEmitRotationStep(null, AodOrientationStep.LANDSCAPE, 5, 5))
        assertTrue(shouldEmitRotationStep(AodOrientationStep.PORTRAIT, AodOrientationStep.REVERSE_LANDSCAPE, 8, 3))
        // 从 null(current 未知)出发也要能落地。
        assertTrue(shouldEmitRotationStep(null, AodOrientationStep.PORTRAIT, 4, 4))
    }
}