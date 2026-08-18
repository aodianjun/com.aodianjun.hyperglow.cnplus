package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded Spicy live-card renderer adapted for Xiaomi AOD. */
internal class AodLyricCanvasView(
    context: Context,
    private val useDozeHandlerCadence: Boolean = false
) : View(context) {
    enum class Alignment { START, CENTER, END }

    private var content = AodCanvasContent(
        trackGeneration = 0L,
        metadata = "",
        original = "",
        romanized = "",
        translated = "",
        alignedRight = false,
        lineLevelSync = false,
        lineStartMs = 0,
        lineEndMs = 0,
        positionMs = 0,
        sampledAtElapsedMs = 0,
        speed = 1f,
        words = emptyList(),
        ruby = emptyList(),
        layoutGroups = emptyList(),
        weight = "Medium",
        textSizeMode = "normal",
        textSizeCustom = 100,
        secondaryMode = "Main only",
        animationMode = "Gradient",
        glowMode = "Off",
        motionMode = "Fluid",
        lineSyncFillMode = "Top to bottom",
        overflowMode = "Wrap",
        transitionMode = "Fade up",
        fontFamily = "noto",
        alignmentMode = "auto",
        metadataVisible = true,
        metadataAnchor = "top",
        metadataSizePercent = 100,
        adaptiveSectioning = true,
        palette = emptyMap(),
        secondaryTextBright = true,
        lyricLineLimit = 3
    )
    private var resolvedPalette = resolveAodPalette(emptyMap())
    private var alignment = Alignment.START
    private var layout = LayoutState(emptyList(), OriginalLayout(emptyList(), 0f, 0f, false))
    private var exitSnapshot: CanvasSnapshot? = null
    private var transitionStartedAt = 0L
    private var handoffActive = false
    private var suppressNextLineTransition = false
    private var timingEffectEnabled = false
    private val dozeCadenceTracker = DozeCadenceTracker(useDozeHandlerCadence)
    private var verticalAlignment = AodCanvasVerticalAlignment.TOP
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val metadataPaint = paint(14f, 0xB3FFFFFF.toInt(), Typeface.NORMAL)
    private val originalPaint = paint(27f, Color.WHITE, Typeface.NORMAL)
    private val romanizedPaint = paint(17f, Color.WHITE, Typeface.NORMAL)
    private val translatedPaint = paint(17f, Color.WHITE, Typeface.ITALIC)
    private val nextLinePaint = paint(15f, 0x59FFFFFF.toInt(), Typeface.NORMAL).apply {
        textAlign = Paint.Align.LEFT
    }
    private val rubyPaint = paint(11f, 0xB3FFFFFF.toInt(), Typeface.NORMAL).apply {
        textAlign = Paint.Align.CENTER
    }
    private var currentRenderStyle = captureRenderStyle()
    private var contentBoundsChangedListener: (() -> Unit)? = null
    private val typefaceResolver = AodTypefaceResolver(context)
    private var sceneActive = false
    private var aggregatedVisible = false
    private val cadenceGate = EffectiveCadenceGate()
    private val frame = object : Runnable {
        override fun run() {
            if (!effectiveCadenceActive()) {
                syncCadence()
                return
            }
            dozeCadenceTracker.recordDozeCadenceCallback()
            if (exitSnapshot != null && isExitTransitionExpired(
                    transitionStartedAt,
                    SystemClock.elapsedRealtime(),
                    ENTER_TRANSITION_MS
                )
            ) {
                transitionStartedAt = 0L
                exitSnapshot = null
                contentBoundsChangedListener?.invoke()
            }
            invalidate()
            val interval = frameInterval()
            if (interval > 0L) scheduleFrame(this, interval)
            else {
                cadenceGate.update(false)
                removeCallbacks(this)
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_NONE, null)
    }

    fun setContent(incomingContent: AodCanvasContent) {
        val nextContent = incomingContent.copy(
            animationMode = normalizeAodAnimation(incomingContent.animationMode),
            motionMode = normalizeAodMotion(incomingContent.motionMode),
            overflowMode = normalizeAodOverflow(incomingContent.overflowMode)
        )
        val lineChanged = this.content.original.isNotBlank() &&
            aodCanvasLineIdentity(this.content) != aodCanvasLineIdentity(nextContent)
        val resuming = suppressNextLineTransition
        suppressNextLineTransition = false
        if (shouldStartLineTransition(
                lineChanged,
                nextContent.transitionMode,
                handoffActive,
                resuming
            )
        ) {
            exitSnapshot = CanvasSnapshot(content, layout, currentRenderStyle)
            transitionStartedAt = SystemClock.elapsedRealtime()
        } else if (resuming || nextContent.transitionMode == "None") {
            exitSnapshot = null
            transitionStartedAt = 0L
        }
        this.content = nextContent
        timingEffectEnabled = hasActiveCanvasTiming(
            nextContent.lineLevelSync,
            nextContent.lineSyncFillMode,
            nextContent.lineStartMs,
            nextContent.lineEndMs,
            nextContent.words,
            nextContent.speed
        )
        resolvedPalette = resolveAodPalette(nextContent.palette)
        alignment = when (nextContent.alignmentMode) {
            "start" -> Alignment.START
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> if (nextContent.alignedRight) Alignment.END else Alignment.START
        }
        val sizeScale = textSizeModeMultiplier(nextContent.textSizeMode, nextContent.textSizeCustom)
        val baseSp = baseTextSizeSp(nextContent.original) * sizeScale
        val typeface = typefaceResolver.resolve(nextContent.fontFamily, nextContent.weight)
        originalPaint.typeface = typeface
        if (nextContent.fontFamily != "auto") {
            val regularTypeface = typefaceResolver.resolve(nextContent.fontFamily, "Regular")
            metadataPaint.typeface = regularTypeface
            romanizedPaint.typeface = regularTypeface
            translatedPaint.typeface = Typeface.create(regularTypeface, Typeface.ITALIC)
            nextLinePaint.typeface = regularTypeface
            rubyPaint.typeface = regularTypeface
        } else {
            metadataPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            romanizedPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            translatedPaint.typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            nextLinePaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            rubyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        originalPaint.textSize = baseSp * scaledDensity
        metadataPaint.textSize = 14f * metadataTextSizeMultiplier(
            nextContent.metadataSizePercent
        ) * scaledDensity
        romanizedPaint.textSize = max(14f, kotlin.math.round(baseSp * 0.48f)) * scaledDensity
        translatedPaint.textSize = max(13f, kotlin.math.round(baseSp * 0.48f) - 1f) * scaledDensity
        rubyPaint.textSize = originalPaint.textSize * 0.46f
        currentRenderStyle = captureRenderStyle()
        rebuildLayout()
        syncCadence()
        invalidate()
    }

    fun stop() {
        cadenceGate.update(false)
        removeCallbacks(frame)
        exitSnapshot = null
        transitionStartedAt = 0L
        suppressNextLineTransition = true
        contentBoundsChangedListener?.invoke()
    }

    fun setContentBoundsChangedListener(listener: (() -> Unit)?) {
        contentBoundsChangedListener = listener
        listener?.invoke()
    }

    fun setVerticalAlignment(alignment: AodCanvasVerticalAlignment) {
        if (verticalAlignment == alignment) return
        verticalAlignment = alignment
        rebuildLayout()
        invalidate()
    }

    fun visibleContentVerticalBounds(): AodCanvasVerticalBounds? =
        unionAodCanvasVerticalBounds(
            verticalBounds(layout, height),
            exitSnapshot?.layout?.let { verticalBounds(it, height) }
        )

    fun setHandoffActive(active: Boolean) {
        handoffActive = active
        if (active) {
            exitSnapshot = null
            transitionStartedAt = 0L
        }
        syncCadence()
    }

    fun setSceneActive(active: Boolean) {
        if (sceneActive == active) return
        sceneActive = active
        syncCadence()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        aggregatedVisible = isShown
        syncCadence()
    }

    override fun onDetachedFromWindow() {
        stop()
        aggregatedVisible = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncCadence()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncCadence()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        syncCadence()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        rebuildLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dozeCadenceTracker.recordDozeDraw()
        syncCadence()
        val snapshot = exitSnapshot
        if (snapshot == null) {
            drawMetadata(canvas, layout)
            drawRows(canvas, layout, content, 1f, 0f)
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - transitionStartedAt).coerceAtLeast(0L)
        val exitProgress = (elapsed / EXIT_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val enterProgress = (elapsed / ENTER_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val metadataMorph = shouldMorphSongChangeMetadata(
            previousOriginal = snapshot.content.original,
            previousMetadata = snapshot.content.metadata,
            previousLineStartMs = snapshot.content.lineStartMs,
            previousLineEndMs = snapshot.content.lineEndMs,
            previousHasTimedWords = snapshot.content.words.any { it.endMs > it.startMs },
            nextMetadata = content.metadata,
            nextMetadataVisible = content.metadataVisible
        ) && canDrawMetadataMorph(snapshot)
        if (metadataMorph) {
            drawMetadataMorph(canvas, snapshot, enterProgress)
        } else if (snapshot.content.metadata != content.metadata ||
            snapshot.content.metadataVisible != content.metadataVisible ||
            snapshot.content.metadataAnchor != content.metadataAnchor
        ) {
            drawMetadata(canvas, snapshot.layout, 1f - exitProgress, snapshot.renderStyle)
            drawMetadata(canvas, layout, enterProgress)
        } else {
            drawMetadata(canvas, layout)
        }
        drawRows(
            canvas,
            snapshot.layout,
            snapshot.content,
            1f - exitProgress,
            if (content.transitionMode == "Fade up") -14f * density * exitProgress else 0f,
            snapshot.renderStyle,
            skipOriginal = metadataMorph
        )
        drawRows(canvas, layout, content, enterProgress, if (content.transitionMode == "Fade up") 14f * density * (1f - enterProgress) else 0f)
        if (enterProgress >= 1f) {
            transitionStartedAt = 0L
            exitSnapshot = null
            contentBoundsChangedListener?.invoke()
        }
    }

    private fun drawRows(
        canvas: Canvas,
        drawLayout: LayoutState,
        drawContent: AodCanvasContent,
        alpha: Float,
        translateY: Float,
        renderStyle: RenderStyleSnapshot? = null,
        skipOriginal: Boolean = false
    ) {
        if (alpha <= 0f || drawLayout.rows.none {
                it.row.kind != RowKind.METADATA && (!skipOriginal || it.row.kind != RowKind.ORIGINAL)
            }
        ) return
        val savedContent = content
        val savedLayout = layout
        if (renderStyle != null) applyRenderStyle(renderStyle)
        content = drawContent
        layout = drawLayout
        val layer = if (alpha < 1f || translateY != 0f) {
            val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255f * alpha).toInt())
            canvas.translate(0f, translateY)
            save
        } else canvas.save()
        var rowIndex = 0
        while (rowIndex < drawLayout.rows.size) {
            val row = drawLayout.rows[rowIndex]
            when (row.row.kind) {
                RowKind.METADATA -> Unit
                RowKind.ORIGINAL -> if (!skipOriginal) drawOriginal(canvas, row.baseline)
                else -> drawText(canvas, row.row, row.baseline)
            }
            rowIndex++
        }
        canvas.restoreToCount(layer)
        content = savedContent
        layout = savedLayout
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun captureRenderStyle(): RenderStyleSnapshot = RenderStyleSnapshot(
        metadataPaint = Paint(metadataPaint),
        originalPaint = Paint(originalPaint),
        romanizedPaint = Paint(romanizedPaint),
        translatedPaint = Paint(translatedPaint),
        rubyPaint = Paint(rubyPaint),
        palette = resolvedPalette,
        alignment = alignment
    )

    private fun applyRenderStyle(style: RenderStyleSnapshot) {
        metadataPaint.set(style.metadataPaint)
        originalPaint.set(style.originalPaint)
        romanizedPaint.set(style.romanizedPaint)
        translatedPaint.set(style.translatedPaint)
        rubyPaint.set(style.rubyPaint)
        resolvedPalette = style.palette
        alignment = style.alignment
    }

    private fun drawMetadata(
        canvas: Canvas,
        drawLayout: LayoutState,
        alpha: Float = 1f,
        renderStyle: RenderStyleSnapshot? = null
    ) {
        if (alpha <= 0f) return
        val metadata = drawLayout.rows.firstOrNull { it.row.kind == RowKind.METADATA } ?: return
        if (renderStyle != null) applyRenderStyle(renderStyle)
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        metadata.row.paint.color = resolvedPalette.metadataText
        metadata.row.paint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt()
        metadata.row.lines.forEachIndexed { index, line ->
            canvas.drawText(
                line.text,
                line.startX,
                metadata.baseline + index * metadata.row.lineHeight,
                metadata.row.paint
            )
        }
        canvas.restore()
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun canDrawMetadataMorph(snapshot: CanvasSnapshot): Boolean =
        snapshot.layout.original.lines.size == 1 &&
            snapshot.layout.rows.count { it.row.kind == RowKind.ORIGINAL } == 1 &&
            layout.rows.firstOrNull { it.row.kind == RowKind.METADATA }
                ?.row?.lines?.size == 1

    private fun drawMetadataMorph(
        canvas: Canvas,
        snapshot: CanvasSnapshot,
        progress: Float
    ) {
        val sourceRow = snapshot.layout.rows.firstOrNull {
            it.row.kind == RowKind.ORIGINAL
        } ?: return
        val sourceLine = snapshot.layout.original.lines.singleOrNull() ?: return
        val destinationRow = layout.rows.firstOrNull {
            it.row.kind == RowKind.METADATA
        } ?: return
        val destinationLine = destinationRow.row.lines.singleOrNull() ?: return
        val value = progress.coerceIn(0f, 1f)
        val paint = Paint(
            if (value < 0.5f) snapshot.renderStyle.originalPaint
            else currentRenderStyle.metadataPaint
        ).apply {
            textSize = snapshot.renderStyle.originalPaint.textSize +
                (currentRenderStyle.metadataPaint.textSize -
                    snapshot.renderStyle.originalPaint.textSize) * value
            color = interpolateAodColor(
                snapshot.renderStyle.palette.primaryText,
                currentRenderStyle.palette.metadataText,
                value
            )
            alpha = 255
            shader = null
            clearShadowLayer()
        }
        val x = sourceLine.startX + (destinationLine.startX - sourceLine.startX) * value
        val y = sourceRow.baseline + (destinationRow.baseline - sourceRow.baseline) * value
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        canvas.drawText(content.metadata, x, y, paint)
        canvas.restore()
    }

    private fun rebuildLayout() {
        val input = layoutInput()
        val originalLayout = buildOriginalLayout(input)
        val rows = ArrayList<Row>(4)
        val hasTimedWords = content.words.any { it.endMs > it.startMs }
        val metadataPlaceholder = isSongChangeMetadataPlaceholder(
            content.original,
            content.metadata,
            content.lineStartMs,
            content.lineEndMs,
            hasTimedWords
        )
        android.util.Log.d(
            "AODMetadata",
            "rebuild metadataVisible=${content.metadataVisible} " +
                "metadata='${content.metadata}' original='${content.original}' " +
                "line=[${content.lineStartMs}..${content.lineEndMs}] timed=$hasTimedWords " +
                "placeholder=$metadataPlaceholder"
        )
        if (content.metadataVisible && content.metadata.isNotBlank() && !metadataPlaceholder) {
            rows += row(input, RowKind.METADATA, content.metadata, metadataPaint, 0f, false)
        }
        if (content.original.isNotBlank()) {
            val metrics = originalPaint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent + 2f * density
            rows += Row(
                RowKind.ORIGINAL,
                content.original,
                originalPaint,
                originalRowHeight(
                    lineHeight,
                    originalLayout.lineCount,
                    originalLayout.rubyHeight,
                    originalLayout.lineGap
                ),
                8f * density,
                emptyList(),
                lineHeight
            )
        }
        val showReading = content.secondaryMode == "Transliteration" || content.secondaryMode == "Both"
        val showTranslation = content.secondaryMode == "Translation" || content.secondaryMode == "Both"
        if (showReading && content.romanized.isNotBlank()) {
            val lines = transliterationLines(input, originalLayout)
                ?: wrapSecondaryText(input, content.romanized, romanizedPaint, originalLayout.lineCount)
            rows += rowWithLines(RowKind.ROMANIZED, content.romanized, romanizedPaint, 2f * density, lines)
        }
        if (showTranslation && content.translated.isNotBlank()) {
            rows += rowWithLines(
                RowKind.TRANSLATED,
                content.translated,
                translatedPaint,
                2f * density,
                wrapSecondaryText(input, content.translated, translatedPaint, originalLayout.lineCount)
            )
        }
        if (content.showNextLine && content.nextLine.isNotBlank()) {
            rows += rowWithLines(
                RowKind.NEXT_LINE,
                content.nextLine,
                nextLinePaint,
                4f * density,
                wrapSecondaryText(input, content.nextLine, nextLinePaint, 1)
            )
        }
        layout = LayoutState(positionRows(input, rows, originalLayout), originalLayout)
        contentBoundsChangedListener?.invoke()
    }

    private fun layoutInput() = AodLyricLayoutInput(
        content = content,
        originalPaint = originalPaint,
        romanizedPaint = romanizedPaint,
        rubyPaint = rubyPaint,
        alignment = alignment,
        verticalAlignment = verticalAlignment,
        width = width,
        height = height,
        paddingLeft = paddingLeft,
        paddingTop = paddingTop,
        paddingRight = paddingRight,
        paddingBottom = paddingBottom,
        density = density
    )

    private fun drawOriginal(canvas: Canvas, baseline: Float) {
        val originalLayout = layout.original
        val lines = originalLayout.lines
        // Minimal 模式：静态全亮，无扫光/发光。
        if (content.animationMode == "Minimal") {
            var precedingRuby = 0f
            var lineIndex = 0
            while (lineIndex < lines.size) {
                val line = lines[lineIndex]
                val lineBaseline = originalLineBaseline(
                    baseline,
                    lineIndex,
                    originalLayout.lineHeight,
                    precedingRuby,
                    line.rubyHeight,
                    originalLayout.lineGap
                )
                val lineClipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
                if (line.ruby.isNotEmpty()) {
                    drawRuby(canvas, line, lineBaseline)
                }
                originalPaint.shader = null
                originalPaint.setShadowLayer(0f, 0f, 0f, 0)
                setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                drawOriginalText(canvas, line, lineBaseline)
                if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
                precedingRuby += line.rubyHeight
                lineIndex++
            }
            return
        }
        // 逐字卡拉OK路径：仅"逐字时间源 + 关闭发光 + 非行级同步"保留，
        // 其余全部走共享 LyricGlowRenderer 统一管线（与预览同源，杜绝效果漂移）。
        if (!usesPreviewGlowPipeline(
                content.animationMode,
                originalLayout.timed,
                content.lineLevelSync,
                content.glowMode
            )
        ) {
            drawWordKaraoke(canvas, baseline, originalLayout)
            return
        }
        // 统一预览管线：dim 底 + 光晕(发光开启时) + 扫光带。
        // 整块进度：行级时间优先，纯逐字源回退全局词范围（unifiedBlockProgress）。
        val blockFill = unifiedBlockProgress(
            projectedPosition(),
            content.lineStartMs,
            content.lineEndMs,
            content.words
        )
        val glowRows = ArrayList<LyricGlowRow>(lines.size)
        var precedingRuby = 0f
        var lineIndex = 0
        // 外层统一裁剪（非 Wrap 溢出模式），替代原先逐行 clip，与预览整块绘制一致。
        val outerClip = clipOriginalBlock(canvas, baseline, originalLayout)
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
            )
            if (line.ruby.isNotEmpty()) {
                drawRuby(canvas, line, lineBaseline)
            }
            val capturedBaseline = lineBaseline
            glowRows += LyricGlowRow(
                left = line.startX,
                width = line.width,
                baseline = capturedBaseline,
                drawText = { c, p -> drawLineTextForGlow(c, line, capturedBaseline, p) }
            )
            precedingRuby += line.rubyHeight
            lineIndex++
        }
        LyricGlowRenderer.draw(
            canvas = canvas,
            paint = originalPaint,
            rows = glowRows,
            progress = blockFill,
            sungColor = resolvedPalette.sungText,
            dimBaseColor = resolvedPalette.unsungText,
            glowColor = resolvedPalette.glow,
            glowEnabled = content.glowMode != "Off"
        )
        if (outerClip != -1) canvas.restoreToCount(outerClip)
    }

    /** 统一管线的整块裁剪：非 Wrap 溢出模式时裁剪到内边距区域（含首行 ruby 顶部余量）。 */
    private fun clipOriginalBlock(canvas: Canvas, baseline: Float, originalLayout: OriginalLayout): Int {
        if (content.overflowMode == "Wrap") return -1
        val firstLine = originalLayout.lines.firstOrNull() ?: return -1
        val firstLineBaseline = originalLineBaseline(
            baseline,
            0,
            originalLayout.lineHeight,
            0f,
            firstLine.rubyHeight,
            originalLayout.lineGap
        )
        val save = canvas.save()
        canvas.clipRect(
            paddingLeft.toFloat(),
            max(
                paddingTop.toFloat(),
                rubyClipTop(firstLineBaseline, originalPaint.fontMetrics.ascent, firstLine.rubyHeight)
            ),
            width - paddingRight.toFloat(),
            (height - paddingBottom).toFloat()
        )
        return save
    }

    /** 逐字卡拉OK：逐词缩放/渐变高亮，仅用于"逐字时间源 + 关闭发光 + 非行级同步"。 */
    private fun drawWordKaraoke(canvas: Canvas, baseline: Float, originalLayout: OriginalLayout) {
        val lines = originalLayout.lines
        val position = projectedPosition()
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
            )
            val lineClipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
            if (line.ruby.isNotEmpty()) {
                drawRuby(canvas, line, lineBaseline)
            }
            var x = 0f
            var wordIndex = 0
            while (wordIndex < line.words.size) {
                val placed = line.words[wordIndex]
                val word = placed.word
                val width = placed.width
                val wordX = line.startX + x
                val progress = timedWordProgress(position, word.startMs, word.endMs)
                val active = position >= word.startMs && position < word.endMs
                val sung = position >= word.endMs
                val scale = if (active) scaleSpline(progress) else if (!sung) 0.95f else 1f
                val y = if (active) yOffsetSpline(progress) * originalPaint.textSize
                else if (!sung) 0.01f * originalPaint.textSize else 0f
                canvas.save()
                val wordBaseline = lineBaseline
                canvas.scale(scale, scale, wordX + width / 2f, wordBaseline)
                originalPaint.shader = null
                setTextAlpha(
                    originalPaint,
                    if (sung) 1f else 0.35f,
                    1f,
                    if (sung) resolvedPalette.sungText else resolvedPalette.unsungText
                )
                canvas.drawText(word.text, wordX, wordBaseline + y, originalPaint)
                if (active) {
                    setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                    applyWordSweepShader(
                        originalPaint,
                        resolvedPalette.sungText,
                        origin = wordX,
                        progress = progress,
                        extent = width
                    )
                    canvas.drawText(word.text, wordX, wordBaseline + y, originalPaint)
                    originalPaint.shader = null
                }
                canvas.restore()
                x += width + placed.gapAfter
                wordIndex++
            }
            if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    private fun drawRuby(
        canvas: Canvas,
        line: OriginalLine,
        baseBaseline: Float,
        bright: Boolean = true
    ) {
        rubyPaint.color = resolvedPalette.secondaryText
        rubyPaint.alpha = (255f * steadyTextAlpha(if (bright) 1f else 0.35f)).toInt()
        val gap = line.rubyHeight + rubyPaint.fontMetrics.ascent
        val baseline = baseBaseline + originalPaint.fontMetrics.ascent -
            gap - rubyPaint.fontMetrics.descent
        var index = 0
        while (index < line.ruby.size) {
            val placement = line.ruby[index]
            canvas.drawText(
                placement.reading,
                rubyDrawCenterX(line.startX, placement.rubyCenterX),
                baseline,
                rubyPaint
            )
            index++
        }
    }

    private fun clipOriginalLine(canvas: Canvas, baseBaseline: Float, rubyHeight: Float): Int {
        if (content.overflowMode == "Wrap") return -1
        val save = canvas.save()
        canvas.clipRect(
            paddingLeft.toFloat(),
            max(paddingTop.toFloat(), rubyClipTop(baseBaseline, originalPaint.fontMetrics.ascent, rubyHeight)),
            width - paddingRight.toFloat(),
            (height - paddingBottom).toFloat()
        )
        return save
    }

    private fun frameInterval(): Long = frameIntervalForTiming(
        effectiveCadenceActive(),
        timingActive = true
    )

    private fun effectiveCadenceActive(): Boolean = isEffectiveCadenceActive(
        attached = isAttachedToWindow,
        sceneActive = sceneActive,
        ownVisible = visibility == VISIBLE,
        windowVisible = windowVisibility == VISIBLE,
        aggregatedVisible = aggregatedVisible && isShown,
        effectiveAlpha = effectiveAlpha(),
        timedOrTransitionActive = timingEffectActive() || exitSnapshot != null,
        handoffActive = handoffActive,
        verifiedDozeHost = useDozeHandlerCadence
    )

    private fun timingEffectActive(): Boolean = timingEffectEnabled

    private fun effectiveAlpha(): Float {
        var value = alpha * transitionAlpha
        var ancestor = parent as? View
        while (ancestor != null) {
            value *= ancestor.alpha * ancestor.transitionAlpha
            if (value <= EFFECTIVE_ALPHA_THRESHOLD) return value
            ancestor = ancestor.parent as? View
        }
        return value
    }

    private fun syncCadence() {
        when (cadenceGate.update(effectiveCadenceActive())) {
            CadenceChange.START -> {
                removeCallbacks(frame)
                scheduleFrame(frame, 0L)
            }
            CadenceChange.STOP -> removeCallbacks(frame)
            CadenceChange.NONE -> Unit
        }
    }

    private fun scheduleFrame(action: Runnable, delayMs: Long) {
        if (useDozeHandlerCadence) postDelayed(action, delayMs)
        else postOnAnimation(action)
    }

    private fun drawOriginalText(
        canvas: Canvas,
        line: OriginalLine,
        baseline: Float
    ) {
        if (line.ruby.isEmpty() || line.textRuns.isEmpty()) {
            canvas.drawText(line.text, line.startX, baseline, originalPaint)
            return
        }
        var index = 0
        while (index < line.textRuns.size) {
            val run = line.textRuns[index]
            val runX = line.startX + run.x
            canvas.drawText(
                line.text,
                run.start,
                run.end,
                runX,
                baseline,
                originalPaint
            )
            index++
        }
    }

    private fun drawText(canvas: Canvas, row: Row, baseline: Float) {
        canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        var lineIndex = 0
        while (lineIndex < row.lines.size) {
            val line = row.lines[lineIndex]
            val lineBaseline = baseline + lineIndex * row.lineHeight
            if (row.kind == RowKind.METADATA) {
                row.paint.color = resolvedPalette.metadataText
                row.paint.alpha = 255
                canvas.drawText(line.text, line.startX, lineBaseline, row.paint)
            } else if (row.kind == RowKind.NEXT_LINE) {
                drawNextLine(canvas, row.paint, line.text, line.startX, lineBaseline)
            } else {
                drawSecondaryLine(canvas, row.paint, line.text, line.startX, lineBaseline)
            }
            lineIndex++
        }
        canvas.restore()
    }

    private fun projectedPosition(): Long {
        val elapsed = (SystemClock.elapsedRealtime() - content.sampledAtElapsedMs).coerceAtLeast(0L)
        return content.positionMs + (elapsed * content.speed).toLong()
    }

    private fun lineProgress(): Float =
        normalizedProgress(projectedPosition(), content.lineStartMs, content.lineEndMs)

    private fun scaleSpline(t: Float): Float = if (t <= 0.7f) lerp(0.95f, 1.0505f, t / 0.7f)
    else lerp(1.0505f, 1f, (t - 0.7f) / 0.3f)

    private fun yOffsetSpline(t: Float): Float = if (t <= 0.9f) lerp(0.01f, -(1f / 60f), t / 0.9f)
    else lerp(-(1f / 60f), 0f, (t - 0.9f) / 0.1f)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun setTextAlpha(
        paint: Paint,
        factor: Float,
        brightness: Float,
        color: Int = resolvedPalette.primaryText
    ) {
        paint.color = color
        paint.alpha = (255f * (steadyTextAlpha(factor) * brightness).coerceIn(0f, 1f)).toInt()
    }

    /**
     * 逐字卡拉OK路径的词内扫光渐变(仅"逐字源+关闭发光+非行级同步"使用):
     * 与共享 LyricGlowRenderer Pass 3 同形状([sung→middle→transparent, CLAMP]),
     * 每词绝对坐标构建,不复用缓存 shader,调用方负责置空。
     */
    private fun applyWordSweepShader(
        paint: Paint,
        color: Int,
        origin: Float,
        progress: Float,
        extent: Float
    ) {
        val safeExtent = extent.coerceAtLeast(0f)
        val band = (safeExtent * LyricGlowRenderer.SWEEP_BAND_FRACTION).coerceAtLeast(1f)
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

    // 辅助：按行布局绘制整行文字（含 ruby 分段），供共享 LyricGlowRenderer 的行回调使用
    private fun drawLineTextForGlow(canvas: Canvas, line: OriginalLine, baseline: Float, paint: Paint) {
        if (line.ruby.isEmpty() || line.textRuns.isEmpty()) {
            canvas.drawText(line.text, line.startX, baseline, paint)
        } else {
            var index = 0
            while (index < line.textRuns.size) {
                val run = line.textRuns[index]
                canvas.drawText(line.text, run.start, run.end, line.startX + run.x, baseline, paint)
                index++
            }
        }
    }

    private fun drawSecondaryLine(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        baseline: Float
    ) {
        setTextAlpha(
            paint,
            staticSecondaryTextFactor(content.secondaryTextBright),
            1f,
            resolvedPalette.secondaryText
        )
        paint.shader = null
        paint.clearShadowLayer()
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawNextLine(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        baseline: Float
    ) {
        paint.color = resolvedPalette.secondaryText
        paint.alpha = (255f * staticNextLineTextFactor()).toInt()
        paint.shader = null
        paint.clearShadowLayer()
        canvas.drawText(text, x, baseline, paint)
    }

    private fun paint(sizeSp: Float, color: Int, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeSp * scaledDensity
        setColor(color)
        typeface = Typeface.create("sans-serif", weight)
        isSubpixelText = true
    }

    private data class CanvasSnapshot(
        val content: AodCanvasContent,
        val layout: LayoutState,
        val renderStyle: RenderStyleSnapshot
    )
    private data class RenderStyleSnapshot(
        val metadataPaint: Paint,
        val originalPaint: Paint,
        val romanizedPaint: Paint,
        val translatedPaint: Paint,
        val rubyPaint: Paint,
        val palette: AodResolvedPalette,
        val alignment: Alignment
    )

    companion object {
        private const val ENTER_TRANSITION_MS = 210L
        private const val EXIT_TRANSITION_MS = 130L
    }
}
