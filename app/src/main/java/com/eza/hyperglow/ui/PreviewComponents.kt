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
import com.eza.hyperglow.root.aod.AodCanvasWord
import com.eza.hyperglow.root.aod.alignMissingWordOffsets
import com.eza.hyperglow.root.aod.edgeSafeAlignedStart
import com.eza.hyperglow.root.aod.LineTransitionFrame
import com.eza.hyperglow.root.aod.LyricGlowRenderer
import com.eza.hyperglow.root.aod.LyricGlowRow
import com.eza.hyperglow.root.aod.LyricLayoutLine
import com.eza.hyperglow.root.aod.LyricLayoutResult
import com.eza.hyperglow.root.aod.LyricLayoutTextLine
import com.eza.hyperglow.root.aod.LyricTypefaceResolver
import com.eza.hyperglow.root.aod.LYRIC_LINE_EXTRA_HEIGHT_DP
import com.eza.hyperglow.root.aod.LYRIC_LINE_GAP_DP
import com.eza.hyperglow.root.aod.LYRIC_WORD_GAP_DP
import com.eza.hyperglow.root.aod.MAX_SECONDARY_LAYOUT_LINES
import com.eza.hyperglow.root.aod.METADATA_LYRIC_GAP_DP
import com.eza.hyperglow.root.aod.ROW_GAP_BEFORE_NEXT_LINE_DP
import com.eza.hyperglow.root.aod.ROW_GAP_BEFORE_ORIGINAL_DP
import com.eza.hyperglow.root.aod.ROW_GAP_BEFORE_SECONDARY_DP
import com.eza.hyperglow.root.aod.ARTWORK_SPIN_PERIOD_MS
import com.eza.hyperglow.root.aod.artworkLeadingPx
import com.eza.hyperglow.root.aod.artworkSidePx
import com.eza.hyperglow.root.aod.baseTextSizeSp
import com.eza.hyperglow.root.aod.duetAlignedRight
import com.eza.hyperglow.root.aod.duetRowTransitionTimeline
import com.eza.hyperglow.root.aod.layoutMetadataLines
import com.eza.hyperglow.root.aod.isLongKaraokeSyllable
import com.eza.hyperglow.root.aod.karaokeFloatSinkPx
import com.eza.hyperglow.root.aod.KaraokeWordRun
import com.eza.hyperglow.root.aod.layoutOriginalLines
import com.eza.hyperglow.root.aod.layoutSecondaryLines
import com.eza.hyperglow.root.aod.LyricWordKaraokeRenderer
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
import com.eza.hyperglow.root.aod.resolvedLyricLayoutLineLimit
import com.eza.hyperglow.root.aod.OriginalLinePath
import com.eza.hyperglow.root.aod.planDuetRow
import com.eza.hyperglow.root.aod.planOriginalLine
import com.eza.hyperglow.root.aod.projectedWordsHaveTimedWindows
import com.eza.hyperglow.root.aod.resolveRowAlignmentMode
import com.eza.hyperglow.root.aod.rubyReservation
import com.eza.hyperglow.root.aod.rubySpanGeometry
import com.eza.hyperglow.root.aod.rubyTextSizePx
import com.eza.hyperglow.root.aod.safeSecondaryLineHeight
import com.eza.hyperglow.root.aod.lineStartX
import com.eza.hyperglow.root.aod.hasFirstLineAuxText
import com.eza.hyperglow.root.aod.harmonyTimedSegments
import com.eza.hyperglow.root.aod.karaokeUnitEnd
import com.eza.hyperglow.root.aod.FittedSecondaryLines
import com.eza.hyperglow.root.aod.fittedSecondaryLines
import com.eza.hyperglow.root.aod.secondaryReadingTextSizeSp
import com.eza.hyperglow.root.aod.secondarySizeFloorSp
import com.eza.hyperglow.root.aod.secondaryTranslationTextSizeSp
import com.eza.hyperglow.root.aod.secondaryTimedVisualRanges
import com.eza.hyperglow.root.aod.secondLineColorArgb
import com.eza.hyperglow.root.aod.SecondLinePresentation
import com.eza.hyperglow.root.aod.SecondLineAuxRow
import com.eza.hyperglow.root.aod.secondLineAuxPreferredLines
import com.eza.hyperglow.root.aod.secondLineAuxRows
import com.eza.hyperglow.root.aod.secondLinePresentation
import com.eza.hyperglow.root.aod.shouldStartDuetRowTransition
import com.eza.hyperglow.root.aod.staticNextLineTextFactor
import com.eza.hyperglow.root.aod.staticSecondaryTextFactor
import com.eza.hyperglow.root.aod.steadyTextAlpha
import com.eza.hyperglow.root.aod.syntheticCharTimeWindow
import com.eza.hyperglow.root.aod.syntheticKaraokeBlocks
import com.eza.hyperglow.root.aod.textSizeModeMultiplier
import com.eza.hyperglow.root.aod.timedWordProgress
import com.eza.hyperglow.root.aod.visualExtents
import com.eza.hyperglow.root.aod.END_EDGE_SAFETY_DP
import com.eza.hyperglow.root.aod.lineTransitionEnterEasing
import com.eza.hyperglow.root.aod.lineTransitionExitEasing
import com.eza.hyperglow.root.lockscreen.cardColorRgb
import com.eza.hyperglow.root.projection.LyricDuetLine
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricWord
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 预览卡片高度自适应范围(dp):内容有多高卡片就多高。内容不足下限时保持卡片形,
 * 超过上限时封顶,防止极端字号组合把主页/设置页其余内容挤出屏幕。
 */
internal const val PREVIEW_CARD_MIN_HEIGHT_DP = 120f

internal const val PREVIEW_CARD_MAX_HEIGHT_DP = 420f

/**
 * 并发行加入淡入时长(ms):与实机 AodLyricCanvasView.DUET_JOIN_FADE_MS 同值同源(实机侧为
 * private,不能直接引用)。并发行内容键变化时整块淡入,淡入完成后恒全亮——这是并发行唯一的
 * 进场过渡:主行换行不带它,它自己换行时才播(任务 1「并发行相对主行独立」的预览侧同源)。
 */
