package com.eza.hyperglow.effect

import com.eza.hyperglow.customization.SceneCompiler
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 盒模型输出模式(EffectProgram.outputMode == "boxes")的 VM 契约:
 * - collectBoxes 只服务 boxes 模式、render 只服务 draw 模式,互相拒绝;
 * - 绘制指令被翻译为带状态快照与烘焙矩阵的 [EffectBox],不触达任何 Canvas;
 * - 预算(步数/drawOps/盒数/路径段数/文本长)超限或执行失败 → 返回 null,不提交部分盒;
 * - outputMode 归一化幂等,未知值回落 draw。
 *
 * 栈序备忘(与 EffectRuntime 一致):drawText 弹 y→x→text(压序 text,x,y);
 * translate 弹 y→x(压序 x,y);drawCircle 弹 r→cy→cx(压序 cx,cy,r);
 * drawLine 弹 y2→x2→y1→x1(压序 x1,y1,x2,y2);pathMove/Line 弹 y→x(压序 x,y)。
 */
class EffectBoxModeTest {
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

    /** 不依赖宿主的文本测量器:按字符数返回固定宽度,供 measureText 指令使用。 */
    private val stubMeasurer = EffectTextMeasurer { text, _, _ -> text.length * 10f }

    private fun collect(program: EffectProgram, ctx: EffectFrameContext = context()) =
        EffectVm(program).collectBoxes(ctx, stubMeasurer)

    // ---- 模式互斥 ----

    @Test
    fun boxModeProgramIsRejectedByRender() {
        val program = EffectProgram(
            outputMode = EFFECT_OUTPUT_BOXES,
            code = listOf(instruction("end"))
        )
        assertFalse(EffectVm(program).render(context(), RecordingSink()))
    }

