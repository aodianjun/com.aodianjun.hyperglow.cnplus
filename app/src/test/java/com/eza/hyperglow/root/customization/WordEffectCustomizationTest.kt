package com.eza.hyperglow.root.customization

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.customization.WordEffectKeyframe
import com.eza.hyperglow.customization.WordEffectSpec
import com.eza.hyperglow.customization.WordEffectTrack
import com.eza.hyperglow.customization.normalizeWordEffectSpec
import com.eza.hyperglow.effect.EffectConstant
import com.eza.hyperglow.effect.EffectInstruction
import com.eza.hyperglow.effect.EffectProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声明式单词效果经编译与 SystemUI 校验后的契约:
 * - `SceneCompiler.compile` 对 [SurfaceProfile.wordEffect] 规范化一次;
 * - 结构非法规格被静默丢弃(null),不阻断整份配置;
 * - SystemUI 侧再规范化必须幂等——两处结果不一致会让 wire 以
 *   `validate_rewrote_fields` 拒收整包(表现为设置页正常、实机无变化)。
 */
class WordEffectCustomizationTest {
    private fun documentWithAodWordEffect(spec: WordEffectSpec?): CustomizationDocument =
        SceneCompiler.safeDefaultDocument().copy(
            profiles = mapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SceneCompiler.safeLockscreenProfile(),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(wordEffect = spec)
            )
        )

    /**
     * 结构合法但未规范化:名称带空白、长音节阈值/滞后量/扫光带宽越界、
     * 未知辉光与缓动名、重复 at 与越界关键帧值。编译后应等于规范化结果。
     */
    private fun rawSpec(): WordEffectSpec = WordEffectSpec(
        id = "bounce_soft",
        name = "  Soft Bounce  ",
        longSyllableMs = 9_000,
        stagger = 2f,
        sweepBand = 0.01f,
        glow = "sometimes",
        tracks = listOf(
            WordEffectTrack(
                property = "translateY",
                keyframes = listOf(
                    WordEffectKeyframe(at = 0.4f, value = 0.2f, easing = "easeOut"),
                    WordEffectKeyframe(at = 0.4f, value = 0.05f, easing = "easeOut"),
                    WordEffectKeyframe(at = 0.9f, value = 9f, easing = "unknown-easing")
                )
            )
        )
    )

    @Test
    fun compileNormalizesAodWordEffect() {
        val raw = rawSpec()
        val expected = normalizeWordEffectSpec(raw)
        assertNotNull(expected)

        val compiled = SceneCompiler.compile(documentWithAodWordEffect(raw))

        val compiledEffect = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        assertEquals(expected, compiledEffect)
        // 证明编译确实改写了非规范值,而不是原样透传。
        assertNotEquals(raw, compiledEffect)
        assertEquals(expected, normalizeWordEffectSpec(compiledEffect))
    }

    @Test
    fun compileDropsStructurallyInvalidWordEffect() {
        val invalid = WordEffectSpec(
            id = "broken_effect",
            name = "Broken",
            tracks = listOf(
                WordEffectTrack(
                    property = "explode",
                    keyframes = listOf(WordEffectKeyframe(at = 0f, value = 1f))
                )
            )
        )
        assertNull(normalizeWordEffectSpec(invalid))

        val compiled = SceneCompiler.compile(documentWithAodWordEffect(invalid))

        assertNull(compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect)
    }

    @Test
    fun systemUiValidateKeepsCompiledWordEffect() {
        val compiled = SceneCompiler.compile(documentWithAodWordEffect(rawSpec()))
        val compiledEffect = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        assertNotNull(compiledEffect)

        val validated = SystemUiCustomizationValidator.validate(compiled)

        assertNotNull(validated)
        assertEquals(
            compiledEffect,
            validated!!.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        )
        // 整份配置逐字段不变 → wire 端不会触发 validate_rewrote_fields。
        assertEquals(compiled, validated)
    }

    @Test
    fun compiledAodWordEffectPreservesProgramAndValidatesIdempotently() {
        val program = EffectProgram(
            constants = listOf(EffectConstant(value = "0")),
            code = listOf(
                EffectInstruction(op = "const", arg = 0),
                EffectInstruction(op = "end")
            )
        )
        val spec = WordEffectSpec(id = "program_effect", name = "Program", program = program)

        val compiled = SceneCompiler.compile(documentWithAodWordEffect(spec))
        val compiledEffect = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        assertNotNull(compiledEffect)
        assertEquals(program, compiledEffect!!.program)

        val validated = SystemUiCustomizationValidator.validate(compiled)
        assertNotNull(validated)
        assertEquals(
            compiledEffect,
            validated!!.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        )
        assertEquals(compiled, validated)
    }

    @Test
    fun wordEffectSurvivesWireValidationWithoutRewrite() {
        val compiled = SceneCompiler.compile(documentWithAodWordEffect(rawSpec()))
        val payload = CompiledCustomizationBundleCodec.toWirePayload(compiled, userId = 10)
        val rejections = ArrayList<String>()

        val parsed = CompiledCustomizationBundleCodec.fromWirePayload(
            payload,
            expectedUserId = 10,
            onReject = { rejections += it }
        )

        assertTrue("payload rejected: $rejections", rejections.isEmpty())
        assertNotNull(parsed)
        assertEquals(
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect,
            parsed!!.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        )
    }
}
