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
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_PORTRAIT
import com.eza.hyperglow.aod.DEFAULT_CANVAS_PADDING_PERCENT
import com.eza.hyperglow.root.HookLogger
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded Spicy live-card renderer adapted for Xiaomi AOD. */
internal class AodLyricCanvasView(
    context: Context,
    private val useDozeHandlerCadence: Boolean = false,
    private val powerSaverProvider: () -> Boolean = { false },
    private val refreshRateCapProvider: () -> Int = { 0 }
) : View(context) {
    enum class Alignment { START, CENTER, END }

    // 绘制热路径性能聚合(issue #68 #13):默认跟随诊断日志开关,关闭时零开销;
    // 开启时每 5s 输出一条 draw count/avg/max 汇总,回答"掉帧的花销在哪"。
    private val perfSampler = AodPerfSampler(
        enabled = { HookLogger.traceEnabled },
        sink = { HookLogger.i("AodLyricCanvasView", it) }
    )

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
        secondaryAlignment = "auto",
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
    /** 辅助行(音标/翻译/下一行/歌曲信息)的独立对齐覆盖;null=沿用历史「auto」行为。 */
    private var secondaryAlignmentOverride: Alignment? = null
    private var layout = LayoutState(emptyList(), OriginalLayout(emptyList(), 0f, 0f, false))
    private var exitSnapshot: CanvasSnapshot? = null
    private var transitionStartedAt = 0L
    private var handoffActive = false
    private var suppressNextLineTransition = false
    private var timingEffectEnabled = false
    private var lastDrawAtElapsedMs = 0L
    private var cadenceWindowStartedAt = 0L
    private var cadenceCallbackCount = 0
    private var cadenceDrawCount = 0
    private var cadenceMaxDrawGapMs = 0L
    private var cadenceLastDrawAt = 0L
    private var verticalAlignment = AodCanvasVerticalAlignment.TOP

    // ---- AOD 画布随设备旋转(刚性绘制变换)配置 ----
    @Volatile
    private var rotationEnabled = false
    @Volatile
    private var rotationMode = AOD_ROTATION_MODE_PORTRAIT
    @Volatile
    private var rotationStep = AodOrientationStep.PORTRAIT
    private var landscapeTextScale = 1f
    private var landscapeAnchor = 0.5f
    private var landscapeFullscreen = false
    private var debugShowCanvasFrame = false
    private var paddingPortraitXPercent = DEFAULT_CANVAS_PADDING_PERCENT
    private var paddingPortraitYPercent = DEFAULT_CANVAS_PADDING_PERCENT
    private var paddingLandscapeXPercent = DEFAULT_CANVAS_PADDING_PERCENT
    private var paddingLandscapeYPercent = DEFAULT_CANVAS_PADDING_PERCENT

    // ---- 逻辑横屏框:布局/裁剪全部以逻辑宽高与逻辑内边距计算,
    //      非竖屏步进时交换视口宽高,绘制期由 beginRotationTransform 映射回视口 ----
    @Volatile
    private var ow = 0
    @Volatile
    private var oh = 0
    @Volatile
    private var padLeft = 0
    @Volatile
    private var padRight = 0
    @Volatile
    private var padTop = 0
    @Volatile
    private var padBottom = 0

    private fun recomputeLogicalFrame() {
        val landscape = rotationEnabled && rotationStep != AodOrientationStep.PORTRAIT
        if (landscape) {
            val layout = aodLandscapeFrameLayout(
                viewWidth = width,
                viewHeight = height,
                paddingXPercent = paddingLandscapeXPercent,
                paddingYPercent = paddingLandscapeYPercent
            )
            ow = layout.ow
            oh = layout.oh
            padLeft = layout.padLeft
            padRight = layout.padRight
            padTop = layout.padTop
            padBottom = layout.padBottom
            // issue #41 诊断:记录横屏逻辑帧参数,便于真机核对宽高交换/padding 是否符合预期。
            val key = "$width x $height s=$rotationStep f=$landscapeFullscreen " +
                "ow=$ow oh=$oh pL=$padLeft pT=$padTop pR=$padRight pB=$padBottom"
            if (key != lastLandscapeFrameKey) {
                lastLandscapeFrameKey = key
                HookLogger.i("AodLyricCanvasView", "Landscape frame: $key")
            }
        } else {
            ow = width
            oh = height
            padLeft = paddingLeft
            padRight = paddingRight
            padTop = paddingTop
            padBottom = paddingBottom
        }
    }

    private var invalidClipRectWarned = false
    private var lastCenteredLogKey = ""
    private var lastLandscapeFrameKey = ""
    private var lastAutoScaleLogKey = ""
    private var lastRowLayoutLogKey = ""
    private var lastTransformLogKey = ""
    private var lastRotationBoundsKey = ""

    /**
     * 裁剪防呆:padding 异常(left>=right 或 top>=bottom)会让 clipRect 变成空矩形,
     * 直接把该路径整屏裁空且日志完全静默。此处记录一次 warn 并退化为"不裁剪"(全帧),
     * 避免再次出现静默的整屏空白。
     */
    private fun lyricClipBounds(left: Int, top: Int, right: Int, bottom: Int): IntArray {
        if (left < right && top < bottom) return intArrayOf(left, top, right, bottom)
        if (!invalidClipRectWarned) {
            invalidClipRectWarned = true
            HookLogger.w(
                "AodLyricCanvasView",
                "Lyric clip rect invalid (l=$left t=$top r=$right b=$bottom); degrading to full frame"
            )
        }
        return intArrayOf(0, 0, ow, oh)
    }

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
    private val debugFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF5252.toInt() // 画布边界(逻辑帧 ow×oh)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val debugClipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA4CAF50.toInt() // 内容裁剪区
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private var currentRenderStyle = captureRenderStyle()
    private var contentBoundsChangedListener: (() -> Unit)? = null
    private var sceneActive = false
    private var aggregatedVisible = false
    private val cadenceGate = EffectiveCadenceGate()
    // issue #68 #12:deadline 调度状态。帧间隔不再每次回调后简单顺延(那样每帧都把
    // 回调延迟累积进相位),而是记录下一个到期 deadline,按整数周期推进保持相位对齐。
    private var frameDeadlineNanos = 0L
    private var lastFrameIntervalMs = -1L
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
            val nowNanos = System.nanoTime()
            if (frameDeadlineNanos != 0L && !isFrameDue(nowNanos, frameDeadlineNanos)) {
                // 早于 deadline 的回调(postDelayed 取整可能提前至多 1ms):按原 deadline
                // 重排,不提前绘制,保持相位不漂移。
                scheduleFrame(
                    this,
                    ((frameDeadlineNanos - nowNanos) / NANOS_PER_MS).coerceAtLeast(1L)
                )
                return
            }
            invalidate()
            val interval = frameInterval()
            if (interval > 0L) {
                if (interval != lastFrameIntervalMs) {
                    // 上限档/省电档切换:重置相位,避免旧 deadline 以错误周期推进。
                    frameDeadlineNanos = 0L
                    lastFrameIntervalMs = interval
                }
                val periodNanos = interval * NANOS_PER_MS
                frameDeadlineNanos = if (frameDeadlineNanos == 0L) {
                    nowNanos + periodNanos
                } else {
                    // 整数周期推进;挂起恢复落后多个周期时跳过已过周期,不补帧追赶。
                    advanceFrameDeadline(frameDeadlineNanos, periodNanos, nowNanos)
                }
                val delayMs = ((frameDeadlineNanos - nowNanos) / NANOS_PER_MS).coerceAtLeast(1L)
                scheduleFrame(this, delayMs)
            } else {
                frameDeadlineNanos = 0L
                lastFrameIntervalMs = -1L
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
        // 辅助行独立对齐:start/center/end 时覆盖主行对齐;auto 时沿用历史行为
        // (歌曲信息跟随 alignmentMode,音标/翻译/下一行跟随主行 alignment)。
        secondaryAlignmentOverride = when (nextContent.secondaryAlignment) {
            "start" -> Alignment.START
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> null
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
        // 字号公式收口到 AodCanvasTextMetrics 共享纯函数(与预览同源,杜绝两套换算漂移)。
        metadataPaint.textSize = metadataTextSizeSp(
            nextContent.metadataSizePercent
        ) * scaledDensity
        romanizedPaint.textSize = secondaryReadingTextSizeSp(baseSp) * scaledDensity
        translatedPaint.textSize = secondaryTranslationTextSizeSp(baseSp) * scaledDensity
        nextLinePaint.textSize = nextLineTextSizeSp() * scaledDensity
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

    /**
     * 配置画布的旋转/横屏参数(AodSurfaceController 在快照变化时调用)。
     * 全部为绘制期参数,不触发布局重测,只在 [onDraw] 内做刚性变换。
     */
    fun updateOrientation(
        rotate: Boolean,
        mode: String,
        landscapeTextScale: Float,
        landscapeAnchor: Float,
        landscapeFullscreen: Boolean,
        debugShowCanvasFrame: Boolean,
        paddingPortraitXPercent: Float,
        paddingPortraitYPercent: Float,
        paddingLandscapeXPercent: Float,
        paddingLandscapeYPercent: Float
    ) {
        val changed = rotationEnabled != rotate ||
            rotationMode != mode ||
            this.landscapeTextScale != landscapeTextScale ||
            this.landscapeAnchor != landscapeAnchor ||
            this.landscapeFullscreen != landscapeFullscreen ||
            this.debugShowCanvasFrame != debugShowCanvasFrame ||
            this.paddingPortraitXPercent != paddingPortraitXPercent ||
            this.paddingPortraitYPercent != paddingPortraitYPercent ||
            this.paddingLandscapeXPercent != paddingLandscapeXPercent ||
            this.paddingLandscapeYPercent != paddingLandscapeYPercent
        rotationEnabled = rotate
        rotationMode = mode
        this.landscapeTextScale = landscapeTextScale
        this.landscapeAnchor = landscapeAnchor
        this.landscapeFullscreen = landscapeFullscreen
        this.debugShowCanvasFrame = debugShowCanvasFrame
        this.paddingPortraitXPercent = paddingPortraitXPercent
        this.paddingPortraitYPercent = paddingPortraitYPercent
        this.paddingLandscapeXPercent = paddingLandscapeXPercent
        this.paddingLandscapeYPercent = paddingLandscapeYPercent
        recomputeLogicalFrame()
        if (!rotate && rotationStep != AodOrientationStep.PORTRAIT) {
            rotationStep = AodOrientationStep.PORTRAIT
        }
        if (changed) {
            rebuildLayout()
            invalidate()
        }
    }

    /** 由 AodOrientationMonitor 驱动的刚性步进;PORTRAIT 时不做变换。 */
    fun setRotationStep(step: AodOrientationStep) {
        if (rotationStep == step) return
        val previous = rotationStep
        rotationStep = step
        recomputeLogicalFrame()
        HookLogger.i(
            "AodLyricCanvasView",
            "rotation step ${previous.name}->${step.name} enabled=$rotationEnabled"
        )
        if (!rotationEnabled) {
            rotationStep = AodOrientationStep.PORTRAIT
        }
        rebuildLayout()
        invalidate()
    }

    /** 当前的横屏文本缩放等参数是否生效的查询,仅供布局层参考。 */
    fun currentRotationEnabled(): Boolean = rotationEnabled

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
        recomputeLogicalFrame()
        rebuildLayout()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        recomputeLogicalFrame()
        rebuildLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val drawStartedAt = perfSampler.begin()
        super.onDraw(canvas)
        val rotationSave = beginRotationTransform(canvas, applyScale = true)
        try {
            drawOrientedContent(canvas)
        } finally {
            if (rotationSave != NO_ROTATION_SAVE) canvas.restoreToCount(rotationSave)
        }
        // issue #44:调试边框独立于全屏缩放绘制。边框与内容共用 scale 时,scale>1 会把本就
        // 铺满 view 的逻辑帧边界推出可视区,恰好最需要看边界时反而不可见;这里用不含 scale
        // 的变换单独描边框,使其始终落在逻辑帧/视口上。
        if (debugShowCanvasFrame) {
            val frameSave = beginRotationTransform(canvas, applyScale = false)
            try {
                drawDebugCanvasFrame(canvas)
            } finally {
                if (frameSave != NO_ROTATION_SAVE) canvas.restoreToCount(frameSave)
            }
        }
        perfSampler.end(AodPerfSampler.Metric.DRAW, drawStartedAt)
    }

    private val NO_ROTATION_SAVE = -1

    /** 是否处于「横屏全屏化」激活状态(启用旋转 且 非竖屏 且 开启横屏全屏开关)。 */
    private fun fullscreenLandscapeActive(): Boolean =
        rotationEnabled &&
        rotationStep != AodOrientationStep.PORTRAIT &&
        landscapeFullscreen

    /** 是否处于横屏步进(启用旋转 且 当前非竖屏),含全屏与非全屏。 */
    private fun landscapeActive(): Boolean =
        rotationEnabled && rotationStep != AodOrientationStep.PORTRAIT

    /**
     * 绘制期生效的横屏放缩比。
     *  - 横屏全屏化:改用 [computeFullscreenAutoScale] 自动铺满,不再使用手动倍数;
     *  - 普通横屏:以用户的横向放缩倍数 [landscapeTextScale] 为上限,但若该倍数会让内容
     *    越出画布框则钳制到「恰好铺满不越界」——见 [computeLandscapeTextScale]。
     */
    private fun effectiveLandscapeScale(): Float {
        val landscape = rotationEnabled && rotationStep != AodOrientationStep.PORTRAIT
        if (!landscape) return 1f
        return if (fullscreenLandscapeActive()) {
            computeFullscreenAutoScale()
        } else {
            computeLandscapeTextScale()
        }
    }

    /**
     * 普通横屏(未开启「横屏全屏」)的放缩比。beginRotationTransform 在 scale=1 时已把
     * 逻辑帧精确铺满画布(逻辑帧 == 旋转后的画布);若直接采用用户的 [landscapeTextScale]>1,
     * 等于在已铺满的基座上再放大,帧内内容必然溢出画布框被裁(issue #54:关闭横屏全屏时
     * 歌词落在框外;issue #61:fit 系数可 >1,放大后四周溢出)。因此最终缩放由
     * [landscapeFrameFitScale] 恒钳在 1.0 —— 用户倍数与 fit 系数(垂直可用高/水平最长
     * 行宽)仅计算留痕;需要更大字号时走「横屏全屏」路径(画布扩至整屏后由
     * [computeFullscreenAutoScale] 放大)。
     */
    private fun computeLandscapeTextScale(): Float {
        val bounds = verticalBounds(layout) ?: return landscapeTextScale
        val contentHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
        val availableHeight = ((oh - padTop - padBottom).toFloat()).coerceAtLeast(1f)
        val maxLineWidth = widestContentLineWidth(layout)
        val availableWidth = ((ow - padLeft - padRight).toFloat()).coerceAtLeast(1f)
        val fitScale = minOf(
            availableHeight / contentHeight,
            availableWidth / maxLineWidth.coerceAtLeast(1f)
        )
        val scale = landscapeFrameFitScale(landscapeTextScale, fitScale)
        // issue #54/#61:记录用户倍数、fit 系数与最终缩放(恒 ≤1)、内容包围盒 vs 可用框,
        // 便于直接判定是否越界。
        val key = "u=$landscapeTextScale fit=$fitScale s=$scale " +
            "c=${contentHeight.roundToInt()} ah=${availableHeight.roundToInt()} " +
            "lw=${maxLineWidth.roundToInt()} aw=${availableWidth.roundToInt()}"
        if (key != lastAutoScaleLogKey) {
            lastAutoScaleLogKey = key
            HookLogger.i("AodLyricCanvasView", "Landscape text-scale: $key")
        }
        return scale
    }

    /**
     * 横屏全屏化的自适应放缩比:按当前内容实际占高([verticalBounds])计算一个尽量铺满
     * 但又不超过可用高度(不越界)的倍数,避免单行歌词被放得过大溢出;再以最长行宽限制
     * 长轴,保证放大后整行仍在画布内(issue #51)。钳制在
     * [FULLSCREEN_MIN_SCALE]..[FULLSCREEN_MAX_SCALE],内容已铺满时不再缩小。
     */
    private fun computeFullscreenAutoScale(): Float {
        val bounds = verticalBounds(layout) ?: return landscapeTextScale
        val contentHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
        val availableHeight = ((oh - padTop - padBottom).toFloat()).coerceAtLeast(1f)
        // 长轴输入:最长行宽与可用逻辑宽。行按 available 换行,故 maxLineWidth <= ow,
        // widthCap 不会把内容缩小;它只在短轴填充倍数会让长行两端越界时介入。
        val maxLineWidth = widestContentLineWidth(layout)
        val availableWidth = ((ow - padLeft - padRight).toFloat()).coerceAtLeast(1f)
        val decision = resolveFullscreenLandscapeScale(
            contentHeight = contentHeight,
            availableHeight = availableHeight,
            maxLineWidth = maxLineWidth,
            availableWidth = availableWidth,
            fillRatio = FULLSCREEN_FILL_RATIO,
            minScale = FULLSCREEN_MIN_SCALE,
            maxScale = FULLSCREEN_MAX_SCALE
        )
        // issue #41/#44/#51:记录横屏全屏自适应缩放的实际输入输出,便于真机核对
        // contentHeight 与 maxLineWidth 是否越界(of=none/h/w)、缩放结果是否被长轴上限截断。
        val key = "rot=$rotationStep c=${contentHeight.roundToInt()} " +
            "a=${availableHeight.roundToInt()} s=${decision.scale} " +
            "lw=${maxLineWidth.roundToInt()} aw=${availableWidth.roundToInt()} " +
            "cap=${decision.widthCap} of=${decision.overflowAxes}"
        if (key != lastAutoScaleLogKey) {
            lastAutoScaleLogKey = key
            HookLogger.i("AodLyricCanvasView", "Landscape auto-scale: $key")
        }
        return decision.scale
    }

    /** 当前布局中最长行的逻辑宽度(原文/副行/元数据取最大),用于全屏放缩的长轴上限。 */
    private fun widestContentLineWidth(state: LayoutState): Float {
        var maxWidth = 0f
        state.original.lines.forEach { line -> maxWidth = maxOf(maxWidth, line.width) }
        state.rows.forEach { positioned ->
            positioned.row.lines.forEach { line -> maxWidth = maxOf(maxWidth, line.width) }
        }
        return maxWidth
    }

    /**
     * 绘制期生效的垂直对齐:横屏时行块改由 [landscapeAnchor] 锚定(见 positionRows),
     * 竖直对齐只在竖屏路径生效,这里直接返回外部设定值。
     * (此前横屏全屏在此强制 CENTER;issue #63 起横屏两方向统一走锚定,默认 0.5 等价居中。)
     */
    private fun effectiveVerticalAlignment(): AodCanvasVerticalAlignment = verticalAlignment

    /**
     * 刚性绘制变换:绕视图中心旋转画布坐标系,把竖屏逻辑框整体转成横屏显示。
     * 横屏时叠加 [landscapeTextScale] 对内容做整体缩放。PORTRAIT / 未启用时直接直通。
     * [applyScale] 为 false 时跳过缩放,只做旋转+平移——用于独立绘制调试边框,
     * 使其不受全屏缩放影响(issue #44)。
     */
    private fun beginRotationTransform(canvas: Canvas, applyScale: Boolean): Int {
        if (!rotationEnabled || rotationStep == AodOrientationStep.PORTRAIT) {
            return NO_ROTATION_SAVE
        }
        val scale = if (applyScale) effectiveLandscapeScale() else 1f
        // 变换参数集中在 [landscapeRotationTransform]:平移取 (d,-d) 且缩放枢轴取逻辑帧中心,
        // 与 90° 旋转后的实际映射一致(issue #57);两个旋转方向共用同一组参数。
        val transform = landscapeRotationTransform(width, height, rotationStep, scale)
            ?: return NO_ROTATION_SAVE
        val save = canvas.save()
        canvas.rotate(transform.degrees, transform.viewPivotX, transform.viewPivotY)
        canvas.translate(transform.translateX, transform.translateY)
        if (applyScale) {
            // issue #41/#44/#57:记录旋转/平移/缩放参数,便于真机核对方向与量级是否正确。
            val key = "rot=$rotationStep w=$width h=$height tx=${transform.translateX} " +
                "ty=${transform.translateY} scale=${transform.scale} " +
                "sp=(${transform.scalePivotX},${transform.scalePivotY}) " +
                "fs=${fullscreenLandscapeActive()}"
            if (key != lastTransformLogKey) {
                lastTransformLogKey = key
                HookLogger.i("AodLyricCanvasView", "Landscape transform: $key")
            }
            if (kotlin.math.abs(transform.scale - 1f) > 0.001f) {
                canvas.scale(
                    transform.scale,
                    transform.scale,
                    transform.scalePivotX,
                    transform.scalePivotY
                )
            }
            // issue #55/#57 自检:把逻辑帧与内容裁剪区经同一变换映射到视口,输出包围盒与
            // 覆盖判定;任何「整屏零输出」都会以 W 级日志直接暴露,无需靠截图反推。
            logRotationTransformBounds(transform)
        }
        return save
    }
    /**
     * issue #55 自检日志:用与 [beginRotationTransform] 完全相同的 pre-concat 顺序重建矩阵
     * (rotate → translate(t) → scale),把内容裁剪区四个角映射到设备坐标,输出其包围盒及
     * 是否落在画布 (0,0)-(width,height) 内。当旋转+平移+缩放把内容推出可视区时,这里会
     * 直接报越界,无需再靠截图或反推日志判断。
     */
    /**
     * issue #55/#57 自检日志:用与 [beginRotationTransform] 完全相同的变换参数把「逻辑帧」与
     * 「内容裁剪区」映射到视口坐标,输出包围盒,并判定覆盖(cover=铺满且有余量)与相交
     * (hit=非零输出)。变换把内容推出画布时 hit=false —— 以 W 级留痕,第一时间暴露「零输出」。
     */
    private fun logRotationTransformBounds(transform: LandscapeRotationTransform) {
        val clip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        val clipBounds = mapLandscapeLogicalRect(
            transform,
            clip[0].toFloat(),
            clip[1].toFloat(),
            clip[2].toFloat(),
            clip[3].toFloat()
        )
        val frameBounds = mapLandscapeLogicalRect(transform, 0f, 0f, ow.toFloat(), oh.toFloat())
        val hit = clipBounds.hits(width, height)
        val cover = frameBounds.covers(width, height)
        // issue #61:cover/hit 只说明「铺满/有交集」,内容被裁时两者仍为 true;补 fits
        // (内容包围盒 ⊆ 画布)与四向裁切像素量,被裁直接量化、可对照截图。
        val fits = clipBounds.fitsWithin(width, height)
        val overflowLeft = (-clipBounds.minX).coerceAtLeast(0f).roundToInt()
        val overflowTop = (-clipBounds.minY).coerceAtLeast(0f).roundToInt()
        val overflowRight = (clipBounds.maxX - width).coerceAtLeast(0f).roundToInt()
        val overflowBottom = (clipBounds.maxY - height).coerceAtLeast(0f).roundToInt()
        val key = "clip=(${clip[0]},${clip[1]})-(${clip[2]},${clip[3]}) " +
            "bounds=(${clipBounds.minX.roundToInt()},${clipBounds.minY.roundToInt()})-" +
            "(${clipBounds.maxX.roundToInt()},${clipBounds.maxY.roundToInt()}) " +
            "view=${width}x$height cover=$cover hit=$hit fits=$fits " +
            "overflow=($overflowLeft,$overflowTop,$overflowRight,$overflowBottom)"
        if (key != lastRotationBoundsKey) {
            lastRotationBoundsKey = key
            if (!hit) {
                HookLogger.w(
                    "AodLyricCanvasView",
                    "Landscape bounds: $key (content outside canvas; check translate/scale)"
                )
            } else if (!fits) {
                HookLogger.w(
                    "AodLyricCanvasView",
                    "Landscape bounds: $key (content clipped by canvas; check scale/anchor)"
                )
            } else {
                HookLogger.i("AodLyricCanvasView", "Landscape bounds: $key")
            }
        }
    }

    /**
     * 调试开关:在画布上描出边界。红色 = 逻辑帧边界(ow×oh),绿色 = 内容裁剪区
     * (padLeft..clipRight, padTop..clipBottom)。用不含 scale 的旋转+平移变换绘制,
     * 不受全屏缩放影响(issue #44)——缩放时内容放大,边框仍恒定落在逻辑帧/视口上。
     */
    private fun drawDebugCanvasFrame(canvas: Canvas) {
        if (!debugShowCanvasFrame) return
        canvas.drawRect(0f, 0f, ow.toFloat(), oh.toFloat(), debugFramePaint)
        canvas.drawRect(
            padLeft.toFloat(),
            padTop.toFloat(),
            (ow - padRight).toFloat(),
            (oh - padBottom).toFloat(),
            debugClipPaint
        )
    }

    private fun drawOrientedContent(canvas: Canvas) {
        super.onDraw(canvas)
        lastDrawAtElapsedMs = SystemClock.elapsedRealtime()
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
        // 行块换行用缓动:旧行加速上滑离场、新行减速上滑落位;元数据淡出仍走线性。
        val exitEased = transitionExitEasing(exitProgress)
        val enterEased = transitionEnterEasing(enterProgress)
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
            1f - exitEased,
            if (content.transitionMode == "Fade up") -14f * density * exitEased else 0f,
            snapshot.renderStyle,
            skipOriginal = metadataMorph
        )
        drawRows(canvas, layout, content, enterEased, if (content.transitionMode == "Fade up") 14f * density * (1f - enterEased) else 0f)
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
            val save = canvas.saveLayerAlpha(0f, 0f, ow.toFloat(), oh.toFloat(), (255f * alpha).toInt())
            canvas.translate(0f, translateY)
            save
        } else canvas.save()
        // 所有歌词绘制路径(原文/注音/翻译/逐字扫光/发光块)共享这一处逻辑裁剪:
        // 即使整词不可分或动画越界超出其测量宽度,也强制限制在周围 padding 框内,
        // 取代原先逐 drawText 的 clip,成为唯一统一边界。
        val frameClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(frameClip[0], frameClip[1], frameClip[2], frameClip[3])
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
        // 副行(音标/翻译/下一行)静态绘制,与预览的静态 Text 行一致,不参与扫光。
        drawSecondaryRowsStatic(canvas, rows, bright = content.secondaryTextBright)
        drawOriginalRubyRows(canvas, original.baseline, bright = true)
        // 主行发光统一委托共享渲染核心 LyricGlowRenderer —— 与预览(PreviewAnimatedLyric)
        // 同一份配方:dim 底、光晕、easeInOut 扫光带,杜绝行级同步路径另走一套旧实现。
        drawOriginalGlowBlock(canvas, original.baseline, layout.original, lineProgress())
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
                // 下一行歌词颜色走独立的 nextLineText token(与预览/非行级同步路径一致),
                // 不能混用 secondaryText,否则"下一行歌词颜色"设置对该路径完全无效。
                setTextAlpha(
                    positioned.row.paint,
                    staticNextLineTextFactor(),
                    1f,
                    resolvedPalette.nextLineText
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
        val metadataClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(metadataClip[0], metadataClip[1], metadataClip[2], metadataClip[3])
        metadata.row.paint.color = resolvedPalette.metadataText
        metadata.row.paint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt()
        metadata.row.lines.forEachIndexed { index, line ->
            // 底部锚点时行向上排（末行贴近屏幕底），顶部锚点向下排。
            val lineBaseline = if (content.metadataAnchor == "bottom") {
                metadata.baseline - (metadata.row.lines.size - 1 - index) * metadata.row.lineHeight
            } else {
                metadata.baseline + index * metadata.row.lineHeight
            }
            canvas.drawText(
                line.text,
                line.startX,
                lineBaseline,
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
        val morphClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(morphClip[0], morphClip[1], morphClip[2], morphClip[3])
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
        if (content.metadataVisible && content.metadata.isNotBlank() && !metadataPlaceholder) {
            rows += rowWithLines(
                RowKind.METADATA,
                content.metadata,
                metadataPaint,
                0f,
                wrapMetadataText(content.metadata, metadataPaint)
            )
        }
        if (content.original.isNotBlank()) {
            val metrics = originalPaint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent + LYRIC_LINE_EXTRA_HEIGHT_DP * density
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
                ROW_GAP_BEFORE_ORIGINAL_DP * density,
                emptyList(),
                lineHeight
            )
        }
        val showReading = content.secondaryMode == "Transliteration" || content.secondaryMode == "Both"
        val showTranslation = content.secondaryMode == "Translation" || content.secondaryMode == "Both"
        if (showReading && content.romanized.isNotBlank()) {
            val lines = transliterationLines(originalLayout)
                ?: wrapSecondaryText(content.romanized, romanizedPaint, originalLayout.lineCount)
            rows += rowWithLines(RowKind.ROMANIZED, content.romanized, romanizedPaint, ROW_GAP_BEFORE_SECONDARY_DP * density, lines)
        }
        if (showTranslation && content.translated.isNotBlank()) {
            rows += rowWithLines(
                RowKind.TRANSLATED,
                content.translated,
                translatedPaint,
                ROW_GAP_BEFORE_SECONDARY_DP * density,
                wrapSecondaryText(content.translated, translatedPaint, originalLayout.lineCount)
            )
        }
        if (content.showNextLine && content.nextLine.isNotBlank()) {
            rows += rowWithLines(
                RowKind.NEXT_LINE,
                content.nextLine,
                nextLinePaint,
                ROW_GAP_BEFORE_NEXT_LINE_DP * density,
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
            top.coerceIn(0f, oh.toFloat()),
            bottom.coerceIn(0f, oh.toFloat())
        )
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
                oh.toFloat(),
                padTop.toFloat(),
                padBottom.toFloat(),
                metadata.paint.fontMetrics.ascent,
                metadata.paint.fontMetrics.descent,
                METADATA_LYRIC_GAP_DP * density
            )
            val metadataBaseline = metadataBounds.metadataBaseline
            positioned += PositionedRow(metadata, metadataBaseline, false)
            val lyricRows = rows.filterNot { it.kind == RowKind.METADATA }
            val gap = METADATA_LYRIC_GAP_DP * density
            // 元数据被换行成多行时，其实际占高超过单行基线；歌词起点需按多出的高度避让。
            val metadataExtraHeight = (metadata.lines.size - 1).coerceAtLeast(0) *
                metadata.lineHeight
            if (anchor == "bottom") {
                var bottom = metadataBounds.lyricEnd - metadataExtraHeight
                lyricRows.asReversed().forEach { row ->
                    bottom -= row.height
                    positioned += PositionedRow(row, bottom - row.paint.fontMetrics.ascent, true)
                    bottom -= row.gapBefore
                }
                positioned.sortBy { it.baseline }
            } else {
                var top = metadataBounds.lyricStart + metadataExtraHeight
                lyricRows.forEach { row ->
                    top += row.gapBefore
                    positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                    top += row.height
                }
            }
        } else {
            val total = rows.sumOf { (it.height + it.gapBefore).toDouble() }.toFloat()
            val topPadding = padTop.toFloat()
            val bottomPadding = oh - padBottom
            val available = (bottomPadding - topPadding).coerceAtLeast(0f)
            // issue #63:横屏时行堆叠轴经 90° 旋转映射为画布的视觉横轴,沿用竖屏的 TOP 会让
            // 内容整块贴向画布一侧(现场实测偏约 185px)。横屏统一按 landscapeAnchor 锚定
            // (默认 0.5=居中);竖屏维持原 TOP/CENTER 语义。
            var top = if (landscapeActive()) {
                topPadding + max(0f, available - total) * landscapeAnchor.coerceIn(0f, 1f)
            } else if (effectiveVerticalAlignment() == AodCanvasVerticalAlignment.TOP) {
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
        // issue #41:横屏全屏时元数据分支走 anchor 排版,会把整块锚到 padTop/padBottom,
        // 完全不做居中(居中只在无元数据分支生效)。这里对整块(元数据+歌词)统一锚定。
        // issue #63 起扩展到全部横屏(含非全屏),锚点取 landscapeAnchor(默认 0.5=居中)。
        if (metadata != null) anchorLandscapeContentBlock(positioned)
        // issue #41/#44/#63:记录横屏整块(元数据+歌词)的最终占位范围与锚定参数,便于真机核对
        // 内容是否被正确锚定/居中、是否偏靠一侧/越界被裁。覆盖 oh/pad/total→block 全链路。
        if (landscapeActive()) {
            var minTop = Float.POSITIVE_INFINITY
            var maxBottom = Float.NEGATIVE_INFINITY
            positioned.forEach { p ->
                val t = p.baseline + p.row.paint.fontMetrics.ascent
                val b = p.baseline + p.row.height
                if (t < minTop) minTop = t
                if (b > maxBottom) maxBottom = b
            }
            val key = "rot=$rotationStep ow=$ow oh=$oh padT=$padTop padB=$padBottom" +
                if (metadata != null) " md=1" else " md=0" +
                " block=${minTop.roundToInt()}..${maxBottom.roundToInt()} " +
                "anchor=$landscapeAnchor fs=${fullscreenLandscapeActive()}"
            if (key != lastRowLayoutLogKey) {
                lastRowLayoutLogKey = key
                HookLogger.i("AodLyricCanvasView", "Landscape row layout: $key")
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

    /**
     * issue #41/#63:横屏(全屏与非全屏)时把整块(元数据+歌词)按 [landscapeAnchor] 锚定。
     * 元数据分支此前只用 metadataAnchor 锚定到 padTop/padBottom,完全不做居中——而无元数据
     * 分支会居中/锚定,导致有元数据时不居中、挤向画布一侧。anchor=0.5 与旧全屏居中行为
     * 逐点等价;仅在横屏激活时生效(需自适应 scale 也读完预缩放布局),竖屏不受影响。
     */
    private fun anchorLandscapeContentBlock(positioned: MutableList<PositionedRow>) {
        if (!landscapeActive() || positioned.isEmpty()) return
        var minTop = Float.POSITIVE_INFINITY
        var maxBottom = Float.NEGATIVE_INFINITY
        positioned.forEach { p ->
            val top = p.baseline + p.row.paint.fontMetrics.ascent
            val bottom = p.baseline + p.row.height
            if (top < minTop) minTop = top
            if (bottom > maxBottom) maxBottom = bottom
        }
        if (!minTop.isFinite() || !maxBottom.isFinite() || maxBottom <= minTop) return
        val available = (oh - padTop - padBottom).coerceAtLeast(0)
        val blockHeight = maxBottom - minTop
        val offset = landscapeBlockAnchorOffset(
            blockTop = minTop,
            blockHeight = blockHeight,
            availableHeight = available.toFloat(),
            padTop = padTop.toFloat(),
            anchor = landscapeAnchor
        )
        if (offset == 0f) return
        val shifted = positioned.map { it.copy(baseline = it.baseline + offset) }
        positioned.clear()
        positioned.addAll(shifted)
        if (lastCenteredLogKey != offset.toString()) {
            lastCenteredLogKey = offset.toString()
            HookLogger.i(
                "AodLyricCanvasView",
                "Landscape content block anchored minTop=$minTop " +
                    "blockH=$blockHeight avail=$available anchor=$landscapeAnchor offset=$offset"
            )
        }
    }

    private fun drawOriginal(canvas: Canvas, baseline: Float) {
        val originalLayout = layout.original
        val lines = originalLayout.lines
        // Minimal 模式：静态全亮，无扫光/发光（timed / untimed 通用）。
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
        // 行级歌词(无逐字时间戳,LRC):同样统一走共享渲染管线,与预览同源。
        if (!originalLayout.timed) {
            drawOriginalGlowBlock(canvas, baseline, originalLayout, lineProgress())
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
        drawOriginalGlowBlock(
            canvas,
            baseline,
            originalLayout,
            unifiedBlockProgress(
                projectedPosition(),
                content.lineStartMs,
                content.lineEndMs,
                content.words
            )
        )
    }

    /**
     * 共享发光渲染管线入口(与预览 PreviewAnimatedLyric 同源):
     * 构建整块行集合并委托 LyricGlowRenderer —— dim 底、光晕、easeInOut 扫光带
     * 的配方只此一份,AOD/锁屏/预览三端由构造保证一致。
     */
    private fun drawOriginalGlowBlock(
        canvas: Canvas,
        baseline: Float,
        originalLayout: OriginalLayout,
        progress: Float
    ) {
        val lines = originalLayout.lines
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
            progress = progress,
            sungColor = resolvedPalette.sungText,
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
        val top = max(
            padTop.toFloat(),
            rubyClipTop(
                firstLineBaseline,
                originalPaint.fontMetrics.ascent,
                firstLine.rubyHeight
            )
        ).toInt()
        val clip = lyricClipBounds(padLeft, top, ow - padRight, oh - padBottom)
        canvas.clipRect(clip[0].toFloat(), clip[1].toFloat(), clip[2].toFloat(), clip[3].toFloat())
        return save
    }

    /** 按行布局绘制整行文字（含 ruby 分段），供共享 LyricGlowRenderer 的行回调使用。 */
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

    /** 逐字卡拉OK路径（逐字源+关发光+非行级同步）：词级缩放/位移 + 词内扫光渐变。 */
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
                canvas.scale(scale, scale, wordX + ow / 2f, wordBaseline)
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

    /**
     * 逐字卡拉OK路径的词内扫光渐变:
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
        val top = max(padTop.toFloat(), rubyClipTop(baseBaseline, originalPaint.fontMetrics.ascent, rubyHeight)).toInt()
        val clip = lyricClipBounds(padLeft, top, ow - padRight, oh - padBottom)
        canvas.clipRect(clip[0].toFloat(), clip[1].toFloat(), clip[2].toFloat(), clip[3].toFloat())
        return save
    }

    private fun frameInterval(): Long = frameIntervalForTiming(
        effectiveCadenceActive(),
        timingActive = true,
        powerSaverActive = powerSaverProvider(),
        refreshRateCapHz = refreshRateCapProvider()
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

    /** 渲染停摆看门狗用:最后一次真实 onDraw 的 elapsedRealtime 时刻,0 表示尚未绘制过。 */
    fun lastDrawAtElapsedMs(): Long = lastDrawAtElapsedMs

    /** 渲染停摆看门狗用:当前内容是否带行级时间轴(播放中本应持续重绘)。 */
    fun isTimingEffectActive(): Boolean = timingEffectEnabled

    private fun effectiveAlpha(): Float {
        var value = alpha * transitionAlpha
        var ancestor = parent as? View
        while (ancestor != null) {
            value *= ancestor.alpha * ancestor.transitionAlpha
            if (value == 0f) return value
            ancestor = ancestor.parent as? View
        }
        return value
    }

    private fun syncCadence() {
        when (cadenceGate.update(effectiveCadenceActive())) {
            CadenceChange.START -> {
                // 重新起帧:重置 deadline 相位,首帧立即绘制,后续按周期对齐。
                frameDeadlineNanos = 0L
                lastFrameIntervalMs = -1L
                removeCallbacks(frame)
                scheduleFrame(frame, 0L)
            }
            CadenceChange.STOP -> {
                frameDeadlineNanos = 0L
                lastFrameIntervalMs = -1L
                removeCallbacks(frame)
            }
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
        // 换行/词行布局统一委托 LyricLayoutEngine(与预览同源,断行一致)。
        val layout = layoutOriginalLines(
            original = content.original,
            words = content.words,
            ruby = content.ruby,
            layoutGroups = content.layoutGroups,
            paint = originalPaint,
            availableWidth = (ow - padLeft - padRight).coerceAtLeast(1).toFloat(),
            lineLimit = content.lyricLineLimit,
            wordGapPx = LYRIC_WORD_GAP_DP * density,
            wrap = content.overflowMode == "Wrap",
            adaptiveSectioning = content.adaptiveSectioning
        )
        val lines = layout.lines.map {
            originalLine(it.text, it.width, it.charStart, it.charEnd).copy(words = it.words)
        }
        val metrics = originalPaint.fontMetrics
        return OriginalLayout(
            assignRuby(lines),
            metrics.descent - metrics.ascent + LYRIC_LINE_EXTRA_HEIGHT_DP * density,
            LYRIC_LINE_GAP_DP * density,
            layout.timed
        )
    }

    private fun lyricLayoutLineLimit(wordCount: Int = content.words.size): Int =
        resolvedLyricLayoutLineLimit(
            content.lyricLineLimit,
            content.original.length,
            wordCount
        )

    private fun transliterationLines(originalLayout: OriginalLayout): List<TextLine>? {
        if (originalLayout.lines.isEmpty() || originalLayout.lines.any { it.words.isEmpty() }) return null
        val available = (ow - padLeft - padRight).coerceAtLeast(1).toFloat()
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
            MAX_SECONDARY_LAYOUT_LINES,
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
            textLine(text, lineWidth, romanizedPaint, alignmentFor(RowKind.ROMANIZED))
                .copy(timedSegments = lineSegments)
        }
    }

    private fun wrapSecondaryText(text: String, paint: Paint, preferredLines: Int): List<TextLine> =
        // 换行统一委托 LyricLayoutEngine(与预览同源);定位 X 仍由 textLine 按实机几何解析。
        layoutSecondaryLines(
            text = text,
            paint = paint,
            availableWidth = (ow - padLeft - padRight).coerceAtLeast(1).toFloat(),
            preferredLines = preferredLines,
            wrap = content.overflowMode == "Wrap",
            adaptiveSectioning = content.adaptiveSectioning
        ).map { textLine(it.text, it.width, paint, alignmentFor(RowKind.ROMANIZED)) }

    /**
     * 歌曲信息（歌名/歌手）专用换行：委托 LyricLayoutEngine.layoutMetadataLines
     * (与预览同源,最多 MAX_SECONDARY_LAYOUT_LINES 行);定位 X 按 metadata 对齐解析。
     */
    private fun wrapMetadataText(text: String, paint: Paint): List<TextLine> =
        layoutMetadataLines(
            text = text,
            paint = paint,
            availableWidth = (ow - padLeft - padRight).coerceAtLeast(1).toFloat()
        ).map { textLine(it.text, it.width, paint, alignmentFor(RowKind.METADATA)) }

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
        // 水平 padding 边界已由 drawRows 顶层的共享逻辑裁剪统一施加,无需逐行再次 clip。
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
    }

    private fun alignmentFor(kind: RowKind): Alignment {
        if (kind == RowKind.ORIGINAL) return alignment
        secondaryAlignmentOverride?.let { return it }
        return if (kind == RowKind.METADATA) {
            when (content.alignmentMode) {
                "start" -> Alignment.START
                "center" -> Alignment.CENTER
                "end" -> Alignment.END
                else -> Alignment.START
            }
        } else {
            alignment
        }
    }

    private fun alignedStart(
        textWidth: Float,
        lineAlignment: Alignment = alignment,
        visualLeft: Float = 0f,
        visualRight: Float = textWidth
    ): Float = edgeSafeAlignedStart(
        // 对齐基准必须是逻辑帧(ow/padLeft/padRight):横屏时 ow=视口高、pad 为逻辑内边距,
        // startX 落在逻辑坐标系(0..ow)里;若误用视口宽 width,长行居中会得到负坐标,
        // 经 beginRotationTransform 的 rotate+scale 后整行移出可视区(issue #51:
        // 横屏全屏歌词不可见)。竖屏时 ow==width、padLeft==paddingLeft,行为不变。
        canvasWidth = ow.toFloat(),
        paddingLeft = padLeft.toFloat(),
        paddingRight = padRight.toFloat(),
        visualLeft = visualLeft,
        visualRight = visualRight,
        alignment = when (lineAlignment) {
            Alignment.START -> "start"
            Alignment.CENTER -> "center"
            Alignment.END -> "end"
        },
        safetyInset = if (lineAlignment == Alignment.END) END_EDGE_SAFETY_DP * density else 0f
    )

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
        // 文字本体不发光: 禁用辉光层
        return
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
        paint.color = resolvedPalette.nextLineText
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

    // 字体解析与预览同源:统一委托 LyricTypefaceResolver(支持 custom/noto-sc/自定义 provider),
    // 消除"预览用 LyricTypefaceResolver、实机用内联 asset 映射"的双路径漂移。
    private fun resolveTypeface(family: String, weight: String): Typeface =
        LyricTypefaceResolver.resolve(fontContext ?: context, family, weight)

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
    }

    private data class LayoutState(
        val rows: List<PositionedRow>,
        val original: OriginalLayout
    )

    companion object {
        private const val ENTER_TRANSITION_MS = 210L
        private const val EXIT_TRANSITION_MS = 130L
        private const val CADENCE_DIAGNOSTIC_WINDOW_MS = 10_000L
        private const val CADENCE_DIAGNOSTIC_TAG = "AodCanvasCadence"
        private const val GLOW_HALO_ALPHA = 235
        private const val GLOW_HALO_RADIUS = 0.52f
    }
}
