package com.eza.hyperglow.ui

import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizationPreviewTest {
    @Test
    fun previewPlacementUsesNotificationAndFodReserves() {
        val profile = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
            .copy(enabled = true)

        val notification = resolvePreviewPlacement(
            profile,
            "Lockscreen · notifications",
            1_000f,
            500f
        )
        val notificationEnvironment = previewEnvironment(
            "Lockscreen · notifications",
            1_000f,
            500f
        )
        assertTrue(
            notification.contentRect?.bottom ?: Float.POSITIVE_INFINITY <=
                notificationEnvironment.notificationTop!!
        )

        val fod = resolvePreviewPlacement(profile, "FOD safe region", 1_000f, 500f)
        val fodEnvironment = previewEnvironment("FOD safe region", 1_000f, 500f)
        assertTrue(
            fod.contentRect?.bottom ?: Float.POSITIVE_INFINITY <= fodEnvironment.bottomReserveTop
        )
    }

    @Test
    fun palettePresetAndMetadataToggleStayDeclarative() {
        val dimmed = palettePreset("dimmed")
        assertTrue(dimmed.isNotEmpty())
        assertEquals("dimmed", palettePresetName(dimmed))
        assertEquals("default", palettePresetName(emptyMap()))

        val hidden = withMetadataVisible(SurfaceProfile(), false)
        assertFalse(hidden.metadataVisible)
        assertFalse(hidden.widgets.any { it.type == "metadata" })

        val visible = withMetadataVisible(hidden, true)
        assertTrue(visible.metadataVisible)
        assertTrue(visible.widgets.any { it.type == "metadata" })
    }

    @Test
    fun paletteColorWritesAndClearsEverySemanticKey() {
        // 每个语义色键都能独立写入任意 hex 颜色
        var palette = emptyMap<String, String>()
        for (key in PaletteColor.entries) {
            palette = applyPaletteColor(palette, key, "#123456")
        }
        for (key in PaletteColor.entries) {
            assertEquals("#123456", palette[key.token])
            assertEquals("#123456", paletteValue(palette, key))
        }

        // default 只清除对应键并回落默认,其余键保留
        palette = applyPaletteColor(palette, PaletteColor.METADATA_TEXT, PALETTE_DEFAULT)
        assertNull(palette[PaletteColor.METADATA_TEXT.token])
        assertEquals(PALETTE_DEFAULT, paletteValue(palette, PaletteColor.METADATA_TEXT))
        assertEquals("#123456", palette[PaletteColor.NEXT_LINE_TEXT.token])
        assertEquals("#123456", palette[PaletteColor.PRIMARY_TEXT.token])
    }

    @Test
    fun paletteEffectiveArgbFallsBackScalesDimmedAndReadsHex() {
        // 缺省回退到键默认色(歌曲信息默认为灰)
        assertEquals(
            PaletteColor.METADATA_TEXT.defaultArgb,
            paletteEffectiveArgb(emptyMap(), PaletteColor.METADATA_TEXT)
        )
        // 任意 hex 原样生效
        assertEquals(
            0xFF123456.toInt(),
            paletteEffectiveArgb(mapOf(PaletteColor.ACCENT.token to "#123456"), PaletteColor.ACCENT)
        )
        // dimmed 预设按 72% 亮度折算白色(255 * 0.72 ≈ 184 = 0xB8)
        assertEquals(
            0xFFB8B8B8.toInt(),
            paletteEffectiveArgb(
                mapOf(PaletteColor.PRIMARY_TEXT.token to PALETTE_DIMMED),
                PaletteColor.PRIMARY_TEXT
            )
        )
    }

    @Test
    fun argbToColorTokenDropsAlphaChannel() {
        assertEquals("#FFD9A0", argbToColorToken(0xFFFFD9A0.toInt()))
        assertEquals("#000000", argbToColorToken(0xFF000000.toInt()))
    }

    @Test
    fun everyPaletteKeySurvivesSceneCompilation() {
        // 9 个语义色键全部通过编译白名单(SceneCompiler / SystemUi 两侧 SEMANTIC_COLORS)
        val palette = PaletteColor.entries.associate { it.token to "#123456" }
        val compiled = SceneCompiler.compile(
            com.eza.hyperglow.customization.CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(palette = palette)
                )
            )
        )
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
        for (key in PaletteColor.entries) {
            assertEquals("#123456", aod.palette[key.token])
        }
    }

    @Test
    fun previewMetadataTextSizeScalesWithUserPercent() {
        assertEquals(10f, previewMetadataTextSizeSp(100).value, 0.0001f)
        assertEquals(5f, previewMetadataTextSizeSp(50).value, 0.0001f)
        assertEquals(20f, previewMetadataTextSizeSp(200).value, 0.0001f)
        // 越界值收敛到 50%~200%。
        assertEquals(5f, previewMetadataTextSizeSp(1).value, 0.0001f)
        assertEquals(20f, previewMetadataTextSizeSp(900).value, 0.0001f)
    }
}
