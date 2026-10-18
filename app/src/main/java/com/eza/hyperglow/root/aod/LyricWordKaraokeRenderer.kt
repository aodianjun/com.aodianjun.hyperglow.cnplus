package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import com.eza.hyperglow.customization.WordEffectSpec
import com.eza.hyperglow.customization.evaluateWordEffect
import com.eza.hyperglow.effect.EffectFrameContext
import com.eza.hyperglow.effect.EffectVm
import com.eza.hyperglow.root.HookLogger
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 逐字卡拉OK的一个词位(行内绝对坐标):[playedFraction] 为该词已唱比例 0..1
 * (0=未唱、1=已唱完、之间=正在唱),驱动底字亮度/扫光(BetterLyrics 档长音节不扫光、整块
 * 亮起)/未唱下沉/已唱上浮;
 * [durationMs] 为词时长,驱动上浮动画的加速段;[longSyllable] 标记长音节
 * (「BetterLyrics」档放大/辉光只作用于长音节)。
 *
 * [highlightFraction] 为「高亮进度」:放大与辉光由它驱动,让同一词块内的字符共享
 * 一个进度(整块同步放大/发光而非单字符一闪,合成源的关键可见性)。负数=未提供,
 * 退回 [playedFraction](逐字源:字符自身进度)。
 *
 * [startMs]/[endMs] 为该词块的绝对时间窗(逐字源=真实词窗;合成源=合成字符时间窗),
 * 供沙箱效果程序([com.eza.hyperglow.effect.EffectWordSample])读取;默认 0 保持旧构造
 * 兼容,内置三档与 DSL 路径不消费这两个字段。
 */
