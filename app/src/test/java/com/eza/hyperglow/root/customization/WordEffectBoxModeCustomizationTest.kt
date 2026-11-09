package com.eza.hyperglow.root.customization

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.customization.WordEffectSpec
import com.eza.hyperglow.customization.normalizeWordEffectSpec
import com.eza.hyperglow.effect.EFFECT_OUTPUT_BOXES
import com.eza.hyperglow.effect.EFFECT_OUTPUT_DRAW
import com.eza.hyperglow.effect.EffectConstant
import com.eza.hyperglow.effect.EffectInstruction
import com.eza.hyperglow.effect.EffectProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 盒模型输出模式(outputMode)在自定义管线里的契约:
 * - App 编译(SceneCompiler.compile)与 SystemUI 侧再校验(SystemUiCustomizationValidator)
 *   对 outputMode 的规范化结果逐字段一致——不一致会被 wire 以 validate_rewrote_fields 拒收;
 * - "boxes" 原样保留,未知值回落 "draw",缺省(旧程序)保持 "draw" 不产生改写。
 */
class WordEffectBoxModeCustomizationTest {

    private fun documentWithAodWordEffect(spec: WordEffectSpec?): CustomizationDocument =
        SceneCompiler.safeDefaultDocument().copy(
            profiles = mapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SceneCompiler.safeLockscreenProfile(),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(wordEffect = spec)
            )
        )

    private fun boxEffect(outputMode: String): WordEffectSpec = WordEffectSpec(
        id = "mv_layout",
        name = "MV Layout",
        program = EffectProgram(
            constants = listOf(EffectConstant(type = "string", value = "hi")),
            code = listOf(
                EffectInstruction(op = "const", arg = 0),
                EffectInstruction(op = "const", arg = 0),
                EffectInstruction(op = "const", arg = 0),
                EffectInstruction(op = "drawText"),
                EffectInstruction(op = "end")
            ),
            maxLocals = 0,
            outputMode = outputMode
        )
    )

    @Test
    fun boxOutputModeSurvivesCompileAndSystemUiValidation() {
        val compiled = SceneCompiler.compile(documentWithAodWordEffect(boxEffect(EFFECT_OUTPUT_BOXES)))
        val compiledEffect = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        assertNotNull(compiledEffect)
        assertEquals(EFFECT_OUTPUT_BOXES, compiledEffect!!.program?.outputMode)
        // App 编译结果 == 共享归一化(编译不改写合法值)。
        assertEquals(normalizeWordEffectSpec(boxEffect(EFFECT_OUTPUT_BOXES)), compiledEffect)

        // SystemUI 侧再校验不得改写(否则 wire 拒收整包)。
        val validated = SystemUiCustomizationValidator.validate(compiled)
        assertNotNull(validated)
        assertEquals(
            compiledEffect,
            validated!!.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        )
    }

    @Test
    fun unknownOutputModeFallsBackToDrawConsistently() {
        val compiled = SceneCompiler.compile(documentWithAodWordEffect(boxEffect("particles")))
        val compiledEffect = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        assertNotNull(compiledEffect)
        assertEquals(EFFECT_OUTPUT_DRAW, compiledEffect!!.program?.outputMode)

        val validated = SystemUiCustomizationValidator.validate(compiled)
        assertNotNull(validated)
        assertEquals(
            compiledEffect,
            validated!!.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        )
    }

    @Test
    fun legacyProgramWithoutOutputModeStaysDraw() {
        val legacy = WordEffectSpec(
            id = "legacy_draw",
            name = "Legacy",
            program = EffectProgram(
                code = listOf(EffectInstruction(op = "end")),
                maxLocals = 0
                // outputMode 缺省 → "draw"
            )
        )
        assertEquals(EFFECT_OUTPUT_DRAW, legacy.program!!.outputMode)
        val compiled = SceneCompiler.compile(documentWithAodWordEffect(legacy))
        val compiledEffect = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).wordEffect
        assertNotNull(compiledEffect)
        assertEquals(EFFECT_OUTPUT_DRAW, compiledEffect!!.program?.outputMode)
        // 缺省值不得被改写成显式值(两个 "draw" 字符串相等,天然满足;此断言防未来引入哨兵值)。
        assertEquals(normalizeWordEffectSpec(legacy), compiledEffect)
    }
}
