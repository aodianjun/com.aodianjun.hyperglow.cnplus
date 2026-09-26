package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 换行动画模式的 profile 层语义:
 * - [normalizeLineTransition] 词表校验,未知值兜底 [LINE_TRANSITION_AUTO];
 * - [resolveLineTransition] "Auto"/缺省跟随歌词源,显式选择一票否决源偏好。
 * 二者分别被 SceneCompiler / SystemUiCustomizationValidator 与 LyricCanvasMapper /
 * LyriconRenderModeMapping 使用,是"设置即所得"与"跟随源"两种语义的唯一裁决点。
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
