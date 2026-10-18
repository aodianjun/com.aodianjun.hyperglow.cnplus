package com.eza.hyperglow.customization

import com.eza.hyperglow.effect.EffectProgram
import com.eza.hyperglow.effect.normalizeEffectProgram
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 单词效果规格（声明式动画 DSL）。
 *
 * 目标：让「单词动画」不再被三档写死的渲染分支限制，而是由一组**数据原语**描述——
 * 位移 / 缩放 / 旋转 / 透明度 / 倾斜 / 颜色 / 辉光 / 扫光进度，配合关键帧与缓动。
 * 解释器在 App 内预览与 SystemUI 画布共用同一份实现（[evaluateWordEffect]），
 * 因此「任意效果」不需要加载第三方代码，也不会破坏插件的信任边界。
 *
 * 设计边界：
 * - 纯数据、无代码执行；所有数值在 [normalizeWordEffectSpec] 中钳制/校验。
 * - 词级粒度（现有画布的 `KaraokeWordRun` 即词块；中文行级源会按字合成词块）。
 * - 效果随 `SurfaceProfile.wordEffect` 进入配置文档，经
 *   `CompiledCustomizationBundleCodec` 的 JSON 配置通道下发，不占用逐帧 wire 的
 *   200 字符样式串。
 *
 * 时间轴：每个词块的进度 `p ∈ [0,1]`（0=未唱、1=已唱完）。关键帧的 `at` 是 `p`。
 * [stagger] 让后一个词块的时间轴滞后：`t = clamp(p - wordIndex * stagger)`，
 * 用于波浪/多米诺类效果。
 */
@Serializable
data class WordEffectSpec(
    /** 稳定 id（`[a-z0-9_]{1,32}`）；选择器按它去重，渲染本身不依赖它。 */
    val id: String = "",
    /** 展示名（≤48 字符）。 */
    val name: String = "",
    /** 长音节阈值（毫秒）；[WordEffectTrack.longOnly] 的轨道按它判定。 */
    val longSyllableMs: Int = 700,
    /** 每个词块的时间轴滞后量（0..0.5 进度单位）。0 = 所有词块同一时间轴。 */
    val stagger: Float = 0f,
    /** 是否保留词内扫光带。 */
    val sweep: Boolean = true,
    /** 扫光带宽度占词宽比例（0.05..1）。 */
    val sweepBand: Float = 0.28f,
    /** 未唱字暗度因子（0.1..0.6，经 steadyTextAlpha 曲线）；已唱恒 1。 */
    val unsungDim: Float = 0.35f,
    /** 辉光策略：`none` / `long`（只长音节）/ `all`（所有音节）。 */
    val glow: String = "none",
    /** 数值轨道；同一属性可有多条（后出现的覆盖先出现的），[WordEffectTrack.longOnly] 可叠加。 */
    val tracks: List<WordEffectTrack> = emptyList(),
    /** 颜色轨道（ARGB 线性插值）；空 = 沿用已唱/未唱调色板。 */
    val colors: List<WordEffectColorKeyframe> = emptyList(),
    /**
     * 沙箱字节码程序（高级档）：非 null 时由 [com.eza.hyperglow.effect.EffectVm] 执行，
     * 程序拿到整行的歌词/词时间/播放位置/画布数据并自行调用受控绘制原语；
     * [tracks]/[colors] 作为低阶 DSL 同时保留，但程序优先。
     */
    val program: EffectProgram? = null
)

/** 一条数值轨道。 */
@Serializable
data class WordEffectTrack(
    /** 属性名，见 [WORD_EFFECT_PROPERTIES]。 */
    val property: String = "",
    /** 关键帧（按 `at` 升序；规范化会补齐 0/1 端点）。 */
    val keyframes: List<WordEffectKeyframe> = emptyList(),
    /** 仅对长音节（时长 ≥ [WordEffectSpec.longSyllableMs]）生效。 */
    val longOnly: Boolean = false
)

