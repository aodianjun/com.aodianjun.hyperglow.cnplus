package com.eza.hyperglow.customization

import com.eza.hyperglow.effect.EffectConstant
import com.eza.hyperglow.effect.EffectInstruction
import com.eza.hyperglow.effect.EffectProgram
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordEffectModelsTest {
    private fun spec(
        id: String = "test",
        tracks: List<WordEffectTrack> = emptyList(),
        colors: List<WordEffectColorKeyframe> = emptyList(),
        stagger: Float = 0f,
        longSyllableMs: Int = 700,
        program: EffectProgram? = null
    ) = WordEffectSpec(
        id = id,
        name = "测试",
        longSyllableMs = longSyllableMs,
        stagger = stagger,
        tracks = tracks,
        colors = colors,
        program = program
    )

    /** 最小合法程序:常量 "0" → const → end。 */
    private fun minimalProgram() = EffectProgram(
        constants = listOf(EffectConstant(value = "0")),
        code = listOf(
            EffectInstruction(op = "const", arg = 0),
            EffectInstruction(op = "end")
        )
    )

    @Test
    fun normalizeFillsEndpointsAndClampsOutOfRangeValues() {
        val normalized = normalizeWordEffectSpec(
            spec(
                tracks = listOf(
                    WordEffectTrack(
                        property = "scale",
                        keyframes = listOf(
                            WordEffectKeyframe(at = 0.2f, value = 9f),
                            WordEffectKeyframe(at = 0.5f, value = 1.5f),
                            WordEffectKeyframe(at = 0.8f, value = -1f)
                        )
                    )
                )
            )
        )
        assertNotNull(normalized)
        val frames = normalized!!.tracks.single().keyframes
        // 端点就地拉伸到 0/1(不新增帧);越界值按属性范围钳制(scale 0.1..3)。
        assertEquals(listOf(0f, 0.5f, 1f), frames.map { it.at })
        assertEquals(3f, frames.first().value, 0f)
        assertEquals(1.5f, frames[1].value, 0f)
        assertEquals(0.1f, frames.last().value, 0f)
    }

    @Test
    fun normalizeIsIdempotent() {
        val once = normalizeWordEffectSpec(
            spec(
                tracks = listOf(
                    WordEffectTrack(
                        property = "translateY",
                        keyframes = listOf(
                            WordEffectKeyframe(0.4f, 0.2f, "easeOut"),
                            WordEffectKeyframe(0f, 0f, "linear")
                        )
                    )
                ),
                colors = listOf(
                    WordEffectColorKeyframe(0.5f, "#FF0000", "easeInOut"),
                    WordEffectColorKeyframe(0f, "#0000FF", "linear")
                )
            )
        )
        assertNotNull(once)
        assertEquals(once, normalizeWordEffectSpec(once))
    }

    @Test
    fun normalizeRejectsUnknownProperty() {
        assertNull(
            normalizeWordEffectSpec(
                spec(
                    tracks = listOf(
                        WordEffectTrack(
                            property = "explode",
                            keyframes = listOf(WordEffectKeyframe(0f, 1f), WordEffectKeyframe(1f, 1f))
                        )
                    )
                )
            )
        )
    }

    @Test
    fun normalizeRejectsDuplicatePropertyWithSameLongOnly() {
        val track = WordEffectTrack(
            property = "scale",
            keyframes = listOf(WordEffectKeyframe(0f, 1f), WordEffectKeyframe(1f, 1f))
        )
        assertNull(normalizeWordEffectSpec(spec(tracks = listOf(track, track))))
        // longOnly 不同则允许:长音节轨道叠加在普通轨道之后。
        assertNotNull(
            normalizeWordEffectSpec(
                spec(tracks = listOf(track, track.copy(longOnly = true)))
            )
        )
    }

    @Test
    fun singleKeyframeBecomesConstant() {
        val normalized = normalizeWordEffectSpec(
            spec(
                tracks = listOf(
                    WordEffectTrack(
                        property = "rotate",
                        keyframes = listOf(WordEffectKeyframe(0.5f, 45f))
                    )
                )
            )
        )!!
        assertEquals(listOf(0f, 1f), normalized.tracks.single().keyframes.map { it.at })
        assertEquals(45f, evaluateWordEffect(normalized, 0f, 0, false).rotateDeg, 0f)
        assertEquals(45f, evaluateWordEffect(normalized, 1f, 0, false).rotateDeg, 0f)
    }

    @Test
    fun linearKeyframesInterpolateAtMidpoint() {
        val track = WordEffectTrack(
            property = "translateY",
            keyframes = listOf(
                WordEffectKeyframe(0f, 0f),
                WordEffectKeyframe(1f, 1f)
            )
        )
        assertEquals(0.5f, evaluateWordEffectTrack(track.keyframes, 0.5f), 1e-6f)
    }

    @Test
    fun easingOutReachesThreeQuartersAtMidpoint() {
        assertEquals(0.75f, applyWordEffectEasing("easeOut", 0.5f), 1e-6f)
        assertEquals(0.25f, applyWordEffectEasing("easeIn", 0.5f), 1e-6f)
        assertEquals(0.5f, applyWordEffectEasing("easeInOut", 0.5f), 1e-6f)
        // stepEnd 保持起始值到段末再跳变。
        assertEquals(0f, applyWordEffectEasing("stepEnd", 0.5f), 0f)
        assertEquals(0f, applyWordEffectEasing("stepEnd", 0.999f), 0f)
        assertEquals(1f, applyWordEffectEasing("stepEnd", 1f), 0f)
    }

    @Test
    fun staggerDelaysLaterWords() {
        val normalized = normalizeWordEffectSpec(
            spec(
                stagger = 0.2f,
                tracks = listOf(
                    WordEffectTrack(
                        property = "translateY",
                        keyframes = listOf(
                            WordEffectKeyframe(0f, 0f),
                            WordEffectKeyframe(1f, 1f)
                        )
                    )
                )
            )
        )!!
        // 归一化时间轴:count=3、stagger=0.2 → span=0.6;p=0.5 时
        // index 0 → 0.5/0.6, index 1 → 0.3/0.6, index 2 → 0.1/0.6。
        assertEquals(
            0.5f / 0.6f,
            evaluateWordEffect(normalized, 0.5f, 0, false, wordCount = 3).translateYEm,
            1e-5f
        )
        assertEquals(
            0.5f,
            evaluateWordEffect(normalized, 0.5f, 1, false, wordCount = 3).translateYEm,
            1e-5f
        )
        assertEquals(
            0.1f / 0.6f,
            evaluateWordEffect(normalized, 0.5f, 2, false, wordCount = 3).translateYEm,
            1e-5f
        )
        // 钳制到 0:滞后量超过进度时保持首帧;最后一个词在 p=1 恰好走完(不冻结)。
        assertEquals(
            0f,
            evaluateWordEffect(normalized, 0.1f, 2, false, wordCount = 3).translateYEm,
            0f
        )
        assertEquals(
            1f,
            evaluateWordEffect(normalized, 1f, 2, false, wordCount = 3).translateYEm,
            1e-5f
        )
    }

    @Test
    fun longOnlyTrackSkipsShortSyllables() {
        val normalized = normalizeWordEffectSpec(
            spec(
                tracks = listOf(
                    WordEffectTrack(
                        property = "glowAlpha",
                        longOnly = true,
                        keyframes = listOf(
                            WordEffectKeyframe(0f, 0f),
                            WordEffectKeyframe(1f, 0.5f)
                        )
                    )
                )
            )
        )!!
        assertEquals(0.5f, evaluateWordEffect(normalized, 1f, 0, true).glowAlpha, 0f)
        // 短音节不命中 longOnly 轨道,保持默认辉光强度 1。
        assertEquals(1f, evaluateWordEffect(normalized, 1f, 0, false).glowAlpha, 0f)
    }

    @Test
    fun colorTrackInterpolatesRgb() {
        val normalized = normalizeWordEffectSpec(
            spec(
                colors = listOf(
                    WordEffectColorKeyframe(0f, "#000000"),
                    WordEffectColorKeyframe(1f, "#FFFFFF")
                )
            )
        )!!
        val middle = evaluateWordEffect(normalized, 0.5f, 0, false).colorArgb
        assertEquals(0xFF808080.toInt(), middle)
        assertEquals(0xFF000000.toInt(), evaluateWordEffect(normalized, 0f, 0, false).colorArgb)
        assertEquals(0xFFFFFFFF.toInt(), evaluateWordEffect(normalized, 1f, 0, false).colorArgb)
    }

    @Test
    fun invalidColorIsRejected() {
        assertNull(
            normalizeWordEffectSpec(
                spec(colors = listOf(WordEffectColorKeyframe(0f, "not-a-color")))
            )
        )
    }

    @Test
    fun specSurvivesConfigJsonRoundTrip() {
        val original = normalizeWordEffectSpec(
            spec(
                tracks = listOf(
                    WordEffectTrack(
                        property = "scale",
                        keyframes = listOf(
                            WordEffectKeyframe(0f, 0.95f, "easeOut"),
                            WordEffectKeyframe(0.35f, 1.18f, "easeInOut"),
                            WordEffectKeyframe(1f, 1f)
                        )
                    ),
                    WordEffectTrack(
                        property = "translateY",
                        keyframes = listOf(
                            WordEffectKeyframe(0f, 0.08f, "easeOut"),
                            WordEffectKeyframe(1f, 0f)
                        )
                    )
                ),
                colors = listOf(
                    WordEffectColorKeyframe(0f, "#FFCC00"),
                    WordEffectColorKeyframe(1f, "#FFFFFF")
                )
            )
        )!!
        val json = SceneCompiler.json.encodeToString(original)
        val decoded = SceneCompiler.json.decodeFromString<WordEffectSpec>(json)
        assertEquals(original, decoded)
        assertTrue(normalizeWordEffectSpec(decoded) == decoded)
    }

    @Test
    fun normalizeIsIdempotentWithOutOfRangeDuplicateAts() {
        // -0.2 与 0.0 钳制后同为 0:必须先钳制再去重,否则第一次留两帧、第二次合并,
        // SystemUI 再规范化就会改写字段并拒收整份配置。
        val once = normalizeWordEffectSpec(
            spec(
                tracks = listOf(
                    WordEffectTrack(
                        property = "translateY",
                        keyframes = listOf(
                            WordEffectKeyframe(-0.2f, 0f),
                            WordEffectKeyframe(0f, 1f),
                            WordEffectKeyframe(1.4f, 2f)
                        )
                    )
                )
            )
        )
        assertNotNull(once)
        assertEquals(once, normalizeWordEffectSpec(once))
        assertEquals(listOf(0f, 1f), once!!.tracks.single().keyframes.map { it.at })
    }

    @Test
    fun normalizeIsIdempotentAtKeyframeLimit() {
        val keyframes = (0 until 16).map { index ->
            WordEffectKeyframe(at = 0.05f + index * 0.05f, value = index / 15f)
        }
        val once = normalizeWordEffectSpec(
            spec(tracks = listOf(WordEffectTrack(property = "translateY", keyframes = keyframes)))
        )
        assertNotNull(once)
        assertEquals(16, once!!.tracks.single().keyframes.size)
        assertEquals(once, normalizeWordEffectSpec(once))
    }

    @Test
    fun normalizeIsIdempotentAtColorLimit() {
        val colors = (0 until 8).map { index ->
            WordEffectColorKeyframe(at = 0.05f + index * 0.1f, color = "#00FF00")
        }
        val once = normalizeWordEffectSpec(spec(colors = colors))
        assertNotNull(once)
        assertEquals(8, once!!.colors.size)
        assertEquals(once, normalizeWordEffectSpec(once))
    }

    @Test
    fun unsafeNameFallsBackToId() {
        // 名称随配置文档持久化;含反斜杠/URL 之类的名称会让 decodeDocument 拒收整份配置。
        val normalized = normalizeWordEffectSpec(spec(id = "safe_id").copy(name = "C:\\fx"))
        assertNotNull(normalized)
        assertEquals("safe_id", normalized!!.name)
        val url = normalizeWordEffectSpec(spec(id = "safe_id").copy(name = "http://x"))
        assertEquals("safe_id", url!!.name)
    }

    @Test
    fun noneIdIsReservedForThePickerSentinel() {
        assertNull(normalizeWordEffectSpec(spec(id = "none")))
    }

    @Test
    fun validProgramSurvivesNormalizationAndJsonRoundTrip() {
        val original = normalizeWordEffectSpec(spec(program = minimalProgram()))
        assertNotNull(original)
        assertEquals(minimalProgram(), original!!.program)

        val json = SceneCompiler.json.encodeToString(original)
        val decoded = SceneCompiler.json.decodeFromString<WordEffectSpec>(json)
        assertEquals(original, decoded)
        assertEquals(minimalProgram(), decoded.program)
    }

    @Test
    fun invalidProgramIsRejected() {
        val invalid = minimalProgram().copy(
            code = listOf(
                EffectInstruction(op = "const", arg = 0),
                EffectInstruction(op = "teleport")
            )
        )
        assertNull(normalizeWordEffectSpec(spec(program = invalid)))
    }

    @Test
    fun normalizeIsIdempotentWithProgram() {
        val once = normalizeWordEffectSpec(spec(program = minimalProgram()))
        assertNotNull(once)
        assertEquals(once, normalizeWordEffectSpec(once))
        assertEquals(minimalProgram(), once!!.program)
    }

    @Test
    fun overshootEasingsAreClampedToPropertyRange() {
        val normalized = normalizeWordEffectSpec(
            spec(
                tracks = listOf(
                    WordEffectTrack(
                        property = "scale",
                        keyframes = listOf(
                            WordEffectKeyframe(0f, 1f, "easeOutBack"),
                            WordEffectKeyframe(1f, 3f)
                        )
                    )
                )
            )
        )!!
        // easeOutBack 在 0.5 处会插值到 ~3.17,必须钳回 3(否则 3x 上限失效)。
        val scale = evaluateWordEffect(normalized, 0.5f, 0, false).scale
        assertTrue(scale <= 3f)
        assertTrue(scale > 1f)
    }
}
