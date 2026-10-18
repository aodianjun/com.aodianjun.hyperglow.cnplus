package com.eza.hyperglow.effect

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** 一帧里一个词块的只读快照（由 HyperGlow 提供，效果程序消费）。 */
data class EffectWordSample(
    val text: String,
    val x: Float,
    val width: Float,
    val startMs: Long,
    val endMs: Long,
    val progress: Float,
    val index: Int,
    val longSyllable: Boolean,
    /** 该词所属视觉行下标（整块上下文用）。 */
    val lineIndex: Int = 0,
    /** 该词所属视觉行的基线 y（整块上下文用；程序应优先用它而不是全局 baseline）。 */
    val baseline: Float = 0f
)

/** 一个视觉行（整块歌词里的一行）的几何与时间快照。 */
data class EffectLineSample(
    val text: String,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val progress: Float,
    val x: Float,
    val width: Float,
    val baseline: Float,
    val height: Float
)

/** 本帧调色板（ARGB）。 */
data class EffectColors(
    val sung: Int,
    val unsung: Int,
    val glow: Int,
    val accent: Int
)

/**
 * HyperGlow 提供给效果程序的**全部数据**：歌词文本、行/词时间、播放位置、真实时钟、
 * 画布尺寸、字号、基线、调色板，以及**整块歌词**的所有视觉行/词与行几何。效果文件只能
 * 通过 [EffectRenderSink] 绘制，不能访问 Android/Java/文件/网络。
 */
data class EffectFrameContext(
    /** 播放位置（毫秒）；由画布/预览的 projected position 传入。 */
    val timeMs: Long,
    /** 真实时钟（elapsedRealtime，毫秒）；效果可用它做与播放无关的呼吸/闪烁。 */
    val wallClockMs: Long,
    val deltaMs: Long,
    val frameIndex: Long,
    val lineText: String,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val lineProgress: Float,
    val lineIndex: Int,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val textSize: Float,
    val baseline: Float,
    val colors: EffectColors,
    /** 整块歌词的**所有**词（跨视觉行；`word.line` 给行下标，`word.baseline` 给该行基线）。 */
    val words: List<EffectWordSample>,
    /** 整块歌词的视觉行（换行后的每一行）；单行源时只有一项。 */
    val lines: List<EffectLineSample> = emptyList(),
    val blockWidth: Float = 0f,
    val blockHeight: Float = 0f
)

/**
 * 受控绘制原语。宿主负责把它映射到 Android Canvas；效果程序只能调用这些方法。
 * 坐标均为画布像素；`drawText` 的 y 是基线。
 */
interface EffectRenderSink {
    fun save()
    fun restore()
    fun translate(x: Float, y: Float)
    fun rotate(deg: Float)
    fun scale(sx: Float, sy: Float)
    fun setColor(argb: Int)
    fun setAlpha(alpha: Float)
    fun setTextSize(px: Float)
    fun drawText(text: String, x: Float, y: Float)
    fun drawRect(x: Float, y: Float, w: Float, h: Float)
    fun drawCircle(cx: Float, cy: Float, r: Float)
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float)
    fun pathMove(x: Float, y: Float)
    fun pathLine(x: Float, y: Float)
    fun pathClose()
    fun drawPath()
    fun clipRect(x: Float, y: Float, w: Float, h: Float)
    fun measureText(text: String): Float

    // 以下为扩展原语;默认空实现,旧 sink(测试/降级)不受影响。
    /** 0=left, 1=center, 2=right。 */
    fun setTextAlign(align: Int) = Unit
    fun setGradient(x0: Float, y0: Float, x1: Float, y1: Float, color0: Int, color1: Int) = Unit
    fun clearGradient() = Unit
    fun setShadow(radius: Float, dx: Float, dy: Float, color: Int) = Unit
    fun clearShadow() = Unit
    fun setStrokeWidth(width: Float) = Unit
    fun setFill() = Unit
    fun setStroke() = Unit
    fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) = Unit
    fun drawOval(x: Float, y: Float, w: Float, h: Float) = Unit
    fun drawArc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float) = Unit
    fun pathQuad(cx: Float, cy: Float, x: Float, y: Float) = Unit
    fun pathCubic(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) = Unit
}