/** 数值关键帧。`easing` 作用于「本帧 → 下一帧」这一段。 */
@Serializable
data class WordEffectKeyframe(
    val at: Float = 0f,
    val value: Float = 0f,
    val easing: String = "linear"
)

/** 颜色关键帧（`#RRGGBB` 或 `#AARRGGBB`）。`easing` 同上。 */
@Serializable
data class WordEffectColorKeyframe(
    val at: Float = 0f,
    val color: String = "#FFFFFF",
    val easing: String = "linear"
)

/** 一帧求值结果（渲染器消费；默认值 = 不改变现有观感）。 */
data class WordEffectFrame(
    val translateXEm: Float = 0f,
    val translateYEm: Float = 0f,
    val scale: Float = 1f,
    val rotateDeg: Float = 0f,
    val alpha: Float = 1f,
    val skewX: Float = 0f,
    /** null = 用渲染器默认辉光半径（行高比例）。 */
    val glowRadiusEm: Float? = null,
    val glowAlpha: Float = 1f,
    /** 负值 = 用词块自身进度。 */
    val sweepProgress: Float = -1f,
    /** null = 沿用已唱/未唱调色板。 */
    val colorArgb: Int? = null
)

/** 允许的属性及其取值范围（规范化时钳制）。 */
val WORD_EFFECT_PROPERTIES: Map<String, ClosedFloatingPointRange<Float>> = linkedMapOf(
    "translateX" to -2f..2f,
    "translateY" to -2f..2f,
    "scale" to 0.1f..3f,
    "rotate" to -720f..720f,
    "alpha" to 0f..1f,
    "skewX" to -1f..1f,
    "glowRadius" to 0f..1f,
    "glowAlpha" to 0f..1f,
    "sweepProgress" to 0f..1f
)

/** 允许的缓动名。未知值回落 `linear`（规范化不拒绝，避免旧包整体失效）。 */
val WORD_EFFECT_EASINGS: Set<String> = setOf(
    "linear",
    "easeIn",
    "easeOut",
    "easeInOut",
    "easeInBack",
    "easeOutBack",
    "easeOutElastic",
    "easeOutBounce",
    "stepEnd"
)

private val WORD_EFFECT_ID = Regex("^[a-z0-9_]{1,32}$")
private const val MAX_NAME_CHARS = 48
private const val MAX_TRACKS = 12
private const val MAX_KEYFRAMES_PER_TRACK = 16
private const val MAX_TOTAL_KEYFRAMES = 64
private const val MAX_COLORS = 8

/**
 * 规范化并校验一份效果规格。返回 null = 结构非法（未知属性、空轨道、非有限数值、
 * 非法颜色等），调用方应丢弃该效果并回落内置档。
 *
 * 幂等：`normalize(normalize(x)) == normalize(x)`。这是 SystemUI 侧
 * `validate_rewrote_fields` 的前提——App 侧编译后的规格必须与消费侧再规范化的结果
 * 逐字段相等，否则整份配置会被拒收。
 *
 * 宽容策略：数值越界钳制、关键帧排序/去重、缺 0/1 端点时按首尾值补齐；
 * 结构错误（未知属性、空关键帧、坏颜色、超限）仍拒绝，避免静默产生无意义效果。
 */
