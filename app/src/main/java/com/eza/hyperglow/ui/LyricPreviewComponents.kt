package com.eza.hyperglow.ui

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.root.aod.splitContinuousFill
import com.eza.hyperglow.root.projection.LyricLayoutGroup
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricWord
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 主页歌词预览卡片及其子组件(演示快照/实时快照/分行扫光渲染)。 */

@Composable
internal fun collectDemoSnapshot(scenario: String): LyricSnapshot {
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DEMO_LINE_SWITCH_MS)
            index = (index + 1) % DEMO_LINES.size
        }
    }
    val line = DEMO_LINES[index]
    return LyricSnapshot(
        revision = index.toLong(),
        trackGeneration = 1,
        updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
        visible = true,
        original = line.original,
        romanized = line.romanized,
        translated = line.translated,
        metadata = "蝴蝶 · 洛天依",
        lineLevelSync = true,
        lineStartMs = 0,
        lineEndMs = DEMO_LINE_SWITCH_MS,
        durationMs = DEMO_LINES.size * DEMO_LINE_SWITCH_MS,
        positionMs = ((index * DEMO_LINE_SWITCH_MS).toFloat()).toLong(),
        sampledAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
        words = emptyList(),
        ruby = if (scenario == "Long/ruby/translated") {
            listOf(LyricRuby(0, 3, "kore wa"))
        } else {
            emptyList()
        }
    )
}

private class DemoLine(val original: String, val romanized: String, val translated: String)

private val DEMO_LINES = listOf(
    DemoLine(
        "你说你来到这世界的那天 神给了每个人快乐入场券",
        "nǐ shuō nǐ lái dào zhè shìjiè de nà tiān",
        "You said the day you came to this world, heaven gave everyone a ticket to joy"
    ),
    DemoLine(
        "那一只蝴蝶 拼了命破茧 却没有漂亮的鳞片",
        "nà yī zhī húdié pīn le mìng pò jiǎn",
        "That butterfly bursts its cocoon with all its might, yet bears no pretty scales"
    ),
    DemoLine(
        "走吧 就算我们无法让大雨停下",
        "zǒu ba jiùsuàn wǒmen wúfǎ ràng dàyǔ tíng xià",
        "Let's go, even if we can't make the heavy rain stop"
    ),
    DemoLine(
        "你我生来时就注定 天真而伟大",
        "nǐ wǒ shēnglái shí jiù zhùdìng tiānzhēn ér wěidà",
        "You and I are destined from birth to be innocent and great"
    )
)

/** How long each demo line stays on screen before cycling to the next. */
internal const val DEMO_LINE_SWITCH_MS = 2_500L

/**
 * 实时歌词快照:从进程内 arbiter(可靠地在 app 进程内填充,与 LiveStatusSection 同源)读取
 * 当前歌词源上报的 [LyricProducerState],映射成预览所需的 [LyricSnapshot]。无实时数据时返回
 * null,由调用方回退到静态示例快照。
 *
 * 注意:不从这里读 SystemUiLyricProjection —— 那是 SystemUI 侧投影,app 进程内并不保证
 * 被喂入实时快照,会导致预览不更新。
 */
@Composable
internal fun collectLiveSnapshot(): LyricSnapshot? {
    val active by collectActiveState()
    return active?.toPreviewSnapshot()
}

internal fun LyricProducerState.toPreviewSnapshot(): LyricSnapshot = LyricSnapshot(
    revision = sequence,
    trackGeneration = generation.toLong(),
    updatedAtElapsedMs = sampledAtElapsedMs,
    visible = true,
    original = line,
    romanized = romanizedLine,
    translated = translatedLine,
    nextLine = nextLine,
    metadata = listOfNotNull(
        title.takeIf { it.isNotBlank() },
        artist.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { "HyperGlow" },
    alignedRight = alignedRight,
    lineLevelSync = words == null,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs,
    durationMs = durationMs,
    positionMs = positionMs,
    sampledAtElapsedMs = sampledAtElapsedMs,
    speed = speed,
    words = (words ?: emptyList()).map {
        LyricWord(
            text = it.text,
            romanized = it.romanized,
            startMs = it.startMs,
            endMs = it.endMs,
            boundaryAfter = it.boundaryAfter,
            sourceStart = it.sourceStart,
            sourceEnd = it.sourceEnd
        )
    },
    ruby = ruby.map { LyricRuby(it.start, it.end, it.reading) },
    layoutGroups = layoutGroups.map {
        LyricLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
    }
)

/**
 * Home lyric-widget preview card. Renders a phone-like dark surface sized to the card and draws a
 * stylized lyric block using the compiled [profile] (text size/weight/alignment, secondary text,
 * metadata, card background, next line), so the home page gives a quick visual sense of how the
 * lockscreen / AOD lyric control looks.
 */
@Composable
internal fun LyricPreviewCard(
    title: String,
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    live: LyricSnapshot?,
    modifier: Modifier
) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ComposeColor(0xFF0B0B0F))
            ) {
                LyricPreviewSurface(
                    profile = profile,
                    scenario = scenario,
                    live = live
                )
            }
        }
    }
}