/**
 * 沙箱字节码解释器。纯 Kotlin、无 Android 依赖，预览/实机共用；单帧指令预算
 * [MAX_EFFECT_STEPS_PER_FRAME]，超预算或栈溢出返回 false（调用方回退到内置/DSL 路径）。
 */
class EffectVm(private val program: EffectProgram) {
    private val stack = ArrayDeque<Any?>()
    // 直接构造的 VM 也不能因负数/超大 maxLocals 崩:按硬上限收敛。
    private val locals = arrayOfNulls<Any?>(program.maxLocals.coerceIn(0, MAX_EFFECT_LOCALS))
    private var context: EffectFrameContext? = null
    private var sink: EffectRenderSink? = null
    private var steps = 0
    private var drawOps = 0
    private var failed = false

    fun render(context: EffectFrameContext, sink: EffectRenderSink): Boolean {
        this.context = context
        this.sink = sink
        stack.clear()
        locals.fill(null)
        steps = 0
        drawOps = 0
        failed = false
        var pc = 0
        val code = program.code
        while (pc < code.size) {
            if (failed) return false
            if (++steps > MAX_EFFECT_STEPS_PER_FRAME) return false
            val instruction = code[pc++]
            if (instruction.op in EFFECT_DRAW_OPS &&
                ++drawOps > MAX_EFFECT_DRAW_OPS_PER_FRAME
            ) {
                return false
            }
            when (instruction.op) {
                "const" -> push(constant(instruction.arg ?: 0))
                "loadLocal" -> push(locals.getOrNull(instruction.arg ?: 0))
                "storeLocal" -> {
                    val slot = instruction.arg ?: -1
                    if (slot !in locals.indices) {
                        failed = true
                    } else {
                        locals[slot] = pop()
                    }
                }
                "pop" -> pop()
                "dup" -> {
                    if (stack.isEmpty()) failed = true else push(stack.last())
                }
                "swap" -> {
                    if (stack.size < 2) {
                        failed = true
                    } else {
                        val b = pop()
                        val a = pop()
                        push(b)
                        push(a)
                    }
                }
                "globalField" -> push(globalField(instruction.text.orEmpty()))
                "lineField" -> push(lineField(instruction.text.orEmpty()))
                "lineCount" -> push(context.lines.size.toDouble())
                "line" -> {
                    val index = popNumber().toInt()
                    push(context.lines.getOrNull(index)?.let { LineRef(index) })
                }
                "lineAtField" -> {
                    val ref = pop()
                    val line = (ref as? LineRef)?.let { context.lines.getOrNull(it.index) }
                    push(lineAtField(line, instruction.text.orEmpty()))
                }
                "canvasField" -> push(
                    when (instruction.text) {
                        "width" -> context.canvasWidth.toDouble()
                        "height" -> context.canvasHeight.toDouble()
                        else -> 0.0
                    }
                )
                "colorField" -> push(
                    when (instruction.text) {
                        "sung" -> context.colors.sung
                        "unsung" -> context.colors.unsung
                        "glow" -> context.colors.glow
                        "accent" -> context.colors.accent
                        else -> 0
                    }
                )
                "wordCount" -> push(context.words.size.toDouble())
                "word" -> {
                    val index = popNumber().toInt()
                    push(context.words.getOrNull(index)?.let { WordRef(index) })
                }
                "wordField" -> {
                    val ref = pop()
                    val word = (ref as? WordRef)?.let { context.words.getOrNull(it.index) }
                    push(wordField(word, instruction.text.orEmpty()))
                }
                "add" -> binary { a, b -> a + b }
                "sub" -> binary { a, b -> a - b }
                "mul" -> binary { a, b -> a * b }
                "div" -> binary { a, b -> if (b == 0.0) 0.0 else a / b }
                "mod" -> binary { a, b -> if (b == 0.0) 0.0 else a % b }
                "neg" -> push(-popNumber())
                "sin" -> push(sin(popNumber()))
                "cos" -> push(cos(popNumber()))
                "abs" -> push(abs(popNumber()))
                "min" -> binary { a, b -> min(a, b) }
                "max" -> binary { a, b -> max(a, b) }
                "clamp" -> {
                    val high = popNumber()
                    val low = popNumber()
                    val value = popNumber()
                    push(value.coerceIn(min(low, high), max(low, high)))
                }
                "lerp" -> {
                    val t = popNumber()
                    val b = popNumber()
                    val a = popNumber()
                    push(a + (b - a) * t.coerceIn(0.0, 1.0))
                }
                "pow" -> binary { a, b -> a.pow(b) }
                "sqrt" -> push(sqrt(popNumber().coerceAtLeast(0.0)))
                "floor" -> push(floor(popNumber()))
                "round" -> push(round(popNumber()))
                "lt" -> {
                    val b = pop()
                    val a = pop()
                    push(compareValues(a, b) < 0)
                }
                "le" -> {
                    val b = pop()
                    val a = pop()
                    push(compareValues(a, b) <= 0)
                }
                "gt" -> {
                    val b = pop()
                    val a = pop()
                    push(compareValues(a, b) > 0)
                }
                "ge" -> {
                    val b = pop()
                    val a = pop()
                    push(compareValues(a, b) >= 0)
                }
                "eq" -> {
                    val b = pop()
                    val a = pop()
                    push(valuesEqual(a, b))
                }
                "ne" -> {
                    val b = pop()
                    val a = pop()
                    push(!valuesEqual(a, b))
                }
                "stringLength" -> push(popString().length.toDouble())
                "charAt" -> {
                    val index = popNumber().toInt()
                    val text = popString()
                    push(if (index in text.indices) text[index].toString() else "")
                }
                "substring" -> {
                    val end = popNumber().toInt()
                    val start = popNumber().toInt()
                    val text = popString()
                    val from = start.coerceIn(0, text.length)
                    val to = end.coerceIn(from, text.length)
                    push(text.substring(from, to))
                }
                "and" -> {
                    val b = popBool()
                    val a = popBool()
                    push(a && b)
                }
                "or" -> {
                    val b = popBool()
                    val a = popBool()
                    push(a || b)
                }
                "not" -> push(!popBool())
                "jump" -> pc = instruction.arg ?: pc
                "jz" -> if (!popBool()) pc = instruction.arg ?: pc
                "jnz" -> if (popBool()) pc = instruction.arg ?: pc
                "setColor" -> sink.setColor(popColor())
                "setAlpha" -> sink.setAlpha(popNumber().toFloat().coerceIn(0f, 1f))
                "setTextSize" -> sink.setTextSize(popNumber().toFloat().coerceIn(1f, 512f))
                "setTextAlign" -> sink.setTextAlign(popNumber().toInt())
                "setGradient" -> {
                    val color1 = popColor()
                    val color0 = popColor()
                    val y1 = popNumber().toFloat()
                    val x1 = popNumber().toFloat()
                    val y0 = popNumber().toFloat()
                    val x0 = popNumber().toFloat()
                    sink.setGradient(x0, y0, x1, y1, color0, color1)
                }
                "clearGradient" -> sink.clearGradient()
                "setShadow" -> {
                    val color = popColor()
                    val dy = popNumber().toFloat().coerceIn(-256f, 256f)
                    val dx = popNumber().toFloat().coerceIn(-256f, 256f)
                    val radius = popNumber().toFloat().coerceIn(0f, 256f)
                    sink.setShadow(radius, dx, dy, color)
                }
                "clearShadow" -> sink.clearShadow()
                "setStrokeWidth" -> sink.setStrokeWidth(
                    popNumber().toFloat().coerceIn(0f, 128f)
                )
                "setFill" -> sink.setFill()
                "setStroke" -> sink.setStroke()
                "save" -> sink.save()
                "restore" -> sink.restore()
                "translate" -> {
                    val y = popNumber().toFloat().coerceIn(-10_000f, 10_000f)
                    val x = popNumber().toFloat().coerceIn(-10_000f, 10_000f)
                    sink.translate(x, y)
                }
                "rotate" -> sink.rotate(popNumber().toFloat().coerceIn(-7_200f, 7_200f))
                "scale" -> {
                    val sy = popNumber().toFloat().coerceIn(0.01f, 100f)
                    val sx = popNumber().toFloat().coerceIn(0.01f, 100f)
                    sink.scale(sx, sy)
                }
                "clipRect" -> {
                    val h = popNumber().toFloat()
                    val w = popNumber().toFloat()
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.clipRect(x, y, w, h)
                }
                "drawText" -> {
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.drawText(popString(), x, y)
                }
                "drawRect" -> {
                    val h = popNumber().toFloat()
                    val w = popNumber().toFloat()
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.drawRect(x, y, w, h)
                }
                "drawCircle" -> {
                    val r = popNumber().toFloat()
                    val cy = popNumber().toFloat()
                    val cx = popNumber().toFloat()
                    sink.drawCircle(cx, cy, r)
                }
                "drawLine" -> {
                    val y2 = popNumber().toFloat()
                    val x2 = popNumber().toFloat()
                    val y1 = popNumber().toFloat()
                    val x1 = popNumber().toFloat()
                    sink.drawLine(x1, y1, x2, y2)
                }
                "drawRoundRect" -> {
                    val ry = popNumber().toFloat().coerceAtLeast(0f)
                    val rx = popNumber().toFloat().coerceAtLeast(0f)
                    val h = popNumber().toFloat()
                    val w = popNumber().toFloat()
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.drawRoundRect(x, y, w, h, rx, ry)
                }
                "drawOval" -> {
                    val h = popNumber().toFloat()
                    val w = popNumber().toFloat()
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.drawOval(x, y, w, h)
                }
                "drawArc" -> {
                    val sweep = popNumber().toFloat()
                    val start = popNumber().toFloat()
                    val h = popNumber().toFloat()
                    val w = popNumber().toFloat()
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.drawArc(x, y, w, h, start, sweep)
                }
                "pathMove" -> {
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.pathMove(x, y)
                }
                "pathLine" -> {
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    sink.pathLine(x, y)
                }
                "pathQuad" -> {
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    val cy = popNumber().toFloat()
                    val cx = popNumber().toFloat()
                    sink.pathQuad(cx, cy, x, y)
                }
                "pathCubic" -> {
                    val y = popNumber().toFloat()
                    val x = popNumber().toFloat()
                    val c2y = popNumber().toFloat()
                    val c2x = popNumber().toFloat()
                    val c1y = popNumber().toFloat()
                    val c1x = popNumber().toFloat()
                    sink.pathCubic(c1x, c1y, c2x, c2y, x, y)
                }
                "pathClose" -> sink.pathClose()
                "drawPath" -> sink.drawPath()
                "measureText" -> push(sink.measureText(popString()).toDouble())
                "end" -> return !failed
                else -> return false
            }
        }
        return !failed
    }