fun normalizeWordEffectSpec(spec: WordEffectSpec?): WordEffectSpec? {
    if (spec == null) return null
    val id = spec.id.trim()
    // "none" 是选择器里的「关闭」哨兵,不能作为效果 id。
    if (!WORD_EFFECT_ID.matches(id) || id == "none") return null
    if (spec.tracks.size > MAX_TRACKS || spec.colors.size > MAX_COLORS) return null
    // 单关键帧轨道会展开成 0/1 两帧;按展开后的数量校验总上限,保证二次规范化不超限。
    val expandedTotal = spec.tracks.sumOf { if (it.keyframes.size == 1) 2 else it.keyframes.size }
    if (expandedTotal > MAX_TOTAL_KEYFRAMES) return null
    if (spec.tracks.any { it.keyframes.isEmpty() } ||
        spec.tracks.any { it.keyframes.size > MAX_KEYFRAMES_PER_TRACK }
    ) {
        return null
    }
    // 名称随配置文档一起持久化,必须过与 SceneCompiler 相同的不安全字符串扫描;
    // 命中时回落 id,避免整份外观配置在下次加载时被 decodeDocument 拒绝。
    val rawName = spec.name.trim().take(MAX_NAME_CHARS)
    val name = if (rawName.isBlank() || SceneCompiler.containsUnsafeSchemaValue(rawName)) {
        id
    } else {
        rawName
    }
    val normalizedTracks = ArrayList<WordEffectTrack>(spec.tracks.size)
    val seen = HashSet<String>()
    for (track in spec.tracks) {
        if (track.property !in WORD_EFFECT_PROPERTIES) return null
        // 同一 (属性, longOnly) 只允许一条：后一条会覆盖前一条，留着只会让包作者困惑。
        if (!seen.add(track.property + "|" + track.longOnly)) return null
        val keyframes = normalizeNumericKeyframes(track.keyframes, track.property) ?: return null
        normalizedTracks += WordEffectTrack(
            property = track.property,
            keyframes = keyframes,
            longOnly = track.longOnly
        )
    }
    val normalizedColors = normalizeColorKeyframes(spec.colors) ?: return null
    // 程序与 DSL 轨道并行保留;程序结构非法时整份规格拒绝(与轨道/颜色同策略)。
    // 必须在此处复制 program,否则编译后的规格会丢掉程序、实机只剩低阶轨道。
    val normalizedProgram = if (spec.program == null) {
        null
    } else {
        normalizeEffectProgram(spec.program) ?: return null
    }
    return WordEffectSpec(
        id = id,
        name = name,
        longSyllableMs = spec.longSyllableMs.coerceIn(200, 3000),
        stagger = finiteOrZero(spec.stagger).coerceIn(0f, 0.5f),
        sweep = spec.sweep,
        sweepBand = finiteOrZero(spec.sweepBand).coerceIn(0.05f, 1f),
        unsungDim = finiteOrZero(spec.unsungDim).coerceIn(0.1f, 0.6f),
        glow = when (spec.glow) {
            "long", "all" -> spec.glow
            else -> "none"
        },
        tracks = normalizedTracks,
        colors = normalizedColors,
        program = normalizedProgram
    )
}

private fun normalizeNumericKeyframes(
    keyframes: List<WordEffectKeyframe>,
    property: String
): List<WordEffectKeyframe>? {
    val range = WORD_EFFECT_PROPERTIES[property] ?: return null
    if (keyframes.isEmpty() || keyframes.size > MAX_KEYFRAMES_PER_TRACK) return null
    if (keyframes.any { !it.at.isFinite() || !it.value.isFinite() }) return null
    // 先钳制 at/value 再排序去重:否则 -0.2 与 0.0 会被当成两个端点、二次规范化才合并,
    // 破坏幂等性(SystemUI 侧再规范化会改写字段,整份配置被 validate_rewrote_fields 拒收)。
    val clamped = keyframes.map {
        WordEffectKeyframe(
            at = it.at.coerceIn(0f, 1f),
            value = it.value.coerceIn(range.start, range.endInclusive),
            easing = it.easing.takeIf { name -> name in WORD_EFFECT_EASINGS } ?: "linear"
        )
    }.sortedBy { it.at }
    val deduped = ArrayList<WordEffectKeyframe>(clamped.size)
    for (frame in clamped) {
        if (deduped.isNotEmpty() && deduped.last().at == frame.at) {
            deduped[deduped.lastIndex] = frame
        } else {
            deduped += frame
        }
    }
    // 单关键帧 = 常量:展开成 0/1 两帧。
    if (deduped.size == 1) {
        val only = deduped.first()
        return listOf(only.copy(at = 0f), only.copy(at = 1f))
    }
    // 多关键帧:把首/尾的 at 就地拉伸到 0/1,不新增帧,尺寸上限保持稳定。
    deduped[0] = deduped[0].copy(at = 0f)
    deduped[deduped.lastIndex] = deduped[deduped.lastIndex].copy(at = 1f)
    return deduped
}

