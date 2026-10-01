package com.eza.hyperglow.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.ARTWORK_SHAPE_CIRCLE
import com.eza.hyperglow.customization.ArtworkDisplayConfig
import com.eza.hyperglow.customization.artworkDisplayConfig
import com.eza.hyperglow.customization.CustomFontContract
import com.eza.hyperglow.customization.resolveLineTransition
import com.eza.hyperglow.root.aod.AodCanvasRuby
import com.eza.hyperglow.root.aod.edgeSafeAlignedStart
import com.eza.hyperglow.root.aod.LineTransitionFrame
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
import com.eza.hyperglow.root.aod.ARTWORK_SPIN_PERIOD_MS
import com.eza.hyperglow.root.aod.artworkLeadingPx
import com.eza.hyperglow.root.aod.artworkSidePx
import com.eza.hyperglow.root.aod.baseTextSizeSp
import com.eza.hyperglow.root.aod.duetAlignedRight
import com.eza.hyperglow.root.aod.layoutMetadataLines
import com.eza.hyperglow.root.aod.layoutOriginalLines
import com.eza.hyperglow.root.aod.layoutSecondaryLines
import com.eza.hyperglow.root.aod.lineTransitionEnterFrame
import com.eza.hyperglow.root.aod.lineTransitionExitFrame
import com.eza.hyperglow.root.aod.lineTransitionMoveFrame
import com.eza.hyperglow.root.aod.lineTransitionPromotes
import com.eza.hyperglow.root.aod.lineTransitionTimeline
import com.eza.hyperglow.root.aod.moveTransitionEase
import com.eza.hyperglow.root.aod.metadataTextSizeSp
import com.eza.hyperglow.root.aod.nextLineTextSizeSp
import com.eza.hyperglow.root.aod.originalRowHeight
import com.eza.hyperglow.root.aod.metadataTextSizeSp
import com.eza.hyperglow.root.aod.nextLineTextSizeSp
import com.eza.hyperglow.root.aod.resolveAodPalette
import com.eza.hyperglow.root.aod.resolvedLineSyncFillMode
import com.eza.hyperglow.root.aod.resolveRowAlignmentMode
import com.eza.hyperglow.root.aod.rubyReservation
import com.eza.hyperglow.root.aod.rubySpanGeometry
import com.eza.hyperglow.root.aod.rubyTextSizePx
import com.eza.hyperglow.root.aod.lineStartX
import com.eza.hyperglow.root.aod.secondaryReadingTextSizeSp
import com.eza.hyperglow.root.aod.secondaryTranslationTextSizeSp
import com.eza.hyperglow.root.aod.secondLineColorArgb
import com.eza.hyperglow.root.aod.SecondLinePresentation
import com.eza.hyperglow.root.aod.SecondLineAuxRow
import com.eza.hyperglow.root.aod.secondLineAuxPreferredLines
import com.eza.hyperglow.root.aod.secondLineAuxRows
import com.eza.hyperglow.root.aod.secondLinePresentation
import com.eza.hyperglow.root.aod.staticNextLineTextFactor
import com.eza.hyperglow.root.aod.staticSecondaryTextFactor
import com.eza.hyperglow.root.aod.steadyTextAlpha
import com.eza.hyperglow.root.aod.textSizeModeMultiplier
import com.eza.hyperglow.root.aod.visualExtents
import com.eza.hyperglow.root.aod.END_EDGE_SAFETY_DP
import com.eza.hyperglow.root.aod.lineTransitionEnterEasing
import com.eza.hyperglow.root.aod.lineTransitionExitEasing
import com.eza.hyperglow.root.lockscreen.cardColorRgb
import com.eza.hyperglow.root.projection.LyricSnapshot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 预览卡片高度自适应范围(dp):内容有多高卡片就多高。内容不足下限时保持卡片形,
 * 超过上限时封顶,防止极端字号组合把主页/设置页其余内容挤出屏幕。
 */
internal const val PREVIEW_CARD_MIN_HEIGHT_DP = 120f

internal const val PREVIEW_CARD_MAX_HEIGHT_DP = 420f

/**
 * 预览卡片高度自适应:内容有多高卡片就多高,钳制在 [PREVIEW_CARD_MIN_HEIGHT_DP]..
 * [PREVIEW_CARD_MAX_HEIGHT_DP](下限保持卡片形,上限防止极端字号把页面其余内容挤出屏幕)。
 *
 * 同一配置内取已见最大内容高度([previousContentDp] 与 [measuredContentDp] 取大):演示行
 * 循环/逐行播放时折行数变化,直接跟随会让卡片高度来回呼吸、推动下方内容上下跳动;
 * 取已见最大值则配置不变期间高度稳定。配置变化或换歌时调用方重置 [previousContentDp]
 * (remember 键),卡片随即重新随内容收缩。
 */