    private fun globalField(field: String): Any? = when (field) {
        "time" -> context?.timeMs?.toDouble() ?: 0.0
        "wall" -> context?.wallClockMs?.toDouble() ?: 0.0
        "dt" -> context?.deltaMs?.toDouble() ?: 0.0
        "frame" -> context?.frameIndex?.toDouble() ?: 0.0
        "baseline" -> context?.baseline?.toDouble() ?: 0.0
        "textSize" -> context?.textSize?.toDouble() ?: 0.0
        "blockWidth" -> context?.blockWidth?.toDouble() ?: 0.0
        "blockHeight" -> context?.blockHeight?.toDouble() ?: 0.0
        else -> 0.0
    }

    private fun lineField(field: String): Any? {
        // 整块上下文优先:当前行的 x/width/baseline/height 也能直接读。
        val current = context?.let { ctx -> ctx.lines.getOrNull(ctx.lineIndex) }
        if (current != null) return lineAtField(current, field)
        return when (field) {
            "text" -> context?.lineText ?: ""
            "start" -> context?.lineStartMs?.toDouble() ?: 0.0
            "end" -> context?.lineEndMs?.toDouble() ?: 0.0
            "progress" -> context?.lineProgress?.toDouble() ?: 0.0
            "index" -> context?.lineIndex?.toDouble() ?: 0.0
            else -> 0.0
        }
    }

