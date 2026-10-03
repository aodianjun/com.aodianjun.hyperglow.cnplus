package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.projection.LyricDuetLine
import com.eza.hyperglow.root.projection.LyricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 并发行的 per-surface 门控:曲面参与对唱渲染([toAodCanvasContent] 的 duet 参数)且
 * 该曲面自己的 `duetConcurrent` 开启时,快照的并发行才进入画布内容(锁屏与息屏各自独立)。
 */
class LyricCanvasMapperTest {

    private val duetOff = SceneCompiler.compile(
        CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(duetConcurrent = false)
            )
        )
    ).profiles.getValue(SceneCompiler.SURFACE_AOD)

    private val duetOn = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        .profiles.getValue(SceneCompiler.SURFACE_AOD)

    private fun snapshot() = LyricSnapshot(
        visible = true,
        original = "main",
        durationMs = 10_000L
    ).copy(
        duetLine = LyricDuetLine(text = "second", lineStartMs = 0L, lineEndMs = 1_000L)
    )

    @Test
    fun mapperCarriesDuetWhenSurfaceWantsIt() {
        val content = snapshot().toAodCanvasContent(duetOn, duet = true)
        assertNotNull(content.duetLine)
        assertEquals("second", content.duetLine!!.text)
    }

    @Test
    fun mapperDropsDuetWhenSurfaceSwitchOff() {
        assertNull(snapshot().toAodCanvasContent(duetOff, duet = true).duetLine)
    }

    @Test
    fun mapperDropsDuetWhenSurfaceNotParticipating() {
        assertNull(snapshot().toAodCanvasContent(duetOn).duetLine)
    }
}
