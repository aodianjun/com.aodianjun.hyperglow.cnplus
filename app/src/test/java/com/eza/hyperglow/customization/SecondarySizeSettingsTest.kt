package com.eza.hyperglow.customization

import com.eza.hyperglow.root.customization.SystemUiCustomizationValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 辅助文字字号倍率与「自适应大小」开关的 compile→validate→canonicalize 往返测试
 * (与 SceneCompilerTest 的 secondaryNextLine/secondaryWordKaraoke 模板同式):
 *  - 两字段任一环节漏字段都会让设置保存后静默弹回默认(逐字段重建映射的经典回归面,
 *    见 CustomizationRepository.toSurfaceProfile);
 *  - percent 越界必须在 compile 与 SystemUI 二次校验两侧归一一致,否则 wire 的
 *    validate_rewrote_fields 会拒收整份配置(实机表现:设置页正常、实机毫无变化)。
 */
class SecondarySizeSettingsTest {

    private fun aodDocument(profile: SurfaceProfile) = CustomizationDocument(
        profiles = mapOf(SceneCompiler.SURFACE_AOD to profile)
    )

    @Test
    fun secondaryTextSizePercentCompilesValidatesAndSurvivesCanonicalizeRoundTrip() {
        val document = aodDocument(SurfaceProfile(secondaryTextSizePercent = 130))
        val compiled = SceneCompiler.compile(document)
        assertEquals(
            130,
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryTextSizePercent
        )
        // 校验器与编译同源归一(不改写),否则 wire 的 validate_rewrote_fields 会拒收。
        assertEquals(compiled, SystemUiCustomizationValidator.validate(compiled))

        val canonical = CustomizationRepository.canonicalizeDocument(document)!!
        assertEquals(
            130,
            canonical.profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryTextSizePercent
        )
        // 默认文档 = 100:既有呈现不回退。
        assertEquals(
            SECONDARY_TEXT_SIZE_PERCENT_DEFAULT,
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
                .profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryTextSizePercent
        )
    }

    @Test
    fun secondaryAutoSizeCompilesValidatesAndSurvivesCanonicalizeRoundTrip() {
        // 关闭状态是「与默认不同」的值:漏字段会被逐字段重建映射静默弹回默认(开启)。
        val document = aodDocument(SurfaceProfile(secondaryAutoSize = false))
        val compiled = SceneCompiler.compile(document)
        assertFalse(compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryAutoSize)
        assertEquals(compiled, SystemUiCustomizationValidator.validate(compiled))
        assertFalse(
            CustomizationRepository.canonicalizeDocument(document)!!
                .profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryAutoSize
        )
        // 默认文档保持开启(工作单口径:默认开,owner 可改;见 PR 说明)。
        assertTrue(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
                .profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryAutoSize
        )
    }

    @Test
    fun secondarySizeSettingsArePerSurface() {
        // per-surface 独立:只改息屏面时锁屏面保持默认(与 secondaryWordKaraoke 同回归面)。
        val document = aodDocument(
            SurfaceProfile(secondaryTextSizePercent = 50, secondaryAutoSize = false)
        )
        val compiled = SceneCompiler.compile(document)
        val compiledAod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals(50, compiledAod.secondaryTextSizePercent)
        assertFalse(compiledAod.secondaryAutoSize)
        val compiledLockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        assertEquals(SECONDARY_TEXT_SIZE_PERCENT_DEFAULT, compiledLockscreen.secondaryTextSizePercent)
        assertTrue(compiledLockscreen.secondaryAutoSize)

        val canonical = CustomizationRepository.canonicalizeDocument(document)!!
        val canonicalAod = canonical.profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals(50, canonicalAod.secondaryTextSizePercent)
        assertFalse(canonicalAod.secondaryAutoSize)
        val canonicalLockscreen = canonical.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        assertEquals(
            SECONDARY_TEXT_SIZE_PERCENT_DEFAULT,
            canonicalLockscreen.secondaryTextSizePercent
        )
        assertTrue(canonicalLockscreen.secondaryAutoSize)
    }

    @Test
    fun outOfRangePercentNormalizesIdenticallyOnBothSides() {
        // 越界 percent 在 compile 与 validate 后值相同(否则 wire hash 校验拒收整份配置)。
        val compiled = SceneCompiler.compile(
            aodDocument(SurfaceProfile(secondaryTextSizePercent = 999))
        )
        assertEquals(
            SECONDARY_TEXT_SIZE_PERCENT_MAX,
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryTextSizePercent
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!
        assertEquals(
            SECONDARY_TEXT_SIZE_PERCENT_MAX,
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryTextSizePercent
        )
        // 整份编译产物经二次校验不改写(revision/hash 一致 = fromWirePayload 的接受判据)。
        assertEquals(compiled.revision, validated.revision)
        assertEquals(compiled.hash, validated.hash)
        // 下限侧同样钳制。
        assertEquals(
            SECONDARY_TEXT_SIZE_PERCENT_MIN,
            SceneCompiler.compile(
                aodDocument(SurfaceProfile(secondaryTextSizePercent = 0))
            ).profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryTextSizePercent
        )
        assertEquals(SECONDARY_TEXT_SIZE_PERCENT_MIN, normalizeSecondaryTextSizePercent(-5))
    }
}