private fun normalizeColorKeyframes(
    keyframes: List<WordEffectColorKeyframe>
): List<WordEffectColorKeyframe>? {
    if (keyframes.isEmpty()) return emptyList()
    if (keyframes.size > MAX_COLORS) return null
    if (keyframes.any { !it.at.isFinite() }) return null
    // 与数值轨道同序:钳制/归一 easing → 排序 → 去重 → 端点拉伸。
    val parsed = keyframes.map { frame ->
        val argb = parseWordEffectColor(frame.color) ?: return null
        WordEffectColorKeyframe(
            at = frame.at.coerceIn(0f, 1f),
            color = formatWordEffectColor(argb),
            easing = frame.easing.takeIf { it in WORD_EFFECT_EASINGS } ?: "linear"
        )
    }.sortedBy { it.at }
    val deduped = ArrayList<WordEffectColorKeyframe>(parsed.size)
    for (frame in parsed) {
        if (deduped.isNotEmpty() && deduped.last().at == frame.at) {
            deduped[deduped.lastIndex] = frame
        } else {
            deduped += frame
        }
    }
    if (deduped.size == 1) {
        val only = deduped.first()
        return listOf(only.copy(at = 0f), only.copy(at = 1f))
    }
    deduped[0] = deduped[0].copy(at = 0f)
    deduped[deduped.lastIndex] = deduped[deduped.lastIndex].copy(at = 1f)
    return deduped
}

/**
 * 求值：给定词块进度、词块序号（stagger 用）、是否长音节与词块总数，返回这一帧的变换。
 * 纯函数；预览与实机共用。
 *
 * stagger 按词块总数归一：`t = (p - i·s) / (1 - (n-1)·s)`，保证最后一个词块在 p=1
 * 时恰好走完（不归一的话 `i·s` 一旦超过 1，后面的词块会永久冻结在首帧）。s 也按
 * `0.9/(n-1)` 封顶，避免分母过小。
 */
fun evaluateWordEffect(
    spec: WordEffectSpec,
    progress: Float,
    wordIndex: Int,
    longSyllable: Boolean,
    wordCount: Int = 1
): WordEffectFrame {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val count = wordCount.coerceAtLeast(1)
    val stagger = if (count > 1) {
        spec.stagger.coerceIn(0f, 0.9f / (count - 1))
    } else {
        0f
    }
    val span = (1f - stagger * (count - 1)).coerceAtLeast(0.1f)
    val t = ((p - wordIndex.coerceAtLeast(0) * stagger) / span).coerceIn(0f, 1f)
    // 逐帧热路径:用局部变量累积,最后只分配一个 WordEffectFrame(不按轨道 copy)。
    var translateX = 0f
    var translateY = 0f
    var scale = 1f
    var rotate = 0f
    var alpha = 1f
    var skewX = 0f
    var glowRadius: Float? = null
    var glowAlpha = 1f
    var sweepProgress = -1f
    for (track in spec.tracks) {
        if (track.longOnly && !longSyllable) continue
        val range = WORD_EFFECT_PROPERTIES[track.property] ?: continue
        // 回弹/弹性缓动会越过 0..1，插值结果必须按属性范围再钳制一次（负 scale 会镜像文字）。
        val value = evaluateWordEffectTrack(track.keyframes, t)
            .coerceIn(range.start, range.endInclusive)
        when (track.property) {
            "translateX" -> translateX = value
            "translateY" -> translateY = value
            "scale" -> scale = value
            "rotate" -> rotate = value
            "alpha" -> alpha = value
            "skewX" -> skewX = value
            "glowRadius" -> glowRadius = value
            "glowAlpha" -> glowAlpha = value
            "sweepProgress" -> sweepProgress = value
        }
    }
    return WordEffectFrame(
        translateXEm = translateX,
        translateYEm = translateY,
        scale = scale,
        rotateDeg = rotate,
        alpha = alpha,
        skewX = skewX,
        glowRadiusEm = glowRadius,
        glowAlpha = glowAlpha,
        sweepProgress = sweepProgress,
        colorArgb = if (spec.colors.isEmpty()) null else evaluateWordEffectColor(spec.colors, t)
    )
}