private const val PREVIEW_DUET_JOIN_FADE_MS = 180L

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
    metadataSeparators: String,
    duetMarkers: Boolean = true,
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile),
    modifier: Modifier = Modifier
) {
    val live = collectLiveSnapshot(
        metadataParts,
        metadataSeparators,
        duetMarkers,
        profile.hideAlbumWhenSameAsTitle
    )
    LyricPreviewSurface(
        profile = profile,
        scenario = scenario,
        live = live,
        metadataParts = metadataParts,
        metadataSeparators = metadataSeparators,
        artwork = artwork,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Home merged lyric-widget preview card. Renders a phone-like dark surface for the selected
 * surface (text size/weight/alignment, secondary text, metadata, card background, next line)
 * placed via [resolvePreviewPlacement], so the home page gives a quick visual sense of how the
 * lockscreen / AOD lyric control looks. The header row carries two text-button chips to switch
 * between lockscreen and AOD, replacing the previous pair of side-by-side cards (overview
 * slim-down).
 *
 * The surface height adapts to the rendered content ([PREVIEW_CARD_MIN_HEIGHT_DP]..
 * [PREVIEW_CARD_MAX_HEIGHT_DP]): large text sizes and extra rows grow the card instead of being
 * clipped by a fixed box.
 */
@Composable
internal fun LyricPreviewCardWithSwitch(
    lockscreenProfile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    aodProfile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    lockscreenLive: LyricSnapshot?,
    aodLive: LyricSnapshot?,
    selectedAod: Boolean,
    onSelectSurface: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val profile = if (selectedAod) aodProfile else lockscreenProfile
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        if (selectedAod) R.string.label_aod_lyrics else R.string.label_lockscreen_lyrics
                    ),
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
                SurfaceChip(
                    label = stringResource(R.string.label_lockscreen_lyrics),
                    selected = !selectedAod,
                    onClick = { onSelectSurface(false) }
                )
                Spacer(Modifier.width(8.dp))
                SurfaceChip(
                    label = stringResource(R.string.label_aod_lyrics),
                    selected = selectedAod,
                    onClick = { onSelectSurface(true) }
                )
            }
            Spacer(Modifier.height(8.dp))
            LyricPreviewSurface(
                profile = profile,
                scenario = if (selectedAod) "Full AOD" else "Lockscreen · notifications",
                live = if (selectedAod) aodLive else lockscreenLive,
                metadataParts = profile.metadataParts,
                metadataSeparators = profile.metadataSeparators,
                artwork = artworkDisplayConfig(profile),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 预览面切换 chip:选中态用主色文本按钮,未选中用默认样式。 */
@Composable
private fun SurfaceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        TextButton(text = label, colors = ButtonDefaults.textButtonColorsPrimary(), onClick = onClick)
    } else {
        TextButton(text = label, onClick = onClick)
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
    metadataSeparators: String,
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile),
    modifier: Modifier = Modifier
) {
    // 有实时歌词时跟随最新快照;否则用循环播放的演示快照,让预览始终可见且持续更新。
    val snapshot = live ?: collectDemoSnapshot(
        metadataParts,
        metadataSeparators,
        profile.hideAlbumWhenSameAsTitle
    )
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
        PreviewArtwork(
            artwork.shape,
            artwork.spins,
            image = null,
            placeholder = true,
            adaptiveScale = artwork.adaptiveScale,
            sizeDp = artwork.sizeDp
        )
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
    // 主行渲染路径与实机同源:读共享决策函数 planOriginalLine(实机 drawRows 的共享扫光门 +
    // drawOriginal 分支树与预览三处只此一份)。此前预览自带一份 when,且判据是
    // `snapshot.lineLevelSync`,而快照该位在实机侧表示「行级同步显示态」(有活动行且非大
    // 元数据引导),预览侧旧值却是「无逐字时间」——逐字源被当成非行级同步,预览整块横扫,
    // 与实机(带行窗的逐字源走共享逐行扫光)不一致。带真实词窗的行压过行级标记:非 Minimal
    // 档一律词级卡拉OK(逐字源真实词窗 / BetterLyrics 行级源字符合成),不再被行级扫光门
    // 吞掉(真机日志 `anim=Gradient timed=true words=13 lineSync=true`)。
    val previewPlan = planOriginalLine(
        animationMode = profile.animation,
        timed = snapshot.words.any { it.text.isNotBlank() && it.endMs > it.startMs },
        lineLevelSync = snapshot.lineLevelSync,
        lineSyncFillMode = profile.lineSyncFillMode,
        lineStartMs = snapshot.lineStartMs,
        lineEndMs = snapshot.lineEndMs
    )
    val previewFillMode = previewPlan.fillMode
    // 词级卡拉OK路径(与实机 drawOriginal 的 WORD_KARAOKE 分支同义):带真实词窗的行,
    // 预览与实机都按词窗逐字渲染;行级源仍走共享扫光块。
    val previewWordKaraoke = previewPlan.path == OriginalLinePath.WORD_KARAOKE

    // 预览卡片高度自适应:面板高度贴合歌词内容(钳制见 previewCardHeightDp),大字号/多行
    // 内容不再被固定高度裁掉。高度取本配置下的已见最大内容高度——演示行循环/逐行播放时
    // 折行数变化,直接跟随会让卡片高度来回呼吸、推动下方内容上下跳动;配置或曲目变化时
    // remember 键重置,卡片随即重新随当前内容收缩。歌词块在面板内水平居中、垂直居中,
    // 忽略真实曲面上的时钟/通知等占位偏移——否则息屏(AOD)歌词会按真实布局被挤到面板
    // 顶部一小条,大字号下一行就被裁掉,看起来像被遮挡。
    val density = LocalDensity.current
    // sp→px 换算因子(与实机 scaledDensity 同式):自适应拟合必须按同一换算走,预览即实机。
    val scaledDensity = density.density * density.fontScale
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
                // 演示/实时快照的行窗跨度:合成源与辅助文字逐字效果的虚拟时间轴同取它,
                // 长音节判定(块时长 ≥700ms)与实机一致,快歌短词块同样不放大/不辉光。
                val demoLineSpanMs = (snapshot.lineEndMs - snapshot.lineStartMs)
                    .takeIf { it >= 100L } ?: 2_500L
                // 换行/测量全部委托 LyricLayoutEngine(与实机同源):断行点、行数上限、
                // Clip 语义一致;预览只负责卡片内的居中摆放。
                val mainLayout = remember(
                    snapshot.original, snapshot.words, snapshot.lineStartMs, snapshot.lineEndMs,
                    textSize, lyricTypeface, regularTypeface,
                    previewRuby, availablePx, profile.lyricLineLimit, profile.overflow,
                    profile.adaptiveSectioning, profile.animation, resolvedColors.unsungText,
                    previewWordKaraoke
                ) {
                    buildPreviewMainLayout(
                        text = snapshot.original,
                        textSizePx = with(density) { textSize.toPx() },
                        typeface = lyricTypeface,
                        rubyTypeface = regularTypeface,
                        ruby = previewRuby,
                        words = snapshot.words,
                        betterLyrics = profile.animation == "BetterLyrics",
                        wordKaraoke = previewWordKaraoke,
                        unsungColorArgb = resolvedColors.unsungText,
                        lineSpanMs = demoLineSpanMs,
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
                // 对唱并发行(与实机 buildRows 同源):本面「显示并发歌词(对唱)」开启且快照
                // 带非空并发行时,在**辅助文字行组之后**堆叠并发行车道(段序同实机 buildRows:
                // 主行 → 主行辅助行 → 并发行/和声行 → 下一行);在场时取代独立「下一行」行
                // (与 #82 的 anti-dup 同式,防下方拥挤)。
                // 和声行(harmony,插件行 role=BG 的 x-bg 回声)不走这一档:它常与主行同文,
                // 同款堆叠就是两条一样的大字行(真机 2026-10-07 反馈的错观感);改走辅助行
                // 车道,见下方 duetRows 的和声首行(与实机 buildRows 同源)。
                val duet = snapshot.duetLine?.takeIf {
                    previewDuetVisible(profile.duetConcurrent, it)
                }
                // 并发行内容键(与实机 duetLineKey 同口径):键不变 = 渲染原地保持,键变化才播
                // 自己的换行过渡——并发行不随主行换行移动的判据(见 PreviewRowBlock.duetKey)。
                val duetKey = duet?.let { previewDuetKey(it.text, it.lineStartMs) }
                // 并发行自己的行窗跨度:并发行辅助行的逐字效果与行级合成时间轴同取它
                // (实机 buildRows 的 duetAuxKaraokeWindow / buildDuetOriginalLayout 同源);
                // 窗口过短(实时源的异常行窗)或本行无并发行时退到演示行跨度。
                val duetSpanMs = duet?.let {
                    (it.lineEndMs - it.lineStartMs).takeIf { span -> span >= 100L }
                } ?: demoLineSpanMs
                // 并发行按自己的分侧解析对齐(与实机 alignmentFor(DUET_*) 同源),不随主行翻转。
                val duetTextAlign = previewRowTextAlign(
                    "auto",
                    profile.alignment,
                    duetAlignedRight(duet?.alignedRight ?: false, profile.duetAlignment)
                )
                // 并发行渲染路径与主行同一份决策([planDuetRow] → planOriginalLine):判据取并发行
                // **自己的**真实词窗(实机 buildDuetOriginalLayout 的 hasTimedWordWindows(duet.words)
                // 同源),不借主行的决策——主行无词窗而并发行有词窗时,预览此前会漏掉逐字推进。
                val duetWordKaraoke = duet != null && planDuetRow(
                    animationMode = profile.animation,
                    timed = projectedWordsHaveTimedWindows(duet.words),
                    lineLevelSync = snapshot.lineLevelSync,
                    lineSyncFillMode = profile.lineSyncFillMode,
                    lineStartMs = duet.lineStartMs,
                    lineEndMs = duet.lineEndMs
                ).path == OriginalLinePath.WORD_KARAOKE
                val duetLayout = remember(
                    duet, duetTextAlign, duetWordKaraoke, duetSpanMs, textSize, lyricTypeface,
                    regularTypeface, availablePx, profile.lyricLineLimit, profile.overflow,
                    profile.adaptiveSectioning, profile.animation, resolvedColors.unsungText
                ) {
                    if (duet == null || duet.harmony) {
                        null
                    } else {
                        buildPreviewMainLayout(
                            text = duet.text,
                            textSizePx = with(density) { textSize.toPx() },
                            typeface = lyricTypeface,
                            rubyTypeface = regularTypeface,
                            // 并发行 v1 不携带注音(与实机 buildDuetOriginalLayout 同口径)。
                            ruby = emptyList(),
                            words = duet.words,
                            betterLyrics = profile.animation == "BetterLyrics",
                            wordKaraoke = duetWordKaraoke,
                            unsungColorArgb = resolvedColors.unsungText,
                            // 行级源的字符合成时间轴取并发行自己的行窗(与实机并发行扫光同拍)。
                            lineSpanMs = duetSpanMs,
                            // 换行上限与实机 buildDuetOriginalLayout 同源:min(档位解析值, 2)
                            // ——并发行双行封顶,防内容块失控。
                            lineLimit = resolvedLyricLayoutLineLimit(
                                profile.lyricLineLimit,
                                duet.text.length,
                                duet.words.size
                            ).coerceAtMost(2),
                            availableWidthPx = availablePx.toFloat(),
                            wrap = profile.overflow == "Wrap",
                            adaptiveSectioning = profile.adaptiveSectioning,
                            alignment = when (duetTextAlign) {
                                TextAlign.Center -> "center"
                                TextAlign.End -> "end"
                                else -> "start"
                            },
                            density = density.density
                        )
                    }
                }
                // 并发行自己的辅助文字行(音标/翻译):文本为空不构造该行(与实机 buildRows 的
                // isNotBlank 门控同源);颜色/亮度档/间隔与主行辅助行一致。
                // 和声行走辅助行车道:和声文本本身占首行(与实机 buildRows 的 ROMANIZED 行同源),
                // 其音标/翻译不再单独出——和声多是主行文本的回声,那两份与主行的辅助行重复。
                val duetRows = if (duet == null) {
                    emptyList()
                } else if (duet.harmony) {
                    // 和声行走辅助行车道:字号同样吃辅助字号设置与自适应(与实机 buildRows 同源)。
                    val harmonyRow = PreviewSecondaryLine(
                        duet.text,
                        secondaryReadingTextSizeSp(baseSp, profile.secondaryTextSizePercent).sp,
                        italic = false
                    )
                    listOf(
                        PreviewBlockRow(
                            row = previewFittedSecondaryRow(
                                harmonyRow,
                                profile,
                                baseSp,
                                mainLayout.lines.size,
                                availablePx,
                                regularTypeface,
                                scaledDensity
                            ),
                            color = secondaryColor,
                            // 和声是主行自己的辅助车道,对齐随主行(与实机 alignmentFor 的非
                            // DUET_* 行同源),不按并发行分侧翻转。
                            align = textAlign,
                            gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp,
                            dimAlpha = staticSecondaryTextFactor(profile.secondaryTextBright),
                            // 换行档取主行行数:和声多是主行文本的回声(实机 wrapSecondaryText
                            // 同传 originalLayout.lineCount)。
                            preferredLines = mainLayout.lines.size,
                            karaoke = profile.secondaryWordKaraoke,
                            // 和声自己的逐字词表(插件逐音节下发):逐字效果按真实词窗点亮,
                            // 与实机 buildRows 的 harmonyTimedLines 共用同一判据函数。
                            words = duet.words
                        )
                    )
                } else {
                    val showReading = profile.secondaryMode == "Transliteration" ||
                        profile.secondaryMode == "Both"
                    val showTranslation = profile.secondaryMode == "Translation" ||
                        profile.secondaryMode == "Both"
                    listOfNotNull(
                        duet.romanized.takeIf { showReading && it.isNotBlank() }?.let {
                            PreviewBlockRow(
                                row = previewFittedSecondaryRow(
                                    PreviewSecondaryLine(
                                        it,
                                        secondaryReadingTextSizeSp(
                                            baseSp,
                                            profile.secondaryTextSizePercent
                                        ).sp,
                                        italic = false
                                    ),
                                    profile,
                                    baseSp,
                                    duetLayout?.lines?.size ?: mainLayout.lines.size,
                                    availablePx,
                                    regularTypeface,
                                    scaledDensity
                                ),
                                color = secondaryColor,
                                align = duetTextAlign,
                                gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp,
                                dimAlpha = staticSecondaryTextFactor(profile.secondaryTextBright),
                                // 并发行辅助行同样吃「辅助文字逐字效果」(实机 buildRows 的
                                // duetAuxKaraokeWindow 同源),窗口取并发行自己的行窗。
                                karaoke = profile.secondaryWordKaraoke
                            )
                        },
                        duet.translated.takeIf { showTranslation && it.isNotBlank() }?.let {
                            PreviewBlockRow(
                                row = previewFittedSecondaryRow(
                                    PreviewSecondaryLine(
                                        it,
                                        secondaryTranslationTextSizeSp(
                                            baseSp,
                                            profile.secondaryTextSizePercent
                                        ).sp,
                                        italic = true
                                    ),
                                    profile,
                                    baseSp,
                                    duetLayout?.lines?.size ?: mainLayout.lines.size,
                                    availablePx,
                                    regularTypeface,
                                    scaledDensity
                                ),
                                color = secondaryColor,
                                align = duetTextAlign,
                                gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp,
                                dimAlpha = staticSecondaryTextFactor(profile.secondaryTextBright),
                                karaoke = profile.secondaryWordKaraoke
                            )
                        }
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
                            // 自适应大小:字号可能被拟合缩小(与实机 buildRows 同源)。
                            row = previewFittedSecondaryRow(
                                row,
                                profile,
                                baseSp,
                                mainLayout.lines.size,
                                availablePx,
                                regularTypeface,
                                scaledDensity
                            ),
                            color = secondaryColor,
                            align = textAlign,
                            gapAbove = ROW_GAP_BEFORE_SECONDARY_DP.dp,
                            dimAlpha = staticSecondaryTextFactor(profile.secondaryTextBright),
                            // 辅助文字逐字效果只作用于第一行辅助文字行(见 PreviewRowBlock)。
                            karaoke = profile.secondaryWordKaraoke
                        )
                    }
                    // 下一行歌词呈现与实机同源(secondLinePresentation):「辅助文字显示第二行歌词」
                    // 开启且第一行辅助文字实际显示时以辅助文字样式(音标行字号公式+亮度档)绘制并
                    // 取代独立下一行行;第一行无辅助文字时该开关不产生呈现;「显示第二行辅助文字」
                    // 开启时第二行歌词行本身也按辅助文字形态呈现(以「显示下一行歌词」为前提)。
                    // 颜色仍走「下一行颜色」。
                    val nextPresentation = secondLinePresentation(
                        profile.secondaryNextLine,
                        showNext,
                        snapshot.nextLine.isNotBlank(),
                        hasFirstLineAuxText(
                            profile.secondaryMode,
                            snapshot.romanized,
                            snapshot.translated
                        ),
                        profile.nextLineAux
                    )
                    // 第二行歌词自身的呈现行数(与实机 buildRows 同序:先按主行行数作
                    // preferredLines 布局第二行,再取其实际行数),第二行辅助行的换行档
                    // 跟随它而不是主行行数(owner 2026-10-02 真机反馈);自适应开启时字号与
                    // 行数同取拟合结果,否则第二行辅助行的换行档会与实机错位。
                    val nextLineFit = if (nextPresentation == SecondLinePresentation.AS_SECONDARY) {
                        remember(
                            snapshot.nextLine,
                            baseSp,
                            profile.secondaryTextSizePercent,
                            profile.secondaryAutoSize,
                            regularTypeface,
                            availablePx,
                            profile.overflow,
                            profile.adaptiveSectioning,
                            mainLayout
                        ) {
                            val fitted = previewFittedSecondarySp(
                                text = snapshot.nextLine,
                                configuredSp = secondaryReadingTextSizeSp(
                                    baseSp,
                                    profile.secondaryTextSizePercent
                                ),
                                translation = false,
                                baseSp = baseSp,
                                preferredLines = mainLayout.lines.size,
                                availableWidthPx = availablePx,
                                wrap = profile.overflow == "Wrap",
                                adaptiveSectioning = profile.adaptiveSectioning,
                                typeface = regularTypeface,
                                autoSize = profile.secondaryAutoSize,
                                scaledDensity = scaledDensity
                            )
                            fitted.fittedSp.sp to fitted.lines.size
                        }
                    } else {
                        // 独立下一行行形态不走辅助字号链(固定 15sp),行数恒 1(实机 preferredLines=1)。
                        secondaryReadingTextSizeSp(baseSp, profile.secondaryTextSizePercent).sp to 1
                    }
                    val nextBlockRow = when (nextPresentation) {
                        SecondLinePresentation.AS_SECONDARY -> PreviewBlockRow(
                            row = PreviewSecondaryLine(
                                snapshot.nextLine,
                                nextLineFit.first,
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
                    // 第二行歌词自身的呈现行数取 [nextLineFit] 的折行结果(同一拟合字号,与实机
                    // buildRows 同源);独立下一行行形态恒 1(实机 preferredLines=1)。
                    val nextLineRenderedLines = nextLineFit.second
                    // 独立下一行行形态下第二行恒单行呈现(与实机 wrapSecondaryText
                    // preferredLines=1 同源),其辅助行换行档随之;辅助形态按第二行自身呈现行数。
                    val nextAuxPreferredLines = secondLineAuxPreferredLines(
                        if (nextPresentation == SecondLinePresentation.STANDALONE) {
                            1
                        } else {
                            nextLineRenderedLines
                        }
                    )
                    // 「显示第二行辅助文字」:第二行歌词行之后追加其自身的辅助文字行
                    // (行清单与实机同源,见 secondLineAuxRows;样式沿用辅助文字行;
                    // 辅助形态与独立下一行行形态都追加)。
                    val nextAuxRows =
                        if (nextPresentation != SecondLinePresentation.NONE) {
                            secondLineAuxRows(
                                profile.nextLineAux,
                                profile.secondaryMode,
                                snapshot.nextLineRomanized,
                                snapshot.nextLineTranslated
                            ).map { auxRow ->
                                when (auxRow) {
                                    SecondLineAuxRow.ROMANIZED -> PreviewBlockRow(
                                        row = previewFittedSecondaryRow(
                                            PreviewSecondaryLine(
                                                snapshot.nextLineRomanized,
                                                secondaryReadingTextSizeSp(
                                                    baseSp,
                                                    profile.secondaryTextSizePercent
                                                ).sp,
                                                italic = false
                                            ),
                                            profile,
                                            baseSp,
                                            nextAuxPreferredLines,
                                            availablePx,
                                            regularTypeface,
                                            scaledDensity
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
                                        row = previewFittedSecondaryRow(
                                            PreviewSecondaryLine(
                                                snapshot.nextLineTranslated,
                                                secondaryTranslationTextSizeSp(
                                                    baseSp,
                                                    profile.secondaryTextSizePercent
                                                ).sp,
                                                italic = true
                                            ),
                                            profile,
                                            baseSp,
                                            nextAuxPreferredLines,
                                            availablePx,
                                            regularTypeface,
                                            scaledDensity
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
                            // anti-dup:并发行在场时取代独立「下一行」行(与实机 buildRows 同式)。
                            nextLine = if (duet == null) nextBlockRow else null,
                            nextRows = if (duet == null) nextAuxRows else emptyList(),
                            // 辅助文字逐字效果:第一行辅助行按行块演示进度逐字点亮;亮度档沿用
                            // 「高亮辅助文字」(与实机 drawAuxKaraokeRow 同源),观感随逐字动画档。
                            auxKaraoke = if (profile.secondaryWordKaraoke) {
                                PreviewAuxKaraoke(
                                    spanMs = demoLineSpanMs,
                                    betterLyrics = profile.animation == "BetterLyrics",
                                    // 与静态辅助行同一亮度公式(实机 drawSecondaryLine 同源)。
                                    alphaFactor = previewSecondaryAlpha(
                                        profile.secondaryTextBright
                                    )
                                )
                            } else {
                                null
                            },
                            duet = duetLayout,
                            duetRows = duetRows,
                            // 并发行辅助行的逐字效果:跨度取并发行自己的行窗(实机
                            // duetAuxKaraokeWindow 同源),其余档位与主行辅助行一致。
                            duetAuxKaraoke = if (profile.secondaryWordKaraoke && duet != null) {
                                PreviewAuxKaraoke(
                                    spanMs = duetSpanMs,
                                    betterLyrics = profile.animation == "BetterLyrics",
                                    alphaFactor = previewSecondaryAlpha(
                                        profile.secondaryTextBright
                                    )
                                )
                            } else {
                                null
                            },
                            duetKey = duetKey
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

/** 预览侧歌曲图片呈现参数:形状/旋转生效值 + 自适应开关/自定义边长 + 解码后的帧(或演示占位)。 */
private data class PreviewArtwork(
    val shape: String,
    val spin: Boolean,
    val image: ImageBitmap?,
    val placeholder: Boolean,
    /** 自适应缩放(见 ArtworkDisplayConfig.adaptiveScale);关闭时用 [sizeDp]。 */
    val adaptiveScale: Boolean = true,
    /** 自定义边长(dp);仅 [adaptiveScale] 关闭时生效。 */
    val sizeDp: Int = com.eza.hyperglow.customization.ARTWORK_SIZE_DEFAULT_DP
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
    return PreviewArtwork(
        artwork.shape,
        artwork.spins,
        image,
        placeholder = false,
        adaptiveScale = artwork.adaptiveScale,
        sizeDp = artwork.sizeDp
    )
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
    val leadingPx = if (artwork != null) {
        artworkLeadingPx(
            sizePx,
            density.density,
            artwork.adaptiveScale,
            artwork.sizeDp
        )
    } else {
        0f
    }
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
                side = with(density) {
                    artworkSidePx(
                        sizePx,
                        density.density,
                        artwork.adaptiveScale,
                        artwork.sizeDp
                    ).toDp()
                },
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

/**
 * 并发行是否参与预览渲染:本面「显示并发歌词(对唱)」开关开启且快照携带非空并发行。
 * 与实机 LyricCanvasMapper 的门控同源(`duet && profile.duetConcurrent`)——实机在映射层
 * 就已按面丢弃,预览的演示快照不经映射层,故判据落在渲染侧;文本剥空(纯标记行)同样不上屏。
 */
internal fun previewDuetVisible(
    duetConcurrent: Boolean,
    duetLine: LyricDuetLine?
): Boolean = duetConcurrent && duetLine != null && duetLine.text.isNotBlank()

/**
 * 并发行内容键(纯函数,JVM 可测;与实机 AodLyricCanvasView 的 duetLineKey 同口径:
 * 文本 + 行窗起点)。键不变 = 并发行没换,渲染必须原地保持(不随主行换行进退);
 * 键变化才播自己的换行过渡(退场→入场,首次出现回落加入淡入)。文本为空/无并发行
 * 返回 null(不可见,不动画)。
 */
internal fun previewDuetKey(text: String?, lineStartMs: Long): String? =
    text?.takeIf { it.isNotBlank() }?.let { "$it@$lineStartMs" }

/**
 * 并发行段在主行过渡期间的静止槽位偏移(px,纯函数,JVM 可测;与实机
 * AodDuetLineIndependence.frozenDuetBaselines 同源):并发行内容键与过渡起点一致(并发行
 * 没换)时,槽位取过渡起点快照——旧行组(主行+辅助文字)与新行组的高差,新布局不参与,
 * 「主行换行不改变并发行位置」由此成立;并发行自己换了(键变化)或不在过渡中(起点键为 null)
 * 时不偏移,由新布局接管。偏移作用于整个并发行段(段内各行同步平移,相对位置不变)。
 *
 * [previousGroupHeightPx] 为 0(退场层实测高要到过渡首帧之后才上报)时不偏移:否则首帧会
 * 按「0 − 新行组高」把并发行顶到块顶,下一帧再跳回,反而制造一次可见跳动。
 */
internal fun previewDuetFrozenOffsetPx(
    previousDuetKey: String?,
    currentDuetKey: String?,
    previousGroupHeightPx: Int,
    currentGroupHeightPx: Int
): Float = if (previousDuetKey != null && previousDuetKey == currentDuetKey &&
    previousGroupHeightPx > 0
) {
    (previousGroupHeightPx - currentGroupHeightPx).toFloat()
} else {
    0f
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
    // 字号与实机 setContent 同源:音标行/翻译行各自公式(不同下限)×辅助字号倍率;
    // 翻译行走斜体(与实机一致)。
    val reading = snapshot.romanized.ifBlank { null }?.let {
        PreviewSecondaryLine(
            it,
            secondaryReadingTextSizeSp(baseSp, profile.secondaryTextSizePercent).sp,
            italic = false
        )
    }
    val translation = snapshot.translated.ifBlank { null }?.let {
        PreviewSecondaryLine(
            it,
            secondaryTranslationTextSizeSp(baseSp, profile.secondaryTextSizePercent).sp,
            italic = true
        )
    }
    return when (profile.secondaryMode) {
        "Transliteration" -> listOfNotNull(reading)
        "Translation" -> listOfNotNull(translation)
        "Both" -> listOfNotNull(reading, translation)
        else -> emptyList()
    }
}

/**
 * 辅助行自适应拟合(预览侧入口,与实机 AodLyricCanvasView.fittedSecondaryPaint 同源):
 * 装得下恒返回设定字号(既有呈现逐像素不变);装不下缩到可读性下限(见 fittedSecondaryLines)。
 * [scaledDensity] 为 sp→px 换算(与实机 scaledDensity 同式),预览与实机按同一换算拟合。
 */
private fun previewFittedSecondarySp(
    text: String,
    configuredSp: Float,
    translation: Boolean,
    baseSp: Float,
    preferredLines: Int,
    availableWidthPx: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    typeface: Typeface,
    autoSize: Boolean,
    scaledDensity: Float
): FittedSecondaryLines {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = configuredSp * scaledDensity
        this.typeface = typeface
    }
    return if (autoSize) {
        fittedSecondaryLines(
            text = text,
            basePaint = paint,
            configuredSp = configuredSp,
            floorSp = secondarySizeFloorSp(baseSp, translation),
            availableWidth = availableWidthPx.toFloat(),
            preferredLines = preferredLines,
            wrap = wrap,
            adaptiveSectioning = adaptiveSectioning,
            scaledDensity = scaledDensity
        )
    } else {
        FittedSecondaryLines(
            paint = paint,
            fittedSp = configuredSp,
            lines = layoutSecondaryLines(
                text = text,
                paint = paint,
                availableWidth = availableWidthPx.toFloat(),
                preferredLines = preferredLines,
                wrap = wrap,
                adaptiveSectioning = adaptiveSectioning
            )
        )
    }
}

/** 辅助行按自适应拟合重建(字号可能缩小;文本/斜体不变):与实机 buildRows 的行字号同源。 */
private fun previewFittedSecondaryRow(
    row: PreviewSecondaryLine,
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    baseSp: Float,
    preferredLines: Int,
    availableWidthPx: Int,
    typeface: Typeface,
    scaledDensity: Float
): PreviewSecondaryLine {
    if (!profile.secondaryAutoSize) return row
    val fitted = previewFittedSecondarySp(
        text = row.text,
        configuredSp = row.size.value,
        translation = row.italic,
        baseSp = baseSp,
        preferredLines = preferredLines,
        availableWidthPx = availableWidthPx,
        wrap = profile.overflow == "Wrap",
        adaptiveSectioning = profile.adaptiveSectioning,
        typeface = if (row.italic) Typeface.create(typeface, Typeface.ITALIC) else typeface,
        autoSize = true,
        scaledDensity = scaledDensity
    )
    return if (fitted.fittedSp < row.size.value) row.copy(size = fitted.fittedSp.sp) else row
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
    val rubyLines: List<PreviewRubyLine>,
    /** 行高(px,与实机 originalLayout.lineHeight 同式):供逐字下沉量 [karaokeFloatSinkPx]。 */
    val lineHeight: Float = 0f,
    /** 「BetterLyrics」档逐字卡拉OK:每行的词位(与 [lines] 下标对齐);空表示无逐字时间。 */
    val wordRuns: List<List<PreviewWordRun>> = emptyList(),
    /** 逐字词位的总时间跨度(演示进度映射到虚拟播放位置的区间)。 */
    val wordSpanStartMs: Long = 0L,
    val wordSpanEndMs: Long = 0L,
    /** 是否走「BetterLyrics」逐字卡拉OK(与实机 drawWordKaraoke(betterLyrics=true) 同源)。 */
    val betterLyrics: Boolean = false,
    /** 决策函数给出的词级卡拉OK路径(与实机 drawOriginal 的 WORD_KARAOKE 分支同义)。 */
    val wordKaraoke: Boolean = false,
    /** 未唱底字色(与实机 resolvedPalette.unsungText 同源)。 */
    val unsungColorArgb: Int = 0,
    /**
     * 该行块的真实行窗跨度(演示快照=切换周期);辅助文字逐字效果
     * ([PreviewAuxKaraoke.spanMs])与主行合成源共用同一跨度口径。
     */
    val lineSpanMs: Long = 0L
)

/** 预览一行内一个逐字词位:行内 x/宽 + 词时间窗;[longSyllable] 为合成词位的恒长音节标记;
 *  [highlightStartMs]/[highlightEndMs] 为合成源所属词块的高亮时间窗(负值=未提供)。 */
private class PreviewWordRun(
    val text: String,
    val x: Float,
    val width: Float,
    val startMs: Long,
    val endMs: Long,
    val longSyllable: Boolean = false,
    val highlightStartMs: Long = -1L,
    val highlightEndMs: Long = -1L
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
    words: List<LyricWord>,
    betterLyrics: Boolean,
    /** 词级卡拉OK路径(planOriginalLine 决策):真实词窗的行非 Minimal 档一律为真。 */
    wordKaraoke: Boolean,
    unsungColorArgb: Int,
    lineSpanMs: Long,
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
    // 词级卡拉OK路径(决策函数给出):把逐字词位映射到各行(与实机词行布局同源语义),
    // 供预览逐字渲染;行级源(BetterLyrics 档)按字符合成时间窗走同一渲染;其余档不注入,
    // 自然退化到行级/块级扫光。词位缺省原文区间时与实机同源补全(alignMissingWordOffsets:
    // 插件词表常不带区间),否则词位无处落位、预览会与实机的逐字路径分叉。
    val alignedWords = alignMissingWordOffsets(
        text,
        words.map {
            AodCanvasWord(
                text = it.text,
                romanized = it.romanized,
                startMs = it.startMs,
                endMs = it.endMs,
                boundaryAfter = it.boundaryAfter,
                sourceStart = it.sourceStart,
                sourceEnd = it.sourceEnd
            )
        }
    )
    val wordRuns = when {
        wordKaraoke && alignedWords.isNotEmpty() ->
            result.lines.map { line -> previewWordRuns(line, text.length, alignedWords, paint) }
        // 合成源只吃行文本与行宽(与副文本行同型),主行行表按同一口径转换后共用。
        wordKaraoke && betterLyrics -> syntheticPreviewWordRuns(
            result.lines.map { LyricLayoutTextLine(it.text, it.width) },
            paint,
            lineSpanMs
        )
        else -> emptyList()
    }
    val wordSpanStartMs = wordRuns.asSequence().flatten().minOfOrNull { it.startMs } ?: 0L
    val wordSpanEndMs = wordRuns.asSequence().flatten().maxOfOrNull { it.endMs } ?: 0L
    return PreviewMainLayout(
        lines = result.lines,
        baselines = baselines,
        startsX = startsX,
        blockHeight = blockHeight,
        paint = paint,
        rubyPaint = rubyPaint,
        rubyLines = rubyLines,
        lineHeight = lineHeight,
        wordRuns = wordRuns,
        wordSpanStartMs = wordSpanStartMs,
        wordSpanEndMs = wordSpanEndMs,
        betterLyrics = betterLyrics,
        wordKaraoke = wordKaraoke,
        unsungColorArgb = unsungColorArgb,
        lineSpanMs = lineSpanMs
    )
}

/**
 * 预览一行的逐字词位(与实机 buildOriginalLayout 的词行布局同源语义):按行字符区间裁剪
 * 与行相交的逐字词,以主行字体的前缀宽/词宽求行内 x/宽。词表缺省区间已由调用方按实机
 * 同源补全([alignMissingWordOffsets]);仍无区间或与行不相交时跳过——与实机一致,
 * 退化到行级/块级扫光。
 */
private fun previewWordRuns(
    line: LyricLayoutLine,
    textLength: Int,
    words: List<AodCanvasWord>,
    paint: TextPaint
): List<PreviewWordRun> {
    val lineStart = line.charStart ?: return emptyList()
    val lineEnd = line.charEnd ?: return emptyList()
    return words.asSequence()
        .filter {
            it.sourceStart >= 0 && it.sourceEnd > it.sourceStart &&
                it.sourceEnd <= textLength &&
                it.sourceStart < lineEnd && it.sourceEnd > lineStart
        }
        .sortedBy { it.sourceStart }
        .mapNotNull { word ->
            val localStart = (maxOf(word.sourceStart, lineStart) - lineStart)
                .coerceIn(0, line.text.length)
            val localEnd = (minOf(word.sourceEnd, lineEnd) - lineStart)
                .coerceIn(localStart, line.text.length)
            if (localStart >= localEnd) return@mapNotNull null
            PreviewWordRun(
                text = line.text.substring(localStart, localEnd),
                x = paint.measureText(line.text, 0, localStart),
                width = paint.measureText(line.text, localStart, localEnd),
                startMs = word.startMs,
                endMs = word.endMs
            )
        }
        .toList()
}

/**
 * 预览行级源(无逐字时间)的合成逐字词位:按词块划分(中文逐字、西文按词),虚拟时间在
 * [lineSpanMs] 内按几何宽度分摊——[PreviewMainLayer] 的虚拟播放位置(progress×跨度)下,
 * 合成推进前缘与逐行扫光几何完全一致(共享 [syntheticCharTimeWindow]);块内字符共享块级
 * 高亮窗口,长块(≥700ms)整块同步放大/辉光(与实机合成源同判定)。主行(PreviewMainLayer)
 * 与辅助文字行([PreviewSecondaryKaraokeRow])共用本函数,只是各自传入自己的行文本与 paint。
 */
private fun syntheticPreviewWordRuns(
    lines: List<LyricLayoutTextLine>,
    paint: TextPaint,
    lineSpanMs: Long
): List<List<PreviewWordRun>> {
    val totalWidth = lines.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(1f)
    val spanMs = lineSpanMs.coerceAtLeast(1L)
    var preceding = 0f
    val out = ArrayList<List<PreviewWordRun>>(lines.size)
    lines.forEach { line ->
        val runs = ArrayList<PreviewWordRun>()
        var prefix = 0f
        var index = 0
        for (block in syntheticKaraokeBlocks(line.text)) {
            while (index < block.first) {
                val unitEnd = karaokeUnitEnd(line.text, index, block.first)
                prefix += paint.measureText(line.text, index, unitEnd)
                index = unitEnd
            }
            val blockWidth = paint.measureText(line.text, block.first, block.last + 1)
            val blockWindow = syntheticCharTimeWindow(
                0L,
                spanMs,
                totalWidth,
                preceding + prefix,
                blockWidth
            )
            // 与实机同判定:块时长不足 700ms 的短词块只有扫光,不放大、不辉光。
            val blockLong = isLongKaraokeSyllable(blockWindow.last - blockWindow.first)
            var charIndex = block.first
            while (charIndex <= block.last) {
                val charEnd = karaokeUnitEnd(line.text, charIndex, block.last + 1)
                val charWidth = paint.measureText(line.text, charIndex, charEnd)
                val charWindow = syntheticCharTimeWindow(
                    0L,
                    spanMs,
                    totalWidth,
                    preceding + prefix,
                    charWidth
                )
                if (charWindow.last > charWindow.first) {
                    runs += PreviewWordRun(
                        text = line.text.substring(charIndex, charEnd),
                        x = prefix,
                        width = charWidth,
                        startMs = charWindow.first,
                        endMs = charWindow.last,
                        longSyllable = blockLong,
                        highlightStartMs = if (blockLong) blockWindow.first else -1L,
                        highlightEndMs = if (blockLong) blockWindow.last else -1L
                    )
                }
                prefix += charWidth
                charIndex = charEnd
            }
            index = block.last + 1
        }
        preceding += line.width
        out += runs
    }
    return out
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
 * 行块一代内容:主行 + 辅助文字行 + 并发行段 + 下一行行。换行过渡按行分流三段式(与实机
 * drawOrientedContent 同构):离场行组(主行+辅助文字)退场 → 下一行晋级位移 →
 * 新到行(新辅助文字+新下一行)进场;[nextLine] 为晋级源(内容延续时升任主行)。
 * 并发行段([duet]/[duetRows])不参与这三段:它有自己的内容键([duetKey])与自己的换行
 * 过渡(退场→入场,首次出现回落加入淡入)。
 */
private class PreviewRowBlock(
    val main: PreviewMainLayout,
    val mainText: String,
    val rows: List<PreviewBlockRow>,
    val nextLine: PreviewBlockRow?,
    /** 「显示第二行辅助文字」:第二行歌词行自身的辅助文字行(音标/翻译),紧随下一行行之后。 */
    val nextRows: List<PreviewBlockRow> = emptyList(),
    /**
     * 辅助文字逐字效果(「辅助文字逐字效果」):非空时 [rows] 中标记 karaoke 的行(第一行
     * 辅助文字)走逐字渲染;第二行歌词及其辅助行不参与(其播放窗口尚未开始)。
     */
    val auxKaraoke: PreviewAuxKaraoke? = null,
    /**
     * 对唱并发行布局:与主行同款布局+词级扫光(同 [PreviewMainLayout]),占独立的并发行车道
     * (段序在辅助文字行组之后、下一行行之前,与实机 buildRows 同源),不随主行换行进退;
     * 在场时取代独立「下一行」行(anti-dup,与实机 buildRows 同式,防下方拥挤)。
     * null = 本面无并发行、「显示并发歌词(对唱)」已关,或本行是和声行(和声走辅助行车道,
     * 只经 [duetRows] 呈现,不再叠主行同款大字行)。
     */
    val duet: PreviewMainLayout? = null,
    /**
     * 并发行自己的辅助文字行(音标/翻译),紧随并发行之后;换行档跟随并发行呈现行数。
     * 和声行([duet] 为空)时是唯一承载:首行即和声文本本身,按辅助行样式渲染。
     */
    val duetRows: List<PreviewBlockRow> = emptyList(),
    /**
     * 并发行辅助行(音标/翻译/和声行)的「辅助文字逐字效果」参数:跨度取并发行自己的行窗
     * (实机 buildRows 的 duetAuxKaraokeWindow 同源),与主行辅助行的 [auxKaraoke] 区分开。
     */
    val duetAuxKaraoke: PreviewAuxKaraoke? = null,
    /**
     * 并发行内容键(文本 + 行窗起点,见 [previewDuetKey]):键不变 = 并发行未换,渲染原地
     * 保持;键变化才播自己的换行过渡(退场→入场/加入淡入)。null = 并发行不可见
     * (无并发行/开关关闭)。
     */
    val duetKey: String? = null
)

/** 行块内副行(辅助文字/下一行)的渲染参数,随所属行块一起冻结;[dimAlpha] 为该行静态亮度档。 */
private class PreviewBlockRow(
    val row: PreviewSecondaryLine,
    val color: ComposeColor,
    val align: TextAlign,
    val gapAbove: Dp,
    val dimAlpha: Float,
    /** 折行档:null = 沿用主行呈现行数;第二行自身的辅助行传第二行呈现行数(实机同源)。 */
    val preferredLines: Int? = null,
    /**
     * 辅助文字逐字效果是否作用于本行:仅第一行辅助文字行(音标/翻译)在开关开启时为真;
     * 下一行行与第二行自身的辅助行恒假(见 [PreviewRowBlock.auxKaraoke])。
     */
    val karaoke: Boolean = false,
    /**
     * 本行自己的逐字词表(仅和声行携带,插件按 AMLL TTML 逐音节下发):逐字效果按**真实
     * 词窗**点亮(实机 `AodLyricCanvasView.harmonyTimedLines` 同源,共用 [harmonyTimedSegments]
     * 判据);空 = 按行窗均匀合成,即历史行为。第一行辅助文字行/下一行行不带词表(它们的
     * 逐字时间另有来源或尚未开始,见 [PreviewRowBlock.auxKaraoke])。
     */
    val words: List<LyricWord> = emptyList()
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
    // 并发行行组的实测高(退场层 = 旧并发行行块、入场层 = 新并发行行块):并发行自己的
    // 过渡帧以它为位移基准(实机各层取本层行块同序,见 animatedBlockHeightDp)。
    var exitDuetHeightPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    var enterDuetHeightPx by remember(block.mainText) { mutableStateOf(0) }
    var nextRowTopPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    var exitNextLineRowHeightPx by remember(exitingBlock?.mainText) { mutableStateOf(0) }
    // 并发行在主行过渡期间的静止槽位偏移(px,与实机 AodDuetLineIndependence.frozenDuetBaselines
    // 同源,判据见 previewDuetFrozenOffsetPx)。
    val duetFrozenOffsetPx = previewDuetFrozenOffsetPx(
        previousDuetKey = previous?.duetKey,
        currentDuetKey = block.duetKey,
        previousGroupHeightPx = exitMainHeightPx + exitRowsHeightPx,
        currentGroupHeightPx = enterMainHeightPx + enterRowsHeightPx
    )
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
    // 并发行自己的过渡帧(与实机同源):同一份配方/缓动,宽高基准取并发行行块自身
    // (退场层用旧行块、入场层用新行块);首帧尚未测量时回落主行块高。
    val duetJoin = remember { Animatable(1f) }
    val duetExitProgress = remember { Animatable(1f) }
    val duetEnterProgress = remember { Animatable(1f) }
    val duetExitBlockHeightDp = with(density) {
        if (exitDuetHeightPx > 0) exitDuetHeightPx.toDp().value else exitMainBlockHeightDp
    }
    val duetEnterBlockHeightDp = with(density) {
        if (enterDuetHeightPx > 0) enterDuetHeightPx.toDp().value else mainBlockHeightDp
    }
    val duetExitFrame = lineTransitionExitFrame(
        lineTransition,
        lineTransitionExitEasing(lineTransition, duetExitProgress.value),
        blockWidthDp,
        duetExitBlockHeightDp
    )
    val duetEnterFrame = lineTransitionEnterFrame(
        lineTransition,
        lineTransitionEnterEasing(lineTransition, duetEnterProgress.value),
        blockWidthDp,
        duetEnterBlockHeightDp
    )
    // 并发行自己的换行过渡(与实机 AodLyricCanvasView 同源):内容键(文本 + 行窗起点)
    // 变化且上一版并发行还在、档位非 None 时,播预设的退场 → 入场半段——**同一槽位、
    // 无位移段**(见 [duetRowTransitionTimeline]);首次出现没有旧内容可退场、None 档
    // 不播动画,回落既有 180ms 加入淡入。并发行不参与主行换行帧:主行换行时它原地不动,
    // 只有自己的内容换了才动(键为 null 时并发行不可见,不动画)。
    // 旧并发行内容取上一版**已落定**的并发行行块([stableDuetBlock],落定 = 它自己的过渡
    // 播完才认新键):主行同时换行时退场层就是主行过渡的起点行块,仅并发行换行(实时数据
    // 两行各有自己的行窗)时由 [stableDuetBlock] 单独成层承载退场半段。
    val duetKey = block.duetKey
    var previousDuetKey by remember { mutableStateOf(block.duetKey) }
    var settledDuetKey by remember { mutableStateOf(block.duetKey) }
    var stableDuetBlock by remember { mutableStateOf(block) }
    // 只在「键真的换了一版」时写一次:该状态在组合期被读(退场层的取用条件),无条件写
    // 新实例会每帧触发重组。
    SideEffect {
        if (block.duetKey == settledDuetKey && stableDuetBlock.duetKey != block.duetKey) {
            stableDuetBlock = block
        }
    }
    LaunchedEffect(duetKey) {
        if (duetKey == previousDuetKey) return@LaunchedEffect
        val previousBlock = stableDuetBlock
        previousDuetKey = duetKey
        val presetTransition = duetKey != null && shouldStartDuetRowTransition(
            previousKey = previousBlock.duetKey,
            nextKey = duetKey,
            transitionMode = lineTransition,
            // 旧内容由退场层重画:上一版行块确有并发行时它才在屏上(与实机
            // previousRowAvailable 同一含义)。
            previousRowAvailable = previousBlock.duetKey != null
        )
        if (presetTransition) {
            val timeline = duetRowTransitionTimeline(lineTransition, lineTransitionSpeed)
            duetJoin.snapTo(1f)
            duetExitProgress.snapTo(0f)
            duetEnterProgress.snapTo(0f)
            coroutineScope {
                launch {
                    duetExitProgress.animateTo(
                        1f,
                        tween(timeline.exitMs.toInt(), easing = LinearEasing)
                    )
                }
                delay(timeline.exitMs)
                duetEnterProgress.animateTo(
                    1f,
                    tween(timeline.enterMs.toInt(), easing = LinearEasing)
                )
            }
            // 两段播完才认新键为落定:过渡期间 [stableDuetBlock] 一直是旧内容(退场层的来源)。
            settledDuetKey = duetKey
        } else {
            duetExitProgress.snapTo(1f)
            duetEnterProgress.snapTo(1f)
            settledDuetKey = duetKey
            if (duetKey == null) {
                duetJoin.snapTo(1f)
                return@LaunchedEffect
            }
            duetJoin.snapTo(0f)
            duetJoin.animateTo(
                1f,
                tween(PREVIEW_DUET_JOIN_FADE_MS.toInt(), easing = LinearEasing)
            )
        }
    }
    val duetAlpha = duetJoin.value
    // 角色分流(与实机同构):退场层画离场行组[主行+辅助文字]、下一行只占位(退场段原地
    // 保持、位移段起隐藏);入场层画新到行组[辅助文字+下一行]、主行只占位(由位移层绘制)。
    // 并发行段不吃这两层的帧:它只吃自己的过渡帧(见 [PreviewRowBlockLayer.duetFrame])——
    // 主行换行时原地不动,这是「并发行不随主行换行移动」的落点。
    val exitFrameParts = if (promoting) {
        setOf(PreviewRowPart.MAIN, PreviewRowPart.ROWS)
    } else {
        setOf(PreviewRowPart.MAIN, PreviewRowPart.ROWS, PreviewRowPart.NEXT)
    }
    // 并发行段在退场层的取舍:并发行**自己**换行时留在退场层(旧内容按退场帧离场,与实机
    // drawDuetRowExitLayer 同源——退场层画的就是上一版行块);否则恒为隐藏段(旧并发行不随
    // 主行离场),由入场层单独绘制。
    val duetExiting = duetExitProgress.value < 1f
    val exitHiddenParts = if (promoting && moveFrameProgress.value > 0f) {
        if (duetExiting) {
            setOf(PreviewRowPart.NEXT)
        } else {
            setOf(PreviewRowPart.NEXT, PreviewRowPart.DUET)
        }
    } else {
        if (duetExiting) emptySet() else setOf(PreviewRowPart.DUET)
    }
    val enterFrameParts = if (promoting) {
        setOf(PreviewRowPart.ROWS, PreviewRowPart.NEXT)
    } else {
        setOf(PreviewRowPart.MAIN, PreviewRowPart.ROWS, PreviewRowPart.NEXT)
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
                duetFrame = duetExitFrame,
                onPartHeightPx = { part, height ->
                    when (part) {
                        PreviewRowPart.MAIN -> exitMainHeightPx = height
                        PreviewRowPart.ROWS -> exitRowsHeightPx = height
                        PreviewRowPart.NEXT -> exitNextHeightPx = height
                        // 并发行段不参与退场组高(它不随主行离场),但并发行自己的过渡帧
                        // 以它为位移基准(实机退场层取本层行块同序)。
                        PreviewRowPart.DUET -> exitDuetHeightPx = height
                    }
                },
                onNextRowTopPx = { nextRowTopPx = it },
                onNextLineRowHeightPx = { exitNextLineRowHeightPx = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
        // 仅并发行换行(主行未换:实时数据两行各有自己的行窗)时没有主行退场层可承载旧
        // 并发行内容——由上一版并发行行块单独成层,只画 DUET 段(其余段隐藏只占位),
        // 与实机 drawDuetRowExitLayer 同源;主行同时换行时上面那层已经承载了它。
        if (previous == null && duetExiting && stableDuetBlock.duetKey != null &&
            stableDuetBlock.duetKey != block.duetKey
        ) {
            PreviewRowBlockLayer(
                block = stableDuetBlock,
                sweepProgress = 1f,
                frame = LineTransitionFrame(alpha = 1f),
                frameParts = emptySet(),
                hiddenParts = setOf(
                    PreviewRowPart.MAIN,
                    PreviewRowPart.ROWS,
                    PreviewRowPart.NEXT
                ),
                color = color,
                glowColor = glowColor,
                glowEnabled = glowEnabled,
                fillMode = fillMode,
                rubyColor = rubyColor,
                regularTypeface = regularTypeface,
                availableWidthPx = availableWidthPx,
                wrap = wrap,
                adaptiveSectioning = adaptiveSectioning,
                duetFrame = duetExitFrame,
                onPartHeightPx = { part, height ->
                    // 退场帧的位移基准取旧并发行行块实测高(与上面那层同口径)。
                    if (part == PreviewRowPart.DUET) exitDuetHeightPx = height
                },
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
        // 由段2接管,本层只画新到行组。并发行段由本层绘制(退场层只在自己换行时接走),
        // 吃自己的过渡帧:预设入场半段或加入淡入。
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
            duetAlpha = duetAlpha,
            duetFrozenOffsetPx = duetFrozenOffsetPx,
            duetFrame = duetEnterFrame,
            onPartHeightPx = { part, height ->
                when (part) {
                    PreviewRowPart.MAIN -> enterMainHeightPx = height
                    PreviewRowPart.ROWS -> enterRowsHeightPx = height
                    PreviewRowPart.NEXT -> enterNextHeightPx = height
                    // 并发行段不参与入场组高(它不随主行进场),但并发行自己的过渡帧
                    // 以它为位移基准(实机入场层取本层行块同序)。
                    PreviewRowPart.DUET -> enterDuetHeightPx = height
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 行块内可独立分帧的四段:主行 / 辅助文字行组 / 并发行段(并发行或和声行及其辅助行) /
 * 下一行行(与实机行角色一一对应;段序与实机 buildRows 的行序同源:主行 → 主行辅助行 →
 * 并发行/和声行 → 下一行)。
 *
 * 并发行段与其它三段的区别:它**不吃主行换行帧**(见 [applyPartTransition] 的 DUET 说明)——
 * 主行换行时并发行原地不动,只有自己的内容键变化才播自己的换行过渡(退场 → 入场半段,
 * 首次出现回落加入淡入)。这是「并发行相对主行独立」在预览侧的同一语义。
 */
private enum class PreviewRowPart { MAIN, ROWS, DUET, NEXT }

/**
 * 行块单层绘制:主行 LyricGlowRow + 辅助文字行 + 并发行段(对唱大字行/和声行及其辅助行)+
 * 下一行行,按 [frameParts]/[hiddenParts] 逐段分流——[frameParts] 内的行段共用 [frame]
 * (alpha/位移/缩放/旋转一次施加);[hiddenParts] 内的行段只占位不绘制(由其它层接管);
 * 其余行段恒等展示;并发行段只吃自己的过渡帧([duetFrame] × [duetAlpha]:预设退场/入场
 * 半段或加入淡入),不吃主行 [frame]。
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
    /** 并发行段的加入淡入系数(实机 drawDuetOriginal 的 duetJoinAlpha 同源);只作用于 DUET 段。 */
    duetAlpha: Float = 1f,
    /**
     * 并发行段在主行过渡期间的静止槽位偏移(px,实机 frozenDuetBaselines 同源):主行换行
     * 过渡期间并发行停在过渡起点槽位;0 = 不偏移(无过渡,或并发行自己刚换行)。
     */
    duetFrozenOffsetPx: Float = 0f,
    /**
     * 并发行段自己的过渡帧(实机 withDuetRowTransition 同源):退场层传退场半段、入场层传
     * 入场半段;恒等帧 = 无预设过渡(只吃 [duetAlpha] 的加入淡入)。
     */
    duetFrame: LineTransitionFrame = LineTransitionFrame(alpha = 1f),
    onPartHeightPx: (PreviewRowPart, Int) -> Unit = { _, _ -> },
    onNextRowTopPx: (Int) -> Unit = {},
    onNextLineRowHeightPx: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Column(modifier) {
        // 主行段(仅原文行):并发行有自己的车道(见下方 DUET 段),不再与主行同层进退——
        // 主行换行时并发行原地不动(任务 1「并发行相对主行独立」的预览侧语义)。
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
                        val auxKaraoke = block.auxKaraoke
                        if (item.karaoke && auxKaraoke != null && auxKaraoke.spanMs > 0L) {
                            // 辅助文字逐字效果:第一行辅助行按行块演示进度逐字点亮(与实机
                            // drawAuxKaraokeRow 同一共享渲染核心)。
                            PreviewSecondaryKaraokeRow(
                                row = item.row,
                                color = item.color,
                                typeface = regularTypeface,
                                availableWidthPx = availableWidthPx,
                                preferredLines = block.main.lines.size,
                                wrap = wrap,
                                adaptiveSectioning = adaptiveSectioning,
                                textAlign = item.align,
                                progress = sweepProgress,
                                auxKaraoke = auxKaraoke,
                                glowColor = glowColor,
                                glowEnabled = glowEnabled,
                                modifier = Modifier.padding(top = item.gapAbove)
                            )
                        } else {
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
        }
        // 并发行段:真对唱的主行同款大字行([block.duet]),或和声行的辅助行车道首行与并发行
        // 自己的辅助行([block.duetRows])。段序在辅助文字行组之后、下一行行之前,与实机
        // buildRows 的行序同源(主行 → 主行辅助行 → 并发行/和声行 → 下一行)。
        // 段内行不吃主行换行帧(退场层只在自己换行时接走它),只吃自己的
        // 过渡帧 [duetFrame] × [duetAlpha](实机 withDuetRowTransition 同源:预设退场/入场
        // 半段或加入淡入);[duetFrozenOffsetPx] 让它在主行过渡期间停在过渡起点槽位
        // (实机 frozenDuetBaselines 同源),与自己的帧位移叠加。
        if (block.duet != null || block.duetRows.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        applyPartTransition(
                            PreviewRowPart.DUET,
                            frame,
                            frameParts,
                            hiddenParts,
                            partAlpha = duetAlpha,
                            duetFrame = duetFrame
                        )
                        translationY += duetFrozenOffsetPx
                    }
            ) {
                Column(Modifier.fillMaxWidth()) {
                    val duet = block.duet
                    if (duet != null) {
                        // 并发行与主行同一渲染核心(自带词级扫光),间隔取实机同源常量;
                        // 词级/行级路径由 [buildPreviewMainLayout] 按并发行自己的词窗决策。
                        PreviewMainLayer(
                            layout = duet,
                            progress = sweepProgress,
                            color = color,
                            glowColor = glowColor,
                            glowEnabled = glowEnabled,
                            fillMode = fillMode,
                            rubyColor = rubyColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = ROW_GAP_BEFORE_ORIGINAL_DP.dp)
                                .height(with(density) { duet.blockHeight.toDp() })
                        )
                    }
                    // 并发行/和声行自己的辅助行:换行档跟随并发行自身呈现行数(实机 buildRows 同源);
                    // 和声行走辅助行车道时 duet 为空、行数档由行自带的 preferredLines 给出。
                    block.duetRows.forEach { item ->
                        val auxKaraoke = block.duetAuxKaraoke
                        if (item.karaoke && auxKaraoke != null && auxKaraoke.spanMs > 0L) {
                            // 并发行/和声行「辅助文字逐字效果」:与实机 drawAuxKaraokeRow 同一共享
                            // 渲染核心(实机按并发行自己的行窗口点亮,预览走演示进度);和声行
                            // 另带自己的词表 [PreviewBlockRow.words],有真实词窗时按词窗点亮
                            // (与实机 harmonyTimedLines 同一判据,预览即实机)。
                            PreviewSecondaryKaraokeRow(
                                row = item.row,
                                color = item.color,
                                typeface = regularTypeface,
                                availableWidthPx = availableWidthPx,
                                preferredLines = item.preferredLines ?: duet?.lines?.size
                                    ?: block.main.lines.size,
                                wrap = wrap,
                                adaptiveSectioning = adaptiveSectioning,
                                textAlign = item.align,
                                progress = sweepProgress,
                                auxKaraoke = auxKaraoke,
                                words = item.words,
                                glowColor = glowColor,
                                glowEnabled = glowEnabled,
                                modifier = Modifier.padding(top = item.gapAbove)
                            )
                        } else {
                            PreviewSecondaryRow(
                                row = item.row,
                                color = item.color,
                                typeface = regularTypeface,
                                availableWidthPx = availableWidthPx,
                                preferredLines = item.preferredLines ?: duet?.lines?.size
                                    ?: block.main.lines.size,
                                wrap = wrap,
                                adaptiveSectioning = adaptiveSectioning,
                                textAlign = item.align,
                                modifier = Modifier.padding(top = item.gapAbove)
                            )
                        }
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

/**
 * graphicsLayer 作用域内按行段分流:隐藏段 alpha 置 0(占位),帧内段施加 [frame],其余恒等
 * (alpha 只吃段自身的 [partAlpha])。
 *
 * 并发行段(DUET)只吃自己的 [duetFrame]:主行换行帧不作用于它(退场层只在自己换行时接走它)
 * ——预设过渡期间是退场/入场半段,无预设过渡时恒等帧、只剩 [partAlpha] 的加入淡入。
 * 与实机 withDuetRowTransition 同源:两行各有自己的时间轴。
 */
private fun GraphicsLayerScope.applyPartTransition(
    part: PreviewRowPart,
    frame: LineTransitionFrame,
    frameParts: Set<PreviewRowPart>,
    hiddenParts: Set<PreviewRowPart>,
    partAlpha: Float = 1f,
    duetFrame: LineTransitionFrame = LineTransitionFrame(alpha = 1f)
) {
    val applied = if (part == PreviewRowPart.DUET) duetFrame else frame
    when {
        part in hiddenParts -> alpha = 0f
        part in frameParts || part == PreviewRowPart.DUET -> {
            alpha = applied.alpha * partAlpha
            translationX = applied.translateXDp.dp.toPx()
            translationY = applied.translateYDp.dp.toPx()
            scaleX = applied.scale
            scaleY = applied.scale
            rotationZ = applied.rotationDeg
            rotationX = applied.rotationXDeg
            rotationY = applied.rotationYDeg
        }
        else -> alpha = partAlpha
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
        // 走词级卡拉OK:共享决策(planOriginalLine)已把「带真实词窗的行」固定在该路径
        // (Gradient 等基础档与 BetterLyrics 都含),这里再加预览自身的必要条件——词位已
        // 构建且时间跨度有效。词位由 [buildPreviewMainLayout] 按同一决策构建,该门与决策同义。
        val useWordKaraoke = layout.wordKaraoke &&
            layout.wordSpanEndMs > layout.wordSpanStartMs &&
            layout.wordRuns.any { it.isNotEmpty() }
        if (useWordKaraoke) {
            // 逐字卡拉OK(与实机 drawWordKaraoke 同源):演示进度按词位总时间跨度映射为虚拟
            // 播放位置,逐词取已唱比例;共享渲染核心负责两种档位形态——BetterLyrics 档的
            // 未唱下沉/已唱上浮、长音节放大/辉光,基础档(Gradient 等)的历史词内扫光带。
            val virtualPosition = layout.wordSpanStartMs +
                ((layout.wordSpanEndMs - layout.wordSpanStartMs) * progress.coerceIn(0f, 1f)).toLong()
            drawIntoCanvas { canvas ->
                drawPreviewKaraokeRuns(
                    canvas = canvas.nativeCanvas,
                    paint = layout.paint,
                    runsPerLine = layout.wordRuns,
                    baselines = layout.baselines,
                    startsX = layout.startsX,
                    virtualPosition = virtualPosition,
                    sungColorArgb = sungArgb,
                    unsungColorArgb = layout.unsungColorArgb,
                    glowColorArgb = glowArgb,
                    glowEnabled = glowEnabled,
                    betterLyrics = layout.betterLyrics,
                    sinkPx = karaokeFloatSinkPx(layout.lineHeight)
                )
            }
        } else {
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
}

/**
 * 逐字词位绘制(共享渲染核心 [LyricWordKaraokeRenderer]):主行(PreviewMainLayer)与
 * 辅助文字行(PreviewSecondaryKaraokeRow)共用同一份「虚拟播放位置 → 已唱比例/块级高亮」
 * 映射,杜绝两条预览路径各写一套换算。实机对应 drawWordKaraoke 与 drawAuxKaraokeRow。
 */
private fun drawPreviewKaraokeRuns(
    canvas: android.graphics.Canvas,
    paint: TextPaint,
    runsPerLine: List<List<PreviewWordRun>>,
    baselines: List<Float>,
    startsX: List<Float>,
    virtualPosition: Long,
    sungColorArgb: Int,
    unsungColorArgb: Int,
    glowColorArgb: Int,
    glowEnabled: Boolean,
    betterLyrics: Boolean,
    sinkPx: Float,
    alphaFactor: Float = 1f
) {
    runsPerLine.forEachIndexed { index, runs ->
        if (runs.isEmpty()) return@forEachIndexed
        val baseline = baselines.getOrNull(index) ?: return@forEachIndexed
        val startX = startsX.getOrNull(index) ?: return@forEachIndexed
        val karaokeRuns = ArrayList<KaraokeWordRun>(runs.size)
        runs.forEach { run ->
            val durationMs = run.endMs - run.startMs
            karaokeRuns += KaraokeWordRun(
                text = run.text,
                x = startX + run.x,
                width = run.width,
                playedFraction = timedWordProgress(virtualPosition, run.startMs, run.endMs),
                durationMs = durationMs,
                longSyllable = run.longSyllable || isLongKaraokeSyllable(durationMs),
                highlightFraction = if (run.highlightStartMs >= 0L) {
                    timedWordProgress(virtualPosition, run.highlightStartMs, run.highlightEndMs)
                } else {
                    -1f
                }
            )
        }
        LyricWordKaraokeRenderer.draw(
            canvas = canvas,
            paint = paint,
            runs = karaokeRuns,
            baseline = baseline,
            sungColor = sungColorArgb,
            unsungColor = unsungColorArgb,
            glowColor = glowColorArgb,
            glowEnabled = glowEnabled,
            betterLyrics = betterLyrics,
            sinkPx = sinkPx,
            alphaFactor = alphaFactor
        )
    }
}

/**
 * 辅助文字行逐字效果(「辅助文字逐字效果」)的预览渲染参数,随行块一起冻结:
 * [spanMs] 为演示虚拟时间跨度(与主行合成源同取真实行窗),<=0 时不参与;
 * [alphaFactor] 为「高亮辅助文字」解析后的行亮度档(与实机 drawAuxKaraokeRow 同源)。
 */
private class PreviewAuxKaraoke(
    val spanMs: Long,
    val betterLyrics: Boolean,
    val alphaFactor: Float
)

/**
 * 预览辅助文字逐字行的实测布局:断行/落位/词位一次算好,逐帧只按进度取比例绘制。
 * [spanStartMs]..[spanEndMs] 为演示进度映射到的虚拟时间跨度:合成路径=行窗(与历史逐字
 * 推进一致),和声行真实词窗路径=片段词窗总跨度(长音节占比如实呈现)。
 */
private class PreviewAuxKaraokeLayout(
    val paint: TextPaint,
    val startsX: List<Float>,
    val baselines: List<Float>,
    val lineHeightPx: Float,
    val heightPx: Float,
    val runs: List<List<PreviewWordRun>>,
    val spanStartMs: Long,
    val spanEndMs: Long
)

/**
 * 辅助文字行逐字效果(预览侧,与实机 drawAuxKaraokeRow 同源):断行走共享
 * [layoutSecondaryLines],词块合成/时间窗走共享纯函数([syntheticKaraokeBlocks] /
 * [syntheticCharTimeWindow]),绘制委托共享 [LyricWordKaraokeRenderer]。
 *
 * 预览是演示循环,进度由行块演示进度 [progress] 映射到虚拟时间轴;源侧真实逐字音标时间
 * 不参与预览(演示态没有真实播放位置),与主行行级源的合成路径同式。**例外是和声行**:
 * [words] 非空(插件逐音节下发)且重建得出整行文本时,断行与时间轴都按真实词窗点亮
 * (与实机 harmonyTimedLines 共用 [harmonyTimedSegments] 判据,预览即实机)——否则预览
 * 把和声的拖长音平摊掉,与实机抢拍漂移是同款形态。
 */
@Composable
private fun PreviewSecondaryKaraokeRow(
    row: PreviewSecondaryLine,
    color: ComposeColor,
    typeface: Typeface,
    availableWidthPx: Int,
    preferredLines: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    textAlign: TextAlign,
    progress: Float,
    auxKaraoke: PreviewAuxKaraoke,
    glowColor: ComposeColor,
    glowEnabled: Boolean,
    /** 本行自己的逐字词表(和声行携带,见 [PreviewBlockRow.words]);空 = 纯合成路径。 */
    words: List<LyricWord> = emptyList(),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val measureTypeface = if (row.italic) Typeface.create(typeface, Typeface.ITALIC) else typeface
    val sizePx = with(density) { row.size.toPx() }
    val layout = remember(
        row.text,
        words,
        sizePx,
        measureTypeface,
        availableWidthPx,
        preferredLines,
        wrap,
        adaptiveSectioning,
        textAlign,
        auxKaraoke.spanMs,
        density.density
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.typeface = measureTypeface
        }
        // 和声行带真实词窗时按词窗点亮(判据与实机 harmonyTimedLines 同源:共享纯函数 +
        // secondaryTimedVisualRanges 折行);重建不出整行文本/无词表时整行回落合成路径。
        val segments = harmonyTimedSegments(
            words.map {
                AodCanvasWord(
                    text = it.text,
                    romanized = it.romanized,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    boundaryAfter = it.boundaryAfter,
                    sourceStart = it.sourceStart,
                    sourceEnd = it.sourceEnd
                )
            },
            row.text,
            paint::measureText
        )
        val lineSegments = if (segments == null) {
            emptyList()
        } else {
            secondaryTimedVisualRanges(
                segments,
                availableWidthPx.toFloat(),
                MAX_SECONDARY_LAYOUT_LINES,
                wrap = wrap && adaptiveSectioning
            ).map { range -> range.map(segments::get) }
        }
        // 断行与实机 wrapSecondaryText 同门控/同算法(引擎内部门控);真实词窗行按片段折行
        // (与实机同式),行文本即片段文本直接相连。
        val lines = if (lineSegments.isEmpty()) {
            layoutSecondaryLines(
                text = row.text,
                paint = paint,
                availableWidth = availableWidthPx.toFloat(),
                preferredLines = preferredLines,
                wrap = wrap,
                adaptiveSectioning = adaptiveSectioning
            )
        } else {
            lineSegments.map { segmentsOfLine ->
                LyricLayoutTextLine(
                    segmentsOfLine.joinToString("") { it.text },
                    segmentsOfLine.sumOf { it.width.toDouble() }.toFloat()
                )
            }
        }
        val metrics = paint.fontMetrics
        val lineHeight = safeSecondaryLineHeight(metrics.ascent, metrics.descent, metrics.bottom)
        val alignment = when (textAlign) {
            TextAlign.Center -> "center"
            TextAlign.End -> "end"
            else -> "start"
        }
        val startsX = lines.map { line ->
            lineStartX(
                text = line.text,
                textWidth = line.width,
                paint = paint,
                alignment = alignment,
                canvasWidth = availableWidthPx.toFloat(),
                padLeft = 0f,
                padRight = 0f,
                density = density.density
            )
        }
        val runs = if (lineSegments.isEmpty()) {
            syntheticPreviewWordRuns(lines, paint, auxKaraoke.spanMs)
        } else {
            // 逐字词位几何取片段自身宽与真实词窗(与实机 drawAuxKaraokeRow 逐字段路径同式):
            // 长音节判定按词窗时长,无块级高亮窗(逐字源语义)。
            lineSegments.map { segmentsOfLine ->
                var x = 0f
                segmentsOfLine.map { segment ->
                    val durationMs = segment.endMs - segment.startMs
                    val run = PreviewWordRun(
                        text = segment.text,
                        x = x,
                        width = segment.width,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        longSyllable = isLongKaraokeSyllable(durationMs)
                    )
                    x += segment.width + segment.gapAfter
                    run
                }
            }
        }
        val timedSegments = lineSegments.flatten()
        PreviewAuxKaraokeLayout(
            paint = paint,
            startsX = startsX,
            baselines = lines.indices.map { -metrics.ascent + it * lineHeight },
            lineHeightPx = lineHeight,
            heightPx = lineHeight * lines.size,
            runs = runs,
            spanStartMs = timedSegments.minOfOrNull { it.startMs } ?: 0L,
            spanEndMs = timedSegments.maxOfOrNull { it.endMs } ?: auxKaraoke.spanMs.coerceAtLeast(1L)
        )
    }
    val virtualPosition = layout.spanStartMs +
        ((layout.spanEndMs - layout.spanStartMs) * progress.coerceIn(0f, 1f)).toLong()
    val colorArgb = color.toArgb()
    val glowArgb = glowColor.toArgb()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(with(density) { layout.heightPx.toDp() })
    ) {
        drawIntoCanvas { canvas ->
            drawPreviewKaraokeRuns(
                canvas = canvas.nativeCanvas,
                paint = layout.paint,
                runsPerLine = layout.runs,
                baselines = layout.baselines,
                startsX = layout.startsX,
                virtualPosition = virtualPosition,
                sungColorArgb = colorArgb,
                unsungColorArgb = colorArgb,
                glowColorArgb = glowArgb,
                glowEnabled = glowEnabled,
                betterLyrics = auxKaraoke.betterLyrics,
                sinkPx = karaokeFloatSinkPx(layout.lineHeightPx),
                alphaFactor = auxKaraoke.alphaFactor
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

