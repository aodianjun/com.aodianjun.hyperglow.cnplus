package com.eza.hyperglow.producer

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.LINE_TRANSITION_AUTO
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归护栏:[toProducerRenderModes] 的 transition 必须取 profile.lineTransition。
 * 历史 bug:此处借用 AOD↔锁屏联动的场景过渡 preset id(continuity/crossfade/none),
 * 小写 "none" 经 wire 归一化兜底成 "Fade up",把"无换行动画"静默变成默认动画。
 */
class LyriconRenderModeMappingTest {

    private fun compileProfile(lineTransition: String) = SceneCompiler.compile(
        CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(lineTransition = lineTransition)
            )
        )
    ).profiles.getValue(SceneCompiler.SURFACE_AOD)

    @Test
    fun transitionComesFromLineTransitionNotScenePresetId() {
        assertEquals("Zoom", compileProfile("Zoom").toProducerRenderModes().transition)
        assertEquals("None", compileProfile("None").toProducerRenderModes().transition)
        assertEquals("Slide up", compileProfile("Slide up").toProducerRenderModes().transition)
        // "Auto"(跟随源)→ 源偏好默认 Fade up
        assertEquals(
            "Fade up",
            compileProfile(LINE_TRANSITION_AUTO).toProducerRenderModes().transition
        )
    }
}
