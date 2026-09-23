package com.eza.hyperglow.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.root.aod.LyricGlowRenderer
import com.eza.hyperglow.root.aod.LyricGlowRow
import com.eza.hyperglow.root.aod.resolveAodPalette
import com.eza.hyperglow.root.projection.LyricSnapshot
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮预览的标题栏:整行可点击切换展开/折叠。折叠后预览让位给设置列表,
 * 便于长列表快速调整;展开时调节下方选项效果实时可见。
 */
@Composable
internal fun AppearancePreviewHeader(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.title_appearance_preview),
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (expanded) "▾" else "▸",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/**
 * 外观编辑页顶部的常驻悬浮预览:与实机同一编译管线(调用方传入 compile 后的 profile,
 * 归一化/白名单与真机一致,所见即所得),实时歌词优先,无歌词时循环播放演示动画。
 */
@Composable
internal fun AppearanceLivePreview(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    modifier: Modifier = Modifier
) {
    val live = collectLiveSnapshot()
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ComposeColor(0xFF0B0B0F))
    ) {
        LyricPreviewSurface(profile = profile, scenario = scenario, live = live)
    }
}

/**
 * Home lyric-widget preview card. Renders a phone-like dark surface sized to the card and draws a
 * stylized lyric block using the compiled [profile] (text size/weight/alignment, secondary text,
 * metadata, card background, next line) placed via [resolvePreviewPlacement], so the home page
 * gives a quick visual sense of how the lockscreen / AOD lyric control looks.
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
    // 颜色与实机同源:统一走 resolveAodPalette(dimmed 预设/自定义字体颜色 hex token 一处解析)
    val resolvedColors = resolveAodPalette(profile.palette)
    val lyricColor = ComposeColor(resolvedColors.primaryText)
    val secondaryColor = ComposeColor(resolvedColors.secondaryText).copy(alpha = 0.72f)
    val metadataColor = ComposeColor(resolvedColors.metadataText).copy(alpha = 0.6f)
    val nextLineColor = ComposeColor(resolvedColors.nextLineText).copy(alpha = 0.45f)
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
                PreviewMetaLine(snapshot.metadata, metadataColor, profile.metadataSizePercent)
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
                overflow = if (profile.overflow == "Clip") TextOverflow.Clip else TextOverflow.Ellipsis
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
                    color = nextLineColor,
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showMetadata && profile.metadataAnchor == "bottom") {
                PreviewMetaLine(snapshot.metadata, metadataColor, profile.metadataSizePercent)
            }
        }
    }
}

@Composable
private fun PreviewMetaLine(text: String, color: ComposeColor, sizePercent: Int) {
    Text(
        text,
        fontSize = previewMetadataTextSizeSp(sizePercent),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 主页预览的歌曲信息字号:基准 10sp,按用户设置的 metadataSizePercent 缩放(50%~200%)。 */
internal fun previewMetadataTextSizeSp(sizePercent: Int): androidx.compose.ui.unit.TextUnit =
    (10 * sizePercent.coerceIn(50, 200) / 100).sp

/**
 * 主页预览的歌词主体渲染:在原生 Canvas 上用 StaticLayout 绘制主歌词,按播放进度模拟
 * 息屏外观的三种效果 —— 文字发光(glow)、整行进度扫光(line progress sweep)与逐字高亮
 * (当前演唱词的强调光斑)。这样预览与 AodLyricCanvasView 的息屏渲染保持一致。
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
    overflow: TextOverflow
) {
    val density = LocalDensity.current
    val glowArgb = glowColor.toArgb()
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

    // 逐条演示行播放时,进度从 0 扫到 1,驱动扫光与逐字高亮。
    val progress = remember { Animatable(0f) }
    LaunchedEffect(text) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(DEMO_LINE_SWITCH_MS.toInt(), easing = LinearEasing))
    }
    // 在组合作用域读取进度,保证每次动画变化都会重绘 Canvas。
    val progressValue = progress.value

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

                // 委托共享渲染核心 LyricGlowRenderer(实机 AOD/锁屏同源):
                // dim 底、光晕、扫光带(缓动/光带占比/渐变 stops)全部单点定义,
                // 预览即实机效果,杜绝两份手工同步的实现漂移。
                val rows = ArrayList<LyricGlowRow>(layout.lineCount)
                for (line in 0 until layout.lineCount) {
                    val start = layout.getLineStart(line)
                    val end = layout.getLineEnd(line)
                    val left = layout.getLineLeft(line)
                    val width = layout.getLineWidth(line)
                    val lineBaseline = layout.getLineBaseline(line).toFloat()
                    rows += LyricGlowRow(left, width, lineBaseline) { c, paintArg ->
                        if (end > start) c.drawText(text, start, end, left, lineBaseline, paintArg)
                    }
                }
                LyricGlowRenderer.draw(
                    canvas = nc,
                    paint = paint,
                    rows = rows,
                    progress = p,
                    sungColor = sungArgb,
                    glowColor = glowArgb,
                    glowEnabled = glowEnabled
                )
                nc.restore()
            }
        }
    }
}

private fun previewFontWeight(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): FontWeight =
    when (profile.weight) {
        "Regular" -> FontWeight.Normal
        "Bold" -> FontWeight.Bold
        else -> FontWeight.Medium
    }

private fun previewTextAlign(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): TextAlign =
    // "auto" 与应用渲染一致:在 alignedRight=false 时解析为左对齐(见 AodLyricCanvasView)。
    when (profile.alignment) {
        "start" -> TextAlign.Start
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }

private fun previewTextSizeSp(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): androidx.compose.ui.unit.TextUnit {
    val percent = when (profile.textSize) {
        "small" -> 90
        "large" -> 118
        "xlarge" -> 140
        "custom" -> profile.textSizeCustom.coerceIn(50, 200)
        else -> 100
    }
    return (20 * percent / 100).sp
}

private fun previewSecondaryLines(
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

private fun previewCardColor(profile: com.eza.hyperglow.customization.CompiledSurfaceProfile): ComposeColor {
    val base = when (profile.cardColor) {
        "white" -> ComposeColor(0xFFFFFFFF)
        "dark_gray" -> ComposeColor(0xFF2A2A2A)
        "accent" -> ComposeColor(0xFF3A6EA5)
        "blur" -> ComposeColor(0xFF1A1A1E)
        else -> ComposeColor(0xFF000000)
    }
    return base.copy(alpha = profile.cardAlpha.coerceIn(0, 100) / 100f)
}
