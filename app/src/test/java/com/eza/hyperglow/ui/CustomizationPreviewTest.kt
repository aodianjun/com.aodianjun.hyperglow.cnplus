package com.eza.hyperglow.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.aod.nextLineTextSizeSp
import com.eza.hyperglow.root.aod.secondaryReadingTextSizeSp
import com.eza.hyperglow.root.aod.secondaryTranslationTextSizeSp
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
        // 与实机 metadataPaint 同源:14sp 基准 × metadataSizeMultiplier。
        assertEquals(14f, previewMetadataTextSizeSp(100).value, 0.0001f)
        assertEquals(7f, previewMetadataTextSizeSp(50).value, 0.0001f)
        assertEquals(28f, previewMetadataTextSizeSp(200).value, 0.0001f)
        // 越界值收敛到 50%~200%。
        assertEquals(7f, previewMetadataTextSizeSp(1).value, 0.0001f)
        assertEquals(28f, previewMetadataTextSizeSp(900).value, 0.0001f)
    }

    @Test
    fun previewTextSizeFollowsDeviceLengthAdaptiveFormula() {
        // 与实机 setContent 同源:baseTextSizeSp 随行长降档(28/26/24/23sp)× LIVE_CARD_SIZE_MULTIPLIER。
        assertEquals(28f * 0.68f, previewBaseTextSizeSp("short", "normal", 100), 0.001f)
        assertEquals(26f * 0.68f, previewBaseTextSizeSp("a".repeat(14), "normal", 100), 0.001f)
        assertEquals(24f * 0.68f, previewBaseTextSizeSp("a".repeat(22), "normal", 100), 0.001f)
        assertEquals(23f * 0.68f, previewBaseTextSizeSp("a".repeat(30), "normal", 100), 0.001f)
        // 字号档倍率与实机 textSizeModeMultiplier 一致(large=1.2/xlarge=1.5,custom=百分比)。
        assertEquals(28f * 0.68f * 1.2f, previewBaseTextSizeSp("short", "large", 100), 0.001f)
        assertEquals(28f * 0.68f * 1.5f, previewBaseTextSizeSp("short", "xlarge", 100), 0.001f)
        assertEquals(28f * 0.68f * 1.5f, previewBaseTextSizeSp("short", "custom", 150), 0.001f)
    }

    @Test
    fun previewSecondaryAndNextLineSizesMatchDeviceFormula() {
        // 与实机 setContent 同源:音标 0.48×base 带 14sp 下限,翻译再小 1sp 带 13sp 下限。
        assertEquals(14f, secondaryReadingTextSizeSp(19.04f), 0.001f)
        assertEquals(18f, secondaryReadingTextSizeSp(38f), 0.001f)
        assertEquals(13f, secondaryTranslationTextSizeSp(19.04f), 0.001f)
        assertEquals(17f, secondaryTranslationTextSizeSp(38f), 0.001f)
        // 下一行固定 15sp,不随字号档位缩放。
        assertEquals(15f, nextLineTextSizeSp(), 0.001f)
    }

    @Test
    fun previewSecondaryAlphaFollowsDeviceBrightnessFormula() {
        // 与实机 drawSecondaryLine 同一公式:bright=不透明,dim 走 AOD 亮度补偿下限。
        assertEquals(1f, previewSecondaryAlpha(true), 0.001f)
        assertEquals(0.56f, previewSecondaryAlpha(false), 0.001f)
    }

    @Test
    fun previewCardColorMatchesDeviceTokenMap() {
        // 与实机 AdaptiveLyricCardBackgroundView.cardColorRgb 同一 token 映射(alpha 由 cardAlpha 单独控制)。
        assertEquals(0x1ED760, previewCardColor("accent", 100).toArgb() and 0xFFFFFF)
        assertEquals(0x333333, previewCardColor("dark_gray", 100).toArgb() and 0xFFFFFF)
        assertEquals(0x1A1A1A, previewCardColor("black", 100).toArgb() and 0xFFFFFF)
        assertEquals(0x1A1A1A, previewCardColor("blur", 100).toArgb() and 0xFFFFFF)
        assertEquals(0xFFFFFF, previewCardColor("white", 100).toArgb() and 0xFFFFFF)
        // Compose Color 的 alpha 走 8-bit 量化(0.5f → 128/255),按通道值断言避免浮点容差踩量化误差。
        assertEquals(128, previewCardColor("black", 50).toArgb() ushr 24)
        assertEquals(255, previewCardColor("black", 100).toArgb() ushr 24)
    }

    @Test
    fun previewRowAlignmentMatchesDeviceResolution() {
        // 与实机 alignmentFor/setContent 同源(resolveRowAlignmentMode):
        // 显式行对齐直接生效;auto 跟随主对齐解析(主 auto 时按歌词方向右对齐)。
        assertEquals(TextAlign.Start, previewRowTextAlign("start", "end", false))
        assertEquals(TextAlign.Center, previewRowTextAlign("center", "start", true))
        assertEquals(TextAlign.End, previewRowTextAlign("end", "center", false))
        assertEquals(TextAlign.Center, previewRowTextAlign("auto", "center", false))
        assertEquals(TextAlign.End, previewRowTextAlign("auto", "auto", true))
        assertEquals(TextAlign.Start, previewRowTextAlign("auto", "auto", false))
        // 非法值按 auto 处理。
        assertEquals(TextAlign.End, previewRowTextAlign("bogus", "end", false))
    }
}
