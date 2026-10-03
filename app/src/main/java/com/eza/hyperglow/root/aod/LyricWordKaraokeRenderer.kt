package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.roundToLong

/**
 * 逐字卡拉OK的一个词位(行内绝对坐标):[playedFraction] 为该词已唱比例 0..1
 * (0=未唱、1=已唱完、之间=正在唱),驱动底字亮度/扫光/未唱下沉/已唱上浮;
 * [durationMs] 为词时长,驱动上浮动画的加速段;[longSyllable] 标记长音节
 * (「BetterLyrics」档放大/辉光只作用于长音节)。
 *
 * [highlightFraction] 为「高亮进度」:放大与辉光由它驱动,让同一词块内的字符共享
 * 一个进度(整块同步放大/发光而非单字符一闪,合成源的关键可见性)。负数=未提供,
 * 退回 [playedFraction](逐字源:字符自身进度)。
 */
internal data class KaraokeWordRun(
    val text: String,
    val x: Float,
    val width: Float,
    val playedFraction: Float,
    val durationMs: Long,
    val longSyllable: Boolean,
    val highlightFraction: Float = -1f
)

/** 长音节阈值:词时长不低于此值才算长音节(参考 jayfunc/BetterLyrics 的 700ms)。 */
internal const val KARAOKE_LONG_SYLLABLE_MS = 700L

/** 「BetterLyrics」档长音节放大峰值(参考其 LyricsScaleEffectAmount 115%)。 */
internal const val KARAOKE_LONG_SYLLABLE_SCALE_PEAK = 1.15f

/** 逐字卡拉OK基础放大峰值(历史档;BetterLyrics 档短音节沿用)。 */
internal const val KARAOKE_BASE_SCALE_PEAK = 1.0505f

/** 未唱字下沉量占行高比例(参考其 LyricsFloatAnimationAmount 自动档 = 行高 10%)。 */
internal const val KARAOKE_FLOAT_SINK_FRACTION = 0.10f

/** 未唱字上浮动画时长(参考其 LyricsFloatAnimationDuration 450ms)。 */
internal const val KARAOKE_FLOAT_DURATION_MS = 450L

/** 长音节判定(按词时长)。纯函数,可单测。 */
internal fun isLongKaraokeSyllable(durationMs: Long): Boolean =
    durationMs >= KARAOKE_LONG_SYLLABLE_MS

/** 未唱底字不透明度因子(与实机 setTextAlpha(0.35f) 同源)。 */
private const val KARAOKE_UNSUNG_FACTOR = 0.35f

/**
 * 已唱字不透明度:[alphaFactor] 为该行的整体亮度档(主行恒 1,辅助文字行取
 * [steadyTextAlpha] 后的辅助亮度),钳制 0..255。纯函数,可单测。
 */
internal fun karaokeSungAlpha(alphaFactor: Float): Int =
    (255f * alphaFactor.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)

/**
 * 未唱底字不透明度:与已唱同乘 [alphaFactor],保持 [KARAOKE_UNSUNG_FACTOR] 的相对暗度
 * (辅助文字逐字效果下「高亮辅助文字」关闭时整行一起变暗)。纯函数,可单测。
 */
internal fun karaokeUnsungAlpha(alphaFactor: Float): Int =
    (255f * steadyTextAlpha(KARAOKE_UNSUNG_FACTOR) * alphaFactor.coerceIn(0f, 1f))
        .roundToInt()
        .coerceIn(0, 255)

/**
 * 单次播放的放大峰值:BetterLyrics 档长音节放大到 [KARAOKE_LONG_SYLLABLE_SCALE_PEAK],
 * 其余沿用 [KARAOKE_BASE_SCALE_PEAK]。纯函数,可单测。
 */
internal fun karaokeScalePeak(betterLyrics: Boolean, longSyllable: Boolean): Float =
    if (betterLyrics && longSyllable) KARAOKE_LONG_SYLLABLE_SCALE_PEAK else KARAOKE_BASE_SCALE_PEAK

/** 演唱中放大曲线:经峰值再回落 1.0(历史逐字档同式,峰值参数化)。纯函数,可单测。 */
internal fun karaokeScaleAt(playedFraction: Float, peak: Float): Float {
    val t = playedFraction.coerceIn(0f, 1f)
    return if (t <= 0.7f) {
        karaokeLerp(0.95f, peak, t / 0.7f)
    } else {
        karaokeLerp(peak, 1f, (t - 0.7f) / 0.3f)
    }
}

/** 未唱字下沉像素:行高 × [KARAOKE_FLOAT_SINK_FRACTION],下限 1px。纯函数,可单测。 */
internal fun karaokeFloatSinkPx(lineHeight: Float): Float =
    (lineHeight * KARAOKE_FLOAT_SINK_FRACTION).coerceAtLeast(1f)

