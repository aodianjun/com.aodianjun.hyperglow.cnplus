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

        // 多选里取消勾选「和声」(显式关闭位)时整条和声不进画布内容——即使对唱开关开着。
        val harmonyOffProfile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        duetConcurrent = true,
                        secondaryMode = "Translation,NoHarmony"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertNull(harmonySnapshot.toAodCanvasContent(harmonyOffProfile, duet = true).duetLine)

        // 多选里勾了和声(内容集合)时同样与对唱开关解耦。
        val harmonyOnProfile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        duetConcurrent = false,
                        secondaryMode = "Translation,BackgroundVocal"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertNotNull(harmonySnapshot.toAodCanvasContent(harmonyOnProfile, duet = true).duetLine)
    }

    @Test
    fun mapperDropsDuetWhenSurfaceNotParticipating() {
        assertNull(snapshot().toAodCanvasContent(duetOn).duetLine)
    }

    @Test
    fun mapperResolvesInterludeDotsWindowPerSurface() {
        // 投影层只下发**原始空隙**两端,per-surface 的开关与「显示下一行」延迟映射
        // 在映射层解析:同一份快照投到两面,各自拿到自己的窗口。
        val snapshot = LyricSnapshot(
            visible = true,
            original = "main",
            durationMs = 60_000L,
            interludeStartMs = 12_000L,
            interludeEndMs = 24_000L
        )
        // 息屏:开关开、未开「显示下一行」→ 保留 1s 延迟(参考实现默认档)。
        val aod = snapshot.toAodCanvasContent(duetOn)
        assertEquals(13_000L, aod.interludeDotsStartMs)
        assertEquals(24_000L, aod.interludeDotsEndMs)

        // 开了「显示下一行歌词」:圆点从间奏起点即开始(无需为上一行保留停留)。
        val withNext = snapshot.toAodCanvasContent(
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(showNextLine = true)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        )
        assertEquals(12_000L, withNext.interludeDotsStartMs)
        assertEquals(24_000L, withNext.interludeDotsEndMs)

        // 「辅助文字显示第二行歌词」同样把延迟映射为 0(该面也在屏上展示下一行)。
        val withSecondaryNext = snapshot.toAodCanvasContent(
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryNextLine = true)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        )
        assertEquals(12_000L, withSecondaryNext.interludeDotsStartMs)

        // 本面开关关闭:窗口整体清空,呈现与改动前逐字一致。
        val off = snapshot.toAodCanvasContent(
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(interludeCountdown = false)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        )
        assertEquals(0L, off.interludeDotsStartMs)
        assertEquals(0L, off.interludeDotsEndMs)
    }

    @Test
    fun mapperLeavesInterludeDotsEmptyWhenSnapshotCarriesNoWindow() {
        // 无长间奏的快照(0/0):两面都不会拿到窗口。
        val content = LyricSnapshot(
            visible = true,
            original = "main",
            durationMs = 60_000L
        ).toAodCanvasContent(duetOn)
        assertEquals(0L, content.interludeDotsStartMs)
        assertEquals(0L, content.interludeDotsEndMs)
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