/** 单条数值轨道求值：段内按**起始关键帧**的缓动插值；端点外取端点值。 */
fun evaluateWordEffectTrack(keyframes: List<WordEffectKeyframe>, t: Float): Float {
    if (keyframes.isEmpty()) return 0f
    if (t <= keyframes.first().at) return keyframes.first().value
    if (t >= keyframes.last().at) return keyframes.last().value
    for (index in 0 until keyframes.lastIndex) {
        val start = keyframes[index]
        val end = keyframes[index + 1]
        if (t <= end.at) {
            val span = (end.at - start.at).coerceAtLeast(1e-6f)
            val u = ((t - start.at) / span).coerceIn(0f, 1f)
            val eased = applyWordEffectEasing(start.easing, u)
            return start.value + (end.value - start.value) * eased
        }
    }
    return keyframes.last().value
}

/** 颜色轨道求值：RGB 线性插值（alpha 忽略，由渲染器的行亮度统一控制）。 */
fun evaluateWordEffectColor(keyframes: List<WordEffectColorKeyframe>, t: Float): Int? {
    if (keyframes.isEmpty()) return null
    val firstColor = parseWordEffectColor(keyframes.first().color) ?: return null
    if (t <= keyframes.first().at) return firstColor
    val lastColor = parseWordEffectColor(keyframes.last().color) ?: return null
    if (t >= keyframes.last().at) return lastColor
    for (index in 0 until keyframes.lastIndex) {
        val start = keyframes[index]
        val end = keyframes[index + 1]
        if (t <= end.at) {
            val startColor = parseWordEffectColor(start.color) ?: return null
            val endColor = parseWordEffectColor(end.color) ?: return null
            val span = (end.at - start.at).coerceAtLeast(1e-6f)
            val u = ((t - start.at) / span).coerceIn(0f, 1f)
            val eased = applyWordEffectEasing(start.easing, u)
            return lerpArgb(startColor, endColor, eased)
        }
    }
    return lastColor
}

/** 缓动函数（作用域：起始关键帧 → 下一关键帧）。 */
fun applyWordEffectEasing(easing: String, t: Float): Float {
    val u = if (t.isFinite()) t.coerceIn(0f, 1f) else 0f
    return when (easing) {
        "easeIn" -> u * u
        "easeOut" -> 1f - (1f - u) * (1f - u)
        "easeInOut" -> if (u < 0.5f) 2f * u * u else 1f - 2f * (1f - u) * (1f - u)
        "easeInBack" -> {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            c3 * u * u * u - c1 * u * u
        }
        "easeOutBack" -> {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val v = u - 1f
            1f + c3 * v * v * v + c1 * v * v
        }
        "easeOutElastic" -> if (u <= 0f) {
            0f
        } else if (u >= 1f) {
            1f
        } else {
            val c4 = (2f * PI / 3f).toFloat()
            (2f.pow(-10f * u) * sin((u * 10f - 0.75f) * c4) + 1f)
        }
        "easeOutBounce" -> easeOutBounce(u)
        "stepEnd" -> if (u >= 1f) 1f else 0f
        else -> u
    }
}

private fun easeOutBounce(u: Float): Float {
    val n1 = 7.5625f
    val d1 = 2.75f
    var x = u
    return when {
        x < 1f / d1 -> n1 * x * x
        x < 2f / d1 -> {
            x -= 1.5f / d1
            n1 * x * x + 0.75f
        }
        x < 2.5f / d1 -> {
            x -= 2.25f / d1
            n1 * x * x + 0.9375f
        }
        else -> {
            x -= 2.625f / d1
            n1 * x * x + 0.984375f
        }
    }
}


