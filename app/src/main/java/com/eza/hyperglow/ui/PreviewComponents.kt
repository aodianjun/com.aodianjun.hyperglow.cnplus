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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.root.aod.LyricGlowRenderer
import com.eza.hyperglow.root.aod.LyricGlowRow
import com.eza.hyperglow.root.aod.LyricTypefaceResolver
import com.eza.hyperglow.root.aod.baseTextSizeSp
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
    val regularFontFamily = remember(context, profile.fontFamily, customFontVersion) {
        if (profile.fontFamily == "auto") null
        else FontFamily(LyricTypefaceResolver.resolve(context, profile.fontFamily, "Regular"))
    }
    val textAlign = previewTextAlign(profile, snapshot.alignedRight)
    val showMetadata = profile.metadataVisible
    val showNext = profile.showNextLine
    val secondaryLines = previewSecondaryLines(profile, snapshot, baseSp)

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
            if (showMetadata && profile.metadataAnchor == "top") {
                PreviewMetaLine(snapshot.metadata, metadataColor, profile.metadataSizePercent, regularFontFamily)
            }
            // 溢出语义与实机一致:Clip 恒单行,Wrap 按行数上限硬截(无省略号)。
            PreviewAnimatedLyric(
                text = snapshot.original,
                textSize = textSize,
                lyricTypeface = lyricTypeface,
                color = lyricColor,
                glowColor = glowColor,
                glowEnabled = profile.glow == "On",
                textAlign = textAlign,
                maxLines = if (profile.overflow == "Clip") 1
                else if (profile.lyricLineLimit > 0) profile.lyricLineLimit else Int.MAX_VALUE,
                overflow = TextOverflow.Clip
            )
            secondaryLines.forEach { line ->
                Text(
                    line.text,
                    fontSize = line.size,
                    fontWeight = FontWeight.Normal,
                    fontFamily = regularFontFamily,
                    color = secondaryColor,
                    textAlign = textAlign,
                    maxLines = line.maxLines,
                    overflow = TextOverflow.Clip
                )
            }
            if (showNext && snapshot.nextLine.isNotBlank()) {
                Text(
                    snapshot.nextLine,
                    fontSize = nextLineTextSizeSp().sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = regularFontFamily,
                    color = nextLineColor,
                    textAlign = textAlign,
                    maxLines = previewSecondaryWrapLines(profile),
                    overflow = TextOverflow.Clip
                )
            }
            if (showMetadata && profile.metadataAnchor == "bottom") {
                PreviewMetaLine(snapshot.metadata, metadataColor, profile.metadataSizePercent, regularFontFamily)
            }
        }
    }
}

@Composable
private fun PreviewMetaLine(text: String, color: ComposeColor, sizePercent: Int, fontFamily: FontFamily?) {
    Text(
        text,
        fontSize = previewMetadataTextSizeSp(sizePercent),
        fontFamily = fontFamily,
        color = color,
        // 与实机 wrapMetadataText 一致:最多 2 行,溢出丢弃(无省略号)。
        maxLines = 2,
        overflow = TextOverflow.Clip
    )
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

/** 副文本/下一行行数上限:与实机 wrapSecondaryText 同门控(adaptiveSectioning + Wrap 时最多 2 行)。 */
internal fun previewSecondaryWrapLines(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile
): Int = if (profile.adaptiveSectioning && profile.overflow == "Wrap") 2 else 1

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
    val maxLines: Int
)

private fun previewSecondaryLines(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    snapshot: LyricSnapshot,
    baseSp: Float
): List<PreviewSecondaryLine> {
    // 字号与实机 setContent 同源:音标行/翻译行各自公式(不同下限),行数与实机换行门控一致。
    val wrapLines = previewSecondaryWrapLines(profile)
    val reading = snapshot.romanized.ifBlank { null }?.let {
        PreviewSecondaryLine(it, secondaryReadingTextSizeSp(baseSp).sp, wrapLines)
    }
    val translation = snapshot.translated.ifBlank { null }?.let {
        PreviewSecondaryLine(it, secondaryTranslationTextSizeSp(baseSp).sp, wrapLines)
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

/**
 * 主页预览的歌词主体渲染:在原生 Canvas 上用 StaticLayout 绘制主歌词,按播放进度模拟
 * 息屏外观的三种效果 —— 文字发光(glow)、整行进度扫光(line progress sweep)与逐字高亮
 * (当前演唱词的强调光斑)。这样预览与 AodLyricCanvasView 的息屏渲染保持一致。
 */
@Composable
private fun PreviewAnimatedLyric(
    text: String,
    textSize: TextUnit,
    lyricTypeface: Typeface,
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
        val layoutHeight = remember(text, textSize, maxLines, widthPx, lyricTypeface) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = fontSizePx
                typeface = lyricTypeface
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
                typeface = lyricTypeface
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

