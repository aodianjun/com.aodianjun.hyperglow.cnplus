package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.projection.LyricDuetLine
import com.eza.hyperglow.root.projection.LyricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun mapperCarriesHarmonyAsAuxContentEvenWhenDuetSwitchOff() {
        // 和声行与「显示并发歌词(对唱)」解耦:辅助文字内容档选「和声」
        // (SECONDARY_MODE_BACKGROUND_VOCAL)时,role=BG 的 x-bg 回声即使本面关掉对唱开关
        // 也进入画布内容(走辅助行车道);同尺寸并发行(非和声)仍只认对唱开关。
        val harmonyProfile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        duetConcurrent = false,
                        secondaryMode = "BackgroundVocal"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        val harmonySnapshot = snapshot().copy(
            duetLine = LyricDuetLine(
                text = "echo",
                harmony = true,
                lineStartMs = 0L,
                lineEndMs = 1_000L
            )
        )
        val harmony = harmonySnapshot.toAodCanvasContent(harmonyProfile, duet = true).duetLine
        assertNotNull(harmony)
        assertTrue(harmony!!.harmony)
        assertEquals("echo", harmony.text)
        // 非和声并发行不受该档影响:对唱开关关闭时照旧丢弃。
        assertNull(snapshot().toAodCanvasContent(harmonyProfile, duet = true).duetLine)
    }

    @Test
    fun mapperDropsDuetWhenSurfaceNotParticipating() {
        assertNull(snapshot().toAodCanvasContent(duetOn).duetLine)
    }

    @Test
    fun mapperCarriesSecondarySizeSettingsAndFallsBackToDefaults() {
        // 辅助字号倍率/自适应大小按面透传(per-surface);profile=null(演示/无配置)回落
        // 默认 100/true —— 与 SurfaceProfile 默认值同源,画布行装配据此拟合字号。
        val custom = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        secondaryTextSizePercent = 130,
                        secondaryAutoSize = false
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val mapped = snapshot().toAodCanvasContent(custom)
        assertEquals(130, mapped.secondaryTextSizePercent)
        assertFalse(mapped.secondaryAutoSize)

        val fallback = snapshot().toAodCanvasContent(null)
        assertEquals(
            com.eza.hyperglow.customization.SECONDARY_TEXT_SIZE_PERCENT_DEFAULT,
            fallback.secondaryTextSizePercent
        )
        assertTrue(fallback.secondaryAutoSize)
    }
}