    @Test
    fun wordOverridePushesPerWordTableValues() {
        // 每个词: line=0、word=i → wordOverride 压入 dx,dy,scale,rotate,alpha;
        // 取 dx/dy 画文本、取 alpha 设透明度,验证查表命中与缺省恒等值。
        val program = EffectProgram(
            outputMode = EFFECT_OUTPUT_BOXES,
            maxLocals = 6,
            constants = listOf(
                EffectConstant(value = "0"),
                EffectConstant(value = "1"),
                EffectConstant(type = "string", value = "o"),
                EffectConstant(type = "string", value = "ab")
            ),
            wordOverrides = listOf(
                EffectWordOverride(
                    line = 0, word = 1,
                    dx = 2f, dy = -1f, scale = 1.5f, rotate = 10f, alpha = 0.5f
                )
            ),
            code = listOf(
                instruction("const", 0),
                instruction("storeLocal", 0),
                // loop:
                instruction("loadLocal", 0),
                instruction("wordCount"),
                instruction("lt"),
                instruction("jz", 30),
                instruction("const", 3),
                instruction("const", 0),
                instruction("loadLocal", 0),
                instruction("wordOverride"),
                instruction("storeLocal", 1),
                instruction("storeLocal", 2),
                instruction("storeLocal", 3),
                instruction("storeLocal", 4),
                instruction("storeLocal", 5),
                instruction("loadLocal", 1),
                instruction("setAlpha"),
                instruction("const", 2),
                instruction("loadLocal", 5),
                instruction("globalField", text = "textSize"),
                instruction("mul"),
                instruction("loadLocal", 4),
                instruction("globalField", text = "textSize"),
                instruction("mul"),
                instruction("drawText"),
                instruction("loadLocal", 0),
                instruction("const", 1),
                instruction("add"),
                instruction("storeLocal", 0),
                instruction("jump", 2),
                instruction("end")
            )
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        val text = boxes!!.filterIsInstance<EffectBox.TextBox>()
        assertEquals(2, text.size)
        assertEquals(0f, text[0].x, 0.001f)
        assertEquals(0f, text[0].baseline, 0.001f)
        assertEquals(1f, text[0].paint.alpha, 0.001f)
        assertEquals(2f * 64f, text[1].x, 0.001f)
        assertEquals(-1f * 64f, text[1].baseline, 0.001f)
        assertEquals(0.5f, text[1].paint.alpha, 0.001f)
    }

    @Test
    fun wordOverrideMatchesByFullLineTextAcrossVisualLineIndices() {
        // 程序压入整行文本 "ab"、视觉行号 7、词号 0;覆盖表用 lineText 匹配,
        // 视觉行号不一致也应命中。
        val program = EffectProgram(
            outputMode = EFFECT_OUTPUT_BOXES,
            maxLocals = 6,
            constants = listOf(
                EffectConstant(value = "7"),
                EffectConstant(value = "0"),
                EffectConstant(value = "1"),
                EffectConstant(type = "string", value = "ab"),
                EffectConstant(type = "string", value = "o")
            ),
            wordOverrides = listOf(
                EffectWordOverride(
                    line = 0, word = 0, lineText = "ab",
                    dx = 2f, dy = 0f, scale = 1f, rotate = 0f, alpha = 1f
                )
            ),
            code = listOf(
                instruction("const", 1),
                instruction("storeLocal", 0),
                // loop:
                instruction("loadLocal", 0),
                instruction("wordCount"),
                instruction("lt"),
                instruction("jz", 17),
                instruction("const", 3),
                instruction("const", 0),
                instruction("loadLocal", 0),
                instruction("wordOverride"),
                instruction("storeLocal", 1),
                instruction("pop"),
                instruction("pop"),
                instruction("pop"),
                instruction("storeLocal", 2),
                instruction("const", 4),
                instruction("drawText"),
                instruction("end")
            )
        )
        // 该程序只画第一帧的一个盒;用更直接的最小程序替代:直接取 dx 作为 x。
        val direct = EffectProgram(
            outputMode = EFFECT_OUTPUT_BOXES,
            maxLocals = 1,
            constants = listOf(
                EffectConstant(type = "string", value = "ab"),
                EffectConstant(value = "7"),
                EffectConstant(value = "0"),
                EffectConstant(type = "string", value = "o")
            ),
            wordOverrides = listOf(
                EffectWordOverride(
                    line = 0, word = 0, lineText = "ab",
                    dx = 2f, dy = 0f, scale = 1f, rotate = 0f, alpha = 1f
                )
            ),
            code = listOf(
                instruction("const", 0),
                instruction("const", 1),
                instruction("const", 2),
                instruction("wordOverride"),
                // 栈: dx dy scale rotate alpha;取 dx 作为 x*ts 绘制
                instruction("storeLocal", 0),
                instruction("pop"),
                instruction("pop"),
                instruction("pop"),
                instruction("globalField", text = "textSize"),
                instruction("mul"),
                instruction("storeLocal", 0),
                instruction("const", 3),
                instruction("loadLocal", 0),
                instruction("globalField", text = "baseline"),
                instruction("drawText"),
                instruction("end")
            )
        )
        val boxes = collect(direct)
        assertNotNull(boxes)
        val text = boxes!!.filterIsInstance<EffectBox.TextBox>()
        assertEquals(1, text.size)
        assertEquals(2f * 64f, text[0].x, 0.001f)
        assertEquals(1f, text[0].paint.alpha, 0.001f)
    }

    @Test
    fun normalizationKeepsValidWordOverridesAndRejectsOutOfRange() {
        val base = EffectProgram(
            outputMode = EFFECT_OUTPUT_BOXES,
            code = listOf(instruction("end")),
            maxLocals = 0
        )
        val valid = normalizeEffectProgram(
            base.copy(wordOverrides = listOf(EffectWordOverride(line = 1, word = 2, dx = 1f)))
        )
        assertNotNull(valid)
        assertEquals(1, valid!!.wordOverrides.size)
        assertNull(normalizeEffectProgram(base.copy(wordOverrides = listOf(EffectWordOverride(scale = 0f)))))
        assertNull(normalizeEffectProgram(base.copy(wordOverrides = listOf(EffectWordOverride(line = -1)))))
        assertNull(normalizeEffectProgram(base.copy(wordOverrides = listOf(EffectWordOverride(alpha = 2f)))))
    }

    @Test
    fun drawModeProgramIsRejectedByCollectBoxes() {
        val program = EffectProgram(
            outputMode = EFFECT_OUTPUT_DRAW,
            code = listOf(instruction("end"))
        )
        assertNull(EffectVm(program).collectBoxes(context(), stubMeasurer))
    }

    // ---- 文字盒:几何/状态 ----

    @Test
    fun drawTextBecomesTextBoxWithCapturedState() {
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(value = "-15654349"), // 0: 颜色 0xFF112233(带符号十进制)
                EffectConstant(value = "72"), //        1: 字号
                EffectConstant(value = "1"), //         2: alpha
                EffectConstant(type = "string", value = "你"), // 3: 文本
                EffectConstant(value = "15"), //        4: x
                EffectConstant(value = "120") //        5: baseline
            ),
            code = listOf(
                instruction("const", 0),
                instruction("setColor"),
                instruction("const", 2),
                instruction("setAlpha"),
                instruction("const", 1),
                instruction("setTextSize"),
                instruction("const", 3),
                instruction("const", 4),
                instruction("const", 5),
                instruction("drawText"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        val box = boxes!!.single() as EffectBox.TextBox
        assertEquals("你", box.text)
        assertEquals(15f, box.x, 0f)
        assertEquals(120f, box.baseline, 0f)
        assertEquals(0xFF112233.toInt(), box.paint.color)
        assertEquals(1f, box.paint.alpha, 0f)
        assertEquals(72f, box.paint.textSize, 0f)
        assertEquals(EFFECT_ALIGN_LEFT, box.paint.align)
        assertTrue(box.transform.isIdentity)
    }

    @Test
    fun initialTextSizeAndColorComeFromContext() {
        // 程序不设置任何状态直接 drawText:字号取 context.textSize、颜色取 colors.unsung。
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(type = "string", value = "a"),
                EffectConstant(value = "1"),
                EffectConstant(value = "2")
            ),
            code = listOf(
                instruction("const", 0),
                instruction("const", 1),
                instruction("const", 2),
                instruction("drawText"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        val box = boxes!!.single() as EffectBox.TextBox
        assertEquals(64f, box.paint.textSize, 0f)
        assertEquals(0x80FFFFFF.toInt(), box.paint.color)
    }

    // ---- 变换烘焙 ----

    @Test
    fun transformIsBakedIntoTextBoxMatrix() {
        // translate(100,50) → rotate(90°) → drawText("X", 10, 20)
        // 直绘语义:M = T·R,点 (10,20) 落在 (80,60);盒模式必须给出同一矩阵。
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(type = "string", value = "X"),
                EffectConstant(value = "10"),
                EffectConstant(value = "20"),
                EffectConstant(value = "100"),
                EffectConstant(value = "50"),
                EffectConstant(value = "90")
            ),
            code = listOf(
                instruction("const", 3), // x=100
                instruction("const", 4), // y=50(压在上,先弹)
                instruction("translate"),
                instruction("const", 5), // deg=90
                instruction("rotate"),
                instruction("const", 0),
                instruction("const", 1),
                instruction("const", 2),
                instruction("drawText"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        val transform = (boxes!!.single() as EffectBox.TextBox).transform
        val x = transform.m0 * 10f + transform.m1 * 20f + transform.m2
        val y = transform.m3 * 10f + transform.m4 * 20f + transform.m5
        assertEquals(80f, x, 1e-3f)
        assertEquals(60f, y, 1e-3f)
        assertFalse(transform.isIdentity)
    }

    @Test
    fun saveRestoreKeepsTransformScoped() {
        // translate(10,20) → save → translate(10,20) → drawText(A) → restore → drawText(B)
        // A 的累积平移 (20,40),B 只带 (10,20)。
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(type = "string", value = "A"),
                EffectConstant(type = "string", value = "B"),
                EffectConstant(value = "10"),
                EffectConstant(value = "20")
            ),
            code = listOf(
                instruction("const", 2),
                instruction("const", 3),
                instruction("translate"),
                instruction("save"),
                instruction("const", 2),
                instruction("const", 3),
                instruction("translate"),
                instruction("const", 0),
                instruction("const", 2),
                instruction("const", 3),
                instruction("drawText"),
                instruction("restore"),
                instruction("const", 1),
                instruction("const", 2),
                instruction("const", 3),
                instruction("drawText"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        val texts = boxes!!.filterIsInstance<EffectBox.TextBox>()
        assertEquals(2, texts.size)
        val a = texts[0].transform
        assertEquals(20f, a.m2, 1e-4f)
        assertEquals(40f, a.m5, 1e-4f)
        val b = texts[1].transform
        assertEquals(10f, b.m2, 1e-4f)
        assertEquals(20f, b.m5, 1e-4f)
    }

    @Test
    fun rotateScaleCompositionStaysExactAsMatrix() {
        // scale(2,3) → rotate(90°):op 序后乘 ⇒ M = S·R,2×2 部分逐值断言(不分解)。
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(type = "string", value = "Y"),
                EffectConstant(value = "2"),
                EffectConstant(value = "3"),
                EffectConstant(value = "90")
            ),
            code = listOf(
                instruction("const", 1),
                instruction("const", 2),
                instruction("scale"),
                instruction("const", 3),
                instruction("rotate"),
                instruction("const", 0),
                instruction("const", 0),
                instruction("const", 0),
                instruction("drawText"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        val t = (boxes!!.single() as EffectBox.TextBox).transform
        val rad = Math.toRadians(90.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        // M = S·R = [2 0; 0 3]·[c -s; s c] = [2c -2s; 3s 3c]
        assertEquals(2f * c, t.m0, 1e-4f)
        assertEquals(-2f * s, t.m1, 1e-4f)
        assertEquals(3f * s, t.m3, 1e-4f)
        assertEquals(3f * c, t.m4, 1e-4f)
    }

    // ---- 形状/路径盒 ----

    @Test
    fun shapeOpsBecomeBoxesWithStyle() {
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(value = "1"), //  0: 通用坐标/半径
                EffectConstant(value = "5"), //  1: 通用坐标
                EffectConstant(value = "0.5") // 2: alpha
            ),
            code = listOf(
                instruction("const", 2),
                instruction("setAlpha"),
                instruction("setStroke"),
                instruction("const", 0), // cx
                instruction("const", 0), // cy
                instruction("const", 1), // r(压在上,先弹)
                instruction("drawCircle"),
                instruction("const", 0), // x1
                instruction("const", 0), // y1
                instruction("const", 1), // x2
                instruction("const", 1), // y2(压在上,先弹)
                instruction("drawLine"),
                instruction("const", 0),
                instruction("const", 0),
                instruction("pathMove"),
                instruction("const", 1),
                instruction("const", 1),
                instruction("pathLine"),
                instruction("drawPath"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        assertEquals(3, boxes!!.size)
        val circle = boxes[0] as EffectBox.CircleBox
        // drawCircle 弹 r→cy→cx:r 取最后压入的常量 1(值 5)。
        assertEquals(5f, circle.radius, 0f)
        assertEquals(EFFECT_STYLE_STROKE, circle.paint.style)
        assertEquals(0.5f, circle.paint.alpha, 0f)
        assertTrue(boxes[1] is EffectBox.LineBox)
        // 线段强制描边(与直绘 drawLine 一致)。
        assertEquals(EFFECT_STYLE_STROKE, (boxes[1] as EffectBox.LineBox).paint.style)
        val path = boxes[2] as EffectBox.PathBox
        assertEquals(2, path.segments.size)
        assertEquals(EffectPathSegment.MOVE, path.segments[0].kind)
        assertEquals(EffectPathSegment.LINE, path.segments[1].kind)
    }

    // ---- 预算与失败语义 ----

    /** 构建一个「循环 N 次 body」的程序;循环头位于下标 2(loadLocal),jz 占位在下标 5。 */
    private fun countLoopProgram(iterations: Double, body: List<EffectInstruction>): EffectProgram {
        val loopStart = 2
        val code = ArrayList<EffectInstruction>().apply {
            add(instruction("const", 0)) // 0 → 计数器
            add(instruction("storeLocal", 0))
            add(instruction("loadLocal", 0))
            add(instruction("const", 2)) // iterations
            add(instruction("lt"))
            add(instruction("jz", -1)) // 占位
            addAll(body)
            add(instruction("loadLocal", 0))
            add(instruction("const", 1))
            add(instruction("add"))
            add(instruction("storeLocal", 0))
            add(instruction("jump", loopStart))
            add(instruction("end"))
        }
        code[5] = instruction("jz", code.size - 1)
        return EffectProgram(
            constants = listOf(
                EffectConstant(value = "0"),
                EffectConstant(value = "1"),
                EffectConstant(value = iterations.toString()),
                EffectConstant(type = "string", value = "w")
            ),
            code = code,
            maxLocals = 1,
            outputMode = EFFECT_OUTPUT_BOXES
        )
    }

    private fun drawTextBoxBody() = listOf(
        instruction("const", 3), // 文本
        instruction("const", 3), // x(借数值口径)
        instruction("const", 3), // y
        instruction("drawText")
    )

    private fun measureTextBody() = listOf(
        instruction("const", 3),
        instruction("measureText"),
        instruction("pop")
    )

    @Test
    fun exceedingBoxCountReturnsNull() {
        assertNull(collect(countLoopProgram(MAX_EFFECT_BOXES + 8.0, drawTextBoxBody())))
    }

    @Test
    fun boxBudgetBoundaryStillSucceeds() {
        val boxes = collect(countLoopProgram(MAX_EFFECT_BOXES.toDouble(), drawTextBoxBody()))
        assertNotNull(boxes)
        assertEquals(MAX_EFFECT_BOXES, boxes!!.size)
    }

    @Test
    fun measureTextCountsAgainstDrawBudget() {
        // measureText 计入 drawOps:超过 MAX_EFFECT_DRAW_OPS_PER_FRAME 次 → 失败。
        assertNull(collect(countLoopProgram(MAX_EFFECT_DRAW_OPS_PER_FRAME + 8.0, measureTextBody())))
    }

    @Test
    fun pathPointOverflowFails() {
        val code = ArrayList<EffectInstruction>()
        repeat(MAX_EFFECT_PATH_POINTS_PER_BOX + 1) {
            // pathLine 弹 y→x:压 x(常量1)、y(常量0)。
            code += instruction("const", 1)
            code += instruction("const", 0)
            code += instruction("pathLine")
        }
        code += instruction("drawPath")
        code += instruction("end")
        val program = EffectProgram(
            constants = listOf(EffectConstant(value = "0"), EffectConstant(value = "1")),
            code = code,
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        assertNull(collect(program))
    }

    @Test
    fun oversizedTextFails() {
        val longText = "x".repeat(MAX_EFFECT_BOX_TEXT_CHARS + 1)
        val program = EffectProgram(
            constants = listOf(EffectConstant(type = "string", value = longText)),
            code = listOf(
                instruction("const", 0),
                instruction("const", 0),
                instruction("const", 0),
                instruction("drawText"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        assertNull(collect(program))
    }

    @Test
    fun failureAfterEmitReturnsNullNotPartialBoxes() {
        // 先成功画一个盒,再触发栈下溢 → 整帧失败,不得返回那个盒。
        val program = EffectProgram(
            constants = listOf(EffectConstant(type = "string", value = "z")),
            code = listOf(
                instruction("const", 0),
                instruction("const", 0),
                instruction("const", 0),
                instruction("drawText"),
                instruction("add"), // 栈空 → 失败
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        assertNull(collect(program))
    }

    @Test
    fun clipRectIsNoopAndEmitsNothing() {
        // clipRect 弹 h→w→y→x(压序 x,y,w,h)。
        val program = EffectProgram(
            constants = listOf(EffectConstant(value = "5")),
            code = listOf(
                instruction("const", 0),
                instruction("const", 0),
                instruction("const", 0),
                instruction("const", 0),
                instruction("clipRect"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        assertTrue(boxes!!.isEmpty())
    }

    // ---- 内置贴图(drawSprite) ----

    @Test
    fun drawSpriteEmitsSpriteBoxWithGeometryAndPaint() {
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(value = "7"),   // 0: spriteId
                EffectConstant(value = "10"),  // 1: x
                EffectConstant(value = "20"),  // 2: y
                EffectConstant(value = "40"),  // 3: w
                EffectConstant(value = "40")   // 4: h
            ),
            code = listOf(
                instruction("const", 0),
                instruction("const", 1),
                instruction("const", 2),
                instruction("const", 3),
                instruction("const", 4),
                instruction("drawSprite"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)
        val box = boxes!!.single() as EffectBox.SpriteBox
        assertEquals(7, box.spriteId)
        assertEquals(10f, box.x, 0f)
        assertEquals(20f, box.y, 0f)
        assertEquals(40f, box.width, 0f)
        assertEquals(40f, box.height, 0f)
        assertTrue(box.transform.isIdentity)
    }

    @Test
    fun outOfRangeSpriteIdIsDroppedWithoutFailingFrame() {
        val program = EffectProgram(
            constants = listOf(
                EffectConstant(value = "300"), // 越界 id
                EffectConstant(value = "0"),
                EffectConstant(value = "0"),
                EffectConstant(value = "10"),
                EffectConstant(value = "10")
            ),
            code = listOf(
                instruction("const", 0),
                instruction("const", 1),
                instruction("const", 2),
                instruction("const", 3),
                instruction("const", 4),
                instruction("drawSprite"),
                instruction("end")
            ),
            maxLocals = 0,
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val boxes = collect(program)
        assertNotNull(boxes)              // 未知素材不整帧失败
        assertTrue(boxes!!.isEmpty())     // 该盒被静默丢弃
    }

    // ---- 归一化与序列化 ----

    @Test
    fun outputModeNormalizationIsIdempotent() {
        val boxesProgram = EffectProgram(
            code = listOf(instruction("end")),
            outputMode = EFFECT_OUTPUT_BOXES
        )
        val once = normalizeEffectProgram(boxesProgram)
        assertNotNull(once)
        assertEquals(EFFECT_OUTPUT_BOXES, once!!.outputMode)
        assertEquals(once, normalizeEffectProgram(once))

        val unknown = EffectProgram(code = listOf(instruction("end")), outputMode = "particles")
        val normalized = normalizeEffectProgram(unknown)
        assertNotNull(normalized)
        assertEquals(EFFECT_OUTPUT_DRAW, normalized!!.outputMode)
        assertEquals(normalized, normalizeEffectProgram(normalized))

        // 默认(缺省字段)保持 draw,且不产生字段改写。
        val legacy = EffectProgram(code = listOf(instruction("end")))
        assertEquals(EFFECT_OUTPUT_DRAW, legacy.outputMode)
        assertEquals(legacy, normalizeEffectProgram(legacy))
    }

    @Test
    fun boxProgramSurvivesConfigJsonRoundTrip() {
        val program = normalizeEffectProgram(
            EffectProgram(
                constants = listOf(EffectConstant(type = "string", value = "hi")),
                code = listOf(
                    instruction("const", 0),
                    instruction("const", 0),
                    instruction("const", 0),
                    instruction("drawText"),
                    instruction("end")
                ),
                maxLocals = 0,
                outputMode = EFFECT_OUTPUT_BOXES
            )
        )
        assertNotNull(program)
        val json = SceneCompiler.json.encodeToString(program!!)
        val decoded = SceneCompiler.json.decodeFromString<EffectProgram>(json)
        assertEquals(program, decoded)

        // 盒数据本身可往返(渐变/阴影/路径/矩阵)。
        val box: EffectBox = EffectBox.PathBox(
            segments = listOf(
                EffectPathSegment(EffectPathSegment.MOVE, x0 = 1f, y0 = 2f),
                EffectPathSegment(
                    EffectPathSegment.CUBIC,
                    x0 = 1f, y0 = 2f, x1 = 3f, y1 = 4f, x2 = 5f, y2 = 6f
                ),
                EffectPathSegment(EffectPathSegment.CLOSE)
            ),
            paint = EffectPaintState(
                color = 0xFF123456.toInt(),
                alpha = 0.5f,
                textSize = 64f,
                align = EFFECT_ALIGN_CENTER,
                style = EFFECT_STYLE_STROKE,
                strokeWidth = 2f,
                gradient = EffectGradient(0f, 0f, 10f, 0f, 1, 2),
                shadow = EffectShadow(set = true, radius = 4f, dx = 1f, dy = 2f, color = 3)
            ),
            transform = EffectTransform(
                m0 = 0.5f, m1 = -0.5f, m2 = 9f, m3 = 0.5f, m4 = 0.5f, m5 = 7f
            )
        )
        val boxJson = SceneCompiler.json.encodeToString(box)
        val decodedBox = SceneCompiler.json.decodeFromString<EffectBox>(boxJson)
        assertEquals(box, decodedBox)
    }
}

/** 直绘模式的记录 sink(与 EffectVmTest 同款,本文件自持避免跨文件耦合)。 */
private class RecordingSink : EffectRenderSink {
    val texts = ArrayList<Triple<String, Float, Float>>()
    val circles = ArrayList<Triple<Float, Float, Float>>()
    override fun save() = Unit
    override fun restore() = Unit
    override fun translate(x: Float, y: Float) = Unit
    override fun rotate(deg: Float) = Unit
    override fun scale(sx: Float, sy: Float) = Unit
    override fun setColor(argb: Int) = Unit
    override fun setAlpha(alpha: Float) = Unit
    override fun setTextSize(px: Float) = Unit
    override fun drawText(text: String, x: Float, y: Float) {
        texts += Triple(text, x, y)
    }
    override fun drawRect(x: Float, y: Float, w: Float, h: Float) = Unit
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
