package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换行动画模式的 profile 层语义:
 * - [normalizeLineTransition] 词表校验,未知值兜底 [LINE_TRANSITION_AUTO];
 * - [resolveLineTransition] "Auto"/缺省跟随歌词源,显式选择一票否决源偏好。
 * 二者分别被 SceneCompiler / SystemUiCustomizationValidator 与 LyricCanvasMapper /
 * LyriconRenderModeMapping 使用,是"设置即所得"与"跟随源"两种语义的唯一裁决点。
 * 速率档 [normalizeLineTransitionSpeed] 同层:词表校验、未知值兜底
 * [LINE_TRANSITION_SPEED_NORMAL];速率是纯视觉偏好,无"跟随源"语义。
 */
class LineTransitionResolutionTest {

    @Test
    fun normalizeLineTransitionKeepsVocabularyAndFallsBackToAuto() {
        for (mode in LINE_TRANSITION_MODES) {
            assertEquals(mode, normalizeLineTransition(mode))
        }
        for (unknown in listOf("Diagonal", "slide", "none", "")) {
            assertEquals(LINE_TRANSITION_AUTO, normalizeLineTransition(unknown))
        }
    }

    @Test
    fun normalizeLineTransitionSpeedKeepsVocabularyAndFallsBackToNormal() {
        for (speed in LINE_TRANSITION_SPEEDS) {
            assertEquals(speed, normalizeLineTransitionSpeed(speed))
        }
        // 旧三档值一字不改(向后兼容);新增两档同层通过。
        for (legacy in listOf("Slow", "Normal", "Fast")) {
            assertEquals(legacy, normalizeLineTransitionSpeed(legacy))
        }
        for (added in listOf("Slowest", "Fastest")) {
            assertEquals(added, normalizeLineTransitionSpeed(added))
        }
        // 词表由慢到快五档,默认档仍在表内。
        assertEquals(listOf("Slowest", "Slow", "Normal", "Fast", "Fastest"), LINE_TRANSITION_SPEEDS)
        assertTrue(LINE_TRANSITION_SPEED_NORMAL in LINE_TRANSITION_SPEEDS)
        for (unknown in listOf("slow", "Instant", "1.5x", "")) {
            assertEquals(LINE_TRANSITION_SPEED_NORMAL, normalizeLineTransitionSpeed(unknown))
        }
    }

    @Test
    fun normalizeLineTransitionAliasesLegacyShortNamesToPresets() {
        // #95 短名档与对应 HyperLyric 预设同配方,归一到预设 id;预设 id 幂等。
        assertEquals("fade_out_left_fade_in_right", normalizeLineTransition("Fade left"))
        assertEquals("fade_out_left_landing", normalizeLineTransition("Landing"))
        assertEquals("slide_out_left_slide_in_right", normalizeLineTransition("Slide swap"))
        for (id in listOf("fade_out_left_fade_in_right", "flip_out_x_flip_in_x", "zoom_out_zoom_in")) {
            assertEquals(id, normalizeLineTransition(id))
        }
    }

    @Test
    fun resolveLineTransitionAutoDefersToSourceAndExplicitChoiceWins() {
        // "Auto" / 缺省 → 跟随歌词源
        assertEquals("Crossfade", resolveLineTransition(LINE_TRANSITION_AUTO, "Crossfade"))
        assertEquals("Crossfade", resolveLineTransition(null, "Crossfade"))
        assertEquals("Fade up", resolveLineTransition(LINE_TRANSITION_AUTO, "Fade up"))
        // 显式选择一票否决源偏好(包括显式 "None" 覆盖源的换行动画)
        assertEquals("Zoom", resolveLineTransition("Zoom", "Fade up"))
        assertEquals("None", resolveLineTransition("None", "Slide up"))
        assertEquals("Slide left", resolveLineTransition("Slide left", "None"))
    }
}