/**
 * 单字浮动偏移(px,正值向下):未唱恒下沉 [sinkPx];进入演唱后在
 * min([KARAOKE_FLOAT_DURATION_MS], 词时长) 内线性上浮回 0;已唱完恒 0。
 * 纯函数,可单测。
 */
internal fun karaokeFloatOffsetPx(playedFraction: Float, durationMs: Long, sinkPx: Float): Float {
    val f = playedFraction.coerceIn(0f, 1f)
    if (f <= 0f) return sinkPx
    if (f >= 1f) return 0f
    val floatFraction = if (durationMs > 0L) {
        (KARAOKE_FLOAT_DURATION_MS.toFloat() / durationMs.toFloat()).coerceIn(0.0001f, 1f)
    } else {
        1f
    }
    return sinkPx * (1f - (f / floatFraction).coerceIn(0f, 1f))
}

private fun karaokeLerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

/**
 * 行级源(无逐字时间戳)的合成字符时间窗:整块时长按几何宽度分摊——与行级扫光前缘
 * ([splitContinuousFill])完全同式,合成推进前缘与扫光前缘逐帧重合。纯函数,可单测。
 */
internal fun syntheticCharTimeWindow(
    blockStartMs: Long,
    blockEndMs: Long,
    totalWidth: Float,
    globalPrefixWidth: Float,
    charWidth: Float
): LongRange {
    val span = (blockEndMs - blockStartMs).coerceAtLeast(0L)
    val safeTotal = totalWidth.coerceAtLeast(1f)
    val start = blockStartMs +
        (span.toDouble() * globalPrefixWidth.coerceAtLeast(0f).toDouble() / safeTotal).roundToLong()
    val end = blockStartMs +
        (span.toDouble() * (globalPrefixWidth + charWidth).coerceAtLeast(0f).toDouble() / safeTotal)
            .roundToLong()
    val safeStart = start.coerceIn(blockStartMs, blockEndMs)
    val safeEnd = end.coerceIn(safeStart, blockEndMs)
    return safeStart until safeEnd
}

/**
 * 行级源合成的词块划分:空白跳过;CJK 汉字各自成块(逐字,中文行没有空格可依),
 * 连续非 CJK 非空白字符成一块(西文按词,连同词内标点)。放大/辉光以块为单位同步,
 * 块内字符仍各自扫光——中文逐字、西文逐词,与参考实现的音节粒度同构。
 */
internal fun syntheticKaraokeBlocks(text: String): List<IntRange> {
    val blocks = ArrayList<IntRange>()
    var index = 0
    while (index < text.length) {
        if (text[index].isWhitespace()) {
            index++
            continue
        }
        val start = index
        if (isCjkKaraokeChar(text[index])) {
            index++
        } else {
            while (index < text.length &&
                !text[index].isWhitespace() &&
                !isCjkKaraokeChar(text[index])
            ) {
                index++
            }
        }
        blocks += start until index
    }
    return blocks
}

/** CJK 统一表意文字(含扩展 A/兼容区):按字成块;其余(西文/数字/标点)按词成块。 */
private fun isCjkKaraokeChar(ch: Char): Boolean =
    ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0xF900..0xFAFF

/**
 * 逐字卡拉OK的共享渲染核心 —— 预览(PreviewComponents)与实机(AodLyricCanvasView)
 * 调用同一实现:底字亮度、词内扫光带、演唱中放大、未唱下沉/已唱上浮、长音节辉光
 * 只在此定义一次,杜绝"两份手工同步的拷贝"造成的漂移。
 *
 * 效果参考 jayfunc/BetterLyrics(WinUI3/Win2D)的逐字效果:未唱字下沉(行高 10%),
 * 唱到后 450ms 内弹回基线(「已唱上浮」);长音节(≥700ms)演唱中放大到 1.15、唱完
 * 回落;长音节 + 发光开启时活动词带 glow 色辉光。短音节沿用历史逐字卡拉OK运动
 * (峰值 1.0505、词内扫光),既有观感不变。
 *
 * 行级源(无逐字时间戳)由调用方用 [syntheticCharTimeWindow] 合成每字符的时间窗后
 * 走同一渲染:下沉/上浮与逐字点亮同样适用;合成词位恒标长音节——「正在唱的字」
 * 放大并(发光开启时)辉光,行级源没有真实音节时长,以当前被唱到的字承担该强调。
 */
internal object LyricWordKaraokeRenderer {
    /** 词内扫光带占词宽比例(与 [LyricGlowRenderer] 同式)。 */
    const val SWEEP_BAND_FRACTION = 0.28f

