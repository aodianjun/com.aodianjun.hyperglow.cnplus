package com.eza.hyperglow.effect

import kotlinx.serialization.Serializable

/**
 * 效果程序（沙箱字节码）。
 *
 * 设计目标：效果文件不再受固定属性表限制——它提供一段**程序**，由 HyperGlow 的
 * [EffectVm] 在渲染时执行。程序只能做两件事：
 *  1. 读取 HyperGlow 提供的上下文（歌词、词时间、播放位置、画布尺寸、调色板）；
 *  2. 调用受控绘制原语（文字、矩形、圆、线、路径、裁剪、变换、透明度）。
 *
 * 没有反射、文件、网络、线程、Java 互操作；没有这些指令，程序无法越出渲染沙箱。
 * 指令数与栈深都有硬上限，单帧超预算立即中止（见 [MAX_EFFECT_STEPS_PER_FRAME]）。
 *
 * JSON 形态（`encodeDefaults = true` 的配置 JSON 里，可空字段省略）：
 * ```json
 * {
 *   "version": 1,
 *   "maxLocals": 8,
 *   "constants": [ { "value": "0.5" }, { "value": "hello" } ],
 *   "code": [
 *     { "op": "const", "arg": 0 },
 *     { "op": "lineField", "text": "progress" },
 *     { "op": "mul" }
 *   ]
 * }
 * ```
 */
@Serializable
data class EffectProgram(
    val version: Int = EFFECT_PROGRAM_VERSION,
    /** 常量池；[EffectConstant.type] 为 null 时按 number 解释。 */
    val constants: List<EffectConstant> = emptyList(),
    val code: List<EffectInstruction> = emptyList(),
    val maxLocals: Int = 16
)

@Serializable
data class EffectConstant(
    /** `number` / `string` / `bool`；null = number。 */
    val type: String? = null,
    val value: String = "0"
)

@Serializable
data class EffectInstruction(
    val op: String = "",
    /** 常量下标 / 局部变量槽 / 跳转目标。 */
    val arg: Int? = null,
    /** 字段名（lineField/canvasField/colorField/globalField/wordField）。 */
    val text: String? = null
)

const val EFFECT_PROGRAM_VERSION = 1
const val MAX_EFFECT_CONSTANTS = 64
const val MAX_EFFECT_CODE = 512
const val MAX_EFFECT_LOCALS = 24
const val MAX_EFFECT_STACK = 64
const val MAX_EFFECT_STEPS_PER_FRAME = 100_000
/** 绘制/测量/状态原语的单帧上限：原生 Canvas/Path 调用比算术贵得多。 */
const val MAX_EFFECT_DRAW_OPS_PER_FRAME = 4_096
/** 程序 JSON 的保守字节预算：两个曲面各一份时仍要留在配置 64KiB 内。 */
const val MAX_EFFECT_PROGRAM_ESTIMATED_BYTES = 12 * 1024

/** 全部合法操作码；规范化与 VM 共用同一张表。 */
val EFFECT_OPS: Set<String> = setOf(
    // 常量/变量
    "const", "loadLocal", "storeLocal", "pop", "dup", "swap",
    // 上下文
    "globalField", "lineField", "lineCount", "line", "lineAtField",
    "canvasField", "colorField",
    "wordCount", "word", "wordField",
    // 算术
    "add", "sub", "mul", "div", "mod", "neg",
    "sin", "cos", "abs", "min", "max", "clamp", "lerp", "pow", "sqrt", "floor", "round",
    // 比较/逻辑
    "lt", "le", "gt", "ge", "eq", "ne", "and", "or", "not",
    // 字符串(逐字符布局用)
    "stringLength", "charAt", "substring",
    // 控制流
    "jump", "jz", "jnz",
    // 绘制状态
    "setColor", "setAlpha", "setTextSize", "setTextAlign",
    "setGradient", "clearGradient", "setShadow", "clearShadow",
    "setStrokeWidth", "setFill", "setStroke",
    "save", "restore", "translate", "rotate", "scale", "clipRect",
    // 绘制
    "drawText", "drawRect", "drawCircle", "drawLine", "drawRoundRect", "drawOval", "drawArc",
    "pathMove", "pathLine", "pathQuad", "pathCubic", "pathClose", "drawPath",
    "measureText", "end"
)