    private fun lineAtField(line: EffectLineSample?, field: String): Any? {
        if (line == null) {
            return if (field == "text") "" else 0.0
        }
        return when (field) {
            "text" -> line.text
            "start" -> line.startMs.toDouble()
            "end" -> line.endMs.toDouble()
            "progress" -> line.progress.toDouble()
            "index" -> line.index.toDouble()
            "x" -> line.x.toDouble()
            "width" -> line.width.toDouble()
            "baseline" -> line.baseline.toDouble()
            "height" -> line.height.toDouble()
            else -> 0.0
        }
    }

    private fun wordField(word: EffectWordSample?, field: String): Any? {
        if (word == null) {
            return when (field) {
                "text" -> ""
                "long" -> false
                else -> 0.0
            }
        }
        return when (field) {
            "text" -> word.text
            "x" -> word.x.toDouble()
            "width" -> word.width.toDouble()
            "start" -> word.startMs.toDouble()
            "end" -> word.endMs.toDouble()
            "progress" -> word.progress.toDouble()
            "index" -> word.index.toDouble()
            "long" -> word.longSyllable
            "line" -> word.lineIndex.toDouble()
            "baseline" -> word.baseline.toDouble()
            else -> 0.0
        }
    }

    private fun constant(index: Int): Any? {
        val constant = program.constants.getOrNull(index) ?: return 0.0
        return when (constant.type ?: "number") {
            "string" -> constant.value
            "bool" -> constant.value == "true"
            else -> constant.value.toDoubleOrNull() ?: 0.0
        }
    }

