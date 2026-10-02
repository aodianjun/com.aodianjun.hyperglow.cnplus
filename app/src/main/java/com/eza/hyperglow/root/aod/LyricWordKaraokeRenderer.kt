package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.roundToLong

/**
 * 逐字卡拉OK的一个词位(行内绝对坐标):[playedFraction] 为该词已唱比例 0..1
 * (0=未唱、1=已唱完、之间=正在唱);[durationMs] 为词时长,驱动未唱下沉/已唱上浮的
 * 动画时长;[longSyllable] 标记长音节(「BetterLyrics」档放大/辉光只作用于长音节)。
 */
internal data class KaraokeWordRun(
    val text: String,
    val x: Float,
    val width: Float,
    val playedFraction: Float,
    val durationMs: Long,
    val longSyllable: Boolean
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

    /** 未唱底字不透明度因子(与实机 setTextAlpha(0.35f) 同源)。 */
    private const val UNSUNG_FACTOR = 0.35f

    /** 未唱字静态缩放(历史逐字卡拉OK档)。 */
    private const val UNSUNG_SCALE = 0.95f

    /**
     * 逐字卡拉OK绘制。[betterLyrics] 为「BetterLyrics」档:启用未唱下沉/已唱上浮与
     * 长音节放大/辉光;否则为历史逐字档(无浮动,短词放大 1.0505)。
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
        sinkPx: Float
    ) {
        val textSize = paint.textSize
        val dimAlpha = (255f * steadyTextAlpha(UNSUNG_FACTOR)).toInt().coerceIn(0, 255)
        var index = 0
        while (index < runs.size) {
            val run = runs[index]
            index++
            if (run.text.isEmpty()) continue
            val played = run.playedFraction.coerceIn(0f, 1f)
            val sung = played >= 1f
            val active = played > 0f && played < 1f
            val scale = when {
                active -> karaokeScaleAt(played, karaokeScalePeak(betterLyrics, run.longSyllable))
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
            paint.alpha = if (sung) 255 else dimAlpha
            canvas.drawText(run.text, run.x, baseline + y, paint)
            if (active) {
                // 长音节辉光(BetterLyrics 档 + 发光开启):glow 色阴影画在 sung 色文字下,
                // 光从文字背后透出(与共享 LyricGlowRenderer Pass 2 同式);shader 置空规避
                // 硬件加速下 shadow+shader 同置导致发光丢失。
                if (betterLyrics && glowEnabled && run.longSyllable) {
                    paint.shader = null
                    paint.color = sungColor
                    paint.alpha = 255
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
                paint.alpha = 255
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
