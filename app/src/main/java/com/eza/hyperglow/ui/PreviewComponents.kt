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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import com.eza.hyperglow.root.aod.enterTransitionMs
import com.eza.hyperglow.root.aod.exitTransitionMs
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
import com.eza.hyperglow.root.aod.lineStartX
import com.eza.hyperglow.root.aod.lineTransitionEnterFrame
import com.eza.hyperglow.root.aod.lineTransitionExitFrame
import com.eza.hyperglow.root.aod.metadataTextSizeSp
import com.eza.hyperglow.root.aod.nextLineTextSizeSp
import com.eza.hyperglow.root.aod.originalRowHeight
import com.eza.hyperglow.root.aod.metadataTextSizeSp
import com.eza.hyperglow.root.aod.nextLineTextSizeSp
import com.eza.hyperglow.root.aod.resolveAodPalette
import com.eza.hyperglow.root.aod.resolveRowAlignmentMode
import com.eza.hyperglow.root.aod.secondaryReadingTextSizeSp
import com.eza.hyperglow.root.aod.secondaryTranslationTextSizeSp
import com.eza.hyperglow.root.aod.secondLineColorArgb
import com.eza.hyperglow.root.aod.SecondLinePresentation
import com.eza.hyperglow.root.aod.secondLinePresentation
import com.eza.hyperglow.root.aod.staticNextLineTextFactor
import com.eza.hyperglow.root.aod.staticSecondaryTextFactor
import com.eza.hyperglow.root.aod.steadyTextAlpha
import com.eza.hyperglow.root.aod.textSizeModeMultiplier
import com.eza.hyperglow.root.aod.isSequentialLineTransition
import com.eza.hyperglow.root.aod.lineTransitionEnterEasing
import com.eza.hyperglow.root.aod.lineTransitionExitEasing
import com.eza.hyperglow.root.lockscreen.cardColorRgb
import com.eza.hyperglow.root.projection.LyricSnapshot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile),
    modifier: Modifier = Modifier
) {
    val live = collectLiveSnapshot(metadataParts, metadataSeparator)
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
    val snapshot = live ?: collectDemoSnapshot(scenario, metadataParts, metadataSeparator)
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
                            regularTypeface, availablePx, metadataAlign,
                            previewArtwork,
                            Modifier.padding(bottom = METADATA_LYRIC_GAP_DP.dp)
                        )
                    }
                    // 行块(主行+辅助文字+下一行)整体参与换行动画:旧行块 exit 帧退场、
                    // 新行块 enter 帧进场,与实机 drawRows 单层语义一致,辅助文字随主行换行。
                    val blockRows = ArrayList<PreviewBlockRow>(secondaryRows.size + 1)
                    secondaryRows.forEach { row ->
                        blockRows += PreviewBlockRow(
                            row = row,
                            color = secondaryColor,
                            align = textAlign,
                            gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp
                        )
                    }
                    // 下一行歌词呈现与实机同源(secondLinePresentation):「辅助文字显示第二行歌词」
                    // 开启时以辅助文字样式(音标行字号公式+亮度档)绘制并取代独立下一行行,
                    // 颜色仍走「下一行颜色」(secondLineColorArgb)。
                    when (secondLinePresentation(
                        profile.secondaryNextLine,
                        showNext,
                        snapshot.nextLine.isNotBlank()
                    )) {
                        SecondLinePresentation.AS_SECONDARY -> blockRows += PreviewBlockRow(
                            row = PreviewSecondaryLine(
                                snapshot.nextLine,
                                secondaryReadingTextSizeSp(baseSp).sp,
                                italic = false
                            ),
                            color = nextLineSecondaryColor,
                            align = nextLineAlign,
                            gapAbove = ROW_GAP_BEFORE_NEXT_LINE_DP.dp
                        )
                        SecondLinePresentation.STANDALONE -> blockRows += PreviewBlockRow(
                            row = PreviewSecondaryLine(
                                snapshot.nextLine,
                                nextLineTextSizeSp().sp,
                                italic = false
                            ),
                            color = nextLineColor,
                            align = nextLineAlign,
                            gapAbove = ROW_GAP_BEFORE_NEXT_LINE_DP.dp
                        )
                        SecondLinePresentation.NONE -> Unit
                    }
                    PreviewAnimatedRowBlock(
                        block = PreviewRowBlock(mainLayout, snapshot.original, blockRows),
                        lineTransition = resolveLineTransition(profile.lineTransition, "Fade up"),
                        lineTransitionSpeed = profile.lineTransitionSpeed,
                        color = lyricColor,
                        glowColor = glowColor,
                        glowEnabled = profile.glow == "On",
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
 * 行块一代内容:主行 + 辅助文字行 + 下一行行。换行过渡以整块为单位冻结/进出,
 * 与实机 drawRows「一块图层包全部行」同语义 —— 辅助文字随主行一起换行。
 */
private class PreviewRowBlock(
    val main: PreviewMainLayout,
    val mainText: String,
    val rows: List<PreviewBlockRow>
)

/** 行块内副行(辅助文字/下一行)的渲染参数,随所属行块一起冻结。 */
private class PreviewBlockRow(
    val row: PreviewSecondaryLine,
    val color: ComposeColor,
    val align: TextAlign,
    val gapAbove: Dp
)

/**
 * 主页预览的歌词主体渲染:行布局来自共享 LyricLayoutEngine(与实机断行一致),
 * 绘制委托 LyricGlowRenderer(实机 AOD/锁屏同源)—— dim 底、光晕、扫光带(缓动/
 * 光带占比/渐变 stops)全部单点定义,预览即实机效果。进度为演示扫光(0→1 循环)。
 * 演示行循环切换时,旧行块/新行块的过渡帧取自共享纯函数 [lineTransitionExitFrame] /
 * [lineTransitionEnterFrame](与实机同源),层变换由 graphicsLayer 施加,预览不另写动画公式;
 * 线性时间轴先过 #68 缓动再查帧,与 AodLyricCanvasView 完全一致。时长按「动画速率」档
 * 缩放(见 [enterTransitionMs] / [exitTransitionMs]),与实机同一速率语义。
 */
@Composable
private fun PreviewAnimatedRowBlock(
    block: PreviewRowBlock,
    lineTransition: String,
    lineTransitionSpeed: String,
    color: ComposeColor,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
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

    // 换行动画演示:演示行文本变化时冻结旧行块按 exit 帧退场、新行块按 enter 帧进场。
    // 帧配方与速率语义来自 root.aod 共享实现,与 AodLyricCanvasView 同源;
    // 仅重排(字体/字号/宽度)不触发过渡,退场冻结的是切换前实际可见的行块。
    var settledText by remember { mutableStateOf(block.mainText) }
    var stableBlock by remember { mutableStateOf(block) }
    var exitingBlock by remember { mutableStateOf<PreviewRowBlock?>(null) }
    val enterFrameProgress = remember { Animatable(1f) }
    val exitFrameProgress = remember { Animatable(1f) }
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
        } else {
            val exitMs = exitTransitionMs(lineTransition, lineTransitionSpeed)
            val enterMs = enterTransitionMs(lineTransition, lineTransitionSpeed)
            exitingBlock = previous
            enterFrameProgress.snapTo(0f)
            exitFrameProgress.snapTo(0f)
            coroutineScope {
                launch {
                    exitFrameProgress.animateTo(
                        1f,
                        tween(exitMs.toInt(), easing = LinearEasing)
                    )
                }
                // 参考 HyperLyric 序列档:退场完成后再播入场(与实机 enterElapsed 同语义);
                // 历史档退场/入场叠加,无延迟。
                if (isSequentialLineTransition(lineTransition)) delay(exitMs)
                enterFrameProgress.animateTo(
                    1f,
                    tween(enterMs.toInt(), easing = LinearEasing)
                )
            }
            exitingBlock = null
        }
    }
    // 与实机 drawOrientedContent 同一顺序:线性进度 → 缓动 → 帧配方;参考档位移以行块
    // 宽高为基准(高度取主行块高度近似,实机为内容裁剪框高)。
    val blockWidthDp = with(LocalDensity.current) { availableWidthPx.toDp().value }
    val blockHeightDp = with(LocalDensity.current) { block.main.blockHeight.toDp().value }
    val exitFrame = lineTransitionExitFrame(
        lineTransition,
        lineTransitionExitEasing(lineTransition, exitFrameProgress.value),
        blockWidthDp,
        blockHeightDp
    )
    val enterFrame = lineTransitionEnterFrame(
        lineTransition,
        lineTransitionEnterEasing(lineTransition, enterFrameProgress.value),
        blockWidthDp,
        blockHeightDp
    )

    Box(modifier.fillMaxWidth()) {
        val previous = exitingBlock
        if (previous != null && exitFrame.alpha > 0f) {
            PreviewRowBlockLayer(
                block = previous,
                sweepProgress = 1f,
                frame = exitFrame,
                color = color,
                glowColor = glowColor,
                glowEnabled = glowEnabled,
                regularTypeface = regularTypeface,
                availableWidthPx = availableWidthPx,
                wrap = wrap,
                adaptiveSectioning = adaptiveSectioning,
                modifier = Modifier.fillMaxWidth()
            )
        }
        PreviewRowBlockLayer(
            block = block,
            sweepProgress = progressValue,
            frame = enterFrame,
            color = color,
            glowColor = glowColor,
            glowEnabled = glowEnabled,
            regularTypeface = regularTypeface,
            availableWidthPx = availableWidthPx,
            wrap = wrap,
            adaptiveSectioning = adaptiveSectioning,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 行块单层绘制:主行 LyricGlowRow + 辅助文字行 + 下一行行,整块共用一个 graphicsLayer
 * (alpha/位移/缩放一次施加,缩放锚点默认块中心,与实机 drawRows 的内容框中心一致)。
 * 旧行块按 exit 帧、新行块按 enter 帧各自成层叠加,与实机同构。
 */
@Composable
private fun PreviewRowBlockLayer(
    block: PreviewRowBlock,
    sweepProgress: Float,
    frame: LineTransitionFrame,
    color: ComposeColor,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
    regularTypeface: Typeface,
    availableWidthPx: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.graphicsLayer {
            alpha = frame.alpha
            translationX = frame.translateXDp.dp.toPx()
            translationY = frame.translateYDp.dp.toPx()
            scaleX = frame.scale
            scaleY = frame.scale
            rotationZ = frame.rotationDeg
            rotationX = frame.rotationXDeg
            rotationY = frame.rotationYDeg
        }
    ) {
        val density = LocalDensity.current
        PreviewMainLayer(
            layout = block.main,
            progress = sweepProgress,
            color = color,
            glowColor = glowColor,
            glowEnabled = glowEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { block.main.blockHeight.toDp() })
        )
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
    modifier: Modifier = Modifier
) {
    val glowArgb = glowColor.toArgb()
    val sungArgb = color.copy(alpha = 1f).toArgb()
    Canvas(modifier) {
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
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