@Composable
private fun LyricPreviewSurface(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    live: LyricSnapshot?
) {
    // 预览卡片空间有限,直接在卡片内水平居中、垂直居中渲染歌词块,忽略真实曲面上的
    // 时钟/通知等占位偏移——否则息屏(AOD)歌词会按真实布局被挤到卡片顶部一小条,
    // 大字号下一行就被裁掉,看起来像被遮挡。这样无论字号多大都完整可见。
    // 有实时歌词时跟随最新快照;否则用循环播放的演示快照,让预览始终可见且持续更新。
    val snapshot = live ?: collectDemoSnapshot(scenario)
    val lyricColor = if (profile.palette.values.any { it == "dimmed" }) {
        ComposeColor(0xFF9AA0A6)
    } else {
        ComposeColor(0xFFFFFFFF)
    }
    val secondaryColor = lyricColor.copy(alpha = 0.72f)
    val metadataColor = lyricColor.copy(alpha = 0.6f)
    val textSize = previewTextSizeSp(profile)
    val weight = previewFontWeight(profile)
    val textAlign = previewTextAlign(profile)
    val showMetadata = profile.metadataVisible
    val showNext = profile.showNextLine
    val secondaryLines = previewSecondaryLines(profile, snapshot)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 锁屏卡片背景(息屏强制无卡片背景),按宽度占比居中显示。
        if (profile.backgroundStyle == "card") {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(previewCardColor(profile))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(profile.widthFraction)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = when (textAlign) {
                TextAlign.Start -> Alignment.Start
                TextAlign.End -> Alignment.End
                else -> Alignment.CenterHorizontally
            },
            verticalArrangement = Arrangement.Center
        ) {
            if (showMetadata && profile.metadataAnchor == "top") {
                PreviewMetaLine(snapshot.metadata, metadataColor)
            }
            PreviewAnimatedLyric(
                text = snapshot.original,
                textSize = textSize,
                weight = weight,
                color = lyricColor,
                glowColor = lyricColor,
                glowEnabled = profile.glow == "On",
                textAlign = textAlign,
                maxLines = if (profile.lyricLineLimit > 0) profile.lyricLineLimit else Int.MAX_VALUE,
                overflow = if (profile.overflow == "Clip") TextOverflow.Clip else TextOverflow.Ellipsis,
                // live 快照带真实行级时间戳时,预览按真实播放进度驱动扫光(与实机一致)
                timing = live?.let {
                    PreviewLineTiming(
                        lineStartMs = it.lineStartMs,
                        lineEndMs = it.lineEndMs,
                        positionMs = it.positionMs,
                        sampledAtElapsedMs = it.sampledAtElapsedMs,
                        speed = it.speed
                    )
                }
            )
            secondaryLines.forEach { line ->
                Text(
                    line,
                    fontSize = textSize * 0.72f,
                    fontWeight = FontWeight.Normal,
                    color = secondaryColor,
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showNext && snapshot.nextLine.isNotBlank()) {
                Text(
                    snapshot.nextLine,
                    fontSize = textSize * 0.72f,
                    fontWeight = FontWeight.Normal,
                    color = lyricColor.copy(alpha = 0.45f),
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showMetadata && profile.metadataAnchor == "bottom") {
                PreviewMetaLine(snapshot.metadata, metadataColor)
            }
        }
    }
}

@Composable
private fun PreviewMetaLine(text: String, color: ComposeColor) {
    Text(
        text,
        fontSize = 10.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 主页预览的歌词主体渲染:在原生 Canvas 上用 StaticLayout 绘制主歌词(支持多行换行,
 * 扫光按视觉行依次推进,复用 AodLyricCanvasView.splitContinuousFill 的按行宽加权分摊,
 * 与实机多行行为一致),按播放进度模拟息屏外观的三种效果 —— 文字发光(glow)、
 * 整行进度扫光(line progress sweep)。进度驱动两种模式:
 * - live(timing 非空):按真实词级/行级时间戳投影推进(与实机 projectedPosition 一致)
 * - demo(timing 为空):Animatable 线性 0→1 循环
 */
@Composable
private fun PreviewAnimatedLyric(
    text: String,
    textSize: TextUnit,
    weight: FontWeight,
    color: ComposeColor,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
    textAlign: TextAlign,
    maxLines: Int,
    overflow: TextOverflow,
    timing: PreviewLineTiming? = null
) {
    val density = LocalDensity.current
    val glowArgb = glowColor.toArgb()
    val dimArgb = color.copy(alpha = 0.30f).toArgb()
    val sungArgb = color.copy(alpha = 1f).toArgb()
    val nativeTypeface = when (weight) {
        FontWeight.Normal -> Typeface.create("sans-serif", Typeface.NORMAL)
        FontWeight.Bold -> Typeface.create("sans-serif", Typeface.BOLD)
        else -> Typeface.create("sans-serif", Typeface.BOLD)
    }
    val align = when (textAlign) {
        TextAlign.Center -> android.text.Layout.Alignment.ALIGN_CENTER
        TextAlign.End -> android.text.Layout.Alignment.ALIGN_OPPOSITE
        else -> android.text.Layout.Alignment.ALIGN_NORMAL
    }
    val truncate = if (overflow == TextOverflow.Clip) null else TextUtils.TruncateAt.END
    val maxLinesSafe = maxLines.coerceAtLeast(1)

    // 进度驱动:live 用真实时间戳投影(每帧重算),demo 用 Animatable 线性扫。
    val progressValue = if (timing != null) {
        var p by remember(timing) { mutableStateOf(previewProjectedProgress(timing)) }
        LaunchedEffect(timing) {
            while (true) {
                withFrameNanos { }
                p = previewProjectedProgress(timing)
            }
        }
        p
    } else {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(text) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DEMO_LINE_SWITCH_MS.toInt(), easing = LinearEasing))
        }
        progress.value
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val fontSizePx = with(density) { textSize.toPx() }
        val layoutHeight = remember(text, textSize, maxLines, widthPx) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = fontSizePx
                typeface = nativeTypeface
            }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(align)
                .setMaxLines(maxLinesSafe)
                .setEllipsize(truncate)
                .build()
            with(density) { layout.height.toDp() }
        }
        Canvas(Modifier.fillMaxWidth().height(layoutHeight)) {
            val p = progressValue
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = fontSizePx
                typeface = nativeTypeface
            }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, size.width.toInt().coerceAtLeast(1))
                .setAlignment(align)
                .setMaxLines(maxLinesSafe)
                .setEllipsize(truncate)
                .build()
            val top = (size.height - layout.height) / 2f
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                nc.translate(0f, top)

                // 多行分行几何:每个视觉行的字符范围/左界/宽度/基线,用于逐行扫光。
                class PreviewRow(
                    val start: Int,
                    val end: Int,
                    val left: Float,
                    val width: Float,
                    val baseline: Float
                )
                val rows = (0 until layout.lineCount).map { i ->
                    PreviewRow(
                        layout.getLineStart(i),
                        layout.getLineEnd(i),
                        layout.getLineLeft(i),
                        layout.getLineWidth(i),
                        layout.getLineBaseline(i).toFloat()
                    )
                }
                // 与实机一致:整行总进度按行宽加权分摊到各视觉行,扫光逐行推进。
                val rowProgress = splitContinuousFill(p, rows.map { it.width })
                val fm = paint.fontMetrics
                val haloRadius = fontSizePx * 0.36f

                // Pass 1: 未唱(暗)底色 —— 整块绘制
                paint.shader = null
                paint.setShadowLayer(0f, 0f, 0f, 0)
                paint.color = dimArgb
                layout.draw(nc)

                // 逐行扫光:每行光带左侧(已唱区)CLAMP 常亮,光带为柔和前沿,
                // 右侧未唱区保持暗底。与 AodLyricCanvasView 的 drawPreviewStyleGlow 一致。
                if (glowEnabled) {
                    rows.forEachIndexed { index, row ->
                        val lp = rowProgress[index]
                        val band = (row.width * PREVIEW_SWEEP_BAND_FRACTION).coerceAtLeast(1f)
                        // Pass 2: 光晕层 —— clip 到该行已扫区域,sung 文字 + glow 色阴影
                        nc.save()
                        nc.clipRect(
                            row.left - band,
                            row.baseline + fm.ascent - haloRadius,
                            row.left + (row.width + band) * lp,
                            row.baseline + fm.descent + haloRadius
                        )
                        paint.shader = null
                        paint.color = sungArgb
                        paint.setShadowLayer(fontSizePx * 0.36f, 0f, 0f, glowArgb)
                        nc.drawText(text, row.start, row.end, row.left, row.baseline, paint)
                        paint.setShadowLayer(0f, 0f, 0f, 0)
                        nc.restore()
                    }
                }
                // Pass 3: 扫光亮部 —— 每行渐变 [sung -> middle -> transparent],
                // band 左侧 CLAMP 常亮,右侧渐隐
                val sweepTransparent = Color.argb(0, Color.red(sungArgb), Color.green(sungArgb), Color.blue(sungArgb))
                val sweepMiddle = Color.argb(184, Color.red(sungArgb), Color.green(sungArgb), Color.blue(sungArgb))
                rows.forEachIndexed { index, row ->
                    val lp = rowProgress[index]
                    val band = (row.width * PREVIEW_SWEEP_BAND_FRACTION).coerceAtLeast(1f)
                    val sweepStart = row.left - band + (row.width + band) * lp
                    val sweepEnd = sweepStart + band
                    paint.shader = LinearGradient(
                        sweepStart, 0f, sweepEnd, 0f,
                        intArrayOf(sungArgb, sweepMiddle, sweepTransparent),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    paint.color = sungArgb
                    nc.drawText(text, row.start, row.end, row.left, row.baseline, paint)
                    paint.shader = null
                }
                nc.restore()
            }
        }
    }
}