/** `#RRGGBB` / `#AARRGGBB` → ARGB；非法返回 null。 */
fun parseWordEffectColor(value: String): Int? {
    WORD_EFFECT_COLOR_CACHE[value]?.let { return it }
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val rgb = hex.toLong(16)
    val argb = if (hex.length == 6) {
        (0xFF000000L or rgb).toInt()
    } else {
        rgb.toInt()
    }
    // 颜色轨道逐帧求值:缓存解析结果,避免每帧对同一 hex 做字符串/进制解析。
    if (WORD_EFFECT_COLOR_CACHE.size >= 64) WORD_EFFECT_COLOR_CACHE.clear()
    WORD_EFFECT_COLOR_CACHE[value] = argb
    return argb
}

/** 颜色解析缓存(有界):键是配置里的原始 hex 串,正常 ≤8 个。 */
private val WORD_EFFECT_COLOR_CACHE = java.util.concurrent.ConcurrentHashMap<String, Int>(64)

private fun formatWordEffectColor(argb: Int): String = "#%08X".format(argb)

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val a = (from ushr shift) and 0xFF
        val b = (to ushr shift) and 0xFF
        return (a + (b - a) * u).roundToInt().coerceIn(0, 255)
    }
    return (channel(24) shl 24) or
        (channel(16) shl 16) or
        (channel(8) shl 8) or
        channel(0)
}

private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

/**
 * 自定义效果可能越出主行行盒的垂直外扩量（px，保守上界）。
 *
 * 画布的内容块两端余量原本只按辉光半径与 BetterLyrics 下沉记账；自定义 DSL 允许
 * translateY ±2em、scale 3、rotate ±720°、glowRadius 1em，若不额外记账，大效果会被
 * 歌词块裁剪沿切平。这里取各轨道关键帧的最大幅度估一个上界，供
 * `AodLyricCanvasView.effectEdges` 与卡片高度测量使用。水平方向仍受歌词块宽度约束
 * （见 docs/WORD_EFFECT_PACKS.md 的边界说明）。
 */
fun wordEffectVerticalOverdrawPx(
    spec: WordEffectSpec,
    textSizePx: Float,
    lineHeightPx: Float
): Float {
    if (!textSizePx.isFinite() || !lineHeightPx.isFinite()) return 0f
    var upEm = 0f
    var downEm = 0f
    var maxScale = 1f
    var maxRotateDeg = 0f
    var maxGlowEm = 0f
    for (track in spec.tracks) {
        when (track.property) {
            "translateY" -> track.keyframes.forEach { frame ->
                if (frame.value < 0f) upEm = maxOf(upEm, -frame.value)
                else downEm = maxOf(downEm, frame.value)
            }
            "scale" -> track.keyframes.forEach { maxScale = maxOf(maxScale, it.value) }
            "rotate" -> track.keyframes.forEach {
                maxRotateDeg = maxOf(maxRotateDeg, kotlin.math.abs(it.value))
            }
            "glowRadius" -> track.keyframes.forEach { maxGlowEm = maxOf(maxGlowEm, it.value) }
        }
    }
    if (spec.glow != "none" && maxGlowEm == 0f) {
        // 与 LyricGlowRenderer.HALO_RADIUS_FRACTION 同值;模型层不依赖 root.aod 包。
        maxGlowEm = 0.36f
    }
    val scaleExtra = (maxScale - 1f).coerceAtLeast(0f) * lineHeightPx * 0.5f
    val rotateExtra = kotlin.math.abs(
        kotlin.math.sin(maxRotateDeg * (PI / 180.0))
    ).toFloat() * lineHeightPx
    return maxOf(upEm, downEm) * textSizePx + scaleExtra + rotateExtra + maxGlowEm * textSizePx
}