    private fun binary(block: (Double, Double) -> Double) {
        val b = popNumber()
        val a = popNumber()
        push(block(a, b))
    }

    private fun push(value: Any?) {
        if (stack.size >= MAX_EFFECT_STACK) {
            failed = true
            return
        }
        stack.addLast(value)
    }

    private fun pop(): Any? {
        if (stack.isEmpty()) {
            failed = true
            return null
        }
        return stack.removeLast()
    }

    private fun popNumber(): Double {
        val number = number(pop())
        if (!number.isFinite()) {
            failed = true
            return 0.0
        }
        return number
    }

    private fun popString(): String = string(pop())

    private fun popBool(): Boolean = bool(pop())

    private fun popColor(): Int {
        val value = pop()
        return when (value) {
            is String -> parseEffectColor(value) ?: 0xFFFFFFFF.toInt()
            is Int -> value
            // Double.toInt() 会饱和:0xFFFFFFFF 变成 0x7FFFFFFF。先转 Long 再截 32 位。
            else -> number(value).toLong().toInt()
        }
    }

    private fun number(value: Any?): Double = when (value) {
        is Double -> if (value.isFinite()) value else 0.0
        is Int -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        // 字符串 "NaN"/"Infinity"/"1e999" 也能解析成非有限值,必须在这里拦掉。
        is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        else -> 0.0
    }

    private fun string(value: Any?): String = when (value) {
        is String -> value
        is Double -> if (value == floor(value) && abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Int -> value.toString()
        is Boolean -> if (value) "true" else "false"
        else -> ""
    }

    private fun bool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Double -> value != 0.0
        is Int -> value != 0
        is String -> value.isNotEmpty()
        else -> false
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean = when {
        a is String && b is String -> a == b
        a is Boolean && b is Boolean -> a == b
        else -> number(a) == number(b)
    }

    private fun compareValues(a: Any?, b: Any?): Int = when {
        a is String && b is String -> a.compareTo(b)
        else -> number(a).compareTo(number(b))
    }

    private data class WordRef(val index: Int)

    private data class LineRef(val index: Int)
}

/** 效果文件里的颜色字面量：`#RRGGBB` / `#AARRGGBB`。 */
fun parseEffectColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val rgb = hex.toLong(16)
    return if (hex.length == 6) (0xFF000000L or rgb).toInt() else rgb.toInt()
}
