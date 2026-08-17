package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.root.HookLogger
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
    private var cadenceWindowStartedAt = 0L
    private var cadenceCallbackCount = 0
    private var cadenceDrawCount = 0
    private var cadenceMaxDrawGapMs = 0L
    private var cadenceLastDrawAt = 0L
    private var verticalAlignment = AodCanvasVerticalAlignment.TOP
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val fontContext = runCatching {
        context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()
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
    private val horizontalSweepShaders = SparseArray<LinearGradient>(4)
    private val verticalSweepShaders = SparseArray<LinearGradient>(4)
    private val sweepMatrix = Matrix()
    private var currentRenderStyle = captureRenderStyle()
    private var contentBoundsChangedListener: (() -> Unit)? = null
    private val typefaceCache = HashMap<TypefaceKey, Typeface>(3)
    private var sceneActive = false
    private var aggregatedVisible = false
    private val cadenceGate = EffectiveCadenceGate()
    private val frame = object : Runnable {
        override fun run() {
            if (!effectiveCadenceActive()) {
                syncCadence()
                return
            }
            recordDozeCadenceCallback()
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
        val typeface = resolveTypeface(nextContent.fontFamily, nextContent.weight)
        originalPaint.typeface = typeface
        if (nextContent.fontFamily != "auto") {
            val regularTypeface = resolveTypeface(nextContent.fontFamily, "Regular")
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
            verticalBounds(layout),
            exitSnapshot?.layout?.let(::verticalBounds)
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
        recordDozeDraw()
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
        val sharedLineLevelSweep = shouldUseSharedLineLevelSweep(
            drawContent.lineLevelSync,
            drawLayout.original.lines.isNotEmpty(),
            drawContent.animationMode,
            drawContent.lineStartMs,
            drawContent.lineEndMs
        )
        if (sharedLineLevelSweep) {
            drawSharedLineLevelRows(canvas, drawLayout.rows)
        } else {
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
        }
        canvas.restoreToCount(layer)
        content = savedContent
        layout = savedLayout
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun drawSharedLineLevelRows(canvas: Canvas, rows: List<PositionedRow>) {
        val original = rows.firstOrNull { it.row.kind == RowKind.ORIGINAL } ?: return
        val progress = lineProgress()
        clearBlockSweepShaders()
        val mode = resolvedLineSyncFillMode(content.lineLevelSync, content.lineSyncFillMode)
        if (mode == "Left to right (whole block)") {
            drawWholeBlockSweepRows(canvas, rows, original.baseline, progress)
            drawLineLevelWordOverlay(canvas, original.baseline)
            return
        }
        drawSecondaryRowsStatic(canvas, rows, bright = content.secondaryTextBright)
        drawOriginalRubyRows(canvas, original.baseline, bright = true)
        when (mode) {
            "None" -> {
                drawUntimedLines(canvas, original.baseline, bright = true, progress)
            }
            "Left to right (main only)" -> {
                drawContinuousLineFill(canvas, original.baseline, progress)
                clearBlockSweepShaders()
            }
            else -> {
                drawUntimedLines(canvas, original.baseline, false, progress)
                val blockTop = (original.baseline + original.row.paint.fontMetrics.ascent)
                    .coerceAtLeast(paddingTop.toFloat())
                val blockBottom = (blockTop + original.row.height)
                    .coerceAtMost((height - paddingBottom).toFloat())
                applyBlockSweepShaders(
                    origin = blockTop,
                    progress = progress,
                    extent = blockBottom - blockTop
                )
                drawUntimedLines(canvas, original.baseline, true, progress)
                clearBlockSweepShaders()
            }
        }
        // 整行扫光基础上叠加缩小幅度的逐字高亮：发光保持饱满，同时当前演唱词有轻微放大/光斑。
        drawLineLevelWordOverlay(canvas, original.baseline)
    }

    /**
     * 行级同步(整行扫光)时叠加的逐字高亮：仅对当前演唱词做缩小幅度的放大/光斑，
     * 兼顾整行扫光的饱满发光与逐字动画的节奏感（幅度弱于逐字路径的 drawOriginal）。
     */
    private fun drawLineLevelWordOverlay(canvas: Canvas, baseline: Float) {
        if (content.animationMode == "Minimal" || content.words.isEmpty()) return
        val position = projectedPosition()
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < layout.original.lines.size) {
            val line = layout.original.lines[lineIndex]
            if (line.words.isEmpty()) {
                precedingRuby += line.rubyHeight
                lineIndex++
                continue
            }
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                layout.original.lineHeight,
                precedingRuby,
                line.rubyHeight,
                layout.original.lineGap
            )
            var x = 0f
            var wordIndex = 0
            while (wordIndex < line.words.size) {
                val placed = line.words[wordIndex]
                val word = placed.word
                val width = placed.width
                val wordX = line.startX + x
                val active = position >= word.startMs && position < word.endMs
                if (active) {
                    val progress = timedWordProgress(position, word.startMs, word.endMs)
                    val scale = if (content.animationMode != "Minimal") {
                        lineLevelWordScale(progress)
                    } else 1f
                    val glow = if (content.animationMode != "Minimal" && content.glowMode != "Off") {
                        LINE_LEVEL_GLOW_PEAK * glowSpline(progress)
                    } else 0f
                    canvas.save()
                    canvas.scale(scale, scale, wordX + width / 2f, lineBaseline)
                    originalPaint.shader = null
                    setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                    drawGlowHalo(canvas, word.text, 0, word.text.length, wordX, lineBaseline, originalPaint, glow)
                    canvas.drawText(word.text, wordX, lineBaseline, originalPaint)
                    canvas.restore()
                }
                x += width + placed.gapAfter
                wordIndex++
            }
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    private fun lineLevelWordScale(t: Float): Float =
        if (t <= 0.7f) lerp(0.98f, 1.02f, t / 0.7f) else lerp(1.02f, 1f, (t - 0.7f) / 0.3f)

    private fun drawWholeBlockSweepRows(
        canvas: Canvas,
        rows: List<PositionedRow>,
        baseline: Float,
        progress: Float
    ) {
        drawSecondaryRowsStatic(canvas, rows, bright = false)
        drawOriginalRubyRows(canvas, baseline, bright = false)
        drawUntimedLines(canvas, baseline, bright = false, progress)
        applyWholeBlockHorizontalSweepShaders(progress)
        drawSecondaryRowsStatic(
            canvas,
            rows,
            bright = content.secondaryTextBright,
            keepShader = true
        )
        drawOriginalRubyRows(canvas, baseline, bright = true)
        drawUntimedLines(canvas, baseline, bright = true, progress)
        clearBlockSweepShaders()
    }

    private fun drawSecondaryRowsStatic(
        canvas: Canvas,
        rows: List<PositionedRow>,
        bright: Boolean,
        keepShader: Boolean = false
    ) {
        var rowIndex = 0
        while (rowIndex < rows.size) {
            val positioned = rows[rowIndex]
            if (positioned.row.kind == RowKind.ORIGINAL ||
                positioned.row.kind == RowKind.METADATA
            ) {
                rowIndex++
                continue
            }
            if (positioned.row.kind == RowKind.NEXT_LINE) {
                setTextAlpha(
                    positioned.row.paint,
                    staticNextLineTextFactor(),
                    1f,
                    resolvedPalette.secondaryText
                )
            } else {
                setTextAlpha(
                    positioned.row.paint,
                    staticSecondaryTextFactor(bright),
                    1f,
                    resolvedPalette.secondaryText
                )
            }
            if (!keepShader) positioned.row.paint.shader = null
            positioned.row.paint.clearShadowLayer()
            var lineIndex = 0
            while (lineIndex < positioned.row.lines.size) {
                val line = positioned.row.lines[lineIndex]
                canvas.drawText(
                    line.text,
                    line.startX,
                    positioned.baseline + lineIndex * positioned.row.lineHeight,
                    positioned.row.paint
                )
                lineIndex++
            }
            rowIndex++
        }
    }

    private fun drawOriginalRubyRows(canvas: Canvas, baseline: Float, bright: Boolean) {
        var precedingRuby = 0f
        layout.original.lines.forEachIndexed { lineIndex, line ->
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                layout.original.lineHeight,
                precedingRuby,
                line.rubyHeight,
                layout.original.lineGap
            )
            if (line.ruby.isNotEmpty()) drawRuby(canvas, line, lineBaseline, bright)
            precedingRuby += line.rubyHeight
        }
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
        val originalLayout = buildOriginalLayout()
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
            rows += row(RowKind.METADATA, content.metadata, metadataPaint, 0f, false)
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
            val lines = transliterationLines(originalLayout)
                ?: wrapSecondaryText(content.romanized, romanizedPaint, originalLayout.lineCount)
            rows += rowWithLines(RowKind.ROMANIZED, content.romanized, romanizedPaint, 2f * density, lines)
        }
        if (showTranslation && content.translated.isNotBlank()) {
            rows += rowWithLines(
                RowKind.TRANSLATED,
                content.translated,
                translatedPaint,
                2f * density,
                wrapSecondaryText(content.translated, translatedPaint, originalLayout.lineCount)
            )
        }
        if (content.showNextLine && content.nextLine.isNotBlank()) {
            rows += rowWithLines(
                RowKind.NEXT_LINE,
                content.nextLine,
                nextLinePaint,
                4f * density,
                wrapSecondaryText(content.nextLine, nextLinePaint, 1)
            )
        }
        layout = LayoutState(positionRows(rows, originalLayout), originalLayout)
        contentBoundsChangedListener?.invoke()
    }

    private fun verticalBounds(state: LayoutState): AodCanvasVerticalBounds? {
        if (state.rows.isEmpty()) return null
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        state.rows.forEach { positioned ->
            val rowTop = positioned.baseline + positioned.row.paint.fontMetrics.ascent
            top = minOf(top, rowTop)
            bottom = maxOf(bottom, rowTop + positioned.row.height)
        }
        if (!top.isFinite() || !bottom.isFinite() || bottom <= top) return null
        return AodCanvasVerticalBounds(
            top.coerceIn(0f, height.toFloat()),
            bottom.coerceIn(0f, height.toFloat())
        )
    }

    private fun row(kind: RowKind, text: String, paint: Paint, gap: Float, allowWrap: Boolean = true): Row {
        val lines = if (allowWrap) {
            wrapSecondaryText(text, paint, MAX_SECONDARY_LINES)
        } else {
            listOf(textLine(text, paint.measureText(text), paint, alignmentFor(kind)))
        }
        return rowWithLines(kind, text, paint, gap, lines)
    }

    private fun rowWithLines(
        kind: RowKind,
        text: String,
        paint: Paint,
        gap: Float,
        lines: List<TextLine>
    ): Row {
        val metrics = paint.fontMetrics
        val lineHeight = safeSecondaryLineHeight(metrics.ascent, metrics.descent, metrics.bottom)
        return Row(kind, text, paint, lineHeight * lines.size, gap, lines, lineHeight)
    }

    private fun positionRows(rows: List<Row>, originalLayout: OriginalLayout): List<PositionedRow> {
        val positioned = ArrayList<PositionedRow>(rows.size)
        val metadata = rows.firstOrNull { it.kind == RowKind.METADATA }
        if (metadata != null) {
            val anchor = when (content.metadataAnchor) {
                "bottom" -> "bottom"
                else -> "top"
            }
            val metadataBounds = metadataLayoutBounds(
                anchor,
                height.toFloat(),
                paddingTop.toFloat(),
                paddingBottom.toFloat(),
                metadata.paint.fontMetrics.ascent,
                metadata.paint.fontMetrics.descent,
                10f * density
            )
            val metadataBaseline = metadataBounds.metadataBaseline
            positioned += PositionedRow(metadata, metadataBaseline, false)
            val lyricRows = rows.filterNot { it.kind == RowKind.METADATA }
            val gap = 10f * density
            if (anchor == "bottom") {
                var bottom = metadataBounds.lyricEnd
                lyricRows.asReversed().forEach { row ->
                    bottom -= row.height
                    positioned += PositionedRow(row, bottom - row.paint.fontMetrics.ascent, true)
                    bottom -= row.gapBefore
                }
                positioned.sortBy { it.baseline }
            } else {
                var top = metadataBounds.lyricStart
                lyricRows.forEach { row ->
                    top += row.gapBefore
                    positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                    top += row.height
                }
            }
        } else {
            val total = rows.sumOf { (it.height + it.gapBefore).toDouble() }.toFloat()
            val topPadding = paddingTop.toFloat()
            val bottomPadding = height - paddingBottom
            val available = (bottomPadding - topPadding).coerceAtLeast(0f)
            var top = if (verticalAlignment == AodCanvasVerticalAlignment.TOP) {
                topPadding
            } else {
                topPadding + max(0f, (available - total) / 2f)
            }
            rows.forEach { row ->
                top += row.gapBefore
                positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                top += row.height
            }
        }
        val original = positioned.firstOrNull { it.row.kind == RowKind.ORIGINAL }
        val firstLine = originalLayout.lines.firstOrNull()
        if (original == null || firstLine == null || firstLine.rubyHeight <= 0f) return positioned
        val firstBaseBaseline = original.baseline + firstLine.rubyHeight
        val top = rubyClipTop(firstBaseBaseline, originalPaint.fontMetrics.ascent, firstLine.rubyHeight)
        val shift = rubyTopShift(top, paddingTop.toFloat())
        return if (shift == 0f) positioned else positioned.map {
            if (it.row.kind == RowKind.METADATA) it else it.copy(baseline = it.baseline + shift)
        }
    }

    private fun drawOriginal(canvas: Canvas, baseline: Float) {
        val originalLayout = layout.original
        val lines = originalLayout.lines
        if (!originalLayout.timed) {
            val progress = if (content.animationMode == "Minimal") 1f else lineProgress()
            if (resolvedLineSyncFillMode(content.lineLevelSync, content.lineSyncFillMode) ==
                "Top to bottom"
            ) {
                drawUntimedTopToBottom(canvas, baseline, progress)
            } else {
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
                    val clipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
                    if (line.ruby.isNotEmpty()) {
                        drawRuby(canvas, line, lineBaseline)
                    }
                    drawLineFill(
                        canvas,
                        line,
                        lineBaseline,
                        originalLayout.continuousFill(progress, lineIndex),
                        false
                    )
                    if (clipSave != -1) canvas.restoreToCount(clipSave)
                    precedingRuby += line.rubyHeight
                    lineIndex++
                }
            }
            return
        }
        val position = projectedPosition()
        // 与预览一致：整块单一总进度，进入逐行循环前一次算好，
        // 循环内按行宽加权分摊（OriginalLayout.continuousFill ≙ 预览 splitContinuousFill），
        // 扫光作为一条连续光带依次穿过各视觉行，而非各行各自独立推进。
        val blockFill = if (content.animationMode != "Minimal" && content.glowMode != "Off") {
            blockSweepProgress(originalLayout)
        } else 0f
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
            // 整行发光：纯复刻预览 PreviewAnimatedLyric，整行扫光，无逐字动画。
            // Pass 1 未唱整行暗底 + Pass 2 已扫光晕 + Pass 3 扫光带，完全对齐预览。
            val previewGlow = content.animationMode != "Minimal" && content.glowMode != "Off"
            if (previewGlow) {
                // Pass 1: 未唱区整行暗底 —— 对齐预览 dim 底色（主色 30% 不透明度）。
                // 不走 steadyTextAlpha：其 AOD 增亮(≈0.56)会明显压低已唱/未唱对比度。
                originalPaint.shader = null
                originalPaint.setShadowLayer(0f, 0f, 0f, 0)
                originalPaint.color = resolvedPalette.unsungText
                originalPaint.alpha = UNSUNG_BASE_ALPHA
                drawOriginalText(canvas, line, lineBaseline)
                // Pass 2 + Pass 3: 已扫区光晕 + 扫光带（drawPreviewStyleGlow 内部完成）
                drawPreviewStyleGlow(canvas, line, lineBaseline, originalLayout.continuousFill(blockFill, lineIndex))
                if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
                precedingRuby += line.rubyHeight
                lineIndex++
                continue
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
                val animated = content.animationMode != "Minimal"
                val scale = if (animated && active) scaleSpline(progress) else if (animated && !sung) 0.95f else 1f
                val y = if (animated && active) yOffsetSpline(progress) * originalPaint.textSize
                else if (animated && !sung) 0.01f * originalPaint.textSize else 0f
                val glow = 0f
                canvas.save()
                val wordBaseline = lineBaseline
                if (animated) canvas.scale(scale, scale, wordX + width / 2f, wordBaseline)
                originalPaint.shader = null
                setTextAlpha(
                    originalPaint,
                    if (content.animationMode == "Minimal" || sung) 1f else 0.35f,
                    1f,
                    if (sung) resolvedPalette.sungText else resolvedPalette.unsungText
                )
                drawGlowHalo(canvas, word.text, 0, word.text.length, wordX, wordBaseline + y, originalPaint, glow)
                canvas.drawText(word.text, wordX, wordBaseline + y, originalPaint)
                if (active && content.animationMode == "Gradient") {
                    setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                    applySoftSweep(
                        originalPaint,
                        resolvedPalette.sungText,
                        origin = wordX,
                        progress = progress,
                        extent = width,
                        vertical = false
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

    private fun drawUntimedTopToBottom(canvas: Canvas, baseline: Float, progress: Float) {
        val lines = layout.original.lines
        val firstLine = lines.firstOrNull()
        val firstLineBaseline = firstLine?.let {
            originalLineBaseline(
                baseline,
                0,
                layout.original.lineHeight,
                0f,
                it.rubyHeight,
                layout.original.lineGap
            )
        } ?: baseline
        val blockTop = max(
            paddingTop.toFloat(),
            rubyClipTop(firstLineBaseline, originalPaint.fontMetrics.ascent, firstLine?.rubyHeight ?: 0f)
        )
        val blockHeight = originalRowHeight(
            layout.original.lineHeight,
            lines.size,
            layout.original.rubyHeight,
            layout.original.lineGap
        )
        clearBlockSweepShaders()
        drawUntimedLines(canvas, baseline, false, progress)
        drawOriginalRubyRows(canvas, baseline, bright = false)
        applyBlockSweepShaders(
            origin = blockTop,
            progress = progress,
            extent = blockHeight
        )
        drawUntimedLines(canvas, baseline, true, progress)
        drawOriginalRubyRows(canvas, baseline, bright = true)
        clearBlockSweepShaders()
    }

    private fun drawContinuousLineFill(canvas: Canvas, baseline: Float, progress: Float) {
        val originalLayout = layout.original
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < originalLayout.lines.size) {
            val line = originalLayout.lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
            )
            val clipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
            drawLineFill(
                canvas,
                line,
                lineBaseline,
                originalLayout.continuousFill(progress, lineIndex),
                false
            )
            if (clipSave != -1) canvas.restoreToCount(clipSave)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    private fun drawUntimedLines(canvas: Canvas, baseline: Float, bright: Boolean, progress: Float) {
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < layout.original.lines.size) {
            val line = layout.original.lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                layout.original.lineHeight,
                precedingRuby,
                line.rubyHeight,
                layout.original.lineGap
            )
            val clipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
            val glow = if (content.animationMode != "Minimal" && content.glowMode != "Off" && bright) {
                GLOW_LINE_INTENSITY
            } else 0f
            setTextAlpha(
                originalPaint,
                if (bright) 1f else 0.35f,
                1f,
                if (bright) resolvedPalette.sungText else resolvedPalette.unsungText
            )
            drawOriginalText(canvas, line, lineBaseline, glow)
            if (clipSave != -1) canvas.restoreToCount(clipSave)
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

    private fun recordDozeCadenceCallback() {
        if (!useDozeHandlerCadence || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceWindowStartedAt == 0L) cadenceWindowStartedAt = now
        cadenceCallbackCount++
        if (now - cadenceWindowStartedAt < CADENCE_DIAGNOSTIC_WINDOW_MS) return
        HookLogger.i(
            CADENCE_DIAGNOSTIC_TAG,
            "callbacks=$cadenceCallbackCount draws=$cadenceDrawCount " +
                "maxDrawGapMs=$cadenceMaxDrawGapMs"
        )
        cadenceWindowStartedAt = now
        cadenceCallbackCount = 0
        cadenceDrawCount = 0
        cadenceMaxDrawGapMs = 0L
    }

    private fun recordDozeDraw() {
        if (!useDozeHandlerCadence || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceLastDrawAt > 0L) {
            cadenceMaxDrawGapMs = maxOf(cadenceMaxDrawGapMs, now - cadenceLastDrawAt)
        }
        cadenceLastDrawAt = now
        cadenceDrawCount++
    }

    private fun buildOriginalLayout(): OriginalLayout {
        val words = coalesceRubyWords(
            content.original,
            content.words.filter { it.text.isNotBlank() },
            content.ruby
        )
        val lines = if (words.isEmpty()) {
            if (content.adaptiveSectioning) layoutTextByGroups()
            else wrapText(content.original, originalPaint)
        } else {
            layoutWordLines(words, 8f * density)
        }
        val metrics = originalPaint.fontMetrics
        return OriginalLayout(
            assignRuby(lines),
            metrics.descent - metrics.ascent + 2f * density,
            ORIGINAL_LINE_GAP_DP * density,
            words.isNotEmpty()
        )
    }

    private fun layoutTextByGroups(): List<OriginalLine> {
        val ranges = coveredLayoutRanges(content.original, content.layoutGroups)
        if (ranges.isEmpty()) return wrapText(content.original, originalPaint)
        val synthetic = ranges.mapIndexed { index, range ->
            val nextStart = ranges.getOrNull(index + 1)?.first ?: range.last + 1
            val boundaryAfter = index < ranges.lastIndex && content.original
                .substring(range.last + 1, nextStart).any { it.isWhitespace() }
            AodCanvasWord(
                content.original.substring(range.first, range.last + 1),
                "",
                0L,
                0L,
                boundaryAfter,
                range.first,
                range.last + 1
            )
        }
        return layoutWordLines(synthetic, 8f * density)
    }

    private fun layoutWordLines(words: List<AodCanvasWord>, gap: Float): List<OriginalLine> {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val maxLines = lyricLayoutLineLimit(words.size)
        val offsets = wordOffsets(words)
        val placed = words.mapIndexed { index, word ->
            val wordWidth = originalPaint.measureText(word.text)
            val gapAfter = if (index == words.lastIndex) {
                0f
            } else {
                authoredWordSeparator(content.original, word, words[index + 1])
                    ?.let(originalPaint::measureText)
                    ?: aodWordGapAfter(word.boundaryAfter, gap)
            }
            PlacedWord(word, wordWidth, gapAfter, offsets[index])
        }
        if (content.overflowMode != "Wrap") {
            return listOf(wordLine(placed))
        }
        if (!content.adaptiveSectioning) {
            return legacyAttachedWordLineRanges(
                words,
                placed.map(PlacedWord::width),
                placed.map(PlacedWord::gapAfter),
                available,
                maxLines
            ).map { range ->
                val lineWords = range.map(placed::get)
                wordLine(lineWords)
            }
        }
        val groupIds = lexicalGroupIds(offsets, content.layoutGroups)
        val chunks = ArrayList<List<PlacedWord>>()
        var index = 0
        while (index < placed.size) {
            val groupId = groupIds[index]
            var end = index + 1
            if (groupId != null) while (end < placed.size && groupIds[end] == groupId) end++
            val chunk = placed.subList(index, end)
            val chunkWidth = chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
            if (chunkWidth > available && chunk.size > 1) chunk.forEach { chunks += listOf(it) }
            else chunks += chunk.toList()
            index = end
        }
        val chunkWidths = chunks.map { chunk ->
            chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
        }
        val lines = balancedChunkRanges(chunkWidths, available, maxLines).map { range ->
            val lineWords = range.flatMap { chunks[it] }
            wordLine(lineWords)
        }
        return lines.ifEmpty { listOf(originalLine("", 0f, null, null)) }
    }

    private fun wordLine(words: List<PlacedWord>): OriginalLine {
        val mapped = words.mapNotNull { word -> word.offset?.let { it.first to it.last + 1 } }
        val offsets = mapped.takeIf { it.size == words.size }
        val start = offsets?.minOf { it.first }
        val end = offsets?.maxOf { it.second }
        val text = if (start != null && end != null && start >= 0 && end <= content.original.length) {
            content.original.substring(start, end)
        } else {
            buildString {
                words.forEachIndexed { index, placed ->
                    append(placed.word.text)
                    if (index < words.lastIndex && placed.gapAfter > 0f) append(' ')
                }
            }
        }
        val width = words.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat() -
            (words.lastOrNull()?.gapAfter ?: 0f)
        return originalLine(
            text,
            width,
            start,
            end
        )
            .copy(words = words)
    }

    private fun wrapText(text: String, paint: Paint): List<OriginalLine> {
        if (text.isBlank()) return emptyList()
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        if (content.overflowMode != "Wrap") {
            return listOf(originalLine(text, paint.measureText(text), 0, text.length))
        }
        val maxLines = lyricLayoutLineLimit()
        val lines = ArrayList<OriginalLine>(maxLines)
        var remaining = text
        var charStart = 0
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
            val line = remaining.take(count)
            lines += originalLine(line, paint.measureText(line), charStart, charStart + line.length)
            remaining = remaining.drop(count)
            charStart += count
        }
        return lines
    }

    private fun lyricLayoutLineLimit(wordCount: Int = content.words.size): Int =
        resolvedLyricLayoutLineLimit(
            content.lyricLineLimit,
            content.original.length,
            wordCount
        )

    private fun transliterationLines(originalLayout: OriginalLayout): List<TextLine>? {
        if (originalLayout.lines.isEmpty() || originalLayout.lines.any { it.words.isEmpty() }) return null
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val sourceWords = originalLayout.lines.flatMap { it.words }.map { it.word }
        if (sourceWords.isEmpty()) return null
        val spaceWidth = romanizedPaint.measureText(" ")
        val timedIndexes = timedRomanizedWordIndexes(sourceWords)
        val segments = timedIndexes.mapIndexed { renderedIndex, sourceIndex ->
            val word = sourceWords[sourceIndex]
            val text = word.romanized.trim()
            val nextSourceIndex = timedIndexes.getOrNull(renderedIndex + 1)
            SecondaryTimedSegment(
                text = text,
                width = romanizedPaint.measureText(text),
                gapAfter = if (nextSourceIndex != null && word.boundaryAfter) spaceWidth else 0f,
                startMs = word.startMs,
                endMs = word.endMs
            )
        }
        if (segments.isEmpty()) return null
        return secondaryTimedVisualRanges(
            segments,
            available,
            MAX_SECONDARY_LINES,
            wrap = content.adaptiveSectioning && content.overflowMode == "Wrap"
        ).map { range ->
            val lineSegments = range.map(segments::get).mapIndexed { index, segment ->
                if (index == range.count() - 1) segment.copy(gapAfter = 0f) else segment
            }
            val text = buildString {
                lineSegments.forEach { segment ->
                    append(segment.text)
                    if (segment.gapAfter > 0f) append(' ')
                }
            }
            val lineWidth = lineSegments.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
            textLine(text, lineWidth, romanizedPaint).copy(timedSegments = lineSegments)
        }
    }

    private fun wrapSecondaryText(text: String, paint: Paint, preferredLines: Int): List<TextLine> {
        if (!content.adaptiveSectioning || content.overflowMode != "Wrap") {
            return listOf(textLine(text, paint.measureText(text), paint))
        }
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val tokens = secondaryTokens(text).flatMap { token ->
            if (paint.measureText(token) <= available) {
                listOf(token)
            } else {
                val pieces = ArrayList<String>()
                var remaining = token
                while (remaining.isNotEmpty()) {
                    val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
                    pieces += remaining.take(count)
                    remaining = remaining.drop(count)
                }
                pieces
            }
        }
        if (tokens.isEmpty()) return emptyList()
        val maxLines = if (paint.measureText(text) > available) {
            maxOf(preferredLines, MAX_SECONDARY_LINES)
        } else {
            preferredLines
        }.coerceIn(1, MAX_SECONDARY_LINES)
        return balancedTokenLineTexts(
            tokens,
            tokens.map(paint::measureText),
            paint.measureText(" "),
            available,
            maxLines
        ).map { line -> textLine(line, paint.measureText(line), paint) }
    }

    private fun textLine(
        text: String,
        width: Float,
        paint: Paint,
        lineAlignment: Alignment = alignment
    ): TextLine {
        val visual = visualExtents(text, paint, width)
        return TextLine(text, width, alignedStart(width, lineAlignment, visual.first, visual.second))
    }

    private fun originalLine(text: String, width: Float, charStart: Int?, charEnd: Int?): OriginalLine {
        val visual = visualExtents(text, originalPaint, width)
        return OriginalLine(
            text,
            emptyList(),
            width,
            alignedStart(width, alignment, visual.first, visual.second),
            charStart,
            charEnd
        )
    }

    private fun wordOffsets(words: List<AodCanvasWord>): List<IntRange?> =
        words.map { transportedWordOffset(content.original, it) }

    private fun assignRuby(lines: List<OriginalLine>): List<OriginalLine> = lines.map { line ->
        val lineStart = line.charStart
        val lineEnd = line.charEnd
        if (lineStart == null || lineEnd == null) return@map line

        val placements = content.ruby.asSequence()
            .filter { segment ->
                segment.start >= 0 && segment.end > segment.start &&
                    segment.end <= content.original.length &&
                    segment.start < lineEnd && segment.end > lineStart
            }
            .sortedBy { it.start }
            .mapNotNull { segment ->
                val baseStart = maxOf(segment.start, lineStart)
                val baseEnd = minOf(segment.end, lineEnd)
                val baseRun = measureBaseRun(line, baseStart, baseEnd) ?: return@mapNotNull null
                val geometry = rubySpanGeometry(
                    baseRun.x,
                    baseRun.width,
                    rubyPaint.measureText(segment.reading)
                )
                RubyPlacement(
                    baseStart = baseStart,
                    baseEnd = baseEnd,
                    baseX = geometry.baseX,
                    baseWidth = geometry.baseWidth,
                    spanX = geometry.spanX,
                    spanWidth = geometry.spanWidth,
                    extraWidth = 0f,
                    baseOffset = 0f,
                    rubyCenterX = geometry.rubyCenterX,
                    reading = segment.reading
                )
            }
            .toList()
        val rubyHeight = if (placements.isEmpty()) 0f else {
            rubyReservation(originalPaint.textSize, rubyPaint.fontMetrics.ascent)
        }
        val baseVisual = visualExtents(line.text, originalPaint, line.width)
        val visualLeft = minOf(
            baseVisual.first,
            placements.minOfOrNull { it.spanX } ?: baseVisual.first
        )
        val visualRight = maxOf(
            baseVisual.second,
            placements.maxOfOrNull { it.spanX + it.spanWidth } ?: baseVisual.second
        )
        line.copy(
            startX = alignedStart(line.width, alignment, visualLeft, visualRight),
            ruby = placements,
            rubyHeight = rubyHeight,
            textRuns = originalTextRuns(
                line.text.length,
                placements.map { placement ->
                    OriginalTextRun(
                        (placement.baseStart - lineStart).coerceIn(0, line.text.length),
                        (placement.baseEnd - lineStart).coerceIn(0, line.text.length),
                        placement.baseX
                    )
                }
            ) { end -> originalPaint.measureText(line.text, 0, end) }
        )
    }

    private fun measureBaseRun(line: OriginalLine, start: Int, end: Int): BaseRun? {
        if (start >= end) return null
        val lineStart = line.charStart ?: return null
        if (line.words.isEmpty()) {
            val localStart = (start - lineStart).coerceIn(0, line.text.length)
            val localEnd = (end - lineStart).coerceIn(localStart, line.text.length)
            val prefixWidth = originalPaint.measureText(line.text, 0, localStart)
            return BaseRun(
                prefixWidth,
                originalPaint.measureText(line.text, localStart, localEnd)
            )
        }

        var x = 0f
        var firstX: Float? = null
        var lastX = 0f
        line.words.forEach { placed ->
            val offset = placed.offset
            if (offset != null) {
                val wordStart = offset.first
                val wordEnd = offset.last + 1
                val overlapStart = maxOf(start, wordStart)
                val overlapEnd = minOf(end, wordEnd)
                if (overlapStart < overlapEnd) {
                    val localStart = overlapStart - wordStart
                    val localEnd = overlapEnd - wordStart
                    val runStart = x + originalPaint.measureText(placed.word.text, 0, localStart)
                    val runEnd = x + originalPaint.measureText(placed.word.text, 0, localEnd)
                    if (firstX == null) firstX = runStart
                    lastX = runEnd
                }
            }
            x += placed.width + placed.gapAfter
        }
        val baseX = firstX ?: return null
        return BaseRun(baseX, (lastX - baseX).coerceAtLeast(0f))
    }

    private fun drawLineFill(
        canvas: Canvas,
        line: OriginalLine,
        baseline: Float,
        progress: Float,
        clipToPaddedWidth: Boolean
    ) {
        val x = line.startX
        val clipSave = if (clipToPaddedWidth) canvas.save() else -1
        if (clipToPaddedWidth) canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        originalPaint.shader = null
        setTextAlpha(originalPaint, 0.35f, 1f, resolvedPalette.unsungText)
        drawOriginalText(canvas, line, baseline)
        setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
        val glow = if (content.animationMode != "Minimal" && content.glowMode != "Off") {
            GLOW_LINE_INTENSITY
        } else 0f
        applySoftSweep(
            originalPaint,
            resolvedPalette.sungText,
            origin = x,
            progress = progress,
            extent = line.width,
            vertical = false
        )
        drawOriginalText(canvas, line, baseline, glow)
        originalPaint.shader = null
        if (clipToPaddedWidth) canvas.restoreToCount(clipSave)
    }

    private fun drawOriginalText(
        canvas: Canvas,
        line: OriginalLine,
        baseline: Float,
        glow: Float = 0f
    ) {
        if (line.ruby.isEmpty()) {
            drawGlowHalo(canvas, line.text, 0, line.text.length, line.startX, baseline, originalPaint, glow)
            canvas.drawText(line.text, line.startX, baseline, originalPaint)
            return
        }
        if (line.textRuns.isEmpty()) {
            drawGlowHalo(canvas, line.text, 0, line.text.length, line.startX, baseline, originalPaint, glow)
            canvas.drawText(line.text, line.startX, baseline, originalPaint)
            return
        }
        var index = 0
        while (index < line.textRuns.size) {
            val run = line.textRuns[index]
            val runX = line.startX + run.x
            drawGlowHalo(canvas, line.text, run.start, run.end, runX, baseline, originalPaint, glow)
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

    private fun alignmentFor(kind: RowKind): Alignment = if (kind == RowKind.METADATA) {
        when (content.alignmentMode) {
            "start" -> Alignment.START
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> Alignment.START
        }
    } else {
        alignment
    }

    private fun alignedStart(
        textWidth: Float,
        lineAlignment: Alignment = alignment,
        visualLeft: Float = 0f,
        visualRight: Float = textWidth
    ): Float = edgeSafeAlignedStart(
        canvasWidth = width.toFloat(),
        paddingLeft = paddingLeft.toFloat(),
        paddingRight = paddingRight.toFloat(),
        visualLeft = visualLeft,
        visualRight = visualRight,
        alignment = when (lineAlignment) {
            Alignment.START -> "start"
            Alignment.CENTER -> "center"
            Alignment.END -> "end"
        },
        safetyInset = if (lineAlignment == Alignment.END) END_EDGE_SAFETY_DP * density else 0f
    )

    private fun visualExtents(text: String, paint: Paint, advanceWidth: Float): Pair<Float, Float> {
        if (text.isEmpty()) return 0f to advanceWidth
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return minOf(0f, bounds.left.toFloat()) to maxOf(advanceWidth, bounds.right.toFloat())
    }

    private fun projectedPosition(): Long {
        val elapsed = (SystemClock.elapsedRealtime() - content.sampledAtElapsedMs).coerceAtLeast(0L)
        return content.positionMs + (elapsed * content.speed).toLong()
    }

    private fun lineProgress(): Float = progress(projectedPosition(), content.lineStartMs, content.lineEndMs)

    private fun progress(position: Long, start: Long, end: Long): Float =
        if (end <= start) if (position >= end) 1f else 0f
        else ((position - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun scaleSpline(t: Float): Float = if (t <= 0.7f) lerp(0.95f, 1.0505f, t / 0.7f)
    else lerp(1.0505f, 1f, (t - 0.7f) / 0.3f)

    private fun yOffsetSpline(t: Float): Float = if (t <= 0.9f) lerp(0.01f, -(1f / 60f), t / 0.9f)
    else lerp(-(1f / 60f), 0f, (t - 0.9f) / 0.1f)

    private fun glowSpline(t: Float): Float = when {
        t <= 0.15f -> lerp(0f, 1f, t / 0.15f)
        t <= 0.6f -> 1f
        else -> lerp(1f, 0f, (t - 0.6f) / 0.4f)
    }

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

    private fun applySoftSweep(
        paint: Paint,
        color: Int,
        origin: Float,
        progress: Float,
        extent: Float,
        vertical: Boolean
    ) {
        val shaders = if (vertical) verticalSweepShaders else horizontalSweepShaders
        var shader = shaders[color]
        if (shader == null) {
            val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            val middle = Color.argb(184, Color.red(color), Color.green(color), Color.blue(color))
            shader = if (vertical) {
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    1f,
                    intArrayOf(color, middle, transparent),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    0f,
                    0f,
                    1f,
                    0f,
                    intArrayOf(color, middle, transparent),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            shaders.put(color, shader)
        }
        val safeExtent = extent.coerceAtLeast(0f)
        val band = (safeExtent * SWEEP_BAND_FRACTION).coerceAtLeast(1f)
        val start = origin - band + (safeExtent + band) * progress.coerceIn(0f, 1f)
        sweepMatrix.setScale(if (vertical) 1f else band, if (vertical) band else 1f)
        sweepMatrix.postTranslate(if (vertical) 0f else start, if (vertical) start else 0f)
        shader.setLocalMatrix(sweepMatrix)
        paint.shader = shader
    }

    private fun applyBlockSweepShaders(origin: Float, progress: Float, extent: Float) {
        applySoftSweep(originalPaint, resolvedPalette.sungText, origin, progress, extent, vertical = true)
    }

    private fun applyWholeBlockHorizontalSweepShaders(progress: Float) {
        val origin = paddingLeft.toFloat()
        val extent = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        applySoftSweep(originalPaint, resolvedPalette.sungText, origin, progress, extent, false)
        applySoftSweep(romanizedPaint, resolvedPalette.secondaryText, origin, progress, extent, false)
        applySoftSweep(translatedPaint, resolvedPalette.secondaryText, origin, progress, extent, false)
        applySoftSweep(rubyPaint, resolvedPalette.secondaryText, origin, progress, extent, false)
    }

    private fun clearBlockSweepShaders() {
        originalPaint.shader = null
        romanizedPaint.shader = null
        translatedPaint.shader = null
        rubyPaint.shader = null
    }

    private fun drawGlowHalo(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        paint: Paint,
        glow: Float
    ) {
        if (glow <= 0.02f || end <= start) return
        val intensity = glow.coerceIn(0f, 1f)
        val glowColor = resolvedPalette.glow
        val savedShader = paint.shader
        val savedColor = paint.color
        val savedAlpha = paint.alpha
        // 柔和光晕：单独绘制一个带模糊阴影的发光层，shader 置空以规避
        // 硬件加速下 shadow+shader 同置导致发光丢失的问题。
        paint.shader = null
        paint.color = glowColor
        paint.alpha = (GLOW_HALO_ALPHA * intensity).roundToInt().coerceIn(0, 255)
        paint.setShadowLayer(
            paint.textSize * GLOW_HALO_RADIUS * intensity,
            0f,
            0f,
            glowColor
        )
        canvas.drawText(text, start, end, x, y, paint)
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.color = savedColor
        paint.alpha = savedAlpha
        paint.shader = savedShader
    }

    private fun easeInOutSine(t: Float): Float =
        -(kotlin.math.cos(kotlin.math.PI.toFloat() * t.coerceIn(0f, 1f)) - 1f) / 2f

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun sweepPeakX(line: OriginalLine, progress: Float): Float {
        val timed = line.words.filter { it.word.startMs >= 0L && it.word.endMs > it.word.startMs }
        if (timed.size >= 2 && content.lineEndMs > content.lineStartMs) {
            val now = projectedPosition()
            var x = line.startX
            val centers = ArrayList<Float>(timed.size)
            timed.forEach { pw ->
                centers.add(x + pw.width * 0.5f)
                x += pw.width + pw.gapAfter
            }
            val n = timed.size
            val us = ArrayList<Float>(n)
            timed.forEach { pw ->
                us.add((pw.word.startMs + (pw.word.endMs - pw.word.startMs) * 0.5f).toFloat())
            }
            val span = us[n - 1] - us[0]
            if (span <= 0f) return centers[0]
            // 全局缓动一次, 整行速度连续; 逐字节奏由时间比例承载
            val us0 = us[0]
            val u = easeInOutSine(((now - us0) / span).coerceIn(0f, 1f))
            for (i in 0 until n) us[i] = (us[i] - us0) / span
            // 定位到当前字区间
            var i = 0
            while (i < n - 1 && u > us[i + 1]) i++
            val lt = if (n > 1 && us[i + 1] > us[i]) ((u - us[i]) / (us[i + 1] - us[i])).coerceIn(0f, 1f) else 0f
            val p0 = centers[if (i > 0) i - 1 else 0]
            val p1 = centers[i]
            val p2 = centers[if (i + 1 < n) i + 1 else n - 1]
            val p3 = centers[if (i + 2 < n) i + 2 else n - 1]
            return catmullRom(p0, p1, p2, p3, lt)
        }
        return line.startX + easeInOutSine(progress.coerceIn(0f, 1f)) * line.width
    }

    // 辅助：按行布局绘制整行文字（含 ruby 分段），用于发光层
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

    // 复刻预览 PreviewAnimatedLyric 的发光方式：
    // Pass 2 — clip 到已扫区域，用 setShadowLayer 画 sungText 色文字产生光晕，
    //          文字本身保持 sungText 色（不被 glow 色覆盖），shadow 用 glow 色散发柔光。
    // Pass 3 — LinearGradient 扫光带（sung→middle→transparent, CLAMP），
    //          左侧已唱区常亮，光锋处柔和过渡，右侧未唱区透明。
    // 两层都画在文字背后（per-word 循环之前），光从文字背后透出，不遮挡文字。
    // 整块扫光总进度（对齐预览 previewProjectedProgress 的"整块单一进度"结构）：
    // 预览按行级时间戳驱动整块 p，再 splitContinuousFill 按行宽加权分摊到各视觉行。
    // 纯逐字歌词源行级时间常为 0，progress() 会退化为 1f（整块全亮、已唱未唱无法区分），
    // 故优先取整块全部单词范围（全局首词 startMs → 末词 endMs），无单词时间再回退行级。
    private fun blockSweepProgress(layout: OriginalLayout): Float {
        var startMs = Long.MAX_VALUE
        var endMs = Long.MIN_VALUE
        layout.lines.forEach { line ->
            line.words.forEach { placed ->
                val word = placed.word
                if (word.startMs >= 0L && word.endMs > word.startMs) {
                    if (word.startMs < startMs) startMs = word.startMs
                    if (word.endMs > endMs) endMs = word.endMs
                }
            }
        }
        if (startMs != Long.MAX_VALUE && endMs > startMs) {
            return progress(projectedPosition(), startMs, endMs)
        }
        return lineProgress()
    }

    private fun drawPreviewStyleGlow(
        canvas: Canvas,
        line: OriginalLine,
        baseline: Float,
        progress: Float
    ) {
        val glowColor = resolvedPalette.glow
        val sungColor = resolvedPalette.sungText
        val band = (line.width * SWEEP_BAND_FRACTION).coerceAtLeast(1f)
        val sweepStart = line.startX - band + (line.width + band) * progress.coerceIn(0f, 1f)
        val sweepEnd = sweepStart + band
        val fm = originalPaint.fontMetrics

        val savedShader = originalPaint.shader
        val savedColor = originalPaint.color
        val savedAlpha = originalPaint.alpha
        // 光晕半径作为纵向溢出余量：仅需覆盖文字高度 + 光晕模糊范围，
        // 避免用水平扫光带宽度做纵向扩展（行宽大时会过度扩展到数倍文字高度）。
        val haloRadius = originalPaint.textSize * GLOW_HALO_RADIUS

        // Pass 2: 光晕层 — clip 到已扫区域，sungText 色文字 + glow 色阴影
        canvas.save()
        canvas.clipRect(
            line.startX - band,
            baseline + fm.ascent - haloRadius,
            sweepEnd,
            baseline + fm.descent + haloRadius
        )
        originalPaint.shader = null
        originalPaint.color = sungColor
        originalPaint.alpha = 255
        originalPaint.setShadowLayer(
            originalPaint.textSize * GLOW_HALO_RADIUS,
            0f, 0f,
            glowColor
        )
        drawLineTextForGlow(canvas, line, baseline, originalPaint)
        originalPaint.setShadowLayer(0f, 0f, 0f, 0)
        canvas.restore()

        // Pass 3: 扫光亮部 — LinearGradient(sung→middle→transparent, CLAMP)
        val transparent = Color.argb(0, Color.red(sungColor), Color.green(sungColor), Color.blue(sungColor))
        val middle = Color.argb(184, Color.red(sungColor), Color.green(sungColor), Color.blue(sungColor))
        originalPaint.shader = LinearGradient(
            sweepStart, 0f, sweepEnd, 0f,
            intArrayOf(sungColor, middle, transparent),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        originalPaint.color = sungColor
        originalPaint.alpha = 255
        drawLineTextForGlow(canvas, line, baseline, originalPaint)
        originalPaint.shader = null

        originalPaint.color = savedColor
        originalPaint.alpha = savedAlpha
        originalPaint.shader = savedShader
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

    private fun resolveTypeface(family: String, weight: String): Typeface {
        val key = TypefaceKey(family, weight)
        typefaceCache[key]?.let { return it }
        val asset = if (family == "noto") {
            "fonts/NotoSans-" + when (weight) {
                "Bold" -> "Bold"
                "Medium" -> "Medium"
                else -> "Regular"
            } + ".ttf"
        } else if (family == "apple") {
            if (weight == "Regular") "fonts/lyrics_medium.ttf" else "fonts/sf-pro-display-bold.ttf"
        } else if (weight == "Bold") {
            "fonts/sf-pro-display-bold.ttf"
        } else {
            "fonts/spotifymix-medium.ttf"
        }
        val typeface = runCatching {
            Typeface.createFromAsset(fontContext?.assets ?: context.assets, asset)
        }.getOrElse {
            val fallback = if (family == "apple") "sans-serif" else "sans-serif-medium"
            Typeface.create(fallback, if (weight == "Bold") Typeface.BOLD else Typeface.NORMAL)
        }
        typefaceCache[key] = typeface
        return typeface
    }

    private enum class RowKind { METADATA, ORIGINAL, ROMANIZED, TRANSLATED, NEXT_LINE }
    private data class Row(
        val kind: RowKind,
        val text: String,
        val paint: Paint,
        val height: Float,
        val gapBefore: Float,
        val lines: List<TextLine>,
        val lineHeight: Float
    )
    private data class PlacedWord(
        val word: AodCanvasWord,
        val width: Float,
        val gapAfter: Float,
        val offset: IntRange?
    )
    private data class OriginalLine(
        val text: String,
        val words: List<PlacedWord>,
        val width: Float,
        val startX: Float,
        val charStart: Int?,
        val charEnd: Int?,
        val ruby: List<RubyPlacement> = emptyList(),
        val rubyHeight: Float = 0f,
        val textRuns: List<OriginalTextRun> = emptyList()
    )
    private data class TextLine(
        val text: String,
        val width: Float,
        val startX: Float,
        val timedSegments: List<SecondaryTimedSegment> = emptyList()
    )
    private data class BaseRun(val x: Float, val width: Float)
    private data class RubyPlacement(
        val baseStart: Int,
        val baseEnd: Int,
        val baseX: Float,
        val baseWidth: Float,
        val spanX: Float,
        val spanWidth: Float,
        val extraWidth: Float,
        val baseOffset: Float,
        val rubyCenterX: Float,
        val reading: String
    )
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
    private data class PositionedRow(val row: Row, val baseline: Float, val animate: Boolean)
    private data class OriginalLayout(
        val lines: List<OriginalLine>,
        val lineHeight: Float,
        val lineGap: Float,
        val timed: Boolean
    ) {
        val lineCount: Int
            get() = lines.size
        val rubyHeight: Float
            get() = lines.sumOf { it.rubyHeight.toDouble() }.toFloat()
        private val totalLineWidth = lines.sumOf { it.width.coerceAtLeast(0f).toDouble() }.toFloat()
        private val precedingWidths = FloatArray(lines.size).also { values ->
            var preceding = 0f
            lines.forEachIndexed { index, line ->
                values[index] = preceding
                preceding += line.width.coerceAtLeast(0f)
            }
        }

        fun continuousFill(progress: Float, lineIndex: Int): Float {
            val width = lines[lineIndex].width.coerceAtLeast(0f)
            if (width == 0f || totalLineWidth <= 0f) return 0f
            return ((progress.coerceIn(0f, 1f) * totalLineWidth - precedingWidths[lineIndex]) / width)
                .coerceIn(0f, 1f)
        }
    }
    private data class LayoutState(
        val rows: List<PositionedRow>,
        val original: OriginalLayout
    )
    private data class TypefaceKey(val family: String, val weight: String)

    companion object {
        private const val MAX_SECONDARY_LINES = 2
        private const val ENTER_TRANSITION_MS = 210L
        private const val EXIT_TRANSITION_MS = 130L
        private const val ORIGINAL_LINE_GAP_DP = 4f
        private const val END_EDGE_SAFETY_DP = 4f
        private const val CADENCE_DIAGNOSTIC_WINDOW_MS = 10_000L
        private const val CADENCE_DIAGNOSTIC_TAG = "AodCanvasCadence"
        private const val GLOW_LINE_INTENSITY = 0.8f
        private const val LINE_LEVEL_GLOW_PEAK = 0.5f
        private const val GLOW_HALO_ALPHA = 235
        private const val GLOW_HALO_RADIUS = 0.36f
        // 未唱底色不透明度，对齐预览 dim 底色 color.copy(alpha = 0.30f)。
        private const val UNSUNG_BASE_ALPHA = (255 * 0.30f).toInt()
    }
}