    /** 未唱字静态缩放(历史逐字卡拉OK档)。 */
    private const val UNSUNG_SCALE = 0.95f

    /**
     * 逐字卡拉OK绘制。[betterLyrics] 为「BetterLyrics」档:启用未唱下沉/已唱上浮与
     * 长音节放大/辉光;否则为历史逐字档(无浮动,短词放大 1.0505)。
     *
     * [alphaFactor] 为该行整体亮度档(默认 1=主行语义):已唱/未唱/辉光/扫光四路不透明度
     * 同乘它,供辅助文字逐字效果沿用「高亮辅助文字」设置而不另建一套取色。
     */
    fun draw(
        canvas: Canvas,
        paint: Paint,
        runs: List<KaraokeWordRun>,
        baseline: Float,
        sungColor: Int,
        unsungColor: Int,
        glowColor: Int,
        glowEnabled: Boolean,
        betterLyrics: Boolean,
        sinkPx: Float,
        alphaFactor: Float = 1f
    ) {
        val textSize = paint.textSize
        val sungAlpha = karaokeSungAlpha(alphaFactor)
        val dimAlpha = karaokeUnsungAlpha(alphaFactor)
        var index = 0
        while (index < runs.size) {
            val run = runs[index]
            index++
            if (run.text.isEmpty()) continue
            val played = run.playedFraction.coerceIn(0f, 1f)
            val sung = played >= 1f
            // 放大/辉光由「高亮进度」驱动:逐字源=字符自身进度;合成源=所属词块的进度
            // (块内字符共享),整块在一个音节演唱期间同步放大/发光、持续可见。
            val highlight = if (run.highlightFraction >= 0f) {
                run.highlightFraction.coerceIn(0f, 1f)
            } else {
                played
            }
            val active = highlight > 0f && highlight < 1f
            val scale = when {
                active -> karaokeScaleAt(highlight, karaokeScalePeak(betterLyrics, run.longSyllable))
                sung -> 1f
                else -> UNSUNG_SCALE
            }
            val y = if (betterLyrics) karaokeFloatOffsetPx(played, run.durationMs, sinkPx) else 0f
            val save = canvas.save()
            if (scale != 1f) canvas.scale(scale, scale, run.x + run.width / 2f, baseline)
            // 底字:已唱用 sung 色满亮,未唱/在唱未扫到的部分用 dim 色。
            paint.shader = null
            paint.clearShadowLayer()
            paint.color = if (sung) sungColor else unsungColor
            paint.alpha = if (sung) sungAlpha else dimAlpha
            canvas.drawText(run.text, run.x, baseline + y, paint)
            if (active) {
                // 长音节辉光(BetterLyrics 档 + 发光开启):glow 色阴影画在 sung 色文字下,
                // 光从文字背后透出(与共享 LyricGlowRenderer Pass 2 同式);shader 置空规避
                // 硬件加速下 shadow+shader 同置导致发光丢失。
                if (betterLyrics && glowEnabled && run.longSyllable) {
                    paint.shader = null
                    paint.color = sungColor
                    paint.alpha = sungAlpha
                    paint.setShadowLayer(
                        textSize * LyricGlowRenderer.HALO_RADIUS_FRACTION,
                        0f,
                        0f,
                        glowColor
                    )
                    canvas.drawText(run.text, run.x, baseline + y, paint)
                    paint.clearShadowLayer()
                }
                // 词内扫光:已扫部分 sung 色 + 光带拖尾(与 LyricGlowRenderer Pass 3 同形状)。
                paint.color = sungColor
                paint.alpha = sungAlpha
                applySweepShader(paint, sungColor, run.x, played, run.width)
                canvas.drawText(run.text, run.x, baseline + y, paint)
                paint.shader = null
            }
            canvas.restoreToCount(save)
        }
    }

    /** 词内扫光渐变(与 [LyricGlowRenderer] Pass 3 同形状):sung→中亮→透明,CLAMP。 */
    private fun applySweepShader(
        paint: Paint,
        color: Int,
        origin: Float,
        progress: Float,
        extent: Float
    ) {
        val safeExtent = extent.coerceAtLeast(0f)
        val band = (safeExtent * SWEEP_BAND_FRACTION).coerceAtLeast(1f)
        val start = origin - band + (safeExtent + band) * progress.coerceIn(0f, 1f)
        val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        val middle = Color.argb(184, Color.red(color), Color.green(color), Color.blue(color))
        paint.shader = LinearGradient(
            start, 0f, start + band, 0f,
            intArrayOf(color, middle, transparent),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }
}