internal fun previewCardHeightDp(previousContentDp: Float, measuredContentDp: Float): Float =
    maxOf(previousContentDp, measuredContentDp)
        .coerceIn(PREVIEW_CARD_MIN_HEIGHT_DP, PREVIEW_CARD_MAX_HEIGHT_DP)

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
    metadataParts: String,
    metadataSeparator: String,
    duetMarkers: Boolean = true,
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile),
    modifier: Modifier = Modifier
) {
    val live = collectLiveSnapshot(metadataParts, metadataSeparator, duetMarkers)
    LyricPreviewSurface(
        profile = profile,
        scenario = scenario,
        live = live,
        metadataParts = metadataParts,
        metadataSeparator = metadataSeparator,
        artwork = artwork,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Home lyric-widget preview card. Renders a phone-like dark surface sized to the card and draws a
 * stylized lyric block using the compiled [profile] (text size/weight/alignment, secondary text,
 * metadata, card background, next line) placed via [resolvePreviewPlacement], so the home page
 * gives a quick visual sense of how the lockscreen / AOD lyric control looks.
 *
 * The surface height adapts to the rendered content ([PREVIEW_CARD_MIN_HEIGHT_DP]..
 * [PREVIEW_CARD_MAX_HEIGHT_DP]): large text sizes and extra rows grow the card instead of being
 * clipped by a fixed box.
 */
@Composable
internal fun LyricPreviewCard(
    title: String,
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    live: LyricSnapshot?,
    metadataParts: String,
    metadataSeparator: String,
    modifier: Modifier,
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile)
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
            LyricPreviewSurface(
                profile = profile,
                scenario = scenario,
                live = live,
                metadataParts = metadataParts,
                metadataSeparator = metadataSeparator,
                artwork = artwork,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 深色预览面板 + 歌词内容。高度自适应内容(上下限见 [PREVIEW_CARD_MIN_HEIGHT_DP] /
 * [PREVIEW_CARD_MAX_HEIGHT_DP]),大字号/多行不再被固定高度裁掉;「card」背景铺满面板,
 * 歌词块在面板内水平居中、垂直居中。
 */
@Composable
private fun LyricPreviewSurface(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    live: LyricSnapshot?,
    metadataParts: String,
    metadataSeparator: String,
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile),
    modifier: Modifier = Modifier
) {
    // 有实时歌词时跟随最新快照;否则用循环播放的演示快照,让预览始终可见且持续更新。
    val snapshot = live ?: collectDemoSnapshot(metadataParts, metadataSeparator)
    // 歌曲图片(与实机同一几何公式):实时快照带已校对封面帧则显示真帧;演示态显示
    // 生成占位图,便于调形状/旋转开关所见即所得;实时无帧=不显示(与实机 fail-closed 一致)。
    // 只在歌曲信息行可见且文本非空时露出(与实机「图片随歌曲信息行」同一门槛)。
    val previewArtwork = if (!artwork.visible || !profile.metadataVisible ||
        snapshot.metadata.isBlank()
    ) {
        null
    } else if (live != null) {
        previewArtworkFromSnapshot(snapshot, artwork)
    } else {
        PreviewArtwork(artwork.shape, artwork.spins, image = null, placeholder = true)
    }
    // 颜色与实机同源:统一走 resolveAodPalette(dimmed 预设/自定义字体颜色 hex token 一处解析)
    val resolvedColors = resolveAodPalette(profile.palette)
    // 主行取色与实机 drawOriginalGlowBlock 调用一致:已唱/底色走 sungText,光晕走 glow token。
    val lyricColor = ComposeColor(resolvedColors.sungText)
    val glowColor = ComposeColor(resolvedColors.glow)
    // 透明度与实机 drawSecondaryLine/drawNextLine 同一公式(含 AOD 亮度补偿),metadata 与实机一致不透明。
    val secondaryColor = ComposeColor(resolvedColors.secondaryText)
        .copy(alpha = previewSecondaryAlpha(profile.secondaryTextBright))
    val metadataColor = ComposeColor(resolvedColors.metadataText)
    val nextLineColor = ComposeColor(
        secondLineColorArgb(SecondLinePresentation.STANDALONE, resolvedColors)
    ).copy(alpha = staticNextLineTextFactor())
    // 第二行歌词以辅助文字形态出现时,颜色仍走「下一行颜色」(secondLineColorArgb 同源),
    // 只借辅助文字的亮度档;否则"下一行颜色"设置对该形态失效。
    val nextLineSecondaryColor = ComposeColor(
        secondLineColorArgb(SecondLinePresentation.AS_SECONDARY, resolvedColors)
    ).copy(alpha = previewSecondaryAlpha(profile.secondaryTextBright))
    // 注音颜色与实机 drawRuby 同源:取辅助文字色,亮度恒按「亮」档绘制(实机注音不随
    // 「明亮辅助文字」开关变化),注音绘制在基线上方。
    val rubyColor = ComposeColor(resolvedColors.secondaryText).copy(alpha = steadyTextAlpha(1f))
    // 字号与实机 setContent 同源:随行长自适应基准 × 字号档倍率(AodCanvasTextMetrics 共享公式)。
    val baseSp = previewBaseTextSizeSp(snapshot.original, profile.textSize, profile.textSizeCustom)
    val textSize = baseSp.sp
    val context = LocalContext.current
    val customFontVersion = if (CustomFontContract.isCustomFontFamily(profile.fontFamily)) {
        LyricTypefaceResolver.customVersion(context, context, profile.fontFamily)
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
    // 对唱分侧门控(与实机 LyricCanvasMapper 同源):关闭时忽略行级 alignedRight。
    val alignedRight = duetAlignedRight(snapshot.alignedRight, profile.duetAlignment)
    val textAlign = previewRowTextAlign("auto", profile.alignment, alignedRight)
    // 行级独立对齐(与实机 alignmentFor 同源):歌曲信息/第二行歌词各自解析。
    val metadataAlign =
        previewRowTextAlign(profile.metadataAlignment, profile.alignment, alignedRight)
    val nextLineAlign =
        previewRowTextAlign(profile.nextLineAlignment, profile.alignment, alignedRight)
    val showMetadata = profile.metadataVisible
    val showNext = profile.showNextLine
    val secondaryRows = previewSecondaryLines(profile, snapshot, baseSp)
    // 注音内容:关闭开关时不注入(与实机 LyricCanvasMapper 的 rubyVisible 门控同源),
    // 开启时按快照的 ruby 段绘制 —— 预览与实机同受该开关控制。
    val previewRuby = remember(profile.rubyVisible, snapshot.ruby) {
        if (profile.rubyVisible) {
            snapshot.ruby.map { AodCanvasRuby(it.start, it.end, it.reading) }
        } else {
            emptyList()
        }
    }
    // 生效进度效果:与实机 drawOriginal 的 Minimal 分支 / effectiveLineSyncFillMode 同源。
    // Minimal=静态全亮(无扫光/发光);行级同步时按配置的四种进度效果;否则整块连续横扫。
    val previewFillMode = when {
        profile.animation == "Minimal" -> "None"
        snapshot.lineLevelSync -> resolvedLineSyncFillMode(true, profile.lineSyncFillMode)
        else -> LyricGlowRenderer.FILL_LEFT_TO_RIGHT_WHOLE_BLOCK
    }

    // 预览卡片高度自适应:面板高度贴合歌词内容(钳制见 previewCardHeightDp),大字号/多行
    // 内容不再被固定高度裁掉。高度取本配置下的已见最大内容高度——演示行循环/逐行播放时
    // 折行数变化,直接跟随会让卡片高度来回呼吸、推动下方内容上下跳动;配置或曲目变化时
    // remember 键重置,卡片随即重新随当前内容收缩。歌词块在面板内水平居中、垂直居中,
    // 忽略真实曲面上的时钟/通知等占位偏移——否则息屏(AOD)歌词会按真实布局被挤到面板
    // 顶部一小条,大字号下一行就被裁掉,看起来像被遮挡。
    val density = LocalDensity.current
    var stableContentDp by remember(profile, scenario, snapshot.trackGeneration, density.density) {
        mutableStateOf(PREVIEW_CARD_MIN_HEIGHT_DP)
    }
    var measuredWidthPx by remember(profile, scenario, snapshot.trackGeneration, density.density) {
        mutableStateOf(0)
    }
    Box(
        modifier = modifier
            .animateContentSize()
            .heightIn(min = stableContentDp.dp, max = PREVIEW_CARD_MAX_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ComposeColor(0xFF0B0B0F)),
        contentAlignment = Alignment.Center
    ) {
        // 锁屏卡片背景(息屏强制无卡片背景)。卡片背景铺满面板,面板高度随歌词内容自适应,
        // 与实机卡片背景(AdaptiveLyricCardBackgroundView)随内容收缩的语义一致。
        if (profile.backgroundStyle == "card") {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(previewCardColor(profile.cardColor, profile.cardAlpha))
            )
        }
        Column(
            modifier = Modifier
                .onSizeChanged { size ->
                    // 宽度变化(转屏/窗口)会改变折行结果,高度从下限重新累计。
                    if (size.width != measuredWidthPx) {
                        measuredWidthPx = size.width
                        stableContentDp = PREVIEW_CARD_MIN_HEIGHT_DP
                    }
                    stableContentDp = previewCardHeightDp(
                        stableContentDp,
                        with(density) { size.height.toDp().value }
                    )
                }
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
                val availablePx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                // 换行/测量全部委托 LyricLayoutEngine(与实机同源):断行点、行数上限、
                // Clip 语义一致;预览只负责卡片内的居中摆放。
                val mainLayout = remember(
                    snapshot.original, textSize, lyricTypeface, regularTypeface, previewRuby,
                    availablePx, profile.lyricLineLimit, profile.overflow, profile.adaptiveSectioning
                ) {
                    buildPreviewMainLayout(
                        text = snapshot.original,
                        textSizePx = with(density) { textSize.toPx() },
                        typeface = lyricTypeface,
                        rubyTypeface = regularTypeface,
                        ruby = previewRuby,
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
                            regularTypeface, availablePx, metadataAlign,
                            previewArtwork,
                            Modifier.padding(bottom = METADATA_LYRIC_GAP_DP.dp)
                        )
                    }
                    // 行块(主行+辅助文字+下一行)按行分流三段式换行:离场行组(主行+辅助
                    // 文字)退场、下一行晋级位移、新到行进场,与实机 drawOrientedContent 同构。
                    val blockRows = ArrayList<PreviewBlockRow>(secondaryRows.size)
                    secondaryRows.forEach { row ->
                        blockRows += PreviewBlockRow(
                            row = row,
                            color = secondaryColor,
                            align = textAlign,
                            gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp,
                            dimAlpha = staticSecondaryTextFactor(profile.secondaryTextBright)
                        )
                    }
                    // 下一行歌词呈现与实机同源(secondLinePresentation):「辅助文字显示第二行歌词」
                    // 或「显示第二行辅助文字」开启时以辅助文字样式(音标行字号公式+亮度档)绘制并
                    // 取代独立下一行行,颜色仍走「下一行颜色」(secondLineColorArgb)。
                    val nextPresentation = secondLinePresentation(
                        profile.secondaryNextLine,
                        profile.nextLineAux,
                        showNext,
                        snapshot.nextLine.isNotBlank()
                    )
                    val nextBlockRow = when (nextPresentation) {
                        SecondLinePresentation.AS_SECONDARY -> PreviewBlockRow(
                            row = PreviewSecondaryLine(
                                snapshot.nextLine,
                                secondaryReadingTextSizeSp(baseSp).sp,
                                italic = false
                            ),
                            color = nextLineSecondaryColor,
                            align = nextLineAlign,
                            gapAbove = ROW_GAP_BEFORE_NEXT_LINE_DP.dp,
                            dimAlpha = staticSecondaryTextFactor(profile.secondaryTextBright)
                        )
                        SecondLinePresentation.STANDALONE -> PreviewBlockRow(
                            row = PreviewSecondaryLine(
                                snapshot.nextLine,
                                nextLineTextSizeSp().sp,
                                italic = false
                            ),
                            color = nextLineColor,
                            align = nextLineAlign,
                            gapAbove = ROW_GAP_BEFORE_NEXT_LINE_DP.dp,
                            dimAlpha = staticNextLineTextFactor()
                        )
                        SecondLinePresentation.NONE -> null
                    }
                    // 第二行歌词自身的呈现行数(与实机 buildRows 同序:先按主行行数作
                    // preferredLines 布局第二行,再取其实际行数),第二行辅助行的换行档
                    // 跟随它而不是主行行数(owner 2026-10-02 真机反馈)。
                    val nextLineRenderedLines = remember(
                        snapshot.nextLine,
                        baseSp,
                        regularTypeface,
                        availablePx,
                        profile.overflow,
                        profile.adaptiveSectioning,
                        mainLayout
                    ) {
                        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            // 显式接收者:外层同名局部变量(TextUnit textSize)会遮蔽 paint 成员。
                            this.textSize = with(density) {
                                secondaryReadingTextSizeSp(baseSp).sp.toPx()
                            }
                            this.typeface = regularTypeface
                        }
                        layoutSecondaryLines(
                            text = snapshot.nextLine,
                            paint = paint,
                            availableWidth = availablePx.toFloat(),
                            preferredLines = mainLayout.lines.size,
                            wrap = profile.overflow == "Wrap",
                            adaptiveSectioning = profile.adaptiveSectioning
                        ).size
                    }
                    val nextAuxPreferredLines =
                        secondLineAuxPreferredLines(nextLineRenderedLines)
                    // 「显示第二行辅助文字」:第二行歌词行之后追加其自身的辅助文字行
                    // (行清单与实机同源,见 secondLineAuxRows;样式沿用辅助文字行)。
                    val nextAuxRows =
                        if (nextPresentation == SecondLinePresentation.AS_SECONDARY) {
                            secondLineAuxRows(
                                profile.nextLineAux,
                                profile.secondaryMode,
                                snapshot.nextLineRomanized,
                                snapshot.nextLineTranslated
                            ).map { auxRow ->
                                when (auxRow) {
                                    SecondLineAuxRow.ROMANIZED -> PreviewBlockRow(
                                        row = PreviewSecondaryLine(
                                            snapshot.nextLineRomanized,
                                            secondaryReadingTextSizeSp(baseSp).sp,
                                            italic = false
                                        ),
                                        color = secondaryColor,
                                        align = nextLineAlign,
                                        gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp,
                                        dimAlpha = staticSecondaryTextFactor(
                                            profile.secondaryTextBright
                                        ),
                                        preferredLines = nextAuxPreferredLines
                                    )
                                    SecondLineAuxRow.TRANSLATED -> PreviewBlockRow(
                                        row = PreviewSecondaryLine(
                                            snapshot.nextLineTranslated,
                                            secondaryTranslationTextSizeSp(baseSp).sp,
                                            italic = true
                                        ),
                                        color = secondaryColor,
                                        align = nextLineAlign,
                                        gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp,
                                        dimAlpha = staticSecondaryTextFactor(
                                            profile.secondaryTextBright
                                        ),
                                        preferredLines = nextAuxPreferredLines
                                    )
                                }
                            }
                        } else {
                            emptyList()
                        }
                    PreviewAnimatedRowBlock(
                        block = PreviewRowBlock(
                            mainLayout,
                            snapshot.original,
                            blockRows,
                            nextBlockRow,
                            nextAuxRows
                        ),
                        lineTransition = resolveLineTransition(profile.lineTransition, "Fade up"),
                        lineTransitionSpeed = profile.lineTransitionSpeed,
                        color = lyricColor,
                        glowColor = glowColor,
                        glowEnabled = profile.glow == "On",
                        fillMode = previewFillMode,
                        rubyColor = rubyColor,
                        regularTypeface = regularTypeface,
                        availableWidthPx = availablePx,
                        wrap = profile.overflow == "Wrap",
                        adaptiveSectioning = profile.adaptiveSectioning,
                        modifier = Modifier.padding(top = ROW_GAP_BEFORE_ORIGINAL_DP.dp)
                    )
                    if (showMetadata && profile.metadataAnchor == "bottom") {
                        PreviewMetaLine(
                            snapshot.metadata, metadataColor, profile.metadataSizePercent,
                            regularTypeface, availablePx, metadataAlign,
                            previewArtwork,
                            Modifier.padding(top = METADATA_LYRIC_GAP_DP.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 预览侧歌曲图片呈现参数:形状/旋转生效值 + 解码后的帧(或演示占位)。 */
private data class PreviewArtwork(
    val shape: String,
    val spin: Boolean,
    val image: ImageBitmap?,
    val placeholder: Boolean
)

/**
 * 实时快照的封面帧:已校对帧按 key 解码一次(remember 键),无帧返回 null(不显示,
 * 与实机 fail-closed 一致);坏帧解码失败同样不显示。
 */
@Composable
private fun previewArtworkFromSnapshot(
    snapshot: LyricSnapshot,
    artwork: ArtworkDisplayConfig
): PreviewArtwork? {
    if (snapshot.artworkKey.isBlank() || snapshot.artworkJpeg.isEmpty()) return null
    val image = remember(snapshot.artworkKey) {
        runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                snapshot.artworkJpeg,
                0,
                snapshot.artworkJpeg.size
            )
            bitmap?.asImageBitmap()
        }.getOrNull()
    } ?: return null
    return PreviewArtwork(artwork.shape, artwork.spins, image, placeholder = false)
}

/**
 * 歌曲信息行:歌曲图片显示时按「图片槽+间距+文本块」成组布局(几何公式与实机
 * wrapMetadataText 同源),[textAlign] 作用于整组——图片恒在文本块左侧。
 */
@Composable
private fun PreviewMetaLine(
    text: String,
    color: ComposeColor,
    sizePercent: Int,
    typeface: Typeface,
    availableWidthPx: Int,
    textAlign: TextAlign,
    artwork: PreviewArtwork?,
    modifier: Modifier = Modifier
) {
    val size = previewMetadataTextSizeSp(sizePercent)
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val leadingPx = if (artwork != null) artworkLeadingPx(sizePx, density.density) else 0f
    // 换行与实机 layoutMetadataLines 同算法:切片/折行后最多 MAX_METADATA_LAYOUT_LINES 行,溢出丢弃(无省略号)。
    val lines = remember(text, sizePx, typeface, availableWidthPx, leadingPx) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.typeface = typeface
        }
        layoutMetadataLines(text, paint, (availableWidthPx - leadingPx).coerceAtLeast(1f))
    }
    val groupArrangement = when (textAlign) {
        TextAlign.Center -> Arrangement.Center
        TextAlign.End -> Arrangement.End
        else -> Arrangement.Start
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = groupArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artwork != null) {
            PreviewArtworkBox(
                artwork,
                side = with(density) { artworkSidePx(sizePx).toDp() },
                contentDescription = text
            )
            Spacer(Modifier.width(com.eza.hyperglow.root.aod.ARTWORK_TEXT_GAP_DP.dp))
        }
        Column(Modifier.width(IntrinsicSize.Max)) {
            lines.forEach { line ->
                Text(
                    line.text,
                    fontSize = size,
                    fontFamily = FontFamily(typeface),
                    color = color,
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 歌曲图片方框:方形=矩形裁切,圆形=圆形裁切;圆形开旋转时匀速自转
 * (周期与实机 artworkSpinDegrees 同源,[ARTWORK_SPIN_PERIOD_MS])。
 * 演示态用渐变占位图(音符字形),仅预览可见,不代表真实封面。
 */
@Composable
private fun PreviewArtworkBox(
    artwork: PreviewArtwork,
    side: androidx.compose.ui.unit.Dp,
    contentDescription: String
) {
    val shape = if (artwork.shape == ARTWORK_SHAPE_CIRCLE) CircleShape else RectangleShape
    val rotation = if (artwork.spin) {
        val transition = rememberInfiniteTransition(label = "artwork-spin")
        val degrees by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(ARTWORK_SPIN_PERIOD_MS.toInt(), easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = "artwork-spin-degrees"
        )
        degrees
    } else {
        0f
    }
    Box(
        Modifier
            .size(side)
            .rotate(rotation)
            .clip(shape)
            .background(ComposeColor(0xFF2A2A32))
    ) {
        if (artwork.image != null) {
            Image(
                bitmap = artwork.image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else if (artwork.placeholder) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Text(
                    "♪",
                    color = ComposeColor(0xFFB9BDC7),
                    fontSize = with(LocalDensity.current) { (side.toPx() * 0.42f).toSp() }
                )
            }
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

/**
 * 预览行级对齐:与实机 alignmentFor/setContent 同源(resolveRowAlignmentMode)。
 * 显式 start/center/end 直接生效;"auto" 跟随主对齐解析(主 auto 时按歌词方向右对齐)。
 */
internal fun previewRowTextAlign(
    rowAlignment: String,
    mainAlignment: String,
    alignedRight: Boolean
): TextAlign = when (resolveRowAlignmentMode(rowAlignment, mainAlignment, alignedRight)) {
    "center" -> TextAlign.Center
    "end" -> TextAlign.End
    else -> TextAlign.Start
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
    val paint: TextPaint,
    /** 注音行绘制参数(与实机 rubyPaint 同字号/字型);关闭注音时无任何 placements。 */
    val rubyPaint: TextPaint,
    val rubyLines: List<PreviewRubyLine>
)

/** 预览一行注音:落位于主行基线之上,[placements] 为各注音段的中心 X 与文本。 */
private class PreviewRubyLine(
    val startX: Float,
    val baseline: Float,
    val placements: List<PreviewRubyPlacement>
)

/** 预览单个注音段:[rubyCenterX] 相对行首 X,与实机 drawRuby 同一落位公式。 */
private class PreviewRubyPlacement(
    val reading: String,
    val rubyCenterX: Float,
    val spanX: Float,
    val spanWidth: Float
)

private fun buildPreviewMainLayout(
    text: String,
    textSizePx: Float,
    typeface: Typeface,
    rubyTypeface: Typeface,
    ruby: List<AodCanvasRuby>,
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
    // 注音 paint 与实机 applyContentStyle 同源:字号走共享纯函数 rubyTextSizePx,
    // 字型取同族 Regular。
    val rubyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = rubyTextSizePx(textSizePx)
        this.typeface = rubyTypeface
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
    val rubyFm = rubyPaint.fontMetrics
    val lineHeight = fm.descent - fm.ascent + LYRIC_LINE_EXTRA_HEIGHT_DP * density
    val lineGap = LYRIC_LINE_GAP_DP * density
    // 注音几何与实机 assignRuby/drawRuby 同源:逐行求解注音段落位与占高,基线整体下移
    // 让出注音带(首行从 0 起算,故块顶恰为注音带顶)。关闭注音时 ruby 为空,
    // 各几何退化为无注音时的原状。
    val baselines = ArrayList<Float>(result.lines.size)
    val startsX = ArrayList<Float>(result.lines.size)
    val rubyLines = ArrayList<PreviewRubyLine>(result.lines.size)
    var precedingRuby = 0f
    var index = 0
    result.lines.forEach { line ->
        val placements = previewRubyPlacements(line, text.length, ruby, paint, rubyPaint)
        val rubyHeight = if (placements.isEmpty()) {
            0f
        } else {
            rubyReservation(paint.textSize, rubyFm.ascent)
        }
        val lineBaseline =
            -fm.ascent + index * lineHeight + precedingRuby + rubyHeight + index * lineGap
        baselines += lineBaseline
        // 对齐 X 与实机 assignRuby 同源:无注音段时直接用共享 lineStartX;有注音段时把
        // 注音 span 外沿并入文本视觉外沿后按 alignment 解析(与实机 assignRuby 同一公式)。
        val startX = if (placements.isEmpty()) {
            lineStartX(
                text = line.text,
                textWidth = line.width,
                paint = paint,
                alignment = alignment,
                canvasWidth = availableWidthPx,
                padLeft = 0f,
                padRight = 0f,
                density = density
            )
        } else {
            val visual = visualExtents(line.text, paint, line.width)
            val visualLeft = minOf(visual.first, placements.minOfOrNull { it.spanX } ?: visual.first)
            val visualRight =
                maxOf(visual.second, placements.maxOfOrNull { it.spanX + it.spanWidth } ?: visual.second)
            edgeSafeAlignedStart(
                canvasWidth = availableWidthPx,
                paddingLeft = 0f,
                paddingRight = 0f,
                visualLeft = visualLeft,
                visualRight = visualRight,
                alignment = alignment,
                safetyInset = if (alignment == "end") END_EDGE_SAFETY_DP * density else 0f
            )
        }
        startsX += startX
        // 注音基线 = 主行基线 + 主行 ascent - (注音占高 + 注音 ascent) - 注音 descent
        // (与实机 drawRuby 同一公式)。
        val rubyBaseline = if (placements.isEmpty()) {
            0f
        } else {
            lineBaseline + fm.ascent - (rubyHeight + rubyFm.ascent) - rubyFm.descent
        }
        rubyLines += PreviewRubyLine(startX, rubyBaseline, placements)
        precedingRuby += rubyHeight
        index++
    }
    val blockHeight = originalRowHeight(
        lineHeight,
        result.lines.size.coerceAtLeast(1),
        precedingRuby,
        lineGap
    )
    return PreviewMainLayout(result.lines, baselines, startsX, blockHeight, paint, rubyPaint, rubyLines)
}

/**
 * 预览一行的注音段落位(与实机 assignRuby 无词分支同源):按行字符区间裁剪与行相交的
 * ruby 段,以主行字体的前缀宽/段宽求 span 几何,返回注音中心 X 与 span 外沿。
 * 行无字符区间(词行布局空回退)时不注入,与实机一致。
 */
private fun previewRubyPlacements(
    line: LyricLayoutLine,
    textLength: Int,
    ruby: List<AodCanvasRuby>,
    paint: TextPaint,
    rubyPaint: TextPaint
): List<PreviewRubyPlacement> {
    val lineStart = line.charStart ?: return emptyList()
    val lineEnd = line.charEnd ?: return emptyList()
    return ruby.asSequence()
        .filter {
            it.start >= 0 && it.end > it.start && it.end <= textLength &&
                it.start < lineEnd && it.end > lineStart
        }
        .sortedBy { it.start }
        .mapNotNull { segment ->
            val baseStart = maxOf(segment.start, lineStart)
            val baseEnd = minOf(segment.end, lineEnd)
            val localStart = (baseStart - lineStart).coerceIn(0, line.text.length)
            val localEnd = (baseEnd - lineStart).coerceIn(localStart, line.text.length)
            if (localStart >= localEnd) return@mapNotNull null
            val baseX = paint.measureText(line.text, 0, localStart)
            val baseWidth = paint.measureText(line.text, localStart, localEnd)
            val geometry = rubySpanGeometry(baseX, baseWidth, rubyPaint.measureText(segment.reading))
            PreviewRubyPlacement(
                reading = segment.reading,
                rubyCenterX = geometry.rubyCenterX,
                spanX = geometry.spanX,
                spanWidth = geometry.spanWidth
            )
        }
        .toList()
}

/**
 * 行块一代内容:主行 + 辅助文字行 + 下一行行。换行过渡按行分流三段式(与实机
 * drawOrientedContent 同构):离场行组(主行+辅助文字)退场 → 下一行晋级位移 →
 * 新到行(新辅助文字+新下一行)进场;[nextLine] 为晋级源(内容延续时升任主行)。
 */
private class PreviewRowBlock(
    val main: PreviewMainLayout,
    val mainText: String,
    val rows: List<PreviewBlockRow>,
    val nextLine: PreviewBlockRow?,
    /** 「显示第二行辅助文字」:第二行歌词行自身的辅助文字行(音标/翻译),紧随下一行行之后。 */
    val nextRows: List<PreviewBlockRow> = emptyList()
)

/** 行块内副行(辅助文字/下一行)的渲染参数,随所属行块一起冻结;[dimAlpha] 为该行静态亮度档。 */
private class PreviewBlockRow(
    val row: PreviewSecondaryLine,
    val color: ComposeColor,
    val align: TextAlign,
    val gapAbove: Dp,
    val dimAlpha: Float,
    /** 折行档:null = 沿用主行呈现行数;第二行自身的辅助行传第二行呈现行数(实机同源)。 */
    val preferredLines: Int? = null
)

/**
 * 主页预览的歌词主体渲染:行布局来自共享 LyricLayoutEngine(与实机断行一致),
 * 绘制委托 LyricGlowRenderer(实机 AOD/锁屏同源)—— dim 底、光晕、扫光带(缓动/
 * 光带占比/渐变 stops)全部单点定义,预览即实机效果。进度为演示扫光(0→1 循环)。
 * 演示行循环切换时,三段式过渡帧取自共享纯函数 [lineTransitionExitFrame] /
 * [lineTransitionMoveFrame] / [lineTransitionEnterFrame](与实机同源),层变换由
 * graphicsLayer 施加,预览不另写动画公式;线性时间轴先过缓动再查帧,与
 * AodLyricCanvasView 完全一致。时长按「动画速率」档缩放(见 [lineTransitionTimeline]),
 * 与实机同一速率语义。
 */
@Composable
private fun PreviewAnimatedRowBlock(
    block: PreviewRowBlock,
    lineTransition: String,
    lineTransitionSpeed: String,
    color: ComposeColor,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
    fillMode: String,
    rubyColor: ComposeColor,
    regularTypeface: Typeface,
    availableWidthPx: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    modifier: Modifier = Modifier
) {
    // 逐条演示行播放时,进度从 0 扫到 1,驱动主行扫光。
    val progress = remember { Animatable(0f) }
    LaunchedEffect(block.main) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(DEMO_LINE_SWITCH_MS.toInt(), easing = LinearEasing))
    }
    // 在组合作用域读取进度,保证每次动画变化都会重绘 Canvas。
    val progressValue = progress.value

    // 换行动画演示:严格序列 退场 → 晋级位移 → 入场,任一时刻至多一段在播(与实机同构);
    // 仅重排(字体/字号/宽度)不触发过渡,退场冻结的是切换前实际可见的行块。
    var settledText by remember { mutableStateOf(block.mainText) }
    var stableBlock by remember { mutableStateOf(block) }
    var exitingBlock by remember { mutableStateOf<PreviewRowBlock?>(null) }
    val enterFrameProgress = remember { Animatable(1f) }
    val exitFrameProgress = remember { Animatable(1f) }
    val moveFrameProgress = remember { Animatable(1f) }
    SideEffect {
        if (block.mainText == settledText) stableBlock = block
    }
    LaunchedEffect(block.mainText) {
        if (block.mainText == settledText) return@LaunchedEffect
        val previous = stableBlock
        settledText = block.mainText
        if (lineTransition == "None") {
            exitingBlock = null
            enterFrameProgress.snapTo(1f)
            exitFrameProgress.snapTo(1f)
            moveFrameProgress.snapTo(1f)
        } else {
            // 角色分流与实机同源:旧「下一行」文本 == 新「主行」文本时晋级(内容延续)。
            val promoting = lineTransitionPromotes(previous.nextLine?.row?.text ?: "", block.mainText)
            val timeline = lineTransitionTimeline(lineTransition, lineTransitionSpeed, promoting)
            exitingBlock = previous
            enterFrameProgress.snapTo(0f)
            exitFrameProgress.snapTo(0f)
            moveFrameProgress.snapTo(0f)
            coroutineScope {
                launch {
                    exitFrameProgress.animateTo(
                        1f,
                        tween(timeline.exitMs.toInt(), easing = LinearEasing)
                    )
                }
                // 严格序列:退场完成 → 晋级位移 → 入场,无重叠(历史档此前叠加播放是
                // 旧行未走完新行已进场、同一句歌词两层各画一次的重叠根因)。
                delay(timeline.exitMs)
                if (timeline.moveMs > 0L) {
                    moveFrameProgress.animateTo(
                        1f,
                        tween(timeline.moveMs.toInt(), easing = LinearEasing)
                    )
                } else {
                    moveFrameProgress.snapTo(1f)
                }
                enterFrameProgress.animateTo(
                    1f,
                    tween(timeline.enterMs.toInt(), easing = LinearEasing)
                )
            }
            exitingBlock = null
        }
    }
    // 与实机 drawOrientedContent 同一顺序:线性进度 → 缓动 → 帧配方;参考档位移以行块
    // 自身宽高为基准(Fade 族 1/4、Slide 族整宽):宽度取可用内容宽(行块横向铺满),
    // 高度取该层参与过渡的行组实测高——退场层 = 旧行组(主行+辅助文字)、入场层 =
    // 新到行组(辅助文字+下一行),与实机各层行盒边界同义。实测高由层内上报,按演示行
    // 文本键控(行块实例每次组合都可能重建,不能按实例键控);首帧尚未测量时回落主行块高。
    val density = LocalDensity.current
    val previous = exitingBlock
    val promoting = lineTransitionPromotes(previous?.nextLine?.row?.text ?: "", block.mainText)
    var exitMainHeightPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    var exitRowsHeightPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    var exitNextHeightPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    var enterMainHeightPx by remember(block.mainText) { mutableStateOf(0) }
    var enterRowsHeightPx by remember(block.mainText) { mutableStateOf(0) }
    var enterNextHeightPx by remember(block.mainText) { mutableStateOf(0) }
    var nextRowTopPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    var exitNextLineRowHeightPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    val mainBlockHeightDp = with(density) { block.main.blockHeight.toDp().value }
    val exitMainBlockHeightDp = with(density) { (previous ?: block).main.blockHeight.toDp().value }
    val blockWidthDp = with(density) { availableWidthPx.toDp().value }
    // 退场层行组 = 主行+辅助文字(晋级时不含下一行,内容延续由位移层接管);未晋级整块同层。
    val exitGroupHeightPx = exitMainHeightPx + exitRowsHeightPx +
        (if (promoting) 0 else exitNextHeightPx)
    val exitGroupHeightDp = with(density) {
        if (exitGroupHeightPx > 0) exitGroupHeightPx.toDp().value else exitMainBlockHeightDp
    }
    // 入场层行组 = 辅助文字+下一行(晋级时不含主行,主行由位移层接管);未晋级整块同层。
    val enterGroupHeightPx = enterRowsHeightPx + enterNextHeightPx +
        (if (promoting) 0 else enterMainHeightPx)
    val enterGroupHeightDp = with(density) {
        if (enterGroupHeightPx > 0) enterGroupHeightPx.toDp().value else mainBlockHeightDp
    }
    val exitFrame = lineTransitionExitFrame(
        lineTransition,
        lineTransitionExitEasing(lineTransition, exitFrameProgress.value),
        blockWidthDp,
        exitGroupHeightDp
    )
    val enterFrame = lineTransitionEnterFrame(
        lineTransition,
        lineTransitionEnterEasing(lineTransition, enterFrameProgress.value),
        blockWidthDp,
        enterGroupHeightDp
    )
    val moveFrame = lineTransitionMoveFrame(
        moveTransitionEase(moveFrameProgress.value),
        sizeRatio = with(density) {
            val fromSize = previous?.nextLine?.row?.size?.toPx() ?: block.main.paint.textSize
            block.main.paint.textSize / fromSize
        },
        fromAlpha = previous?.nextLine?.dimAlpha ?: staticNextLineTextFactor()
    )
    // 角色分流(与实机同构):退场层画离场行组[主行+辅助文字]、下一行只占位(退场段原地
    // 保持、位移段起隐藏);入场层画新到行组[辅助文字+下一行]、主行只占位(由位移层绘制)。
    val exitFrameParts = if (promoting) {
        setOf(PreviewRowPart.MAIN, PreviewRowPart.ROWS)
    } else {
        PreviewRowPart.entries.toSet()
    }
    val exitHiddenParts = if (promoting && moveFrameProgress.value > 0f) {
        setOf(PreviewRowPart.NEXT)
    } else {
        emptySet()
    }
    val enterFrameParts = if (promoting) {
        setOf(PreviewRowPart.ROWS, PreviewRowPart.NEXT)
    } else {
        PreviewRowPart.entries.toSet()
    }
    // 晋级时新主行的辅助行(内容延续组)由晋级层呈现:入场层占位不绘制,
    // 新到行组 = 新下一行及其辅助行(与实机入场层口径一致)。
    val enterHiddenParts = if (promoting) {
        setOf(PreviewRowPart.MAIN, PreviewRowPart.ROWS)
    } else {
        emptySet()
    }

    Box(modifier.fillMaxWidth()) {
        // 段1 退场层:离场行组(主行+辅助文字)按退场半段离场,晋级时旧「下一行」原地保持。
        // 晋级档在退场结束、位移尚未推进的交接帧继续绘制下一行(alpha 已 0 的主行组不可见),
        // 避免「退场层已移除、位移层未起笔」的空白帧。
        if (previous != null &&
            (exitFrame.alpha > 0f || (promoting && moveFrameProgress.value <= 0f))
        ) {
            PreviewRowBlockLayer(
                block = previous,
                sweepProgress = 1f,
                frame = exitFrame,
                frameParts = exitFrameParts,
                hiddenParts = exitHiddenParts,
                color = color,
                glowColor = glowColor,
                glowEnabled = glowEnabled,
                fillMode = fillMode,
                rubyColor = rubyColor,
                regularTypeface = regularTypeface,
                availableWidthPx = availableWidthPx,
                wrap = wrap,
                adaptiveSectioning = adaptiveSectioning,
                onPartHeightPx = { part, height ->
                    when (part) {
                        PreviewRowPart.MAIN -> exitMainHeightPx = height
                        PreviewRowPart.ROWS -> exitRowsHeightPx = height
                        PreviewRowPart.NEXT -> exitNextHeightPx = height
                    }
                },
                onNextRowTopPx = { nextRowTopPx = it },
                onNextLineRowHeightPx = { exitNextLineRowHeightPx = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
        // 段2 晋级位移层:新「主行」(即旧「下一行」内容)自旧槽位平移+放大+亮度接续。
        if (promoting && previous != null && moveFrameProgress.value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = moveFrame.alpha
                        translationY = nextRowTopPx * moveFrame.translateFraction
                        scaleX = moveFrame.scale
                        scaleY = moveFrame.scale
                    }
            ) {
                PreviewMainLayer(
                    layout = block.main,
                    progress = progressValue,
                    color = color,
                    glowColor = glowColor,
                    glowEnabled = glowEnabled,
                    fillMode = fillMode,
                    rubyColor = rubyColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { block.main.blockHeight.toDp() })
                )
            }
            // 第二行辅助行随晋级自旧槽位平移至新主行的辅助槽位(与实机 drawPromotedAuxLayer
            // 同语义:第二行辅助文字的换行动画跟随第二行歌词);不随主行缩放、恒定辅助亮度。
            if (previous.nextRows.isNotEmpty() && exitNextLineRowHeightPx > 0) {
                val auxDisplacementPx = (
                    enterMainHeightPx - nextRowTopPx - exitNextLineRowHeightPx
                    ).toFloat()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = -auxDisplacementPx * moveFrame.translateFraction
                        }
                ) {
                    Column {
                        Spacer(
                            Modifier.height(
                                with(density) { block.main.blockHeight.toDp() }
                            )
                        )
                        previous.nextRows.forEach { aux ->
                            PreviewSecondaryRow(
                                row = aux.row,
                                color = aux.color,
                                typeface = regularTypeface,
                                availableWidthPx = availableWidthPx,
                                preferredLines = aux.preferredLines ?: block.main.lines.size,
                                wrap = wrap,
                                adaptiveSectioning = adaptiveSectioning,
                                textAlign = aux.align,
                                modifier = Modifier.padding(top = aux.gapAbove)
                            )
                        }
                    }
                }
            }
        }
        // 段3 入场层:新到行(新辅助文字+新下一行)按入场半段进场;晋级时新「主行」
        // 由段2接管,本层只画新到行组。
        PreviewRowBlockLayer(
            block = block,
            sweepProgress = progressValue,
            frame = enterFrame,
            frameParts = enterFrameParts,
            hiddenParts = enterHiddenParts,
            color = color,
            glowColor = glowColor,
            glowEnabled = glowEnabled,
            fillMode = fillMode,
            rubyColor = rubyColor,
            regularTypeface = regularTypeface,
            availableWidthPx = availableWidthPx,
            wrap = wrap,
            adaptiveSectioning = adaptiveSectioning,
            onPartHeightPx = { part, height ->
                when (part) {
                    PreviewRowPart.MAIN -> enterMainHeightPx = height
                    PreviewRowPart.ROWS -> enterRowsHeightPx = height
                    PreviewRowPart.NEXT -> enterNextHeightPx = height
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 行块内可独立分帧的三段:主行 / 辅助文字行组 / 下一行行(与实机行角色一一对应)。 */
private enum class PreviewRowPart { MAIN, ROWS, NEXT }

/**
 * 行块单层绘制:主行 LyricGlowRow + 辅助文字行 + 下一行行,按 [frameParts]/[hiddenParts]
 * 逐段分流——[frameParts] 内的行段共用 [frame](alpha/位移/缩放/旋转一次施加);
 * [hiddenParts] 内的行段只占位不绘制(由其它层接管);其余行段恒等展示。
 * 各段实测高经 [onPartHeightPx] 上报,供调用方按角色组求和作为该层位移基准
 * (参考实现 target.getHeight()/4);下一行行顶经 [onNextRowTopPx] 上报,作为晋级位移起点。
 */
@Composable
private fun PreviewRowBlockLayer(
    block: PreviewRowBlock,
    sweepProgress: Float,
    frame: LineTransitionFrame,
    frameParts: Set<PreviewRowPart>,
    hiddenParts: Set<PreviewRowPart>,
    color: ComposeColor,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
    fillMode: String,
    rubyColor: ComposeColor,
    regularTypeface: Typeface,
    availableWidthPx: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    onPartHeightPx: (PreviewRowPart, Int) -> Unit = { _, _ -> },
    onNextRowTopPx: (Int) -> Unit = {},
    onNextLineRowHeightPx: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Column(modifier) {
        // 主行段。
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { onPartHeightPx(PreviewRowPart.MAIN, it.height) }
                .graphicsLayer { applyPartTransition(PreviewRowPart.MAIN, frame, frameParts, hiddenParts) }
        ) {
            PreviewMainLayer(
                layout = block.main,
                progress = sweepProgress,
                color = color,
                glowColor = glowColor,
                glowEnabled = glowEnabled,
                fillMode = fillMode,
                rubyColor = rubyColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { block.main.blockHeight.toDp() })
            )
        }
        // 辅助文字行组段。
        if (block.rows.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onPartHeightPx(PreviewRowPart.ROWS, it.height) }
                    .graphicsLayer { applyPartTransition(PreviewRowPart.ROWS, frame, frameParts, hiddenParts) }
            ) {
                Column(Modifier.fillMaxWidth()) {
                    block.rows.forEach { item ->
                        PreviewSecondaryRow(
                            row = item.row,
                            color = item.color,
                            typeface = regularTypeface,
                            availableWidthPx = availableWidthPx,
                            preferredLines = block.main.lines.size,
                            wrap = wrap,
                            adaptiveSectioning = adaptiveSectioning,
                            textAlign = item.align,
                            modifier = Modifier.padding(top = item.gapAbove)
                        )
                    }
                }
            }
        }
        // 下一行行段。
        block.nextLine?.let { item ->
            val gapPx = with(density) { item.gapAbove.toPx().roundToInt() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onPartHeightPx(PreviewRowPart.NEXT, it.height) }
                    // 上报下一行行内容顶(含 gapAbove 的行盒顶 + gap),作为晋级位移起点:
                    // 与被晋升行落位后的内容顶对齐,交接处不跳动。
                    .onGloballyPositioned { onNextRowTopPx(it.positionInParent().y.roundToInt() + gapPx) }
                    .graphicsLayer { applyPartTransition(PreviewRowPart.NEXT, frame, frameParts, hiddenParts) }
            ) {
                Column(Modifier.fillMaxWidth()) {
                    PreviewSecondaryRow(
                        row = item.row,
                        color = item.color,
                        typeface = regularTypeface,
                        availableWidthPx = availableWidthPx,
                        preferredLines = item.preferredLines ?: block.main.lines.size,
                        wrap = wrap,
                        adaptiveSectioning = adaptiveSectioning,
                        textAlign = item.align,
                        modifier = Modifier
                            .padding(top = item.gapAbove)
                            .onSizeChanged { onNextLineRowHeightPx(it.height) }
                    )
                    // 第二行歌词自身的辅助文字行(「显示第二行辅助文字」),同属 NEXT 段:
                    // 与下一行行一起进场/退场,晋级位移起点仍取下一行行顶。
                    block.nextRows.forEach { aux ->
                        PreviewSecondaryRow(
                            row = aux.row,
                            color = aux.color,
                            typeface = regularTypeface,
                            availableWidthPx = availableWidthPx,
                            preferredLines = aux.preferredLines ?: block.main.lines.size,
                            wrap = wrap,
                            adaptiveSectioning = adaptiveSectioning,
                            textAlign = aux.align,
                            modifier = Modifier.padding(top = aux.gapAbove)
                        )
                    }
                }
            }
        }
    }
}

/** graphicsLayer 作用域内按行段分流:隐藏段 alpha 置 0(占位),帧内段施加 [frame],其余恒等。 */
private fun GraphicsLayerScope.applyPartTransition(
    part: PreviewRowPart,
    frame: LineTransitionFrame,
    frameParts: Set<PreviewRowPart>,
    hiddenParts: Set<PreviewRowPart>
) {
    when {
        part in hiddenParts -> alpha = 0f
        part in frameParts -> {
            alpha = frame.alpha
            translationX = frame.translateXDp.dp.toPx()
            translationY = frame.translateYDp.dp.toPx()
            scaleX = frame.scale
            scaleY = frame.scale
            rotationZ = frame.rotationDeg
            rotationX = frame.rotationXDeg
            rotationY = frame.rotationYDeg
        }
    }
}

/**
 * 主歌词单层绘制:按 [layout] 行基线画 LyricGlowRow,发光/扫光委托 LyricGlowRenderer。
 * 换行过渡的 alpha/位移/缩放在行块层([PreviewRowBlockLayer])统一施加,
 * 主行不再单独套层,保证辅助文字与主行同层进退。
 */
@Composable
private fun PreviewMainLayer(
    layout: PreviewMainLayout,
    progress: Float,
    color: ComposeColor,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
    fillMode: String,
    rubyColor: ComposeColor,
    modifier: Modifier = Modifier
) {
    val glowArgb = glowColor.toArgb()
    val sungArgb = color.copy(alpha = 1f).toArgb()
    val rubyArgb = rubyColor.toArgb()
    Canvas(modifier) {
        // 注音先于主行绘制(与实机 drawOriginalGlowBlock 顺序一致):注音带位于主行基线
        // 上方,与主行字形不重叠;注音关闭时 placements 为空,无操作。
        if (layout.rubyLines.any { it.placements.isNotEmpty() }) {
            layout.rubyPaint.color = rubyArgb
            drawIntoCanvas { canvas ->
                layout.rubyLines.forEach { rubyLine ->
                    if (rubyLine.placements.isEmpty()) return@forEach
                    rubyLine.placements.forEach { placement ->
                        canvas.nativeCanvas.drawText(
                            placement.reading,
                            rubyLine.startX + placement.rubyCenterX,
                            rubyLine.baseline,
                            layout.rubyPaint
                        )
                    }
                }
            }
        }
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
                progress = progress,
                sungColor = sungArgb,
                glowColor = glowArgb,
                glowEnabled = glowEnabled,
                fillMode = fillMode
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
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

