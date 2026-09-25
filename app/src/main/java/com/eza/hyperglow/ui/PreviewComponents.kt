package com.eza.hyperglow.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.root.aod.LyricGlowRenderer
import com.eza.hyperglow.root.aod.LyricGlowRow
import com.eza.hyperglow.root.aod.LyricLayoutLine
import com.eza.hyperglow.root.aod.LyricLayoutResult
import com.eza.hyperglow.root.aod.LyricTypefaceResolver
import com.eza.hyperglow.root.aod.LYRIC_LINE_EXTRA_HEIGHT_DP
import com.eza.hyperglow.root.aod.LYRIC_LINE_GAP_DP
import com.eza.hyperglow.root.aod.LYRIC_WORD_GAP_DP
import com.eza.hyperglow.root.aod.METADATA_LYRIC_GAP_DP
import com.eza.hyperglow.root.aod.ROW_GAP_BEFORE_NEXT_LINE_DP
import com.eza.hyperglow.root.aod.ROW_GAP_BEFORE_ORIGINAL_DP
import com.eza.hyperglow.root.aod.ROW_GAP_BEFORE_SECONDARY_DP
import com.eza.hyperglow.root.aod.baseTextSizeSp
import com.eza.hyperglow.root.aod.layoutMetadataLines
import com.eza.hyperglow.root.aod.layoutOriginalLines
import com.eza.hyperglow.root.aod.layoutSecondaryLines
import com.eza.hyperglow.root.aod.lineStartX
import com.eza.hyperglow.root.aod.metadataTextSizeSp
import com.eza.hyperglow.root.aod.nextLineTextSizeSp
import com.eza.hyperglow.root.aod.originalRowHeight
import com.eza.hyperglow.root.aod.metadataTextSizeSp
import com.eza.hyperglow.root.aod.nextLineTextSizeSp
import com.eza.hyperglow.root.aod.resolveAodPalette
import com.eza.hyperglow.root.aod.secondaryReadingTextSizeSp
import com.eza.hyperglow.root.aod.secondaryTranslationTextSizeSp
import com.eza.hyperglow.root.aod.staticNextLineTextFactor
import com.eza.hyperglow.root.aod.staticSecondaryTextFactor
import com.eza.hyperglow.root.aod.steadyTextAlpha
import com.eza.hyperglow.root.aod.textSizeModeMultiplier
import com.eza.hyperglow.root.lockscreen.cardColorRgb
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
    // 主行取色与实机 drawOriginalGlowBlock 调用一致:已唱/底色走 sungText,光晕走 glow token。
    val lyricColor = ComposeColor(resolvedColors.sungText)
    val glowColor = ComposeColor(resolvedColors.glow)
    // 透明度与实机 drawSecondaryLine/drawNextLine 同一公式(含 AOD 亮度补偿),metadata 与实机一致不透明。
    val secondaryColor = ComposeColor(resolvedColors.secondaryText)
        .copy(alpha = previewSecondaryAlpha(profile.secondaryTextBright))
    val metadataColor = ComposeColor(resolvedColors.metadataText)
    val nextLineColor = ComposeColor(resolvedColors.nextLineText)
        .copy(alpha = staticNextLineTextFactor())
    // 字号与实机 setContent 同源:随行长自适应基准 × 字号档倍率(AodCanvasTextMetrics 共享公式)。
    val baseSp = previewBaseTextSizeSp(snapshot.original, profile.textSize, profile.textSizeCustom)
    val textSize = baseSp.sp
    val context = LocalContext.current
    val customFontVersion = if (profile.fontFamily == LyricTypefaceResolver.FAMILY_CUSTOM) {
        LyricTypefaceResolver.customVersion(context)
    } else {
        null
    }
    val lyricTypeface = remember(context, profile.fontFamily, profile.weight, customFontVersion) {
        LyricTypefaceResolver.resolve(context, profile.fontFamily, profile.weight)
    }
    val regularTypeface = remember(context, profile.fontFamily, customFontVersion) {
        if (profile.fontFamily == "auto") Typeface.create("sans-serif", Typeface.NORMAL)
        else LyricTypefaceResolver.resolve(context, profile.fontFamily, "Regular")
    }
    val textAlign = previewTextAlign(profile, snapshot.alignedRight)
    val showMetadata = profile.metadataVisible
    val showNext = profile.showNextLine
    val secondaryRows = previewSecondaryLines(profile, snapshot, baseSp)

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
                    .background(previewCardColor(profile.cardColor, profile.cardAlpha))
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
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val availablePx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                // 换行/测量全部委托 LyricLayoutEngine(与实机同源):断行点、行数上限、
                // Clip 语义一致;预览只负责卡片内的居中摆放。
                val mainLayout = remember(
                    snapshot.original, textSize, lyricTypeface, availablePx,
                    profile.lyricLineLimit, profile.overflow, profile.adaptiveSectioning
                ) {
                    buildPreviewMainLayout(
                        text = snapshot.original,
                        textSizePx = with(density) { textSize.toPx() },
                        typeface = lyricTypeface,
                        availableWidthPx = availablePx.toFloat(),
                        lineLimit = profile.lyricLineLimit,
                        wrap = profile.overflow == "Wrap",
                        adaptiveSectioning = profile.adaptiveSectioning,
                        alignment = when (textAlign) {
                            TextAlign.Center -> "center"
                            TextAlign.End -> "end"
                            else -> "start"
                        },
                        density = density.density
                    )
                }
                Column(Modifier.fillMaxWidth()) {
                    if (showMetadata && profile.metadataAnchor == "top") {
                        PreviewMetaLine(
                            snapshot.metadata, metadataColor, profile.metadataSizePercent,
                            regularTypeface, availablePx,
                            Modifier.padding(bottom = METADATA_LYRIC_GAP_DP.dp)
                        )
                    }
                    PreviewAnimatedLyric(
                        layout = mainLayout,
                        color = lyricColor,
                        glowColor = glowColor,
                        glowEnabled = profile.glow == "On",
                        modifier = Modifier.padding(top = ROW_GAP_BEFORE_ORIGINAL_DP.dp)
                    )
                    secondaryRows.forEach { row ->
                        PreviewSecondaryRow(
                            row = row,
                            color = secondaryColor,
                            typeface = regularTypeface,
                            availableWidthPx = availablePx,
                            preferredLines = mainLayout.lines.size,
                            wrap = profile.overflow == "Wrap",
                            adaptiveSectioning = profile.adaptiveSectioning,
                            textAlign = textAlign,
                            modifier = Modifier.padding(top = ROW_GAP_BEFORE_SECONDARY_DP.dp)
                        )
                    }
                    if (showNext && snapshot.nextLine.isNotBlank()) {
                        PreviewSecondaryRow(
                            row = PreviewSecondaryLine(
                                snapshot.nextLine,
                                nextLineTextSizeSp().sp,
                                italic = false
                            ),
                            color = nextLineColor,
                            typeface = regularTypeface,
                            availableWidthPx = availablePx,
                            preferredLines = mainLayout.lines.size,
                            wrap = profile.overflow == "Wrap",
                            adaptiveSectioning = profile.adaptiveSectioning,
                            textAlign = textAlign,
                            modifier = Modifier.padding(top = ROW_GAP_BEFORE_NEXT_LINE_DP.dp)
                        )
                    }
                    if (showMetadata && profile.metadataAnchor == "bottom") {
                        PreviewMetaLine(
                            snapshot.metadata, metadataColor, profile.metadataSizePercent,
                            regularTypeface, availablePx,
                            Modifier.padding(top = METADATA_LYRIC_GAP_DP.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewMetaLine(
    text: String,
    color: ComposeColor,
    sizePercent: Int,
    typeface: Typeface,
    availableWidthPx: Int,
    modifier: Modifier = Modifier
) {
    val size = previewMetadataTextSizeSp(sizePercent)
    val sizePx = with(LocalDensity.current) { size.toPx() }
    // 换行与实机 wrapMetadataText 同算法:最多 2 行,溢出丢弃(无省略号)。
    val lines = remember(text, sizePx, typeface, availableWidthPx) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.typeface = typeface
        }
        layoutMetadataLines(text, paint, availableWidthPx.toFloat())
    }
    Column(modifier.fillMaxWidth()) {
        lines.forEach { line ->
            Text(
                line.text,
                fontSize = size,
                fontFamily = FontFamily(typeface),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/** 主页预览的歌曲信息字号:与实机 metadataPaint 同源(14sp 基准 × metadataSizePercent,50%~200%)。 */
internal fun previewMetadataTextSizeSp(sizePercent: Int): androidx.compose.ui.unit.TextUnit =
    metadataTextSizeSp(sizePercent).sp

/** 预览主歌词字号(sp):与实机 setContent 同源 —— baseTextSizeSp(随行长自适应)× 字号档倍率。 */
internal fun previewBaseTextSizeSp(text: String, textSizeMode: String, textSizeCustom: Int): Float =
    baseTextSizeSp(text) * textSizeModeMultiplier(textSizeMode, textSizeCustom)

/** 副文本透明度:与实机 drawSecondaryLine 同一公式(steadyTextAlpha 含 AOD 亮度补偿)。 */
internal fun previewSecondaryAlpha(bright: Boolean): Float =
    steadyTextAlpha(staticSecondaryTextFactor(bright))

private fun previewTextAlign(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    alignedRight: Boolean
): TextAlign =
    // "auto" 与实机 setContent 一致:alignedRight 时右对齐,否则左对齐(见 AodLyricCanvasView)。
    when (profile.alignment) {
        "start" -> TextAlign.Start
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> if (alignedRight) TextAlign.End else TextAlign.Start
    }

private data class PreviewSecondaryLine(
    val text: String,
    val size: TextUnit,
    val italic: Boolean
)

private fun previewSecondaryLines(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    snapshot: LyricSnapshot,
    baseSp: Float
): List<PreviewSecondaryLine> {
    // 字号与实机 setContent 同源:音标行/翻译行各自公式(不同下限);翻译行走斜体(与实机一致)。
    val reading = snapshot.romanized.ifBlank { null }?.let {
        PreviewSecondaryLine(it, secondaryReadingTextSizeSp(baseSp).sp, italic = false)
    }
    val translation = snapshot.translated.ifBlank { null }?.let {
        PreviewSecondaryLine(it, secondaryTranslationTextSizeSp(baseSp).sp, italic = true)
    }
    return when (profile.secondaryMode) {
        "Transliteration" -> listOfNotNull(reading)
        "Translation" -> listOfNotNull(translation)
        "Both" -> listOfNotNull(reading, translation)
        else -> emptyList()
    }
}

internal fun previewCardColor(cardColor: String, cardAlpha: Int): ComposeColor {
    // 卡片色与实机 AdaptiveLyricCardBackgroundView.cardColorRgb 同一 token 映射,alpha 由 cardAlpha 单独控制。
    val rgb = cardColorRgb(cardColor) and 0x00FFFFFF
    return ComposeColor(0xFF000000.toInt() or rgb).copy(alpha = cardAlpha.coerceIn(0, 100) / 100f)
}

/** 预览主歌词行布局:行断点来自共享引擎,基线/对齐 X 按实机行距公式解析。 */
private class PreviewMainLayout(
    val lines: List<LyricLayoutLine>,
    val baselines: List<Float>,
    val startsX: List<Float>,
    val blockHeight: Float,
    val paint: TextPaint
)

private fun buildPreviewMainLayout(
    text: String,
    textSizePx: Float,
    typeface: Typeface,
    availableWidthPx: Float,
    lineLimit: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    alignment: String,
    density: Float
): PreviewMainLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        this.typeface = typeface
    }
    // 与实机 buildOriginalLayout 同一引擎入口(预览无词/音标时退化为整段折行)。
    val result: LyricLayoutResult = layoutOriginalLines(
        original = text,
        words = emptyList(),
        ruby = emptyList(),
        layoutGroups = emptyList(),
        paint = paint,
        availableWidth = availableWidthPx,
        lineLimit = lineLimit,
        wordGapPx = LYRIC_WORD_GAP_DP * density,
        wrap = wrap,
        adaptiveSectioning = adaptiveSectioning
    )
    val fm = paint.fontMetrics
    val lineHeight = fm.descent - fm.ascent + LYRIC_LINE_EXTRA_HEIGHT_DP * density
    val lineGap = LYRIC_LINE_GAP_DP * density
    val baselines = ArrayList<Float>(result.lines.size)
    val startsX = ArrayList<Float>(result.lines.size)
    var top = 0f
    result.lines.forEach { line ->
        baselines += top - fm.ascent
        startsX += lineStartX(
            text = line.text,
            textWidth = line.width,
            paint = paint,
            alignment = alignment,
            canvasWidth = availableWidthPx,
            padLeft = 0f,
            padRight = 0f,
            density = density
        )
        top += lineHeight + lineGap
    }
    val blockHeight = originalRowHeight(lineHeight, result.lines.size.coerceAtLeast(1), 0f, lineGap)
    return PreviewMainLayout(result.lines, baselines, startsX, blockHeight, paint)
}

/**
 * 主页预览的歌词主体渲染:行布局来自共享 LyricLayoutEngine(与实机断行一致),
 * 绘制委托 LyricGlowRenderer(实机 AOD/锁屏同源)—— dim 底、光晕、扫光带(缓动/
 * 光带占比/渐变 stops)全部单点定义,预览即实机效果。进度为演示扫光(0→1 循环)。
 */
@Composable
private fun PreviewAnimatedLyric(
    layout: PreviewMainLayout,
    color: ComposeColor,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val glowArgb = glowColor.toArgb()
    val sungArgb = color.copy(alpha = 1f).toArgb()

    // 逐条演示行播放时,进度从 0 扫到 1,驱动扫光。
    val progress = remember { Animatable(0f) }
    LaunchedEffect(layout) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(DEMO_LINE_SWITCH_MS.toInt(), easing = LinearEasing))
    }
    // 在组合作用域读取进度,保证每次动画变化都会重绘 Canvas。
    val progressValue = progress.value

    Canvas(
        modifier
            .fillMaxWidth()
            .height(with(density) { layout.blockHeight.toDp() })
    ) {
        val rows = ArrayList<LyricGlowRow>(layout.lines.size)
        layout.lines.forEachIndexed { index, line ->
            val baseline = layout.baselines[index]
            val left = layout.startsX[index]
            rows += LyricGlowRow(left, line.width, baseline) { c, paintArg ->
                if (line.text.isNotEmpty()) c.drawText(line.text, left, baseline, paintArg)
            }
        }
        drawIntoCanvas { canvas ->
            LyricGlowRenderer.draw(
                canvas = canvas.nativeCanvas,
                paint = layout.paint,
                rows = rows,
                progress = progressValue,
                sungColor = sungArgb,
                glowColor = glowArgb,
                glowEnabled = glowEnabled
            )
        }
    }
}

/** 副文本/下一行一行内容(字号/斜体按实机 paint 语义区分音标与翻译)。 */
@Composable
private fun PreviewSecondaryRow(
    row: PreviewSecondaryLine,
    color: ComposeColor,
    typeface: Typeface,
    availableWidthPx: Int,
    preferredLines: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    textAlign: TextAlign,
    modifier: Modifier = Modifier
) {
    val measureTypeface = if (row.italic) Typeface.create(typeface, Typeface.ITALIC) else typeface
    val sizePx = with(LocalDensity.current) { row.size.toPx() }
    // 换行与实机 wrapSecondaryText 同门控/同算法(引擎内部门控)。
    val lines = remember(row.text, sizePx, measureTypeface, availableWidthPx, preferredLines, wrap, adaptiveSectioning) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.typeface = measureTypeface
        }
        layoutSecondaryLines(
            text = row.text,
            paint = paint,
            availableWidth = availableWidthPx.toFloat(),
            preferredLines = preferredLines,
            wrap = wrap,
            adaptiveSectioning = adaptiveSectioning
        )
    }
    Column(modifier.fillMaxWidth()) {
        lines.forEach { line ->
            Text(
                line.text,
                fontSize = row.size,
                fontWeight = FontWeight.Normal,
                fontStyle = if (row.italic) FontStyle.Italic else FontStyle.Normal,
                fontFamily = FontFamily(measureTypeface),
                color = color,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