/** live 模式的时间参数,取自 LyricSnapshot 的行级时间戳与采样时刻。 */
internal class PreviewLineTiming(
    val lineStartMs: Long,
    val lineEndMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float
)

/** 与实机 projectedPosition/progress 一致:按采样时刻外推播放位置,再归一化到行区间。 */
internal fun previewProjectedProgress(timing: PreviewLineTiming): Float {
    val elapsed = (android.os.SystemClock.elapsedRealtime() - timing.sampledAtElapsedMs).coerceAtLeast(0L)
    val projected = timing.positionMs + (elapsed * timing.speed).toLong()
    val span = (timing.lineEndMs - timing.lineStartMs).coerceAtLeast(1L)
    return ((projected - timing.lineStartMs).toFloat() / span).coerceIn(0f, 1f)
}

// 预览整行扫光的光带宽度占比,与 AodLyricCanvasView 的 SWEEP_BAND_FRACTION 保持一致。
internal const val PREVIEW_SWEEP_BAND_FRACTION = 0.4f

internal fun previewFontWeight(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): FontWeight =
    when (profile.weight) {
        "Regular" -> FontWeight.Normal
        "Bold" -> FontWeight.Bold
        else -> FontWeight.Medium
    }

internal fun previewTextAlign(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): TextAlign =
    // "auto" 与应用渲染一致:在 alignedRight=false 时解析为左对齐(见 AodLyricCanvasView)。
    when (profile.alignment) {
        "start" -> TextAlign.Start
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }

internal fun previewTextSizeSp(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): TextUnit {
    val percent = when (profile.textSize) {
        "small" -> 90
        "large" -> 118
        "xlarge" -> 140
        "custom" -> profile.textSizeCustom.coerceIn(50, 200)
        else -> 100
    }
    return (20 * percent / 100).sp
}

internal fun previewSecondaryLines(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    snapshot: LyricSnapshot
): List<String> = when (profile.secondaryMode) {
    "Transliteration" -> listOfNotNull(snapshot.romanized.ifBlank { null })
    "Translation" -> listOfNotNull(snapshot.translated.ifBlank { null })
    "Both" -> listOfNotNull(
        snapshot.romanized.ifBlank { null },
        snapshot.translated.ifBlank { null }
    )
    else -> emptyList()
}

internal fun previewCardColor(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): ComposeColor {
    val base = when (profile.cardColor) {
        "white" -> ComposeColor(0xFFFFFFFF)
        "dark_gray" -> ComposeColor(0xFF2A2A2A)
        "accent" -> ComposeColor(0xFF3A6EA5)
        "blur" -> ComposeColor(0xFF1A1A1E)
        else -> ComposeColor(0xFF000000)
    }
    return base.copy(alpha = profile.cardAlpha.coerceIn(0, 100) / 100f)
}
