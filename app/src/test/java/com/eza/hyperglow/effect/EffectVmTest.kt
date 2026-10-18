package com.eza.hyperglow.effect

import com.eza.hyperglow.customization.SceneCompiler
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectVmTest {
    private fun context() = EffectFrameContext(
        timeMs = 1_000L,
        wallClockMs = 5_000L,
        deltaMs = 16L,
        frameIndex = 3L,
        lineText = "ab",
        lineStartMs = 0L,
        lineEndMs = 2_000L,
        lineProgress = 0.5f,
        lineIndex = 0,
        canvasWidth = 1_080f,
        canvasHeight = 2_400f,
        textSize = 64f,
        baseline = 1_200f,
        colors = EffectColors(
            sung = 0xFFFFFFFF.toInt(),
            unsung = 0x80FFFFFF.toInt(),
            glow = 0xFFFFCC00.toInt(),
            accent = 0xFF00CCFF.toInt()
        ),
        words = listOf(
            EffectWordSample("a", 10f, 30f, 0L, 1_000L, 0.5f, 0, false),
            EffectWordSample("b", 50f, 30f, 1_000L, 2_000L, 0f, 1, true)
        )
    )

    private fun instruction(op: String, arg: Int? = null, text: String? = null) =
        EffectInstruction(op = op, arg = arg, text = text)

    private fun drawWordsProgram(): EffectProgram = EffectProgram(
        maxLocals = 2,
        constants = listOf(
            EffectConstant(value = "0"),
            EffectConstant(value = "1")
        ),
        code = listOf(
            instruction("const", 0),
            instruction("storeLocal", 0),
            // loop:
            instruction("loadLocal", 0),
            instruction("wordCount"),
            instruction("lt"),
            instruction("jz", 19),
            instruction("loadLocal", 0),
            instruction("word"),
            instruction("wordField", text = "text"),
            instruction("loadLocal", 0),
            instruction("word"),
            instruction("wordField", text = "x"),
            instruction("globalField", text = "baseline"),
            instruction("drawText"),
            instruction("loadLocal", 0),
            instruction("const", 1),
            instruction("add"),
            instruction("storeLocal", 0),
            instruction("jump", 2),
            instruction("end")
        )
    )

    @Test
    fun drawsEveryWordThroughTheRenderSink() {
        val sink = RecordingSink()
        assertTrue(EffectVm(drawWordsProgram()).render(context(), sink))
        assertEquals(listOf("a", "b"), sink.texts.map { it.first })
        assertEquals(listOf(10f, 50f), sink.texts.map { it.second })
        assertEquals(listOf(1_200f, 1_200f), sink.texts.map { it.third })
    }

    @Test
    fun arithmeticAndWordFieldsEvaluate() {
        // sum = word[0].progress + word[1].progress; draw a marker if sum > 0.4
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(value = "0"),
                EffectConstant(value = "0.4"),
                EffectConstant(value = "1")
            ),
            code = listOf(
                instruction("const", 0),
                instruction("word"),
                instruction("wordField", text = "progress"),
                instruction("const", 2),
                instruction("word"),
                instruction("wordField", text = "progress"),
                instruction("add"),
                instruction("storeLocal", 0),
                instruction("loadLocal", 0),
                instruction("const", 1),
                instruction("gt"),
                instruction("jz", 16),
                instruction("const", 0),
                instruction("const", 0),
                instruction("const", 0),
                instruction("drawCircle"),
                instruction("end")
            ),
            maxLocals = 2
        )
        val sink = RecordingSink()
        assertTrue(EffectVm(program).render(context(), sink))
        assertEquals(1, sink.circles.size)
    }

    @Test
    fun infiniteLoopIsStoppedByTheStepBudget() {
        val program = EffectProgram(
            code = listOf(instruction("jump", 0))
        )
        val sink = RecordingSink()
        assertFalse(EffectVm(program).render(context(), sink))
    }

    @Test
    fun stackOverflowIsStopped() {
        val code = ArrayList<EffectInstruction>()
        repeat(MAX_EFFECT_STACK + 8) {
            code += instruction("const", 0)
        }
        code += instruction("end")
        val program = EffectProgram(
            constants = listOf(EffectConstant(value = "1")),
            code = code
        )
        assertFalse(EffectVm(program).render(context(), RecordingSink()))
    }

    @Test
    fun normalizationRejectsInvalidPrograms() {
        assertNull(normalizeEffectProgram(null))
        assertNull(normalizeEffectProgram(EffectProgram(version = 2, code = listOf(instruction("end")))))
        assertNull(normalizeEffectProgram(EffectProgram(code = listOf(instruction("nope")))))
        assertNull(
            normalizeEffectProgram(
                EffectProgram(code = listOf(instruction("jump", 99)))
            )
        )
        assertNull(
            normalizeEffectProgram(
                EffectProgram(
                    constants = listOf(EffectConstant(value = "1")),
                    code = listOf(instruction("const", 4))
                )
            )
        )
        assertNull(
            normalizeEffectProgram(
                EffectProgram(code = listOf(instruction("lineField", text = "nope")))
            )
        )
        assertNotNull(
            normalizeEffectProgram(
                EffectProgram(
                    constants = listOf(EffectConstant(value = "1")),
                    code = listOf(instruction("const", 0), instruction("end"))
                )
            )
        )
    }

    @Test
    fun programSurvivesConfigJsonRoundTrip() {
        val program = normalizeEffectProgram(drawWordsProgram())
        assertNotNull(program)
        val json = SceneCompiler.json.encodeToString(program!!)
        val decoded = SceneCompiler.json.decodeFromString<EffectProgram>(json)
        assertEquals(program, decoded)
        assertEquals(program, normalizeEffectProgram(decoded))
    }

    @Test
    fun stackUnderflowFailsInsteadOfRunningGarbage() {
        val program = EffectProgram(
            code = listOf(instruction("add"), instruction("end"))
        )
        assertFalse(EffectVm(program).render(context(), RecordingSink()))
    }

    @Test
    fun nonFiniteStringIsSanitizedBeforeReachingTheSink() {
        val program = EffectProgram(
            constants = listOf(EffectConstant(type = "string", value = "NaN")),
            code = listOf(
                instruction("const", 0),
                instruction("setAlpha"),
                instruction("end")
            )
        )
        val sink = RecordingSink()
        assertTrue(EffectVm(program).render(context(), sink))
        assertEquals(0f, sink.alphas.single(), 0f)
    }

    @Test
    fun numericArgbKeepsTheHighBit() {
        // 4294967295 (0xFFFFFFFF) 不能经 Double.toInt() 饱和成 0x7FFFFFFF。
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(value = "4294967295"),
                EffectConstant(value = "0"),
                EffectConstant(value = "10")
            ),
            code = listOf(
                instruction("const", 0),
                instruction("setColor"),
                instruction("const", 1),
                instruction("const", 1),
                instruction("const", 2),
                instruction("const", 2),
                instruction("drawRect"),
                instruction("end")
            )
        )
        val sink = RecordingSink()
        assertTrue(EffectVm(program).render(context(), sink))
        assertEquals(0xFFFFFFFF.toInt(), sink.lastColor)
        assertEquals(1, sink.rects.size)
    }

    @Test
    fun drawBudgetStopsARunawayProgram() {
        val program = EffectProgram(
            maxLocals = 1,
            constants = listOf(
                EffectConstant(value = "0"),
                EffectConstant(value = "1"),
                EffectConstant(type = "string", value = "x")
            ),
            code = listOf(
                instruction("const", 0),
                instruction("storeLocal", 0),
                // loop: i++ + drawText
                instruction("loadLocal", 0),
                instruction("const", 1),
                instruction("add"),
                instruction("storeLocal", 0),
                instruction("const", 2),
                instruction("const", 0),
                instruction("const", 0),
                instruction("drawText"),
                instruction("jump", 2),
                instruction("end")
            )
        )
        assertFalse(EffectVm(program).render(context(), RecordingSink()))
    }

    @Test
    fun unsafeProgramStringConstantsAreRejected() {
        assertNull(
            normalizeEffectProgram(
                EffectProgram(
                    constants = listOf(EffectConstant(type = "string", value = "http://x")),
                    code = listOf(instruction("const", 0), instruction("end"))
                )
            )
        )
        assertNull(
            normalizeEffectProgram(
                EffectProgram(
                    constants = listOf(EffectConstant(type = "string", value = "C:\\fx")),
                    code = listOf(instruction("const", 0), instruction("end"))
                )
            )
        )
    }

    @Test
    fun oversizedProgramIsRejected() {
        val constants = (0 until MAX_EFFECT_CONSTANTS).map {
            EffectConstant(type = "string", value = "x".repeat(256))
        }
        assertNull(
            normalizeEffectProgram(
                EffectProgram(
                    constants = constants,
                    code = listOf(instruction("const", 0), instruction("end"))
                )
            )
        )
    }

    private data class Quad(val x: Float, val y: Float, val w: Float, val h: Float)

    private class RecordingSink : EffectRenderSink {
        val texts = ArrayList<Triple<String, Float, Float>>()
        val circles = ArrayList<Triple<Float, Float, Float>>()
        val rects = ArrayList<Quad>()
        val alphas = ArrayList<Float>()
        var lastColor = 0

        override fun save() = Unit
        override fun restore() = Unit
        override fun translate(x: Float, y: Float) = Unit
        override fun rotate(deg: Float) = Unit
        override fun scale(sx: Float, sy: Float) = Unit
        override fun setColor(argb: Int) {
            lastColor = argb
        }

        override fun setAlpha(alpha: Float) {
            alphas += alpha
        }

        override fun setTextSize(px: Float) = Unit
        override fun drawText(text: String, x: Float, y: Float) {
            texts += Triple(text, x, y)
        }

        override fun drawRect(x: Float, y: Float, w: Float, h: Float) {
            rects += Quad(x, y, w, h)
        }
        override fun drawCircle(cx: Float, cy: Float, r: Float) {
            circles += Triple(cx, cy, r)
        }

        override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) = Unit
        override fun pathMove(x: Float, y: Float) = Unit
        override fun pathLine(x: Float, y: Float) = Unit
        override fun pathClose() = Unit
        override fun drawPath() = Unit
        override fun clipRect(x: Float, y: Float, w: Float, h: Float) = Unit
        override fun measureText(text: String): Float = text.length * 10f
    }
}