val EFFECT_GLOBAL_FIELDS: Set<String> = setOf(
    "time", "wall", "baseline", "textSize", "blockWidth", "blockHeight"
)
val EFFECT_LINE_FIELDS: Set<String> = setOf(
    "text", "start", "end", "progress", "index", "x", "width", "baseline", "height"
)
val EFFECT_CANVAS_FIELDS: Set<String> = setOf("width", "height")
val EFFECT_COLOR_FIELDS: Set<String> = setOf("sung", "unsung", "glow", "accent")
val EFFECT_WORD_FIELDS: Set<String> = setOf(
    "text", "x", "width", "start", "end", "progress", "index", "long",
    "line", "baseline"
)

/** 计入单帧绘制预算的操作码（原生绘制/测量/状态调用）。 */
val EFFECT_DRAW_OPS: Set<String> = setOf(
    "setColor", "setAlpha", "setTextSize", "setTextAlign",
    "setGradient", "clearGradient", "setShadow", "clearShadow",
    "setStrokeWidth", "setFill", "setStroke",
    "save", "restore", "translate", "rotate", "scale", "clipRect",
    "drawText", "drawRect", "drawCircle", "drawLine", "drawRoundRect", "drawOval", "drawArc",
    "pathMove", "pathLine", "pathQuad", "pathCubic", "pathClose", "drawPath", "measureText"
)

private val EFFECT_COMMAND_PREFIXES = setOf(
    "sh", "bash", "zsh", "cmd", "su", "exec", "am", "pm", "rm", "curl", "wget", "adb"
)

/**
 * 字符串常量会随配置文档一起持久化，必须过与 `SceneCompiler` 相同的不安全字符串扫描；
 * 否则一个 `"http://x"` 常量就能让整份外观配置在下次加载时被拒。
 */
private fun isUnsafeEffectString(value: String): Boolean {
    val lower = value.lowercase()
    return value.indexOf('\u0000') >= 0 ||
        "://" in lower ||
        lower.startsWith("file:") ||
        lower.startsWith("content:") ||
        lower.startsWith("javascript:") ||
        value.startsWith("/") ||
        "../" in lower ||
        "..\\" in lower ||
        '\\' in value ||
        EFFECT_COMMAND_PREFIXES.any { lower == it || lower.startsWith("$it ") }
}

/**
 * 规范化并校验效果程序。返回 null = 结构非法；调用方应拒绝该效果。
 *
 * 幂等：只做裁剪/校验，不重写合法输入。`version` 必须为 [EFFECT_PROGRAM_VERSION]。
 */
fun normalizeEffectProgram(program: EffectProgram?): EffectProgram? {
    if (program == null) return null
    if (program.version != EFFECT_PROGRAM_VERSION) return null
    if (program.constants.size > MAX_EFFECT_CONSTANTS) return null
    if (program.code.isEmpty() || program.code.size > MAX_EFFECT_CODE) return null
    if (program.maxLocals !in 0..MAX_EFFECT_LOCALS) return null
    // 保守字节预算：两个曲面各带一份程序时仍要留在配置 64KiB 内。
    var estimatedBytes = program.code.size * 12
    for (constant in program.constants) {
        val type = constant.type ?: "number"
        if (type !in setOf("number", "string", "bool")) return null
        estimatedBytes += constant.value.length + 8
        when (type) {
            "number" -> {
                val number = constant.value.toDoubleOrNull() ?: return null
                if (!number.isFinite()) return null
            }
            "string" -> {
                if (constant.value.length > 256) return null
                if (isUnsafeEffectString(constant.value)) return null
            }
            "bool" -> if (constant.value != "true" && constant.value != "false") return null
        }
    }
    if (estimatedBytes > MAX_EFFECT_PROGRAM_ESTIMATED_BYTES) return null
    for (index in program.code.indices) {
        val instruction = program.code[index]
        if (instruction.op !in EFFECT_OPS) return null
        when (instruction.op) {
            "const" -> if (instruction.arg !in 0 until program.constants.size) return null
            "loadLocal", "storeLocal" ->
                if (instruction.arg !in 0 until program.maxLocals) return null
            "jump", "jz", "jnz" ->
                if (instruction.arg !in 0..program.code.size) return null
            "globalField" ->
                if (instruction.text !in EFFECT_GLOBAL_FIELDS) return null
            "lineField" ->
                if (instruction.text !in EFFECT_LINE_FIELDS) return null
            "canvasField" ->
                if (instruction.text !in EFFECT_CANVAS_FIELDS) return null
            "colorField" ->
                if (instruction.text !in EFFECT_COLOR_FIELDS) return null
            "wordField" ->
                if (instruction.text !in EFFECT_WORD_FIELDS) return null
        }
    }
    return program
}