internal data class KaraokeWordRun(
    val text: String,
    val x: Float,
    val width: Float,
    val playedFraction: Float,
    val durationMs: Long,
    val longSyllable: Boolean,
    val highlightFraction: Float = -1f,
    val startMs: Long = 0L,
    val endMs: Long = 0L
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
 * 词级时间源判据:时间窗有效(endMs>startMs)才构成词级时间源。
 *
 * 布局分组合成的占位词(无词表行 + layoutGroups 时由 `layoutTextByGroups` 生成,
 * 时间窗恒 0..0)必须排除在词级卡拉OK之外——`timedWordProgress(pos, 0, 0)` 对零窗
 * 恒返回 1(全亮),且它们会让词位列表非空、跳过行级合成,整行呈静态全亮
 * (与 Minimal 档同观感)。排除后整行自然落到行级合成逐字路径。纯函数,可单测。
 */
internal fun isTimedKaraokeWord(startMs: Long, endMs: Long): Boolean = endMs > startMs

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
    karaokeUnsungAlpha(alphaFactor, KARAOKE_UNSUNG_FACTOR)

/**
 * 未唱底字不透明度(可指定暗度因子):自定义效果用 [WordEffectSpec.unsungDim] 覆盖
 * 历史 0.35,其余口径与 [karaokeUnsungAlpha] 完全一致。纯函数,可单测。
 */
internal fun karaokeUnsungAlpha(alphaFactor: Float, dimFactor: Float): Int =
    (255f * steadyTextAlpha(dimFactor) * alphaFactor.coerceIn(0f, 1f))
        .roundToInt()
        .coerceIn(0, 255)

/**
 * 单次播放的放大峰值:BetterLyrics 档长音节放大到 [KARAOKE_LONG_SYLLABLE_SCALE_PEAK],
 * 其余沿用 [KARAOKE_BASE_SCALE_PEAK]。纯函数,可单测。
 */
internal fun karaokeScalePeak(betterLyrics: Boolean, longSyllable: Boolean): Float =
    if (betterLyrics && longSyllable) KARAOKE_LONG_SYLLABLE_SCALE_PEAK else KARAOKE_BASE_SCALE_PEAK

/**
 * 词内扫光是否启用(口径 B):「BetterLyrics」档**只对长音节关扫光**——长音节(≥700ms)
 * 词块「开始唱即整块按已唱色亮起」,不出现填充前缘;其余音节恢复历史词内扫光带。辉光仍只
 * 挂长音节词块(见 [draw])。非 BetterLyrics 档(基础卡拉OK路径)长/短音节全部保持词内
 * 扫光,历史观感不变。纯函数,可单测。
 *
 * 口径沿革(owner 真机复核后定案):只关长音节(2026-10-04)→ 关「含长音节的整行」
 * (2026-10-04 晚)——两版都留下「行内没有 ≥700ms 音节时整行照旧逐字填」的残留,真机实测
 * (网易云《蝴蝶》,逐行探针每个词首窗仅 200ms 级)整首歌都在扫,观感与改前无差别,故
 * 0.3.156 (183) 整档关闭(长音节整块亮起规则一并删去);本次恢复短音节扫光——当初逼出
 * 整档关闭的跳变成因已由同行形态稳定化 / 下一行文本稳定化 / 位置时钟过渡 / 摄取归一修掉。
 */
internal fun karaokeSweepEnabled(betterLyrics: Boolean, longSyllable: Boolean): Boolean =
    !(betterLyrics && longSyllable)

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

/**
 * 逐字单元的下一码点边界:从 [index] 起返回下一个**码点**边界,并收敛到 [endExclusive]
 * (不得越出当前词块/行切片边界)。非 BMP 字符(emoji 等,如占位符 🎶)在 UTF-16 里是
 * 代理对,按单码元推进会把代理对劈成两个孤立代理项——Android 文本绘制把孤立代理项画成
 * 未知符号方框(真机「歌词中间出现未知符号」的根因)。这里按 [Character.codePointAt] +
 * [Character.charCount] 整对前进,任何歌词里的 emoji 都不会再被劈开。纯函数,可单测。
 */
internal fun karaokeUnitEnd(text: String, index: Int, endExclusive: Int): Int {
    if (index >= endExclusive) return endExclusive
    val codePoint = Character.codePointAt(text, index)
    return (index + Character.charCount(codePoint)).coerceAtMost(endExclusive)
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
 * 回落,发光开启时带 glow 色辉光。扫光按口径 B 逐词块判定([karaokeSweepEnabled]):
 * 长音节「开始唱即整块亮起」不扫光,其余音节恢复历史词内扫光带;短音节保留历史放大
 * 运动(峰值 1.0505)。
 *
 * 行级源(无逐字时间戳)由调用方用 [syntheticCharTimeWindow] 合成每字符的时间窗后
 * 走同一渲染:下沉/上浮与点亮进度同样适用;合成块按块时长判定长音节(中文逐字块、
 * 西文按词块,≥700ms 才算)——只有长块放大/辉光,长块整块亮起、短块词内扫光。
 */
internal object LyricWordKaraokeRenderer {
    /** 词内扫光带占词宽比例(与 [LyricGlowRenderer] 同式;BetterLyrics 档长音节整块亮起,不适用)。 */
    const val SWEEP_BAND_FRACTION = 0.28f

    /** 未唱字静态缩放(历史逐字卡拉OK档)。 */
    private const val UNSUNG_SCALE = 0.95f

    /**
     * 逐字卡拉OK绘制。[betterLyrics] 为「BetterLyrics」档:启用未唱下沉/已唱上浮与
     * 长音节放大/辉光,扫光按口径 B 逐词块判定(长音节整块亮起、其余音节词内扫光);
     * 否则为历史逐字档(无浮动,短词放大 1.0505,全部扫光)。
     *
     * [alphaFactor] 为该行整体亮度档(默认 1=主行语义):已唱/未唱/辉光/扫光四路不透明度
     * 同乘它,供辅助文字逐字效果沿用「高亮辅助文字」设置而不另建一套取色。
     *
     * [effectContext] 非 null 且 [effect] 带沙箱程序([WordEffectSpec.program])时,由
     * [EffectVm] 执行程序并直接经 [AndroidEffectRenderSink] 绘制整行,随后返回——不再走
     * 内置三档或 DSL 路径(避免双重绘制);程序超预算中止时记一条告警日志。program 为
     * null 时行为与旧版完全一致。
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
        alphaFactor: Float = 1f,
        /** 自定义单词效果(声明式 DSL);非 null 时走共享解释器,覆盖内置三档分支。 */
        effect: WordEffectSpec? = null,
        /** 沙箱效果程序的整行上下文;与 [effect] 的 program 同时非 null 时程序优先。 */
        effectContext: EffectFrameContext? = null
    ) {
        val program = effect?.program
        if (program != null && effectContext != null) {
            val sink = AndroidEffectRenderSink(canvas, paint, effectContext.colors)
            val rendered = runCatching {
                EffectVm(program).render(effectContext, sink)
            }.getOrElse { false }
            // 程序可能留下未配对的 save;先弹回宿主深度,再决定是否记录中止。
            runCatching { sink.restoreAll() }
            if (!rendered) {
                HookLogger.iThrottled("effect-program", 2_000L, "LyricWordKaraokeRenderer") {
                    "effect program aborted (${program.code.size} instructions)"
                }
            }
            return
        }
        if (effect != null) {
            drawWithEffect(
                canvas = canvas,
                paint = paint,
                runs = runs,
                baseline = baseline,
                sungColor = sungColor,
                unsungColor = unsungColor,
                glowColor = glowColor,
                glowEnabled = glowEnabled,
                alphaFactor = alphaFactor,
                effect = effect
            )
            return
        }
        val textSize = paint.textSize
        val sungAlpha = karaokeSungAlpha(alphaFactor)
        val dimAlpha = karaokeUnsungAlpha(alphaFactor)
        var index = 0
        while (index < runs.size) {
            val run = runs[index]
            index++
            if (run.text.isEmpty()) continue
            // 扫光逐词块判定(口径 B):「BetterLyrics」档只对长音节关扫光,其余音节照常填充。
            val sweepEnabled = karaokeSweepEnabled(betterLyrics, run.longSyllable)
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
                if (!sweepEnabled) {
                    // 「BetterLyrics」档长音节:整块按已唱色一次亮起、不做逐字填充(见
                    // [karaokeSweepEnabled])。辉光仍只挂长音节词块——glow 色阴影画在 sung 色
                    // 文字下,光从文字背后透出(与共享 LyricGlowRenderer Pass 2 同式);shader
                    // 置空规避硬件加速下 shadow+shader 同置导致发光丢失。
                    paint.shader = null
                    paint.color = sungColor
                    paint.alpha = sungAlpha
                    if (glowEnabled && run.longSyllable) {
                        paint.setShadowLayer(
                            textSize * LyricGlowRenderer.HALO_RADIUS_FRACTION,
                            0f,
                            0f,
                            glowColor
                        )
                    }
                    canvas.drawText(run.text, run.x, baseline + y, paint)
                    paint.clearShadowLayer()
                } else {
                    // 词内扫光:已扫部分 sung 色 + 光带拖尾(与 LyricGlowRenderer Pass 3 同形状)。
                    paint.color = sungColor
                    paint.alpha = sungAlpha
                    applySweepShader(paint, sungColor, run.x, played, run.width)
                    canvas.drawText(run.text, run.x, baseline + y, paint)
                    paint.shader = null
                }
            }
            canvas.restoreToCount(save)
        }
    }

    /**
     * 自定义效果绘制路径：把声明式 [WordEffectSpec] 逐词求值成位移/缩放/旋转/透明度/
     * 颜色/辉光/扫光，再复用同一套 Paint 与扫光缓存。内置三档不进入本函数，观感逐值不变。
     *
     * 绘制顺序：辉光（阴影）→ 底字 → 扫光前缘。辉光单独一遍是为了不与扫光 shader 抢
     * Paint（旧路径里两者同置会丢辉光）；每个词块一次 save/restore，变换以词块中心为枢轴。
     */
    private fun drawWithEffect(
        canvas: Canvas,
        paint: Paint,
        runs: List<KaraokeWordRun>,
        baseline: Float,
        sungColor: Int,
        unsungColor: Int,
        glowColor: Int,
        glowEnabled: Boolean,
        alphaFactor: Float,
        effect: WordEffectSpec
    ) {
        val textSize = paint.textSize
        val sungAlpha = karaokeSungAlpha(alphaFactor)
        val dimAlpha = karaokeUnsungAlpha(alphaFactor, effect.unsungDim)
        val wordCount = runs.count { it.text.isNotEmpty() }.coerceAtLeast(1)
        var index = 0
        var runIndex = 0
        while (index < runs.size) {
            val run = runs[index]
            index++
            if (run.text.isEmpty()) continue
            val staggerIndex = runIndex++
            val played = run.playedFraction.coerceIn(0f, 1f)
            val sung = played >= 1f
            // 合成源(run.durationMs)是单字符窗,块级长音节标记在 run.longSyllable 上:
            // 两者取或,否则 LRC/行级源的中文长块永远命不中 longOnly / glow=long。
            val longSyllable = run.longSyllable || run.durationMs >= effect.longSyllableMs
            val frame = evaluateWordEffect(effect, played, staggerIndex, longSyllable, wordCount)
            val alpha = ((if (sung) sungAlpha else dimAlpha) * frame.alpha)
                .roundToInt().coerceIn(0, 255)
            // 扫光/辉光是「已唱」语义:用满亮度,不跟底字的未唱暗度走(内置路径同式)。
            val sweepAlpha = (sungAlpha * frame.alpha).roundToInt().coerceIn(0, 255)
            // 底字用未唱色,扫光/辉光用已唱色(与内置路径同语义);颜色轨道覆盖两者。
            val baseColor = frame.colorArgb ?: if (sung) sungColor else unsungColor
            val sweepColor = frame.colorArgb ?: sungColor
            val pivotX = run.x + run.width / 2f
            val save = canvas.save()
            if (frame.translateXEm != 0f || frame.translateYEm != 0f) {
                canvas.translate(frame.translateXEm * textSize, frame.translateYEm * textSize)
            }
            if (frame.rotateDeg != 0f) canvas.rotate(frame.rotateDeg, pivotX, baseline)
            if (frame.scale != 1f) canvas.scale(frame.scale, frame.scale, pivotX, baseline)
            if (frame.skewX != 0f) {
                // skew 以画布原点为枢轴,直接调用会把整词按基线高度横向甩出去;
                // 先平移到词中心、skew、再平移回来。
                canvas.translate(pivotX, baseline)
                canvas.skew(frame.skewX, 0f)
                canvas.translate(-pivotX, -baseline)
            }

            val glowApplies = glowEnabled && effect.glow != "none" &&
                (effect.glow == "all" || longSyllable) && frame.glowAlpha > 0f
            if (glowApplies) {
                val radius = (frame.glowRadiusEm ?: LyricGlowRenderer.HALO_RADIUS_FRACTION) *
                    textSize
                paint.shader = null
                paint.color = sweepColor
                paint.alpha = (sweepAlpha * frame.glowAlpha).roundToInt().coerceIn(0, 255)
                paint.setShadowLayer(radius, 0f, 0f, glowColor)
                canvas.drawText(run.text, run.x, baseline, paint)
                paint.clearShadowLayer()
            }

            // 底字：已唱/未唱同色（效果可覆盖颜色），透明度由行亮度 × 效果 alpha 决定。
            paint.shader = null
            paint.clearShadowLayer()
            paint.color = baseColor
            paint.alpha = alpha
            canvas.drawText(run.text, run.x, baseline, paint)

            // 扫光前缘：默认跟随词块进度；效果给了 sweepProgress 轨道则按轨道推进。
            val sweepProgress = if (frame.sweepProgress >= 0f) {
                frame.sweepProgress
            } else {
                played
            }
            val sweepActive = effect.sweep && (
                frame.sweepProgress >= 0f || (played > 0f && played < 1f)
                )
            if (sweepActive) {
                paint.color = sweepColor
                paint.alpha = sweepAlpha
                applyEffectSweepShader(
                    paint = paint,
                    color = sweepColor,
                    origin = run.x,
                    progress = sweepProgress,
                    extent = run.width,
                    bandFraction = effect.sweepBand
                )
                canvas.drawText(run.text, run.x, baseline, paint)
                paint.shader = null
                paint.colorFilter = null
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
        extent: Float,
        bandFraction: Float = SWEEP_BAND_FRACTION
    ) {
        val safeExtent = extent.coerceAtLeast(0f)
        val band = (safeExtent * bandFraction.coerceIn(0f, 1f)).coerceAtLeast(1f)
        val start = origin - band + (safeExtent + band) * progress.coerceIn(0f, 1f)
        val shader = sweepShader(color)
        // local t∈[0,1] → device [start, start+band](等价旧式按 start/start+band 逐词新建)。
        sweepMatrix.setScale(band, 1f)
        sweepMatrix.postTranslate(start, 0f)
        shader.setLocalMatrix(sweepMatrix)
        paint.shader = shader
    }

    /**
     * 自定义效果的词内扫光：颜色轨道每帧都会产生新色值，若按颜色建渐变缓存会每词每帧
     * 新建 native LinearGradient 并冲掉内置档的稳定缓存。这里固定用白色渐变 + 颜色滤镜
     * 着色（SRC_IN 保留渐变 alpha、替换 RGB），缓存只保留一个实例；颜色 alpha 也被
     * 滤镜强制为不透明，避免 #AARRGGBB 让光带变淡。
     */
    private fun applyEffectSweepShader(
        paint: Paint,
        color: Int,
        origin: Float,
        progress: Float,
        extent: Float,
        bandFraction: Float
    ) {
        val safeExtent = extent.coerceAtLeast(0f)
        val band = (safeExtent * bandFraction.coerceIn(0f, 1f)).coerceAtLeast(1f)
        val start = origin - band + (safeExtent + band) * progress.coerceIn(0f, 1f)
        val shader = sweepShader(Color.WHITE)
        sweepMatrix.setScale(band, 1f)
        sweepMatrix.postTranslate(start, 0f)
        shader.setLocalMatrix(sweepMatrix)
        paint.shader = shader
        paint.colorFilter = PorterDuffColorFilter(
            color or 0xFF000000.toInt(),
            PorterDuff.Mode.SRC_IN
        )
    }

    /**
     * 词内扫光渐变缓存:键=全部构造输入(sung 色;stops 常量、颜色数组由色值导出),
     * 逐词几何经 [sweepMatrix] 的 localMatrix 表达 —— 逐字档每帧 O(字数) 次新建
     * LinearGradient 降为零分配。Android 16 语义已核实:setLocalMatrix 丢弃 native
     * 实例、下次使用重建(Paint.getNativeInstance 重取),已录制的绘制指令持有旧
     * native shader 不受影响。仅绘制线程(主线程)访问。
     */
    private val sweepShaderCache = HashMap<Int, LinearGradient>(SWEEP_SHADER_CACHE_CAPACITY)

    /** 逐帧复用的 localMatrix:M = T(start) · S(band)。 */
    private val sweepMatrix = Matrix()

    private fun sweepShader(color: Int): LinearGradient {
        sweepShaderCache[color]?.let { return it }
        if (sweepShaderCache.size >= SWEEP_SHADER_CACHE_CAPACITY) sweepShaderCache.clear()
        val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        val middle = Color.argb(184, Color.red(color), Color.green(color), Color.blue(color))
        val shader = LinearGradient(
            0f, 0f, 1f, 0f,
            intArrayOf(color, middle, transparent),
            SWEEP_STOPS,
            Shader.TileMode.CLAMP
        )
        sweepShaderCache[color] = shader
        return shader
    }

    /** 测试钩子:验证词内扫光渐变缓存键等价性(同色命中同一实例、异色 miss、容量有界)。 */
    internal fun sweepShaderForTest(color: Int): LinearGradient = sweepShader(color)

    /** 3 段渐变 stop 位置:sung→中亮(0.45)→透明。 */
    private val SWEEP_STOPS = floatArrayOf(0f, 0.45f, 1f)

    /** 扫光渐变缓存容量:键只有 sung 色,正常 ≤2-3 个;超限整表清空保持有界。 */
    private const val SWEEP_SHADER_CACHE_CAPACITY = 8
}
