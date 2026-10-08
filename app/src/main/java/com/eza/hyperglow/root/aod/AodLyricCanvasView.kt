package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Camera
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_PORTRAIT
import com.eza.hyperglow.aod.DEFAULT_CANVAS_PADDING_PERCENT
import com.eza.hyperglow.aod.DEFAULT_FULLSCREEN_SAFE_MARGIN_PERCENT
import com.eza.hyperglow.customization.ARTWORK_SHAPE_CIRCLE
import com.eza.hyperglow.producer.DuetLineWindow
import com.eza.hyperglow.producer.shouldAdoptDuetLineCandidate
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
        lineTransitionSpeed = "Normal",
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
    /** 位置式过渡时钟起点:过渡开始时的歌词位置(见 [lineTransitionClockAtPosition])。 */
    private var transitionStartPositionMs = 0L
    /**
     * 位置式过渡时钟采样状态:平滑位置(限速后,进度推导基准)与原始位置高水位
     * (seek/拖动判定基准),见 [advanceTransitionPosition] / [isTransitionSeekJump]。
     */
    private var transitionPositionState = TransitionPositionState(0L, 0L)
    private var transitionTimeline: LineTransitionTimeline? = null
    /**
     * 位移段起点是否已锚定(见 [moveStartAnchorPosition]):位置式时钟下,delta 首次越过
     * 退场段时把起点重锚到「当前平滑位置 − 退场时长」,保证位移段第一帧画的是上一帧几何
     * (moveProgress = 0),不被首帧晚到/批跳变一帧跳进。每段过渡只锚一次。
     */
    private var moveStartAnchored = false
    /**
     * 旧账压缩补播:为真时三段进度按挂钟在压缩时间线上换算(见 [lineTransitionClockAtElapsed])。
     * 位置锚不参与本路径——过期快照的位置推进量远超压缩时长,按位置驱动就是一帧硬切。
     */
    private var transitionWallClockDriven = false
    /** 压缩补播的挂钟起点;0 = 尚未落帧(首帧到达才起算,低节拍/doze 下保证整段可见)。 */
    private var transitionStartedAtElapsedMs = 0L
    /** 上一次过渡时钟采样时刻:限速步长 = 本帧时刻 − 上一次(见 [advanceTransitionPosition])。 */
    private var transitionLastClockAtElapsedMs = 0L
    /**
     * 同一帧到达窗口内的换行快照计数(见 [shouldSkipLineTransition]):与上一条换行内容的
     * 到达间隔不超过 [SAME_FRAME_ARRIVAL_WINDOW_MS](≈60Hz 一帧)时累加,否则重新计 1。
     */
    private var lineChangesInFrame = 0
    private var lastLineChangeAtElapsedMs = 0L
    /**
     * 待落定过渡:位移段时长要吃「起点布局 → 目标布局」的实际行位差(见
     * [lineTransitionMoveDistancePx]),而目标布局在 [setContent] 末尾才重建。先捕获起点
     * 快照/档位/起点位置,重建布局后按真实距离落定时间线(此间不绘制,时钟语义不变)。
     */
    private var pendingLineTransition: PendingLineTransition? = null
    // 对唱并发行(duetLine)加入淡入状态:内容键变化即重计时,淡入完成后归零(恒全亮)。
    private var duetLineKey: String? = null
    private var duetJoinStartedAt = 0L
    /**
     * 并发行自己的时间轴锁(见 [latchDuetLine]):主行换行只换候选来源,不换屏上内容——
     * 已上屏的并发行锁到它自己的窗口结束才让位。null = 当前无并发行。
     */
    private var duetLockLine: AodCanvasDuetLine? = null
    /** 并发行锁的位置高水位(seek/倒退判定基准,只进不退;换歌或明显倒退时重算)。 */
    private var duetLockHighWaterPositionMs = 0L

    /** 并发行加入淡入系数:未在淡入期恒 1;淡入窗口内 0→1 线性推进。 */
    private fun duetJoinAlpha(): Float {
        if (duetJoinStartedAt == 0L) return 1f
        val elapsed = (SystemClock.elapsedRealtime() - duetJoinStartedAt).coerceAtLeast(0L)
        return (elapsed.toFloat() / DUET_JOIN_FADE_MS).coerceIn(0f, 1f)
    }

    /**
     * 并发行自己的时间轴(见 [shouldAdoptDuetLineCandidate]):主行换行(活动行变化)只改变
     * **候选来源**,不改变屏上的并发行——已上屏的并发行锁到它自己的窗口结束才让位;换歌
     * (代次变化)与位置明显倒退(seek/拖动)时解锁,按本次候选重选。
     */
    private fun latchDuetLine(incoming: AodCanvasContent): AodCanvasContent {
        val candidate = incoming.duetLine
        val locked = duetLockLine
        val trackChanged = incoming.trackGeneration != content.trackGeneration
        val seekBack = locked != null && !trackChanged &&
            incoming.positionMs < duetLockHighWaterPositionMs - TRANSITION_REWIND_TOLERANCE_MS
        val adopt = trackChanged || seekBack || shouldAdoptDuetLineCandidate(
            locked = locked?.let { DuetLineWindow(it.lineStartMs, it.lineEndMs, false) },
            incoming = candidate?.let { DuetLineWindow(it.lineStartMs, it.lineEndMs, false) },
            positionMs = incoming.positionMs
        )
        if (adopt) duetLockLine = candidate
        // 高水位只进不退;换歌/倒退后从当前位置重算,否则锁会在倒退后的低位上永久失效。
        duetLockHighWaterPositionMs = if (trackChanged || seekBack) {
            incoming.positionMs
        } else {
            maxOf(duetLockHighWaterPositionMs, incoming.positionMs)
        }
        val line = duetLockLine ?: return incoming
        return if (line === candidate) incoming else incoming.copy(duetLine = line)
    }
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
    private var landscapeFullscreenSafeMarginPercent = DEFAULT_FULLSCREEN_SAFE_MARGIN_PERCENT
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
    private var lastEffectClipCheckKey = ""
    private var lastRenderModeLogKey = ""
    private var lastIncomingProbeKey = ""

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
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var artworkBitmap: Bitmap? = null
    private var artworkBitmapKey = ""
    /** 暂停停转时冻结的旋转角:节拍门停后偶发重绘不推进角度,恢复播放前保持停转时刻画面。 */
    private var lastArtworkSpinAngle = 0f
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
            if (exitSnapshot != null) {
                // 位置式过渡:位置推进到总时长即走完,位置跳变(seek/拖动)立即结束;
                // 暂停(位置冻结)时不结束,过渡停在当前进度等待恢复。
                val timeline = transitionTimeline
                val clock = if (timeline == null) null else transitionClock(timeline)
                if (clock == null || clock.completed || clock.interrupted) {
                    endLineTransition()
                }
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

    /** 同行内容稳定化(见 [shouldAdoptLineEnhancements]):当前行的行身份与增强数据。 */
    private var stableLineIdentity: AodCanvasLineIdentity? = null
    private var stableLineWords: List<AodCanvasWord> = emptyList()
    private var stableLineGroups: List<AodCanvasLayoutGroup> = emptyList()
    private var stableLineRuby: List<AodCanvasRuby> = emptyList()
    private var stableLineTranslationWords: List<AodCanvasWord> = emptyList()

    /** 下一行文本稳定化(见 [isNextLineStale]):最近一次「已就绪」的下一行文本。 */
    private var stableNextLine: String = ""

    /**
     * 按行身份稳定增强数据:同一行在显示期间只认第一次的折行形态,仅允许行开始前的
     * 「无词→带词」升级(见 [shouldAdoptLineEnhancements])——上游对同一行两阶段下发时
     * 不再重排(修「歌词位置重载」:换行瞬间下一行被顶下去一行高、填充映射中途切换)。
     */
    private fun stabilizeLineEnhancements(incoming: AodCanvasContent): AodCanvasContent {
        val identity = aodCanvasLineIdentity(incoming)
        val sameLine = stableLineIdentity == identity
        // 逐字翻译词表(插件提供)也参与稳定化:它决定翻译辅助行的折行形态(逐字段 vs 行窗口
        // 合成),演唱中到达时同样不能重排——与词表/注音同一条「同行只认第一次形态」的口径。
        val translationWordsArrived =
            incoming.translationWords.isNotEmpty() != stableLineTranslationWords.isNotEmpty()
        val adopt = shouldAdoptLineEnhancements(
            sameLine = sameLine,
            layoutSignatureChanged = translationWordsArrived ||
                aodLineLayoutSignature(incoming.words, incoming.layoutGroups) !=
                aodLineLayoutSignature(stableLineWords, stableLineGroups),
            incomingEnriches = (incoming.words.isNotEmpty() && stableLineWords.isEmpty()) ||
                (incoming.translationWords.isNotEmpty() && stableLineTranslationWords.isEmpty()),
            positionMs = incoming.positionMs,
            lineStartMs = incoming.lineStartMs
        )
        val adopted = if (adopt) {
            incoming
        } else {
            incoming.copy(
                words = stableLineWords,
                layoutGroups = stableLineGroups,
                ruby = stableLineRuby,
                translationWords = stableLineTranslationWords
            )
        }
        // 下一行:与主行同文 = 未就绪(刚被晋级的那句,新下一行尚未到达),沿用上一版文本——
        // 否则换行后行集合/行高随文本切换重排整块(「换行动画后跳一下」)。
        val result = if (isNextLineStale(adopted.nextLine, adopted.original)) {
            adopted.copy(nextLine = stableNextLine)
        } else {
            stableNextLine = adopted.nextLine
            adopted
        }
        stableLineIdentity = identity
        stableLineWords = result.words
        stableLineGroups = result.layoutGroups
        stableLineRuby = result.ruby
        stableLineTranslationWords = result.translationWords
        return result
    }

    fun setContent(incomingContent: AodCanvasContent) {
        if (HookLogger.traceEnabled) {
            // 诊断探针(配置下发排查):记录画布实例与「收到」的档位,与控制器侧
            // Render profile probe 配对,定位「控制器读到的 profile 正确但画布渲染旧档」。
            val incomingKey = "id=${System.identityHashCode(this)} " +
                "inAnim=${incomingContent.animationMode} inGlow=${incomingContent.glowMode} " +
                "words=${incomingContent.words.size}"
            if (incomingKey != lastIncomingProbeKey) {
                lastIncomingProbeKey = incomingKey
                HookLogger.i("AodLyricCanvasView", "setContent in: $incomingKey")
            }
        }
        val nextContent = latchDuetLine(
            stabilizeLineEnhancements(
                incomingContent.copy(
                    animationMode = normalizeAodAnimation(incomingContent.animationMode),
                    motionMode = normalizeAodMotion(incomingContent.motionMode),
                    overflowMode = normalizeAodOverflow(incomingContent.overflowMode)
                )
            )
        )
        // 行变更判定:身份(曲目/行窗/文本)变化才算换行;「同曲同文、仅时间窗更新」不算——
        // 空档预览行真正开始时主行文本不变,只是预览退化窗 [nextLineStartMs, nextLineStartMs]
        // 换成真实行窗,此时不该再播一次入场动画(文本未变,播了就是同一句重复入场)。
        val previousLineIdentity = aodCanvasLineIdentity(this.content)
        val nextLineIdentity = aodCanvasLineIdentity(nextContent)
        val lineChanged = previousLineIdentity.original.isNotBlank() &&
            previousLineIdentity != nextLineIdentity &&
            !isSameLineTextUpdate(previousLineIdentity, nextLineIdentity)
        val resuming = suppressNextLineTransition
        suppressNextLineTransition = false
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (lineChanged) {
            // 同一帧判定:两条换行内容的到达间隔不超过约 60Hz 一帧即按同帧计(doze 批投递
            // 实测 1–3ms 间隔);间隔过大则重新计 1,避免画布停绘期间把不同拍的内容累计成同帧。
            lineChangesInFrame =
                if (nowElapsedMs - lastLineChangeAtElapsedMs <= SAME_FRAME_ARRIVAL_WINDOW_MS) {
                    lineChangesInFrame + 1
                } else {
                    1
                }
            lastLineChangeAtElapsedMs = nowElapsedMs
        }
        if (shouldStartLineTransition(
                lineChanged,
                nextContent.transitionMode,
                handoffActive,
                resuming
            )
        ) {
            // 角色分流在起点定死:旧「下一行」文本 == 新「主行」文本且旧布局真有下一行行时
            // 晋级(内容延续,只位移),否则旧行组整体退场、新行组整体进场。
            val promoting = layout.rows.any { it.row.kind == RowKind.NEXT_LINE } &&
                lineTransitionPromotes(content.nextLine, nextContent.original)
            // 起点快照/起点位置在起点定死;位移段时长要吃「起点布局 → 目标布局」的实际行位差,
            // 而目标布局在本方法末尾 rebuildLayout() 后才就绪——先挂起,重建布局后落定
            // (见 resolvePendingLineTransition;此间不绘制,时钟语义不变)。
            pendingLineTransition = PendingLineTransition(
                snapshot = CanvasSnapshot(content, layout, currentRenderStyle),
                promoting = promoting,
                transitionMode = nextContent.transitionMode,
                lineTransitionSpeed = nextContent.lineTransitionSpeed,
                snapshotAgeMs = nextContent.updatedAtElapsedMs
                    .takeIf { it > 0L }
                    ?.let { (nowElapsedMs - it).coerceAtLeast(0L) },
                startPositionMs = projectedPosition(),
                startedAtElapsedMs = nowElapsedMs
            )
        } else if (resuming || nextContent.transitionMode == "None") {
            pendingLineTransition = null
            exitSnapshot = null
            transitionStartPositionMs = 0L
            transitionPositionState = TransitionPositionState(0L, 0L)
            moveStartAnchored = false
            transitionWallClockDriven = false
            transitionStartedAtElapsedMs = 0L
            transitionLastClockAtElapsedMs = 0L
            transitionTimeline = null
        }
        // 并发行自己的内容键(见 [aodDuetContentKey]):主行换行不改变它,只有并发行自己
        // 换行才重计时并播自己的加入淡入。
        val duetKey = nextContent.duetLine?.let { aodDuetContentKey(it.text, it.lineStartMs) }
        if (duetKey != duetLineKey) {
            duetLineKey = duetKey
            duetJoinStartedAt = if (duetKey != null) SystemClock.elapsedRealtime() else 0L
        }
        this.content = nextContent
        syncArtworkBitmap()
        // 渲染模式留痕(仅变化时记录):实机实际生效的逐字动画档/是否逐字时间源/发光开关/
        // 行进度效果——「预览有效果实机没有」类反馈靠这条日志即可区分是配置没下发到这侧、
        // 还是该 surface 未选 BetterLyrics、还是渲染分支问题。字符串构造走 trace 门控。
        if (HookLogger.traceEnabled) {
            val renderModeKey = "anim=" + nextContent.animationMode +
                " timed=" + nextContent.words.any { it.endMs > it.startMs } +
                " words=" + nextContent.words.size +
                " glow=" + nextContent.glowMode +
                " fill=" + resolvedLineSyncFillMode(
                    nextContent.lineLevelSync,
                    nextContent.lineSyncFillMode
                ) +
                " lineSync=" + nextContent.lineLevelSync
            if (renderModeKey != lastRenderModeLogKey) {
                lastRenderModeLogKey = renderModeKey
                HookLogger.i("AodLyricCanvasView", "Render mode: $renderModeKey")
            }
        }
        timingEffectEnabled = hasActiveCanvasTiming(
            nextContent.lineLevelSync,
            nextContent.lineSyncFillMode,
            nextContent.lineStartMs,
            nextContent.lineEndMs,
            nextContent.words,
            nextContent.speed,
            // 辅助文字逐字效果:确有第一行辅助文字行时独立驱动帧(主行进度效果 None 也照常)。
            auxKaraoke = nextContent.secondaryWordKaraoke && hasFirstLineAuxText(
                nextContent.secondaryMode,
                nextContent.romanized,
                nextContent.translated
            )
        )
        resolvedPalette = resolveAodPalette(nextContent.palette)
        alignment = viewAlignment(
            resolveAlignmentMode(nextContent.alignmentMode, nextContent.alignedRight)
        )
        applyContentStyle(nextContent)
        currentRenderStyle = captureRenderStyle()
        rebuildLayout()
        resolvePendingLineTransition()
        syncCadence()
        invalidate()
    }

    /**
     * 目标布局就绪后落定过渡:位移距离取起点布局与目标布局的实际行位差
     * ([lineTransitionMoveDistancePx],主行对 + 辅助行对),位移段时长以配置时长为准
     * ([moveTransitionMs] = 220ms × 速率倍率,五档全程可见;只对 1500px 级极远距离保留
     * 平均速度护栏)。起步不再跳变靠位置限速平滑与起点几何锚定,不靠拉长时长(历史
     * 按峰值速度上限拉长到 ~1.2s 会把速率档整条抹掉,真机表现「改速率没用 + 太慢」);
     * 距离取不到时回退配置时长。[shouldSkipLineTransition] 命中时,压缩补播的位移段
     * 同样按该公式定时长(退场/入场维持短时长),不再压到 40ms 一帧跳完。
     */
    private fun resolvePendingLineTransition() {
        val pending = pendingLineTransition ?: return
        pendingLineTransition = null
        val startDistance = if (pending.promoting) {
            lineTransitionMoveDistancePx(lineTransitionMoveDistancePairs(pending.snapshot.layout, layout))
        } else {
            0f
        }
        val timeline = lineTransitionTimeline(
            pending.transitionMode,
            pending.lineTransitionSpeed,
            pending.promoting,
            startDistance
        )
        if (shouldSkipLineTransition(pending.snapshotAgeMs, timeline.totalMs, lineChangesInFrame)) {
            // 旧账压缩补播:过期快照/同帧多条不再一帧硬切到目标几何,改以压缩时长连续播完
            // 同一三段序列(段顺序/缓动/帧配方不变;退场/入场 ~140ms、每段 ≥40ms,位移段按
            // 配置时长,见 compressedLineTransitionTimeline)。起点几何取屏上现有旧快照
            // (同帧多条时为首条换行前的形态),目标几何仍在此定死;补播按挂钟计时——旧账的
            // 位置推进量远超补播时长,按位置驱动会瞬间推完(即要消除的一帧硬切)。
            val replaySnapshot = exitSnapshot ?: pending.snapshot
            val replayPromoting =
                replaySnapshot.layout.rows.any { it.row.kind == RowKind.NEXT_LINE } &&
                    lineTransitionPromotes(replaySnapshot.content.nextLine, content.original)
            val replayDistance = if (replayPromoting) {
                lineTransitionMoveDistancePx(
                    lineTransitionMoveDistancePairs(replaySnapshot.layout, layout)
                )
            } else {
                0f
            }
            exitSnapshot = replaySnapshot
            transitionTimeline = compressedLineTransitionTimeline(
                pending.transitionMode,
                pending.lineTransitionSpeed,
                replayPromoting,
                replayDistance
            )
            transitionWallClockDriven = true
            transitionStartedAtElapsedMs = 0L
            transitionLastClockAtElapsedMs = pending.startedAtElapsedMs
            transitionStartPositionMs = 0L
            // 挂钟补播从首个绘制帧起算(首帧即 progress=0),位移段无需重锚。
            moveStartAnchored = true
            // 原始位置高水位照常推进:补播期间 seek/拖动(倒退超容差)仍立即结束、静态落位。
            transitionPositionState = TransitionPositionState(0L, pending.startPositionMs)
            return
        }
        exitSnapshot = pending.snapshot
        // 位置式过渡时钟:起点取过渡开始时的歌词位置(同源 projectedPosition()),
        // 之后三段进度由位置推进量推导,不再挂钟计时(见 lineTransitionClockAtPosition);
        // 采样间按实时速率限速(见 advanceTransitionPosition),批投递跳变不在一帧内推完。
        transitionStartPositionMs = pending.startPositionMs
        transitionPositionState =
            TransitionPositionState(pending.startPositionMs, pending.startPositionMs)
        moveStartAnchored = false
        transitionWallClockDriven = false
        transitionStartedAtElapsedMs = 0L
        transitionLastClockAtElapsedMs = pending.startedAtElapsedMs
        transitionTimeline = timeline
    }

    /**
     * 晋级位移的基线对(起点布局 → 目标布局):主行对 = 旧「下一行」基线 → 新「主行」
     * 基线;辅助行对 = 旧下一行之后的辅助行 zip 新主行之后的辅助行(与
     * [drawPromotedAuxLayer] 同一配对),保证位移段内每一层都有真实距离参与平均速度护栏。
     */
    private fun lineTransitionMoveDistancePairs(
        startLayout: LayoutState,
        targetLayout: LayoutState
    ): List<Pair<Float, Float>> {
        val pairs = ArrayList<Pair<Float, Float>>(4)
        val fromNext = startLayout.rows.firstOrNull { it.row.kind == RowKind.NEXT_LINE }
        val toOriginal = targetLayout.rows.firstOrNull { it.row.kind == RowKind.ORIGINAL }
        if (fromNext != null && toOriginal != null) {
            pairs += fromNext.baseline to toOriginal.baseline
        }
        val nextGroupStart = startLayout.rows.indexOfFirst { it.row.kind == RowKind.NEXT_LINE }
        val originalIndex = targetLayout.rows.indexOfFirst { it.row.kind == RowKind.ORIGINAL }
        if (nextGroupStart >= 0 && originalIndex >= 0) {
            // 并发行行不属于主行块(自己独立的时间轴),不参与晋级位移的基线对。
            val fromAuxRows = startLayout.rows.drop(nextGroupStart + 1).filterNot { it.row.duet }
            val toAuxRows = targetLayout.rows.drop(originalIndex + 1).filterNot { it.row.duet }
            for ((fromRow, toRow) in fromAuxRows.zip(toAuxRows)) {
                pairs += fromRow.baseline to toRow.baseline
            }
        }
        return pairs
    }

    fun stop() {
        cadenceGate.update(false)
        removeCallbacks(frame)
        endLineTransition()
        suppressNextLineTransition = true
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
        paddingLandscapeYPercent: Float,
        landscapeFullscreenSafeMarginPercent: Float = DEFAULT_FULLSCREEN_SAFE_MARGIN_PERCENT
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
            this.paddingLandscapeYPercent != paddingLandscapeYPercent ||
            this.landscapeFullscreenSafeMarginPercent != landscapeFullscreenSafeMarginPercent
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
        this.landscapeFullscreenSafeMarginPercent = landscapeFullscreenSafeMarginPercent
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
            transitionStartPositionMs = 0L
            transitionPositionState = TransitionPositionState(0L, 0L)
            transitionWallClockDriven = false
            transitionStartedAtElapsedMs = 0L
            transitionLastClockAtElapsedMs = 0L
            transitionTimeline = null
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
        // 安全边界:四周各留画布短边的一定百分比,避免放大铺满后内容贴屏幕边缘(圆角/挖孔)。
        val safeInset = fullscreenSafeInset(
            minOf(ow, oh).toFloat(),
            landscapeFullscreenSafeMarginPercent
        )
        // 与内容块锚定(anchorLandscapeContentBlock)共用同一安全区:同一圈留白既约束放缩、
        // 也约束摆放,否则「填 85%」与「居中」两处各算一套会让内容偏向一侧。
        val safeRegion = landscapeSafeRegion(oh, padTop, padBottom, safeInset)
        val availableHeight = safeRegion.height.coerceAtLeast(1f)
        // 长轴输入:最长行宽与可用逻辑宽。行按 available 换行,故 maxLineWidth <= ow,
        // widthCap 不会把内容缩小;它只在短轴填充倍数会让长行两端越界时介入。
        val maxLineWidth = widestContentLineWidth(layout)
        val availableWidth =
            ((ow - padLeft - padRight).toFloat() - safeInset * 2f).coerceAtLeast(1f)
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
            "a=${availableHeight.roundToInt()} inset=${safeInset.roundToInt()} " +
            "s=${decision.scale} " +
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

    /**
     * 换行三段式(owner 2026-09-30 定案):严格序列「退场 → 晋级位移 → 入场」,按行分流 ——
     * 1. 离场行组(旧行组=主行+音标/翻译)播预设退场半段(如「向上渐隐」);
     * 2. 内容延续的旧「下一行」平移+放大+亮度接续到当前行槽位(晋级,不播半段);
     * 3. 新到行(新下一行/新辅助文字)播预设入场半段(如「向上渐现」)。
     * 任意时刻至多一段在播,同一句歌词只在一个层出现 —— 旧行未走完新行已进场的
     * 「歌词重叠」由结构消除。歌曲信息行(固定行)不参与,元数据走自己的淡入淡出。
     */
    private fun drawOrientedContent(canvas: Canvas) {
        super.onDraw(canvas)
        lastDrawAtElapsedMs = SystemClock.elapsedRealtime()
        recordDozeDraw()
        syncCadence()
        // 并发行加入淡入:在淡入窗口内主动续帧(暂停态/低节拍下也能完成淡入),结束后归零。
        if (duetJoinStartedAt != 0L) {
            if (SystemClock.elapsedRealtime() - duetJoinStartedAt < DUET_JOIN_FADE_MS) {
                scheduleFrame(frame, 16L)
            } else {
                duetJoinStartedAt = 0L
            }
        }
        if (exitSnapshot != null) {
            val activeTimeline = transitionTimeline
            val activeClock = if (activeTimeline == null) null else transitionClock(activeTimeline)
            if (activeClock == null || activeClock.interrupted) {
                // 位置跳变(seek/拖动):进度不"追"新位置,立即结束过渡,静态绘制新内容。
                endLineTransition()
            }
        }
        val snapshot = exitSnapshot
        if (snapshot == null) {
            drawMetadata(canvas, layout)
            drawRows(canvas, layout, content, LineTransitionFrame(alpha = 1f))
            return
        }
        val timeline = transitionTimeline ?: lineTransitionTimeline(
            content.transitionMode,
            content.lineTransitionSpeed,
            promoting = false
        )
        val clock = transitionClock(timeline)
        val promoting = timeline.moveMs > 0L
        val transitionMode = content.transitionMode
        // 速率档只缩放时长(见 lineTransitionDurationScale),缓动/帧配方不变。
        // 位置式时钟:三段进度由歌词位置推导(见 lineTransitionClockAtPosition),
        // 暂停冻结在当前进度、位置跳变立即结束,不再挂钟计时。
        val exitProgress = clock.exitProgress
        val moveProgress = clock.moveProgress
        val enterProgress = clock.enterProgress
        // 行块换行用缓动:历史档旧行加速上滑离场、新行减速上滑落位,参考档过冲/柔落;
        // 元数据淡出仍走线性。
        val exitEased = lineTransitionExitEasing(transitionMode, exitProgress)
        val enterEased = lineTransitionEnterEasing(transitionMode, enterProgress)
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
        // 并发行有自己独立的时间轴:主行过渡期间它不参与退场/位移/进场(各层的行集都排除
        // 并发行行,见下),按起点快照的槽位静止画在原地。画在过渡层之下:主行块从它旁边
        // 移走/进场时从它上面经过,而不是把它一起带走。
        drawFrozenDuetRows(canvas, snapshot)
        // 参考档位移以行块自身边界为基准(Fade 族 1/4 宽高、Slide 族整宽高),历史档忽略该参数。
        // 宽度取内容框宽(行块横向铺满内容框,等价参考实现 target.getWidth());高度取该层行块
        // 实测高——退场层用旧行块、入场层用新行块,与 ObjectAnimator 在动画起始读取 target
        // 尺寸同序。此前两层共用内容裁剪框高,竖向漂移被放大数倍(真机实测 169px,见
        // [animatedBlockHeightDp])。
        val blockWidthDp = (ow - padLeft - padRight) / density
        // 段1 退场:离场行组(主行+辅助文字)按退场半段离场;晋级时旧「下一行」不属于
        // 离场组(内容延续),排除在退场层外。并发行行不属于任何主行组(自己独立的时间轴),
        // 按行下标跳过绘制(布局保留——共享缩放 [duetSharedScale] 吃整块行堆叠,含并发行),
        // 过渡期间由 [drawFrozenDuetRows] 画在原地。
        // 晋级时「下一行组」(下一行及其辅助行)是内容延续组:不随主行组离场,整组改由
        // 原地保持段/晋级层接管(第二行辅助文字的换行动画跟随第二行歌词,
        // owner 2026-10-02 真机反馈:此前辅助行复用 ROMANIZED/TRANSLATED kind 被算进主行离场组)。
        val nextGroupStart = snapshot.layout.rows.indexOfFirst { it.row.kind == RowKind.NEXT_LINE }
        val exitRows = if (promoting) {
            if (nextGroupStart < 0) {
                snapshot.layout.rows
            } else {
                snapshot.layout.rows.take(nextGroupStart)
            }
        } else {
            snapshot.layout.rows
        }
        val exitLayout = snapshot.layout.copy(rows = exitRows)
        val exitDuetIndices = exitRows.indices.filter { exitRows[it].row.duet }.toSet()
        drawRows(
            canvas,
            exitLayout,
            snapshot.content,
            lineTransitionExitFrame(
                transitionMode,
                exitEased,
                blockWidthDp,
                animatedBlockHeightDp(
                    exitLayout,
                    skipOriginal = metadataMorph,
                    skipRowIndices = exitDuetIndices
                )
            ),
            snapshot.renderStyle,
            skipOriginal = metadataMorph,
            skipRowIndices = exitDuetIndices
        )
        if (promoting) {
            // 段2 晋级位移:退场段内旧「下一行」原地保持,位移段开始即由新「主行」层接管
            // (单层即换,不与旧行同帧叠加)。
            if (moveProgress <= 0f) {
                drawRows(
                    canvas,
                    snapshot.layout.copy(rows = if (nextGroupStart < 0) {
                        emptyList()
                    } else {
                        snapshot.layout.rows.drop(nextGroupStart)
                    }),
                    snapshot.content,
                    LineTransitionFrame(alpha = 1f),
                    snapshot.renderStyle
                )
            } else {
                drawPromotionLayer(canvas, snapshot, moveProgress)
            }
        }
        // 段3 入场:真正新到的行(新下一行及其辅助行)按入场半段进场;晋级时新「主行」与其
        // 辅助行(内容延续组)由段2晋级层接管——布局保留(基线不变)、仅跳过绘制,transition
        // 结束后静态绘制无缝接管。
        val originalIndex = layout.rows.indexOfFirst { it.row.kind == RowKind.ORIGINAL }
        val promotedAuxCount = if (nextGroupStart < 0) {
            0
        } else {
            snapshot.layout.rows.size - nextGroupStart - 1
        }
        // 入场层的行集:晋级时移除旧主行(由晋级层接管);并发行行按行下标跳过绘制(布局保留,
        // 共享缩放口径与静止层一致),由 [drawFrozenDuetRows] 画在原地,不入场、不随主行块移动。
        val enterRows = if (promoting) {
            layout.rows.filter { it.row.kind != RowKind.ORIGINAL }
        } else {
            layout.rows
        }
        // 被晋级的新主行辅助行(内容延续组)布局保留、仅不绘制(由晋级层接管),位移基准按
        // 实际绘制行计。索引在入场层的行集里算(并发行行另有自己的跳过集,互不影响)。
        val promotedAuxRows = if (promoting && originalIndex >= 0 && promotedAuxCount > 0) {
            val from = originalIndex + 1
            val toExclusive = (from + promotedAuxCount).coerceAtMost(layout.rows.size)
            if (from < toExclusive) layout.rows.subList(from, toExclusive) else emptyList()
        } else {
            emptyList()
        }
        val enterSkipIndices = (promotedAuxRows.mapNotNull { aux ->
            enterRows.indexOfFirst { it === aux }.takeIf { it >= 0 }
        } + enterRows.indices.filter { enterRows[it].row.duet }).toSet()
        val enterLayout = layout.copy(rows = enterRows)
        drawRows(
            canvas,
            enterLayout,
            content,
            lineTransitionEnterFrame(
                transitionMode,
                enterEased,
                blockWidthDp,
                animatedBlockHeightDp(enterLayout, skipRowIndices = enterSkipIndices)
            ),
            skipRowIndices = enterSkipIndices
        )
        if (clock.completed) {
            endLineTransition()
        }
    }

    /**
     * 晋级位移段:旧「下一行」即新「主行」(内容延续),自旧槽位平移到当前行槽位,按两槽
     * 字号比等比放大并自旧行亮度升至全亮(同 HyperLyric 下一句晋级的 translation+scale)。
     * 只画新「主行」一层,旧行在位移段开始时即被本层接管。
     *
     * round 3 起点连续:绘制放置由 [lineTransitionMovePlacement] 给出——首行基线在两槽基线间
     * 线性插值、缩放枢轴取该基线,progress=0 画出的就是上一帧的几何(旧「下一行」原位/原字号);
     * 旧实现 translate + 绕目标行盒中心缩放的复合偏移会让起步第一帧整块上移数十像素(真机
     * recwalk4 实测 -38px)。行数变化(旧 1 行 ↔ 新主行 2 行折行)由同一式接管:首行不跳,
     * 块高随缩放进度增长。
     */
    private fun drawPromotionLayer(canvas: Canvas, snapshot: CanvasSnapshot, moveProgress: Float) {
        val target = layout.rows.firstOrNull { it.row.kind == RowKind.ORIGINAL } ?: return
        val from = snapshot.layout.rows.firstOrNull { it.row.kind == RowKind.NEXT_LINE } ?: return
        val eased = moveTransitionEase(moveProgress)
        val sizeRatio = target.row.paint.textSize / from.row.paint.textSize
        val frame = lineTransitionMoveFrame(
            eased,
            sizeRatio = sizeRatio,
            fromAlpha = if (
                secondLineRendersAsSecondary(
                    snapshot.content.secondaryNextLine,
                    snapshot.content.showNextLine,
                    snapshot.content.nextLine.isNotBlank(),
                    hasFirstLineAuxText(
                        snapshot.content.secondaryMode,
                        snapshot.content.romanized,
                        snapshot.content.translated
                    ),
                    snapshot.content.nextLineAux
                )
            ) {
                staticSecondaryTextFactor(snapshot.content.secondaryTextBright)
            } else {
                staticNextLineTextFactor()
            }
        )
        val placement = lineTransitionMovePlacement(
            fromBaselinePx = from.baseline,
            targetBaselinePx = target.baseline,
            sizeRatio = sizeRatio,
            easedProgress = eased
        )
        val pivotX = (padLeft + (ow - padRight)) / 2f
        // 落位帧(alpha/scale/基线均为恒等)不再开离屏层,省电场景不空转一次 saveLayer。
        val identity = frame.alpha >= 1f && placement.scale == 1f &&
            placement.baselinePx == target.baseline
        val layer = if (!identity) {
            val save = canvas.saveLayerAlpha(
                0f,
                0f,
                ow.toFloat(),
                oh.toFloat(),
                (255f * frame.alpha).toInt()
            )
            if (placement.scale != 1f) {
                canvas.scale(placement.scale, placement.scale, pivotX, placement.pivotYPx)
            }
            save
        } else {
            canvas.save()
        }
        val frameClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(frameClip[0], frameClip[1], frameClip[2], frameClip[3])
        drawOriginal(canvas, placement.baselinePx)
        canvas.restoreToCount(layer)
        drawPromotedAuxLayer(canvas, snapshot, frame)
    }

    /**
     * 晋级段的第二行辅助行:与被晋级的「下一行」同属内容延续组,随晋级平移到新主行的
     * 辅助槽位——位移 = 新辅助行基线 − 旧辅助行基线,乘 (1 − 晋级分数)(晋级开始时在旧
     * 槽位、落位时在新槽位),不缩放、恒定辅助亮度。第二行辅助文字的换行动画由此跟随
     * 第二行歌词(owner 2026-10-02 真机反馈:此前辅助行被算进主行离场组、跟着主行退场)。
     */
    private fun drawPromotedAuxLayer(
        canvas: Canvas,
        snapshot: CanvasSnapshot,
        frame: LineTransitionMoveFrame
    ) {
        val nextGroupStart = snapshot.layout.rows.indexOfFirst { it.row.kind == RowKind.NEXT_LINE }
        if (nextGroupStart < 0) return
        val originalIndex = layout.rows.indexOfFirst { it.row.kind == RowKind.ORIGINAL }
        if (originalIndex < 0) return
        // 并发行行不属于主行块(自己独立的时间轴,过渡期间由 [drawFrozenDuetRows] 静止绘制),
        // 不参与晋级位移的辅助行配对。
        val fromAuxRows = snapshot.layout.rows.drop(nextGroupStart + 1).filterNot { it.row.duet }
        val toAuxRows = layout.rows.drop(originalIndex + 1).filterNot { it.row.duet }
        val bright = snapshot.content.secondaryTextBright
        val remain = 1f - frame.translateFraction
        for ((fromRow, toRow) in fromAuxRows.zip(toAuxRows)) {
            canvas.save()
            canvas.translate(0f, (toRow.baseline - fromRow.baseline) * remain)
            setTextAlpha(
                fromRow.row.paint,
                staticSecondaryTextFactor(bright),
                1f,
                resolvedPalette.secondaryText
            )
            fromRow.row.paint.shader = null
            fromRow.row.paint.clearShadowLayer()
            var lineIndex = 0
            while (lineIndex < fromRow.row.lines.size) {
                val line = fromRow.row.lines[lineIndex]
                canvas.drawText(
                    line.text,
                    line.startX,
                    fromRow.baseline + lineIndex * fromRow.row.lineHeight,
                    fromRow.row.paint
                )
                lineIndex++
            }
            canvas.restore()
        }
    }

    private fun drawRows(
        canvas: Canvas,
        drawLayout: LayoutState,
        drawContent: AodCanvasContent,
        frame: LineTransitionFrame,
        renderStyle: RenderStyleSnapshot? = null,
        skipOriginal: Boolean = false,
        skipRowIndices: Set<Int> = emptySet()
    ) {
        if (frame.alpha <= 0f || drawLayout.rows.none {
                it.row.kind != RowKind.METADATA && (!skipOriginal || it.row.kind != RowKind.ORIGINAL)
            }
        ) return
        val savedContent = content
        val savedLayout = layout
        if (renderStyle != null) applyRenderStyle(renderStyle)
        content = drawContent
        layout = drawLayout
        val layer = if (frame.alpha < 1f || frame.translateXDp != 0f ||
            frame.translateYDp != 0f || frame.scale != 1f ||
            frame.rotationDeg != 0f || frame.rotationXDeg != 0f || frame.rotationYDeg != 0f
        ) {
            val save = canvas.saveLayerAlpha(0f, 0f, ow.toFloat(), oh.toFloat(), (255f * frame.alpha).toInt())
            canvas.translate(frame.translateXDp * density, frame.translateYDp * density)
            val pivotX = (padLeft + (ow - padRight)) / 2f
            val pivotY = (padTop + (oh - padBottom)) / 2f
            if (frame.scale != 1f) {
                // 放缩绕内容框中心,保证 Zoom 模式收放不偏离版面锚点。
                canvas.scale(frame.scale, frame.scale, pivotX, pivotY)
            }
            if (frame.rotationDeg != 0f) {
                // 平面旋转同样绕内容框中心(旋转档)。
                canvas.rotate(frame.rotationDeg, pivotX, pivotY)
            }
            if (frame.rotationXDeg != 0f || frame.rotationYDeg != 0f) {
                // 翻转档:Camera 透视等价于 View/graphicsLayer 的 rotationX/Y
                // (Camera 坐标 Y 向上、屏幕 Y 向下,故取负号对齐语义)。
                val camera = Camera()
                val matrix = Matrix()
                camera.rotateX(-frame.rotationXDeg)
                camera.rotateY(-frame.rotationYDeg)
                camera.getMatrix(matrix)
                matrix.preTranslate(-pivotX, -pivotY)
                matrix.postTranslate(pivotX, pivotY)
                canvas.concat(matrix)
            }
            save
        } else canvas.save()
        // 所有歌词绘制路径(原文/注音/翻译/逐字扫光/发光块)共享这一处逻辑裁剪:
        // 即使整词不可分或动画越界超出其测量宽度,也强制限制在周围 padding 框内,
        // 取代原先逐 drawText 的 clip,成为唯一统一边界。
        val frameClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(frameClip[0], frameClip[1], frameClip[2], frameClip[3])
        // 对唱共享缩放:并发行在场且行堆叠总高超出内容区时整块按同一比例缩小(0.3 绝对
        // 下限,见 duetSharedFitScale);枢轴取内容框中心,与 Zoom 档同口径。缺席恒 1 不介入。
        val duetScale = duetSharedScale(drawLayout)
        if (duetScale != 1f) {
            canvas.scale(
                duetScale,
                duetScale,
                (padLeft + (ow - padRight)) / 2f,
                (padTop + (oh - padBottom)) / 2f
            )
        }
        val sharedLineLevelSweep = shouldUseSharedLineLevelSweep(
            drawContent.lineLevelSync,
            drawLayout.original.lines.isNotEmpty(),
            drawContent.animationMode,
            drawContent.lineStartMs,
            drawContent.lineEndMs,
            // 带真实词窗的行压过行级标记,走 drawOriginal 的词级卡拉OK分支(与决策函数同源)。
            drawLayout.original.timed
        )
        if (sharedLineLevelSweep) {
            drawSharedLineLevelRows(
                canvas,
                drawLayout.rows.filterIndexed { index, _ -> index !in skipRowIndices },
                skipOriginal
            )
        } else {
            var rowIndex = 0
            while (rowIndex < drawLayout.rows.size) {
                val row = drawLayout.rows[rowIndex]
                if (rowIndex in skipRowIndices) {
                    rowIndex++
                    continue
                }
                when (row.row.kind) {
                    RowKind.METADATA -> Unit
                    RowKind.ORIGINAL -> if (!skipOriginal) drawOriginal(canvas, row.baseline)
                    RowKind.DUET_ORIGINAL -> if (!skipOriginal) {
                        withDuetRowTransition(canvas, row.row) {
                            drawDuetOriginal(canvas, row.baseline)
                        }
                    }
                    else -> withDuetRowTransition(canvas, row.row) {
                        drawText(canvas, row.row, row.baseline)
                    }
                }
                rowIndex++
            }
        }
        canvas.restoreToCount(layer)
        content = savedContent
        layout = savedLayout
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun drawSharedLineLevelRows(
        canvas: Canvas,
        rows: List<PositionedRow>,
        skipOriginal: Boolean = false
    ) {
        // 副行(音标/翻译/下一行)静态绘制,与预览的静态 Text 行一致,不参与扫光。
        // 换行分层时入场层只带新到副行(主行由晋级位移层接管),这里按行集内是否有主行分流;
        // 歌曲变更形变时旧层主行由元数据形变接管,按 [skipOriginal] 与逐行路径同义跳过。
        drawSecondaryRowsStatic(canvas, rows, bright = content.secondaryTextBright)
        val original = rows.firstOrNull { it.row.kind == RowKind.ORIGINAL } ?: return
        if (skipOriginal) return
        drawOriginalRubyRows(canvas, original.baseline, bright = true)
        // 主行发光统一委托共享渲染核心 LyricGlowRenderer —— 与预览(PreviewAnimatedLyric)
        // 同一份配方:dim 底、光晕、easeInOut 扫光带,杜绝行级同步路径另走一套旧实现。
        drawOriginalGlowBlock(
            canvas,
            original.baseline,
            layout.original,
            lineProgress(),
            effectiveLineSyncFillMode()
        )
        // 并发行(对唱)在共享行级扫光路径下同样绘制(自带行窗口/词表进度),只播自己的
        // 加入淡入(见 [withDuetRowTransition])。
        rows.firstOrNull { it.row.kind == RowKind.DUET_ORIGINAL }?.let { duetRow ->
            withDuetRowTransition(canvas, duetRow.row) {
                drawDuetOriginal(canvas, duetRow.baseline)
            }
        }
    }

    /** 生效进度效果:仅行级同步时按配置解析;逐字时间源路径保持整块同时横向扫光。 */
    private fun effectiveLineSyncFillMode(): String =
        if (content.lineLevelSync) {
            resolvedLineSyncFillMode(true, content.lineSyncFillMode)
        } else {
            LyricGlowRenderer.FILL_LEFT_TO_RIGHT_WHOLE_BLOCK
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
            // DUET_ORIGINAL 由 drawDuetOriginal 自绘(自带词级扫光),不在此按辅助行重复画。
            if (positioned.row.kind == RowKind.ORIGINAL ||
                positioned.row.kind == RowKind.METADATA ||
                positioned.row.kind == RowKind.DUET_ORIGINAL
            ) {
                rowIndex++
                continue
            }
            // 并发行辅助行(含和声走的辅助行车道)只播自己的加入淡入(见 [withDuetRowTransition])。
            withDuetRowTransition(canvas, positioned.row) {
                drawSecondaryRowStatic(canvas, positioned, bright, keepShader)
            }
            rowIndex++
        }
    }

    /** [drawSecondaryRowsStatic] 的单行主体(并发行行由调用方套自己的过渡层)。 */
    private fun drawSecondaryRowStatic(
        canvas: Canvas,
        positioned: PositionedRow,
        bright: Boolean,
        keepShader: Boolean
    ) {
        // 辅助文字逐字效果:携带行窗口的辅助行走逐字渲染(自管取色/亮度),不落静态路径。
        val auxWindow = positioned.row.auxKaraokeWindow
        if (auxWindow != null) {
            drawAuxKaraokeRow(canvas, positioned.row, positioned.baseline, auxWindow)
            return
        }
        // 下一行歌词颜色恒走独立的 nextLineText token(与预览/非行级同步路径同源
        // secondLineColorArgb):「辅助文字显示第二行歌词」只借辅助文字的亮度档,
        // 不借「辅助行颜色」,否则"下一行颜色"设置对该形态完全失效。
        if (positioned.row.kind == RowKind.NEXT_LINE) {
            val secondaryForm = secondLineRendersAsSecondary(
                content.secondaryNextLine,
                content.showNextLine,
                content.nextLine.isNotBlank(),
                hasFirstLineAuxText(
                    content.secondaryMode,
                    content.romanized,
                    content.translated
                ),
                content.nextLineAux
            )
            setTextAlpha(
                positioned.row.paint,
                if (secondaryForm) {
                    staticSecondaryTextFactor(bright)
                } else {
                    staticNextLineTextFactor()
                },
                1f,
                secondLineColorArgb(
                    if (secondaryForm) {
                        SecondLinePresentation.AS_SECONDARY
                    } else {
                        SecondLinePresentation.STANDALONE
                    },
                    resolvedPalette
                )
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

    /**
     * 辅助文字行(第一行音标/翻译,以及并发行自己的辅助行)的逐字效果
     * (「辅助文字逐字效果」,见 SurfaceProfile.secondaryWordKaraoke):与主行 drawWordKaraoke
     * 同一共享渲染核心(LyricWordKaraokeRenderer),观感随档——「BetterLyrics」档同主行有
     * 未唱下沉/已唱上浮、长块放大与辉光(整档不做逐字扫光、整块亮起),其余档为基础
     * 逐字卡拉OK(词内扫光)。
     *
     * 时间源两路:源提供逐字音标时间时(transliterationLines 的 timedSegments)按真实词窗
     * 点亮;翻译行与无逐字音标的音标行按行窗口 + 行内几何合成(syntheticKaraokeBlocks /
     * syntheticCharTimeWindow,与主行行级源同式,推进前缘与行级扫光几何一致)。
     * [window] 为该行自身的播放窗口(第一行辅助行=当前行窗口,并发行辅助行=并发行窗口)。
     * 颜色取辅助行颜色,亮度档沿用「高亮辅助文字」([alphaFactor]),不另建取色体系。
     */
    private fun drawAuxKaraokeRow(canvas: Canvas, row: Row, baseline: Float, window: LongRange) {
        if (row.lines.isEmpty()) return
        val paint = row.paint
        val position = projectedPosition()
        val betterLyrics = content.animationMode == "BetterLyrics"
        val sinkPx = karaokeFloatSinkPx(row.lineHeight)
        val alphaFactor = steadyTextAlpha(staticSecondaryTextFactor(content.secondaryTextBright))
        val totalWidth = row.lines.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(1f)
        val runs = ArrayList<KaraokeWordRun>(16)
        var precedingWidth = 0f
        row.lines.forEachIndexed { lineIndex, line ->
            val lineBaseline = baseline + lineIndex * row.lineHeight
            runs.clear()
            val segments = line.timedSegments
            if (segments.isNotEmpty()) {
                // 逐字音标源:每词一段按真实词窗点亮;长词判定/放大/辉光与主行词级路径同式。
                var x = 0f
                segments.forEach { segment ->
                    val durationMs = segment.endMs - segment.startMs
                    runs += KaraokeWordRun(
                        text = segment.text,
                        x = line.startX + x,
                        width = segment.width,
                        playedFraction = timedWordProgress(position, segment.startMs, segment.endMs),
                        durationMs = durationMs,
                        longSyllable = isLongKaraokeSyllable(durationMs)
                    )
                    x += segment.width + segment.gapAfter
                }
            } else {
                // 无逐字时间(翻译行/纯行级音标):按行窗口 + 行内几何合成——中文逐字成块、
                // 西文按词成块,块内字符各自扫光、共享块级高亮进度(与主行行级源同式)。
                var prefix = 0f
                var charIndex = 0
                for (block in syntheticKaraokeBlocks(line.text)) {
                    while (charIndex < block.first) {
                        val unitEnd = karaokeUnitEnd(line.text, charIndex, block.first)
                        prefix += paint.measureText(line.text, charIndex, unitEnd)
                        charIndex = unitEnd
                    }
                    val blockWidth = paint.measureText(line.text, block.first, block.last + 1)
                    val blockWindow = syntheticCharTimeWindow(
                        window.first,
                        window.last,
                        totalWidth,
                        precedingWidth + prefix,
                        blockWidth
                    )
                    val blockHighlight = timedWordProgress(position, blockWindow.first, blockWindow.last)
                    val blockLong = isLongKaraokeSyllable(blockWindow.last - blockWindow.first)
                    var blockChar = block.first
                    while (blockChar <= block.last) {
                        val charEnd = karaokeUnitEnd(line.text, blockChar, block.last + 1)
                        val charWidth = paint.measureText(line.text, blockChar, charEnd)
                        val charWindow = syntheticCharTimeWindow(
                            window.first,
                            window.last,
                            totalWidth,
                            precedingWidth + prefix,
                            charWidth
                        )
                        runs += KaraokeWordRun(
                            text = line.text.substring(blockChar, charEnd),
                            x = line.startX + prefix,
                            width = charWidth,
                            playedFraction = timedWordProgress(position, charWindow.first, charWindow.last),
                            durationMs = charWindow.last - charWindow.first,
                            longSyllable = blockLong,
                            highlightFraction = if (blockLong) blockHighlight else -1f
                        )
                        prefix += charWidth
                        blockChar = charEnd
                    }
                    charIndex = block.last + 1
                }
            }
            if (runs.isNotEmpty()) {
                LyricWordKaraokeRenderer.draw(
                    canvas = canvas,
                    paint = paint,
                    runs = runs,
                    baseline = lineBaseline,
                    sungColor = resolvedPalette.secondaryText,
                    unsungColor = resolvedPalette.secondaryText,
                    glowColor = resolvedPalette.glow,
                    glowEnabled = content.glowMode != "Off",
                    betterLyrics = betterLyrics,
                    sinkPx = sinkPx,
                    alphaFactor = alphaFactor
                )
            }
            precedingWidth += line.width
        }
    }

    /**
     * 字型/字号按 [forContent] 重配(setContent 与自适应高度测量 [measureContentStack] 共用
     * 同一公式)。测量路径调用后 setContent 会按同一内容再应用一次,绘制态不受影响。
     */
    private fun applyContentStyle(forContent: AodCanvasContent) {
        val sizeScale = textSizeModeMultiplier(forContent.textSizeMode, forContent.textSizeCustom)
        val baseSp = baseTextSizeSp(forContent.original) * sizeScale
        val typeface = resolveTypeface(forContent.fontFamily, forContent.weight)
        originalPaint.typeface = typeface
        if (forContent.fontFamily != "auto") {
            val regularTypeface = resolveTypeface(forContent.fontFamily, "Regular")
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
        metadataPaint.textSize = metadataTextSizeSp(forContent.metadataSizePercent) * scaledDensity
        romanizedPaint.textSize =
            secondaryReadingTextSizeSp(baseSp, forContent.secondaryTextSizePercent) * scaledDensity
        translatedPaint.textSize =
            secondaryTranslationTextSizeSp(baseSp, forContent.secondaryTextSizePercent) * scaledDensity
        nextLinePaint.textSize = nextLineTextSizeSp() * scaledDensity
        rubyPaint.textSize = rubyTextSizePx(originalPaint.textSize)
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

    // --- 歌曲图片(歌曲信息左侧):槽位/解码/绘制,几何公式同源 AodCanvasTextMetrics ---

    /** 槽位判定(布局期,与解码结果无关):显示开关 + 帧非空即在歌曲信息左侧预留图片槽。 */
    private fun artworkSlotActive(content: AodCanvasContent): Boolean =
        content.artworkVisible && content.artworkJpeg.isNotEmpty() && content.artworkKey.isNotEmpty()

    /** 帧按 key 解码缓存;换帧重解,残帧/坏帧一律清空(fail-closed:不显示错的图)。 */
    private fun syncArtworkBitmap() {
        val key = content.artworkKey
        if (key.isEmpty() || content.artworkJpeg.isEmpty()) {
            artworkBitmap = null
            artworkBitmapKey = ""
            return
        }
        if (key == artworkBitmapKey && artworkBitmap != null) return
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(content.artworkJpeg, 0, content.artworkJpeg.size)
        }.getOrNull()
        artworkBitmap = decoded
        artworkBitmapKey = if (decoded != null) key else ""
    }

    /** 圆形+旋转开启时需要持续帧推进旋转角(受同一可见性/节拍门控,隐藏即停;暂停默认停)。 */
    private fun artworkSpinActive(): Boolean =
        artworkSlotActive(content) && content.artworkShape == ARTWORK_SHAPE_CIRCLE &&
            artworkSpinEffective(
                content.artworkSpin,
                content.artworkSpinWhenPaused,
                content.playbackPaused
            ) && artworkBitmap != null

    /** 歌曲信息行基线(锚点感知):底部锚点向上排,顶部锚点向下排(与 drawMetadata 同式)。 */
    private fun metadataLineBaseline(metadata: PositionedRow, index: Int): Float =
        if (content.metadataAnchor == "bottom") {
            metadata.baseline - (metadata.row.lines.size - 1 - index) * metadata.row.lineHeight
        } else {
            metadata.baseline + index * metadata.row.lineHeight
        }

    /**
     * 图片槽矩形:恒在歌曲信息文本块左侧(wrapMetadataText 的组布局已为文本块让出
     * 前置宽度),垂直居中于文本块(首末行基线中点为文本中线)。无有效帧返回 null。
     */
    private fun metadataArtworkRect(metadata: PositionedRow): RectF? {
        val bitmap = artworkBitmap ?: return null
        if (bitmap.isRecycled || !artworkSlotActive(content)) return null
        val lines = metadata.row.lines
        if (lines.isEmpty()) return null
        val leading = artworkLeadingPx(
            metadata.row.paint.textSize,
            density,
            content.artworkAdaptiveScale,
            content.artworkSizeDp
        )
        val blockWidth = lines.maxOf { it.width }
        val groupWidth = leading + blockWidth
        val groupLeft = alignedStart(
            groupWidth,
            alignmentFor(content, RowKind.METADATA),
            0f,
            groupWidth
        )
        val side = artworkSidePx(
            metadata.row.paint.textSize,
            density,
            content.artworkAdaptiveScale,
            content.artworkSizeDp
        )
        val metrics = metadata.row.paint.fontMetrics
        // 图片槽与文本块同心中线:文本视觉中线 = 首末行基线中点 + (ascent + descent)/2
        // (纯函数与预览 Row 居中同源;此前这里符号写反,图片整体低于文本约 0.7×字号)。
        val textMiddleY = metadataTextCenterY(
            metadataLineBaseline(metadata, 0),
            metadataLineBaseline(metadata, lines.size - 1),
            metrics.ascent,
            metrics.descent
        )
        val top = textMiddleY - side / 2f
        return RectF(groupLeft, top, groupLeft + side, top + side)
    }

    /**
     * 绘制歌曲图片:方形=矩形裁切;圆形=圆形裁切。旋转只对圆形生效(配置层已把
     * 方形的 spin 归零),角速度与预览同源(artworkSpinDegrees)。中心裁切填满方槽。
     */
    private fun drawArtwork(canvas: Canvas, rect: RectF, alpha: Float) {
        val bitmap = artworkBitmap ?: return
        if (bitmap.isRecycled) return
        canvas.save()
        val clip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(clip[0], clip[1], clip[2], clip[3])
        if (content.artworkShape == ARTWORK_SHAPE_CIRCLE) {
            canvas.clipPath(Path().apply { addOval(rect, Path.Direction.CW) })
        }
        if (content.artworkSpin) {
            val angle = if (
                artworkSpinEffective(content.artworkSpin, content.artworkSpinWhenPaused, content.playbackPaused)
            ) {
                artworkSpinDegrees(true, SystemClock.elapsedRealtime())
                    .also { lastArtworkSpinAngle = it }
            } else {
                // 暂停停转:冻结在停转时刻的角度(节拍门已停,偶发重绘不推进)。
                lastArtworkSpinAngle
            }
            if (angle != 0f) canvas.rotate(angle, rect.centerX(), rect.centerY())
        } else {
            lastArtworkSpinAngle = 0f
        }
        artworkPaint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt()
        val scale = max(
            rect.width() / bitmap.width.toFloat(),
            rect.height() / bitmap.height.toFloat()
        )
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val destination = RectF(
            rect.centerX() - drawWidth / 2f,
            rect.centerY() - drawHeight / 2f,
            rect.centerX() + drawWidth / 2f,
            rect.centerY() + drawHeight / 2f
        )
        canvas.drawBitmap(bitmap, null, destination, artworkPaint)
        canvas.restore()
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
        // 歌曲图片画在文本下层、文本块左侧。离场帧(renderStyle!=null)不画旧图:
        // 曲目不变的换行过渡走整帧 alpha=1(见 drawOrientedContent),图片不随行闪。
        if (renderStyle == null) {
            metadataArtworkRect(metadata)?.let { drawArtwork(canvas, it, alpha) }
        }
        metadata.row.lines.forEachIndexed { index, line ->
            // 底部锚点时行向上排（末行贴近屏幕底），顶部锚点向下排。
            val lineBaseline = metadataLineBaseline(metadata, index)
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
                // 源色取主行实际绘制所用的「已唱颜色」:主行三条渲染路径(静态/扫光块/
                // 词级卡拉OK)底色一律取 sungText,primaryText 键已移除。
                snapshot.renderStyle.palette.sungText,
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

    /** 自适应卡片高度测量结果:内容自然堆叠高度与元数据行实测高度(无元数据行时为 0)。 */
    internal data class ContentStackMeasure(
        val stackHeightPx: Float,
        val metadataRowHeightPx: Float
    )

    private data class StackMeasureCache(
        val content: AodCanvasContent,
        val widthPx: Int,
        val measure: ContentStackMeasure
    )

    private var stackMeasureCache: StackMeasureCache? = null

    /**
     * 自适应卡片高度测量:按 [forContent] 与画布宽度构建行,返回内容自然堆叠高度(各行行高
     * +行前距、元数据-歌词间距、画布上下 padding,见 contentStackHeightPx)与元数据行实测
     * 高度,供锁屏卡片按实测内容定高(「高度」设置仍是上限)。只读测量:不改 content/layout
     * 行状态;字型字号按 [forContent] 重配(与 setContent 同一公式,随后 setContent 会再应用
     * 一次,绘制态不受影响)。结果按(内容,宽度)缓存,碰撞刷新每帧只在内容/宽度变化时重算换行。
     */
    fun measureContentStack(forContent: AodCanvasContent, widthPx: Int): ContentStackMeasure {
        // 与 setContent 同一规范化入口,保证测量与绘制的换行语义一致。
        val content = forContent.copy(
            animationMode = normalizeAodAnimation(forContent.animationMode),
            motionMode = normalizeAodMotion(forContent.motionMode),
            overflowMode = normalizeAodOverflow(forContent.overflowMode)
        )
        stackMeasureCache?.let { cached ->
            if (cached.content == content && cached.widthPx == widthPx) return cached.measure
        }
        applyContentStyle(content)
        val built = buildRows(
            content,
            (widthPx - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        )
        val metadataRow = built.rows.firstOrNull { it.kind == RowKind.METADATA }
        // 效果余量计入卡片高度(与 positionRows 同一取值):辉光/下沉会越出行盒,块尾又贴住
        // 裁剪沿,卡片不长高这部分外扩就会被切平(「歌词刚好叠到画布边缘被裁切」)。
        val effectNeeds = effectEdges(content, built.rows, built.originalLayout.timed).needs
        val measure = ContentStackMeasure(
            stackHeightPx = contentStackHeightPx(
                built.rows.map { it.height },
                built.rows.map { it.gapBefore },
                metadataGapPx = if (metadataRow != null) METADATA_LYRIC_GAP_DP * density else 0f,
                padTopPx = paddingTop.toFloat() + effectNeeds.topPx,
                padBottomPx = paddingBottom.toFloat() + effectNeeds.bottomPx
            ),
            metadataRowHeightPx = metadataRow?.height ?: 0f
        )
        stackMeasureCache = StackMeasureCache(content, widthPx, measure)
        return measure
    }

    private fun rebuildLayout() {
        val built = buildRows(content, (ow - padLeft - padRight).coerceAtLeast(1).toFloat())
        layout = LayoutState(
            positionRows(built.rows, built.originalLayout),
            built.originalLayout,
            built.duetLayout
        )
        contentBoundsChangedListener?.invoke()
    }

    private data class BuiltRows(
        val rows: List<Row>,
        val originalLayout: OriginalLayout,
        val duetLayout: OriginalLayout? = null
    )

    /**
     * 行装配(rebuildLayout 与自适应高度测量 [measureContentStack] 共用,杜绝两套装配漂移):
     * [content] 与可用宽度参数化,行内容/换行只依赖这两者,不依赖画布高度。
     */
    private fun buildRows(content: AodCanvasContent, availableWidth: Float): BuiltRows {
        val originalLayout = buildOriginalLayout(content, availableWidth)
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
            val metadataRow = rowWithLines(
                RowKind.METADATA,
                content.metadata,
                metadataPaint,
                0f,
                wrapMetadataText(content, content.metadata, metadataPaint, availableWidth)
            )
            // 图片槽高于文本块时行高按槽边长记账:自适应卡片高度与元数据组件预算都吃这行实测高,
            // 否则图片会被卡片/内容框裁掉(与预览「行高取文本与图片的大者」同语义)。
            rows += if (artworkSlotActive(content)) {
                metadataRow.copy(
                    height = max(
                        metadataRow.height,
                        artworkSidePx(
                            metadataPaint.textSize,
                            density,
                            content.artworkAdaptiveScale,
                            content.artworkSizeDp
                        )
                    )
                )
            } else {
                metadataRow
            }
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
        // 辅助文字逐字效果(见 SurfaceProfile.secondaryWordKaraoke):第一行辅助行取当前行窗口;
        // 关闭时恒空,行按静态绘制零变化。
        val auxKaraokeWindow = if (content.secondaryWordKaraoke) {
            content.lineStartMs..content.lineEndMs
        } else {
            null
        }
        if (showReading && content.romanized.isNotBlank()) {
            // 自适应大小:字号可能被拟合缩小;行用独立 Paint(共享 paint 不得就地改字号)。
            val readingPaint = fittedSecondaryPaint(
                content,
                content.romanized,
                romanizedPaint,
                originalLayout.lineCount,
                availableWidth,
                translation = false
            )
            val lines = transliterationLines(content, originalLayout, availableWidth, readingPaint)
                ?: wrapSecondaryText(
                    content,
                    content.romanized,
                    readingPaint,
                    originalLayout.lineCount,
                    availableWidth
                )
            rows += rowWithLines(RowKind.ROMANIZED, content.romanized, readingPaint, ROW_GAP_BEFORE_SECONDARY_DP * density, lines, auxKaraokeWindow)
        }
        if (showTranslation && content.translated.isNotBlank()) {
            val translationPaint = fittedSecondaryPaint(
                content,
                content.translated,
                translatedPaint,
                originalLayout.lineCount,
                availableWidth,
                translation = true
            )
            // 插件提供逐字翻译词表且本面开启「辅助文字逐字效果」时按真实词窗折行点亮;
            // 开关关闭时保持原行装配(逐字节不变)。
            val translatedLines = (if (content.secondaryWordKaraoke) {
                translatedTimedLines(content, availableWidth, translationPaint)
            } else {
                null
            }) ?: wrapSecondaryText(
                content,
                content.translated,
                translationPaint,
                originalLayout.lineCount,
                availableWidth
            )
            rows += rowWithLines(
                RowKind.TRANSLATED,
                content.translated,
                translationPaint,
                ROW_GAP_BEFORE_SECONDARY_DP * density,
                translatedLines,
                auxKaraokeWindow
            )
        }
        // 对唱并发行(仅息屏内容携带,见 SurfaceProfile.duetConcurrent):主行块(原文+辅助行)
        // 之后同尺寸堆叠并发行块(原文+其辅助行),各画各的词级扫光;并发行在场时取代独立
        // 「下一行」行(与 #82「辅助文字显示第二行歌词」的 anti-dup 同式,防下方拥挤)。
        // 和声行(harmony,插件行 role=BG 的 x-bg 回声)不走这一档:它常与主行同文,同尺寸
        // 堆叠出来就是两条一样的大字行(真机 2026-10-07 反馈的错观感);改走辅助行车道,
        // 与第一行辅助行同规格——参照 HyperLyric 把 x-bg 折进父行 secondary 车道。
        val duet = content.duetLine
        var duetLayout: OriginalLayout? = null
        if (duet != null && duet.text.isNotBlank()) {
            if (duet.harmony) {
                // 复用辅助行装配:同一字号公式/亮度档/「辅助文字逐字效果」路径(行窗口取和声
                // 自己的,逐字推进与和声同拍);换行档取主行行数——和声多是主行文本的回声。
                // 和声不受「辅助文字模式」门控,辅助字号设置必须同样覆盖它(见 setContent 注释)。
                val harmonyPaint = fittedSecondaryPaint(
                    content,
                    duet.text,
                    romanizedPaint,
                    originalLayout.lineCount,
                    availableWidth,
                    translation = false
                )
                rows += rowWithLines(
                    RowKind.ROMANIZED,
                    duet.text,
                    harmonyPaint,
                    ROW_GAP_BEFORE_SECONDARY_DP * density,
                    wrapSecondaryText(
                        content,
                        duet.text,
                        harmonyPaint,
                        originalLayout.lineCount,
                        availableWidth
                    ),
                    if (content.secondaryWordKaraoke) duet.lineStartMs..duet.lineEndMs else null
                ).copy(duet = true)
            } else {
                val built = buildDuetOriginalLayout(duet, availableWidth)
                duetLayout = built
                val duetAuxKaraokeWindow = if (content.secondaryWordKaraoke) {
                    duet.lineStartMs..duet.lineEndMs
                } else {
                    null
                }
                val metrics = originalPaint.fontMetrics
                val lineHeight = metrics.descent - metrics.ascent + LYRIC_LINE_EXTRA_HEIGHT_DP * density
                rows += Row(
                    RowKind.DUET_ORIGINAL,
                    duet.text,
                    originalPaint,
                    originalRowHeight(
                        lineHeight,
                        built.lineCount,
                        built.rubyHeight,
                        built.lineGap
                    ),
                    ROW_GAP_BEFORE_ORIGINAL_DP * density,
                    emptyList(),
                    lineHeight,
                    duet = true
                )
                if (showReading && duet.romanized.isNotBlank()) {
                    val duetReadingPaint = fittedSecondaryPaint(
                        content,
                        duet.romanized,
                        romanizedPaint,
                        built.lineCount,
                        availableWidth,
                        translation = false
                    )
                    rows += rowWithLines(
                        RowKind.DUET_ROMANIZED,
                        duet.romanized,
                        duetReadingPaint,
                        ROW_GAP_BEFORE_SECONDARY_DP * density,
                        wrapSecondaryText(
                            content,
                            duet.romanized,
                            duetReadingPaint,
                            built.lineCount,
                            availableWidth,
                            alignmentFor(content, RowKind.DUET_ROMANIZED)
                        ),
                        // 并发行辅助行取并发行自己的窗口,逐字推进与并发行主行同拍。
                        duetAuxKaraokeWindow
                    ).copy(duet = true)
                }
                if (showTranslation && duet.translated.isNotBlank()) {
                    val duetTranslationPaint = fittedSecondaryPaint(
                        content,
                        duet.translated,
                        translatedPaint,
                        built.lineCount,
                        availableWidth,
                        translation = true
                    )
                    rows += rowWithLines(
                        RowKind.DUET_TRANSLATED,
                        duet.translated,
                        duetTranslationPaint,
                        ROW_GAP_BEFORE_SECONDARY_DP * density,
                        wrapSecondaryText(
                            content,
                            duet.translated,
                            duetTranslationPaint,
                            built.lineCount,
                            availableWidth,
                            alignmentFor(content, RowKind.DUET_TRANSLATED)
                        ),
                        duetAuxKaraokeWindow
                    ).copy(duet = true)
                }
            }
        }
        // 下一行歌词呈现与预览同源(secondLinePresentation):「辅助文字显示第二行歌词」
        // 开启且第一行辅助文字实际显示时以辅助文字样式(音标行字号公式+亮度档)绘制并取代
        // 独立下一行行,同一行不重复出现;第一行无辅助文字时该开关不产生第二行呈现。
        // 「显示第二行辅助文字」开启时第二行歌词行本身也按辅助文字形态呈现(即使「辅助
        // 文字显示第二行歌词」关闭——此时以「显示下一行歌词」为前提),独立下一行行形态
        // 同样追加其辅助行。颜色恒走「下一行颜色」(secondLineColorArgb),不随形态改用辅助行颜色。
        // 对唱并发行在场时独立下一行行整体让位(见上)。
        if (duet == null || duet.text.isBlank()) when (secondLinePresentation(
            content.secondaryNextLine,
            content.showNextLine,
            content.nextLine.isNotBlank(),
            hasFirstLineAuxText(content.secondaryMode, content.romanized, content.translated),
            content.nextLineAux
        )) {
            SecondLinePresentation.AS_SECONDARY -> {
                // 第二行歌词自身布局先行落定:其辅助行的换行档跟随「第二行实际呈现的行数」,
                // 而不是主行行数(owner 2026-10-02 真机反馈)。
                val nextLineSecondaryPaint = fittedSecondaryPaint(
                    content,
                    content.nextLine,
                    romanizedPaint,
                    originalLayout.lineCount,
                    availableWidth,
                    translation = false
                )
                val nextLineLines = wrapSecondaryText(
                    content,
                    content.nextLine,
                    nextLineSecondaryPaint,
                    originalLayout.lineCount,
                    availableWidth,
                    alignmentFor(content, RowKind.NEXT_LINE)
                )
                rows += rowWithLines(
                    RowKind.NEXT_LINE,
                    content.nextLine,
                    nextLineSecondaryPaint,
                    ROW_GAP_BEFORE_NEXT_LINE_DP * density,
                    nextLineLines
                )
                // 「显示第二行辅助文字」:在第二行歌词行之后追加该行自己的辅助文字行
                // (行清单与预览同源,见 appendSecondLineAuxRows)。
                appendSecondLineAuxRows(rows, content, nextLineLines.size, availableWidth)
            }
            SecondLinePresentation.STANDALONE -> {
                // 独立下一行行形态同样追加「显示第二行辅助文字」的辅助行(owner 2026-10-04:
                // 第四行以第二行歌词行为前提,不区分第二行以哪种形态呈现;换行档跟随独立
                // 下一行行的呈现行数)。
                val nextLineLines = wrapSecondaryText(
                    content,
                    content.nextLine,
                    nextLinePaint,
                    1,
                    availableWidth,
                    alignmentFor(content, RowKind.NEXT_LINE)
                )
                rows += rowWithLines(
                    RowKind.NEXT_LINE,
                    content.nextLine,
                    nextLinePaint,
                    ROW_GAP_BEFORE_NEXT_LINE_DP * density,
                    nextLineLines
                )
                appendSecondLineAuxRows(rows, content, nextLineLines.size, availableWidth)
            }
            SecondLinePresentation.NONE -> Unit
        }
        return BuiltRows(rows, originalLayout, duetLayout)
    }

    /**
     * 「显示第二行辅助文字」:第二行歌词行之后追加其自身的辅助文字行(音标/翻译,按辅助
     * 文字模式取用;行清单与预览同源,见 [secondLineAuxRows])。辅助形态与独立下一行行形态
     * 共用本装配,防止两处漂移;换行档跟随第二行歌词自身呈现的行数
     * ([secondLineAuxPreferredLines])。
     */
    private fun appendSecondLineAuxRows(
        rows: ArrayList<Row>,
        content: AodCanvasContent,
        nextLineRenderedLineCount: Int,
        availableWidth: Float
    ) {
        val preferredLines = secondLineAuxPreferredLines(nextLineRenderedLineCount)
        secondLineAuxRows(
            content.nextLineAux,
            content.secondaryMode,
            content.nextLineRomanized,
            content.nextLineTranslated
        ).forEach { auxRow ->
            when (auxRow) {
                SecondLineAuxRow.ROMANIZED -> {
                    val rowPaint = fittedSecondaryPaint(
                        content,
                        content.nextLineRomanized,
                        romanizedPaint,
                        preferredLines,
                        availableWidth,
                        translation = false
                    )
                    rows += rowWithLines(
                        RowKind.ROMANIZED,
                        content.nextLineRomanized,
                        rowPaint,
                        ROW_GAP_BEFORE_SECONDARY_DP * density,
                        wrapSecondaryText(
                            content,
                            content.nextLineRomanized,
                            rowPaint,
                            preferredLines,
                            availableWidth,
                            alignmentFor(content, RowKind.NEXT_LINE)
                        )
                    )
                }
                SecondLineAuxRow.TRANSLATED -> {
                    val rowPaint = fittedSecondaryPaint(
                        content,
                        content.nextLineTranslated,
                        translatedPaint,
                        preferredLines,
                        availableWidth,
                        translation = true
                    )
                    rows += rowWithLines(
                        RowKind.TRANSLATED,
                        content.nextLineTranslated,
                        rowPaint,
                        ROW_GAP_BEFORE_SECONDARY_DP * density,
                        wrapSecondaryText(
                            content,
                            content.nextLineTranslated,
                            rowPaint,
                            preferredLines,
                            availableWidth,
                            alignmentFor(content, RowKind.NEXT_LINE)
                        )
                    )
                }
            }
        }
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

    /**
     * 换行动画的行块高度(dp):取与 [drawRows] 同一批参与过渡的行(主行 + 辅助文字 + 下一行,
     * 跳过歌曲信息行;歌曲变更形变时旧层主行由元数据形变接管,按 [skipOriginal] 排除)的包围盒
     * 高。参考实现把位移施加在歌词行视图上(target.getHeight()/4),基准是该视图自身尺寸,
     * 不是画布内容裁剪框;空块回落内容框高(仅兜底,空块不绘制)。
     */
    private fun animatedBlockHeightDp(
        state: LayoutState,
        skipOriginal: Boolean = false,
        skipRowIndices: Set<Int> = emptySet()
    ): Float {
        val boxes = ArrayList<AodCanvasRowBox>(state.rows.size)
        state.rows.forEachIndexed { index, positioned ->
            val top = positioned.baseline + positioned.row.paint.fontMetrics.ascent
            boxes += AodCanvasRowBox(
                topPx = top,
                bottomPx = top + positioned.row.height,
                animated = index !in skipRowIndices && positioned.animate &&
                    (!skipOriginal || positioned.row.kind != RowKind.ORIGINAL)
            )
        }
        return animatedBlockHeightPx(boxes, (oh - padTop - padBottom).toFloat()) / density
    }

    private fun rowWithLines(
        kind: RowKind,
        text: String,
        paint: Paint,
        gap: Float,
        lines: List<TextLine>,
        auxKaraokeWindow: LongRange? = null
    ): Row {
        val metrics = paint.fontMetrics
        val lineHeight = safeSecondaryLineHeight(metrics.ascent, metrics.descent, metrics.bottom)
        return Row(kind, text, paint, lineHeight * lines.size, gap, lines, lineHeight, auxKaraokeWindow)
    }

    /**
     * 效果余量:两端要补的量([needs])与两端行自身的绘制外扩量(自检用,见
     * [logEffectClipCheck])。
     */
    private data class CanvasEffectEdges(
        val needs: CanvasEffectEdgeNeeds,
        val topOverdrawPx: Float,
        val bottomOverdrawPx: Float
    )

    /**
     * 内容块两端要补的效果余量(见 [canvasEffectEdgeNeeds]):按视觉首/末行取外扩量。
     * 是否真会画出辉光/下沉由**共享渲染决策**给出(主行走 [planOriginalLine] 的三选一,
     * 辅助行走「辅助文字逐字效果」开关),布局余量与渲染同源、不另写一套档位判断;
     * 歌曲信息行与下一行行静态绘制,恒 0。歌曲信息行按 [AodCanvasContent.metadataAnchor]
     * 落在上沿或下沿,此时视觉首/末行分别是末条歌词行与歌曲信息行(反向堆叠分支里首行的
     * 行前距不会被排版落位,故不计入抵扣)。
     */
    private fun effectEdges(
        forContent: AodCanvasContent,
        rows: List<Row>,
        timed: Boolean
    ): CanvasEffectEdges {
        if (rows.isEmpty()) return CanvasEffectEdges(CanvasEffectEdgeNeeds(0f, 0f), 0f, 0f)
        val glowOn = forContent.glowMode != "Off"
        val betterLyrics = forContent.animationMode == "BetterLyrics"
        // 主行:路径与生效填充档由共享决策函数给出(与 drawOriginal 同一份)。
        val plan = planOriginalLine(
            animationMode = forContent.animationMode,
            timed = timed,
            lineLevelSync = forContent.lineLevelSync,
            lineSyncFillMode = forContent.lineSyncFillMode,
            lineStartMs = forContent.lineStartMs,
            lineEndMs = forContent.lineEndMs
        )
        val mainGlow = when (plan.path) {
            OriginalLinePath.STATIC -> false
            OriginalLinePath.WORD_KARAOKE -> glowOn
            OriginalLinePath.BLOCK_SWEEP -> glowOn && plan.fillMode != "None"
        }
        val mainSink = plan.path == OriginalLinePath.WORD_KARAOKE && betterLyrics
        // 辅助行逐字效果:辉光与下沉都只在「BetterLyrics」档出现(共享渲染核心同判)。
        val auxGlow = betterLyrics && glowOn
        val metadataAtBottom = forContent.metadataAnchor == "bottom"
        val topRow = if (metadataAtBottom) {
            rows.firstOrNull { it.kind != RowKind.METADATA }
        } else {
            rows.first()
        }
        val topOverdraw = rowEffectOverdrawPx(topRow, mainGlow, mainSink, auxGlow, betterLyrics)
        val bottomOverdraw = rowEffectOverdrawPx(rows.last(), mainGlow, mainSink, auxGlow, betterLyrics)
        return CanvasEffectEdges(
            needs = canvasEffectEdgeNeeds(
                topRowOverdrawPx = topOverdraw,
                topRowGapBeforePx = if (metadataAtBottom) 0f else topRow?.gapBefore ?: 0f,
                bottomRowOverdrawPx = bottomOverdraw
            ),
            topOverdrawPx = topOverdraw,
            bottomOverdrawPx = bottomOverdraw
        )
    }

    /**
     * 单行的绘制外扩量(px):主行(ORIGINAL/DUET_ORIGINAL)走词级卡拉OK/扫光渲染,按
     * [mainGlow]/[mainSink] 外扩;携带行窗的辅助行(「辅助文字逐字效果」)按
     * [auxGlow]/[auxSink] 外扩;其余行(歌曲信息/下一行)静态绘制,恒 0。
     */
    private fun rowEffectOverdrawPx(
        row: Row?,
        mainGlow: Boolean,
        mainSink: Boolean,
        auxGlow: Boolean,
        auxSink: Boolean
    ): Float {
        if (row == null) return 0f
        val (glow, sink) = when (row.kind) {
            RowKind.ORIGINAL, RowKind.DUET_ORIGINAL -> mainGlow to mainSink
            RowKind.ROMANIZED, RowKind.TRANSLATED, RowKind.DUET_ROMANIZED, RowKind.DUET_TRANSLATED ->
                if (row.auxKaraokeWindow == null) false to false else auxGlow to auxSink
            else -> false to false
        }
        if (!glow && !sink) return 0f
        return canvasEffectAllowancePx(
            textSizePx = row.paint.textSize,
            lineHeightPx = row.lineHeight,
            glowEnabled = glow,
            floatSinkActive = sink
        )
    }

    private fun positionRows(rows: List<Row>, originalLayout: OriginalLayout): List<PositionedRow> {
        val positioned = ArrayList<PositionedRow>(rows.size)
        val metadata = rows.firstOrNull { it.kind == RowKind.METADATA }
        // 效果余量:辉光/下沉会越出行盒,块沿贴住内容裁剪框时被切平(「歌词刚好叠到画布边缘
        // 被裁切」)。有歌曲信息行时它按锚点贴住一侧、由卡片长高在另一侧让出余量,放置不变;
        // 无歌曲信息行时整块顶锚,顶部余量要在放置起点里让出。底部余量两种分支都由
        // measureContentStack 计进卡片高度(块尾与裁剪沿之间没有现成留白)。
        val effectEdges = effectEdges(content, rows, originalLayout.timed)
        val effectNeeds = effectEdges.needs
        if (metadata != null) {
            val anchor = when (content.metadataAnchor) {
                "bottom" -> "bottom"
                else -> "top"
            }
            val metadataMetrics = metadata.paint.fontMetrics
            val metadataBounds = metadataLayoutBounds(
                anchor,
                oh.toFloat(),
                padTop.toFloat(),
                padBottom.toFloat(),
                metadataMetrics.ascent,
                metadataMetrics.descent,
                METADATA_LYRIC_GAP_DP * density,
                // 带高 = max(文本块高, 图片槽边长):文本块在带内居中,歌词从带沿让出(见纯函数 KDoc)。
                blockHeight = metadataBlockHeightPx(
                    metadata.lines.size,
                    metadata.lineHeight,
                    metadataMetrics.ascent,
                    metadataMetrics.descent
                ),
                bandHeight = if (artworkSlotActive(content)) {
                    artworkSidePx(
                        metadata.paint.textSize,
                        density,
                        content.artworkAdaptiveScale,
                        content.artworkSizeDp
                    )
                } else {
                    0f
                }
            )
            val metadataBaseline = metadataBounds.metadataBaseline
            positioned += PositionedRow(metadata, metadataBaseline, false)
            val lyricRows = rows.filterNot { it.kind == RowKind.METADATA }
            // 多行文本块与图片槽的高度差已由带几何吸收(lyricStart/lyricEnd 从带沿让出 gap)。
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
            // 无歌曲信息行:整块顶锚,两端余量都从放置区间让出(底部余量与卡片长高同源,
            // 卡片变高后块尾自然离裁剪沿 effectNeeds.bottomPx)。
            val topPadding = padTop.toFloat() + effectNeeds.topPx
            val bottomPadding = oh - padBottom - effectNeeds.bottomPx
            val available = (bottomPadding - topPadding).coerceAtLeast(0f)
            // issue #63:横屏时行堆叠轴经 90° 旋转映射为画布的视觉横轴,沿用竖屏的 TOP 会让
            // 内容整块贴向画布一侧(现场实测偏约 185px)。横屏的最终摆放统一交给
            // [anchorLandscapeContentBlock](按块包围盒 + 安全区反解偏移),这里只给自然起点;
            // 竖屏维持原 TOP/CENTER 语义。
            var top = if (effectiveVerticalAlignment() == AodCanvasVerticalAlignment.TOP) {
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
        // issue #41/#63:横屏(全屏与非全屏)整块(元数据+歌词)统一按 landscapeAnchor 锚定在
        // 安全区内——有无元数据两条分支共用同一基准,不再一条居中、另一条贴边。
        if (landscapeActive()) anchorLandscapeContentBlock(positioned)
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
            // 拼接必须给 if 表达式加括号:否则 " md=1" 分支会吞掉其后的 block/anchor/fs
            // (Kotlin 中 else 分支才继续参与 + 链),有元数据时日志恰好缺掉最该看的三个字段。
            val key = "rot=$rotationStep ow=$ow oh=$oh padT=$padTop padB=$padBottom" +
                (if (metadata != null) " md=1" else " md=0") +
                " block=${minTop.roundToInt()}..${maxBottom.roundToInt()} " +
                "anchor=$landscapeAnchor fs=${fullscreenLandscapeActive()}"
            if (key != lastRowLayoutLogKey) {
                lastRowLayoutLogKey = key
                HookLogger.i("AodLyricCanvasView", "Landscape row layout: $key")
            }
        }
        val original = positioned.firstOrNull { it.row.kind == RowKind.ORIGINAL }
        val firstLine = originalLayout.lines.firstOrNull()
        if (original == null || firstLine == null || firstLine.rubyHeight <= 0f) {
            logEffectClipCheck(positioned, effectEdges)
            return positioned
        }
        val firstBaseBaseline = original.baseline + firstLine.rubyHeight
        val top = rubyClipTop(firstBaseBaseline, originalPaint.fontMetrics.ascent, firstLine.rubyHeight)
        val shift = rubyTopShift(top, paddingTop.toFloat())
        val finalRows = if (shift == 0f) positioned else positioned.map {
            if (it.row.kind == RowKind.METADATA) it else it.copy(baseline = it.baseline + shift)
        }
        logEffectClipCheck(finalRows, effectEdges)
        return finalRows
    }

    /**
     * 「安全栅栏」自检(见 [effectClipCheckPx]):把最终摆放(含横屏锚定与注音位移)的内容块
     * 与其绘制外扩对一遍内容裁剪框。已知形状恒为 clean —— 余量由 [effectEdges] 算准;
     * 只有**未知形状**(新增效果/新增行种类没同步进余量)或内容本身放不下才越界,以 W 级留痕,
     * 而不是静默把辉光/下沉切平在裁剪沿上。按几何签名去重,避免每帧刷屏。
     */
    private fun logEffectClipCheck(rows: List<PositionedRow>, edges: CanvasEffectEdges) {
        if (rows.isEmpty()) return
        var minTop = Float.POSITIVE_INFINITY
        var maxBottom = Float.NEGATIVE_INFINITY
        rows.forEach { p ->
            val top = p.baseline + p.row.paint.fontMetrics.ascent
            val bottom = p.baseline + p.row.height
            if (top < minTop) minTop = top
            if (bottom > maxBottom) maxBottom = bottom
        }
        if (!minTop.isFinite() || !maxBottom.isFinite()) return
        val clipBottom = oh - padBottom
        val check = effectClipCheckPx(
            blockTopPx = minTop,
            blockBottomPx = maxBottom,
            topOverdrawPx = edges.topOverdrawPx,
            bottomOverdrawPx = edges.bottomOverdrawPx,
            clipTopPx = padTop.toFloat(),
            clipBottomPx = clipBottom.toFloat()
        )
        if (check.clean) return
        val reason = if (check.rowOverflowPx > 0f) {
            "content taller than the content clip box"
        } else {
            "effect allowance insufficient; halo/float would be cut"
        }
        val key = "block=${minTop.roundToInt()}..${maxBottom.roundToInt()} " +
            "clip=$padTop..$clipBottom row=${check.rowOverflowPx} " +
            "eff=${check.effectOverflowPx} ($reason)"
        if (key != lastEffectClipCheckKey) {
            lastEffectClipCheckKey = key
            HookLogger.w("AodLyricCanvasView", "Effect clip check: $key")
        }
    }

    /**
     * issue #41/#63:横屏(全屏与非全屏)时把整块(元数据+歌词)按 [landscapeAnchor] 锚定在
     * 安全区 [landscapeSafeRegion] 内——两条分支(有无元数据)统一走这里,避免「有元数据时
     * 按一种基准摆放、无元数据时按另一种」的居中口径分叉:
     *  - 块包围盒取各行视觉上下沿(baseline+ascent … baseline+行高),含元数据带与歌曲图片槽;
     *  - 全屏化时安全区上下再各让出 safeMargin(与自适应缩放同一圈留白),故区间中心恒为
     *    逻辑帧中心 oh/2,anchor=0.5 即视觉正中间;
     *  - 竖屏不受影响(仅横屏激活时生效)。
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
        // 全屏化沿短边再让出安全边界,使内容块在安全区内锚定/居中(与自适应缩放同一圈留白)。
        val safeInset = if (fullscreenLandscapeActive()) {
            fullscreenSafeInset(minOf(ow, oh).toFloat(), landscapeFullscreenSafeMarginPercent)
        } else {
            0f
        }
        // 区间起点必须与高度同源:只减高度、起点仍取 padTop 会让整块偏向一侧 safeInset 像素
        // (全屏化放大后偏移同步放大),现场表现为「横屏全屏化后歌词不在正中间」。
        val region = landscapeSafeRegion(oh, padTop, padBottom, safeInset)
        val available = region.height
        val blockHeight = maxBottom - minTop
        val offset = landscapeBlockAnchorOffset(
            blockTop = minTop,
            blockHeight = blockHeight,
            availableHeight = available,
            regionTop = region.top,
            anchor = landscapeAnchor
        )
        if (offset != 0f) {
            val shifted = positioned.map { it.copy(baseline = it.baseline + offset) }
            positioned.clear()
            positioned.addAll(shifted)
        }
        // 自检留痕:直接给出整块中心与逻辑帧中心(相差 0 即「正中间」),并标出安全区范围;
        // 现场只要看这一行即可判定居中与否,无需再靠截图反推。
        val key = "$minTop/$blockHeight/${region.top}/$available/$landscapeAnchor/$offset"
        if (lastCenteredLogKey != key) {
            lastCenteredLogKey = key
            HookLogger.i(
                "AodLyricCanvasView",
                "Landscape content block anchored minTop=$minTop blockH=$blockHeight " +
                    "region=${region.top}..${region.top + available} avail=$available " +
                    "anchor=$landscapeAnchor offset=$offset " +
                    "center=${minTop + offset + blockHeight / 2f} frameCenter=${oh / 2f} " +
                    "fs=${fullscreenLandscapeActive()}"
            )
        }
    }

    private fun drawOriginal(canvas: Canvas, baseline: Float) {
        val originalLayout = layout.original
        val lines = originalLayout.lines
        // 主行渲染路径由共享决策函数给出(与 App 内预览读同一份,杜绝两侧分支树漂移):
        // 静态全亮 / 词级卡拉OK / 共享扫光块三选一。
        val plan = planOriginalLine(
            animationMode = content.animationMode,
            timed = originalLayout.timed,
            lineLevelSync = content.lineLevelSync,
            lineSyncFillMode = content.lineSyncFillMode,
            lineStartMs = content.lineStartMs,
            lineEndMs = content.lineEndMs
        )
        when (plan.path) {
            // 静态全亮，无扫光/发光（Minimal 档或行进度效果 None）。
            OriginalLinePath.STATIC -> {
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
            }
            // 「BetterLyrics」档(参考 jayfunc/BetterLyrics):逐字时间源走真词级卡拉OK;
            // 行级源(LRC,无逐字时间)由 [drawWordKaraoke] 按字符合成时间窗走同一渲染——
            // 未唱下沉/已唱上浮/正在唱的字放大辉光对两类源同样适用,推进前缘与行级扫光几何一致。
            // 非 BetterLyrics 的基础卡拉OK路径(逐字源 + 关闭发光 + 大元数据引导态)同走此分支。
            OriginalLinePath.WORD_KARAOKE -> drawWordKaraoke(
                canvas,
                baseline,
                originalLayout,
                betterLyrics = content.animationMode == "BetterLyrics"
            )
            // 统一共享管线：dim 底 + 光晕(发光开启时) + 扫光带,fillMode 由决策函数给出
            // (逐行依次 / 整块同时 / 纵向推进 / None 静态)。整块进度：行级时间优先，
            // 纯逐字源回退全局词范围（unifiedBlockProgress）。
            OriginalLinePath.BLOCK_SWEEP -> drawOriginalGlowBlock(
                canvas,
                baseline,
                originalLayout,
                unifiedBlockProgress(
                    projectedPosition(),
                    content.lineStartMs,
                    content.lineEndMs,
                    content.words
                ),
                plan.fillMode
            )
        }
    }

    /**
     * 对唱并发行绘制(移植上游 duet/secondLine 呈现,按 CN+ 画布适配):
     * 复用主行共享发光管线(LyricGlowRenderer),进度取并发行自己的行窗口/词表;
     * 加入时整块 180ms alpha 淡入,淡入期间按静音态绘制(无发光/扫光,上游 exit-side
     * muted 同语义);并发行离场随主行换行过渡的整块退场一起消失(数据面退出缓冲已保证
     * 它不会在对唱中途凭空塌掉),v1 不做独立的并发行退场动画。
     */
    private fun drawDuetOriginal(canvas: Canvas, baseline: Float) {
        val duetLayout = layout.duet ?: return
        val duet = content.duetLine ?: return
        // 并发行/和声行随**活动行**一起显示(参照 HyperLyric:和声折进父行、随父行在唱就在屏上),
        // 不做自己的时间窗门控——插件的行窗与生产者上报的位置来自两条时间轴(实测同一句
        // 5.2s vs 20.5s),用其中一条去卡另一条会把和声整段挡掉。并发行行的槽位只由
        // buildRows 的装配给出,主行换行过渡不移动它(见 [drawFrozenDuetRows]),这里只负责画。
        // 诊断探针:并发行真的画出来时记一条(带窗口与文本),供真机判定「和声/对唱行有没有上屏」,
        // 不依赖掐点抓屏。与 karaoke-probe 同口径(2s 节流、仅诊断日志开启时落盘)。
        HookLogger.iThrottled("duet-draw", 2_000L, "AodLyricCanvasView") {
            "Duet draw: pos=${projectedPosition()} window=${duet.lineStartMs}..${duet.lineEndMs} " +
                "words=${duet.words.size} text=${duet.text.take(24)}"
        }
        // 加入淡入期间按静音态绘制(无发光/扫光,上游 exit-side muted 同语义);透明度由
        // 行级过渡层施加(见 [withDuetRowTransition]),这里只选绘制形态。
        if (duetJoinAlpha() < 1f) {
            drawDuetStatic(canvas, duetLayout, baseline)
        } else {
            drawOriginalGlowBlock(
                canvas,
                baseline,
                duetLayout,
                unifiedBlockProgress(
                    projectedPosition(),
                    duet.lineStartMs,
                    duet.lineEndMs,
                    duet.words
                ),
                effectiveLineSyncFillMode()
            )
        }
    }

    /** 并发行静音态绘制:无发光/扫光/Shader,全亮 sungText(与主行 Minimal 档同式)。 */
    private fun drawDuetStatic(canvas: Canvas, duetLayout: OriginalLayout, baseline: Float) {
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < duetLayout.lines.size) {
            val line = duetLayout.lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                duetLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                duetLayout.lineGap
            )
            originalPaint.shader = null
            originalPaint.setShadowLayer(0f, 0f, 0f, 0)
            setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
            drawOriginalText(canvas, line, lineBaseline)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    /**
     * 并发行行自己的过渡:内容键变化后的加入淡入(见 [duetJoinAlpha]);恒全亮时不建层。
     * 主行换行过渡不作用于并发行行(见 [drawFrozenDuetRows]),并发行的过渡只有这一处
     * ——「并发行切换时播放它自己的过渡」。
     */
    private inline fun withDuetRowTransition(canvas: Canvas, row: Row, draw: () -> Unit) {
        if (!row.duet) {
            draw()
            return
        }
        val alpha = duetJoinAlpha()
        if (alpha >= 1f) {
            draw()
            return
        }
        if (alpha <= 0f) return
        val layer = canvas.saveLayerAlpha(0f, 0f, ow.toFloat(), oh.toFloat(), (255f * alpha).toInt())
        draw()
        canvas.restoreToCount(layer)
    }

    /**
     * 主行过渡期间的并发行静止层(见 [drawOrientedContent]):并发行有自己独立的时间轴,
     * 不参与主行的退场/位移/进场——过渡期间按过渡起点快照的槽位画在原地,主行层移走/
     * 淡出/缩放时它不动、不变暗(共享缩放取起点快照,与前一帧口径一致)。
     * 内容取当前布局(并发行自己的折行与词级进度照常推进;它自己到点换行时也只播自己的
     * 加入淡入);槽位取起点快照而不是新布局——新布局是主行换行后的形态,槽位可能已随
     * 主行块高变化,用新槽位等于在过渡开始时把并发行弹到别处。
     */
    private fun drawFrozenDuetRows(canvas: Canvas, snapshot: CanvasSnapshot) {
        val duetRows = layout.rows.filter { it.row.duet }
        if (duetRows.isEmpty()) return
        val frozen = frozenDuetBaselines(
            snapshotBaselines = snapshot.layout.rows.filter { it.row.duet }.map { it.baseline },
            currentBaselines = duetRows.map { it.baseline }
        )
        val scale = duetSharedScale(snapshot.layout)
        val layer = canvas.save()
        if (scale != 1f) {
            canvas.scale(
                scale,
                scale,
                (padLeft + (ow - padRight)) / 2f,
                (padTop + (oh - padBottom)) / 2f
            )
        }
        val frameClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(frameClip[0], frameClip[1], frameClip[2], frameClip[3])
        duetRows.forEachIndexed { index, positioned ->
            val baseline = frozen[index]
            if (positioned.row.kind == RowKind.DUET_ORIGINAL) {
                withDuetRowTransition(canvas, positioned.row) {
                    drawDuetOriginal(canvas, baseline)
                }
            } else {
                withDuetRowTransition(canvas, positioned.row) {
                    drawDuetAuxRowStatic(canvas, positioned.row, baseline)
                }
            }
        }
        canvas.restoreToCount(layer)
    }

    /** 并发行辅助行(含和声走的辅助行车道)的静态绘制:逐字效果开启时按自己的行窗点亮,否则全亮静态。 */
    private fun drawDuetAuxRowStatic(canvas: Canvas, row: Row, baseline: Float) {
        val auxWindow = row.auxKaraokeWindow
        if (auxWindow != null) {
            drawAuxKaraokeRow(canvas, row, baseline, auxWindow)
            return
        }
        setTextAlpha(
            row.paint,
            staticSecondaryTextFactor(content.secondaryTextBright),
            1f,
            resolvedPalette.secondaryText
        )
        row.paint.shader = null
        row.paint.clearShadowLayer()
        row.lines.forEachIndexed { index, line ->
            canvas.drawText(line.text, line.startX, baseline + index * row.lineHeight, row.paint)
        }
    }

    /**
     * 对唱共享缩放系数:并发行在场时,行堆叠总高(含元数据带)超出内容区则整块缩小。
     * v1 简化:缩放作用于整块行堆叠(含元数据),上游只缩歌词段;整块等比不会出现
     * 歌词与元数据字号不一致,偏差已记 SPEC。
     */
    private fun duetSharedScale(state: LayoutState): Float {
        if (state.duet == null) return 1f
        val total = state.rows.sumOf { (it.row.height + it.row.gapBefore).toDouble() }.toFloat()
        val available = (oh - padTop - padBottom).coerceAtLeast(1).toFloat()
        return duetSharedFitScale(total, available)
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
        progress: Float,
        fillMode: String = LyricGlowRenderer.FILL_LEFT_TO_RIGHT_WHOLE_BLOCK
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
            glowEnabled = content.glowMode != "Off",
            fillMode = fillMode
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

    /**
     * 逐字卡拉OK路径（[betterLyrics] 为「BetterLyrics」档）：词级进度/放大/扫光与
     * 未唱下沉、已唱上浮、长音节辉光统一委托共享渲染核心 [LyricWordKaraokeRenderer]
     * （预览同源，杜绝效果漂移）。逐字时间源用真词时间窗;行级源（[betterLyrics] 且
     * 行无词）按字符合成时间窗（[syntheticCharTimeWindow],与行级扫光前缘同式）,
     * 合成块按块时长判定长音节（≥700ms）。「BetterLyrics」档扫光按口径 B 逐词块
     * 判定（见 karaokeSweepEnabled）：长音节「开始唱即整块亮起」不扫光（只有长块
     * 放大/辉光），其余音节恢复历史词内扫光带。
     */
    private fun drawWordKaraoke(
        canvas: Canvas,
        baseline: Float,
        originalLayout: OriginalLayout,
        betterLyrics: Boolean = false
    ) {
        val lines = originalLayout.lines
        val position = projectedPosition()
        val sinkPx = karaokeFloatSinkPx(originalLayout.lineHeight)
        val totalWidth = lines.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(1f)
        val blockStartMs = content.lineStartMs
        val blockEndMs = content.lineEndMs
        val runs = ArrayList<KaraokeWordRun>(8)
        var precedingWidth = 0f
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
            runs.clear()
            var x = 0f
            var wordIndex = 0
            while (wordIndex < line.words.size) {
                val placed = line.words[wordIndex]
                val word = placed.word
                val durationMs = word.endMs - word.startMs
                // 布局分组合成的占位词(无词表行 + layoutGroups 时由 layoutTextByGroups 生成,
                // 时间窗恒 0..0)不构成词级时间源:跳过它,让整行走下方行级合成(逐字)——否则
                // timedWordProgress(pos, 0, 0) 对零窗恒返回 1(全亮),整行呈静态全亮、与 Minimal 档同观感。
                if (isTimedKaraokeWord(word.startMs, word.endMs)) {
                    runs += KaraokeWordRun(
                        text = word.text,
                        x = line.startX + x,
                        width = placed.width,
                        playedFraction = timedWordProgress(position, word.startMs, word.endMs),
                        durationMs = durationMs,
                        longSyllable = isLongKaraokeSyllable(durationMs)
                    )
                }
                x += placed.width + placed.gapAfter
                wordIndex++
            }
            if (runs.isEmpty() && betterLyrics) {
                // 行级源合成:按词块划分(中文逐字、西文按词),块内字符各自扫光进度、
                // 共享块级高亮进度——整块在演唱期间同步放大/辉光(可见),未唱整块下沉、
                // 唱到逐字上浮;推进前缘与行级扫光几何同式。
                var prefix = 0f
                var index = 0
                for (block in syntheticKaraokeBlocks(line.text)) {
                    while (index < block.first) {
                        val unitEnd = karaokeUnitEnd(line.text, index, block.first)
                        prefix += originalPaint.measureText(line.text, index, unitEnd)
                        index = unitEnd
                    }
                    val blockWidth = originalPaint.measureText(line.text, block.first, block.last + 1)
                    val blockWindow = syntheticCharTimeWindow(
                        blockStartMs,
                        blockEndMs,
                        totalWidth,
                        precedingWidth + prefix,
                        blockWidth
                    )
                    val blockHighlight = timedWordProgress(position, blockWindow.first, blockWindow.last)
                    // 长音节按合成块时长判定:短词块(不足 700ms)不放大、不辉光;被拖长的长词块
                    // 整块同步放大+辉光(块内共享高亮进度)。「BetterLyrics」档扫光按口径 B 逐
                    // 词块判定(见 karaokeSweepEnabled):长块整块亮起不扫光、短块照常词内扫光。
                    val blockLong = isLongKaraokeSyllable(blockWindow.last - blockWindow.first)
                    var charIndex = block.first
                    while (charIndex <= block.last) {
                        val charEnd = karaokeUnitEnd(line.text, charIndex, block.last + 1)
                        val charWidth = originalPaint.measureText(line.text, charIndex, charEnd)
                        val charWindow = syntheticCharTimeWindow(
                            blockStartMs,
                            blockEndMs,
                            totalWidth,
                            precedingWidth + prefix,
                            charWidth
                        )
                        runs += KaraokeWordRun(
                            text = line.text.substring(charIndex, charEnd),
                            x = line.startX + prefix,
                            width = charWidth,
                            playedFraction = timedWordProgress(position, charWindow.first, charWindow.last),
                            durationMs = charWindow.last - charWindow.first,
                            longSyllable = blockLong,
                            highlightFraction = if (blockLong) blockHighlight else -1f
                        )
                        prefix += charWidth
                        charIndex = charEnd
                    }
                    index = block.last + 1
                }
            }
            if (lineIndex == 0) {
                // 诊断探针(「BetterLyrics 效果和最简一样」排查):打印词级卡拉OK的全部输入现场值。
                HookLogger.iThrottled(
                    "karaoke-probe", 2_000L, "AodLyricCanvasView"
                ) {
                    val firstRun = runs.firstOrNull()
                    val firstWord = line.words.firstOrNull()?.word
                    "Karaoke probe: pos=$position lStart=${content.lineStartMs} " +
                        "lEnd=${content.lineEndMs} lineSync=${content.lineLevelSync} " +
                        "words=${content.words.size} runs=${runs.size} " +
                        "run0=[${firstRun?.text} played=${firstRun?.playedFraction}] " +
                        "word0=[${firstWord?.startMs}..${firstWord?.endMs}]"
                }
            }
            LyricWordKaraokeRenderer.draw(
                canvas = canvas,
                paint = originalPaint,
                runs = runs,
                baseline = lineBaseline,
                sungColor = resolvedPalette.sungText,
                unsungColor = resolvedPalette.unsungText,
                glowColor = resolvedPalette.glow,
                glowEnabled = content.glowMode != "Off",
                betterLyrics = betterLyrics,
                sinkPx = sinkPx
            )
            if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
            precedingWidth += line.width
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
        // 圆形封面旋转与行级时间轴/过渡同待遇:驱动帧循环推进旋转角,隐藏即停。
        timedOrTransitionActive = timingEffectActive() || exitSnapshot != null ||
            artworkSpinActive(),
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

    private fun buildOriginalLayout(
        content: AodCanvasContent,
        availableWidth: Float
    ): OriginalLayout {
        // 换行/词行布局统一委托 LyricLayoutEngine(与预览同源,断行一致)。
        val layout = layoutOriginalLines(
            original = content.original,
            words = content.words,
            ruby = content.ruby,
            layoutGroups = content.layoutGroups,
            paint = originalPaint,
            availableWidth = availableWidth,
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
            assignRuby(content, lines),
            metrics.descent - metrics.ascent + LYRIC_LINE_EXTRA_HEIGHT_DP * density,
            LYRIC_LINE_GAP_DP * density,
            // 真实词窗判据(见 hasTimedWordWindows):行级标记不再压过它,渲染路径决策
            // (planOriginalLine / shouldUseSharedLineLevelSweep)与效果余量共用这一位。
            hasTimedWordWindows(content.words)
        )
    }

    /**
     * 并发行原始行布局:与 [buildOriginalLayout] 同一套 LyricLayoutEngine 断行(与预览同源),
     * 换行上限取 min(2, 主行档位解析值)——并发行通常一行,双行封顶防内容块失控;
     * v1 不带 ruby(rubyHeight 恒 0),startX 按并发行自己的分侧对齐解析。
     */
    private fun buildDuetOriginalLayout(
        duet: AodCanvasDuetLine,
        availableWidth: Float
    ): OriginalLayout {
        val layout = layoutOriginalLines(
            original = duet.text,
            words = duet.words,
            ruby = emptyList(),
            layoutGroups = emptyList(),
            paint = originalPaint,
            availableWidth = availableWidth,
            lineLimit = resolvedLyricLayoutLineLimit(
                content.lyricLineLimit,
                duet.text.length,
                duet.words.size
            ).coerceAtMost(2),
            wordGapPx = LYRIC_WORD_GAP_DP * density,
            wrap = content.overflowMode == "Wrap",
            adaptiveSectioning = content.adaptiveSectioning
        )
        val lineAlignment = viewAlignment(
            resolveAlignmentMode(content.alignmentMode, duet.alignedRight)
        )
        val lines = layout.lines.map {
            val visual = visualExtents(it.text, originalPaint, it.width)
            OriginalLine(
                it.text,
                it.words,
                it.width,
                alignedStart(it.width, lineAlignment, visual.first, visual.second),
                it.charStart,
                it.charEnd
            )
        }
        val metrics = originalPaint.fontMetrics
        return OriginalLayout(
            lines,
            metrics.descent - metrics.ascent + LYRIC_LINE_EXTRA_HEIGHT_DP * density,
            LYRIC_LINE_GAP_DP * density,
            hasTimedWordWindows(duet.words)
        )
    }

    private fun lyricLayoutLineLimit(wordCount: Int = content.words.size): Int =
        resolvedLyricLayoutLineLimit(
            content.lyricLineLimit,
            content.original.length,
            wordCount
        )

    /**
     * 翻译行的逐字时间线(插件/歌词源提供的词级译文,见 `PluginLyricField.TRANSLATION_WORDS`)。
     *
     * 与音标行 [transliterationLines] 同构:每段自带真实词窗(段文本直接相连——西文词间的
     * 空格由来源写在片段内,这里不另插分隔符),按宽度均衡折行,折行后各行同样携带
     * [SecondaryTimedSegment],由 [drawAuxKaraokeRow] 按真实词窗点亮。行文本与
     * [AodCanvasContent.translated] 逐字符一致(片段重建不出整行译文时整体回落,见
     * [translatedTimedSegments])。
     *
     * 无词级数据(插件没给 / 未装插件 / 该行源无逐字时间)返回 null,调用方回落到
     * 行窗口 + 行内几何合成——即「辅助文字逐字效果」的历史行为。
     */
    private fun translatedTimedLines(
        content: AodCanvasContent,
        availableWidth: Float,
        paint: Paint
    ): List<TextLine>? {
        // 逐字段的取舍(片段重建整行译文、空片段剔除、全零窗拒绝)在共享纯函数里,与单测同源。
        val segments = translatedTimedSegments(
            content.translationWords,
            content.translated,
            paint::measureText
        ) ?: return null
        return secondaryTimedVisualRanges(
            segments,
            availableWidth,
            MAX_SECONDARY_LAYOUT_LINES,
            wrap = content.adaptiveSectioning && content.overflowMode == "Wrap"
        ).map { range ->
            val lineSegments = range.map(segments::get)
            val text = lineSegments.joinToString("") { it.text }
            val lineWidth = lineSegments.sumOf { it.width.toDouble() }.toFloat()
            textLine(text, lineWidth, paint).copy(timedSegments = lineSegments)
        }
    }

    private fun transliterationLines(
        content: AodCanvasContent,
        originalLayout: OriginalLayout,
        availableWidth: Float,
        paint: Paint
    ): List<TextLine>? {
        if (originalLayout.lines.isEmpty() || originalLayout.lines.any { it.words.isEmpty() }) return null
        val sourceWords = originalLayout.lines.flatMap { it.words }.map { it.word }
        if (sourceWords.isEmpty()) return null
        val spaceWidth = paint.measureText(" ")
        val timedIndexes = timedRomanizedWordIndexes(sourceWords)
        val segments = timedIndexes.mapIndexed { renderedIndex, sourceIndex ->
            val word = sourceWords[sourceIndex]
            val text = word.romanized.trim()
            val nextSourceIndex = timedIndexes.getOrNull(renderedIndex + 1)
            SecondaryTimedSegment(
                text = text,
                width = paint.measureText(text),
                gapAfter = if (nextSourceIndex != null && word.boundaryAfter) spaceWidth else 0f,
                startMs = word.startMs,
                endMs = word.endMs
            )
        }
        if (segments.isEmpty()) return null
        return secondaryTimedVisualRanges(
            segments,
            availableWidth,
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
            textLine(text, lineWidth, paint).copy(timedSegments = lineSegments)
        }
    }

    /**
     * 辅助行自适应字号拟合(「自适应大小」开启时,见 SurfaceProfile.secondaryAutoSize):
     * 装得下恒返回共享 [paint](既有呈现逐像素不变);装不下缩到可读性下限,返回独立 Paint
     * 副本 —— 共享 paint 为同车道多行共用,就地改字号会污染其他行。关闭开关直接返回共享 paint。
     * 拟合判据走共享引擎 fittedSecondaryLines(与预览同一份)。
     */
    private fun fittedSecondaryPaint(
        content: AodCanvasContent,
        text: String,
        paint: Paint,
        preferredLines: Int,
        availableWidth: Float,
        translation: Boolean
    ): Paint {
        if (!content.secondaryAutoSize) return paint
        // 下限与字号公式同源;baseSp 与 applyContentStyle 同式(同一 content,两处逐值一致)。
        val baseSp = baseTextSizeSp(content.original) *
            textSizeModeMultiplier(content.textSizeMode, content.textSizeCustom)
        val configuredSp = paint.textSize / scaledDensity
        val fitted = fittedSecondaryLines(
            text = text,
            basePaint = paint,
            configuredSp = configuredSp,
            floorSp = secondarySizeFloorSp(baseSp, translation),
            availableWidth = availableWidth,
            preferredLines = preferredLines,
            wrap = content.overflowMode == "Wrap",
            adaptiveSectioning = content.adaptiveSectioning,
            scaledDensity = scaledDensity
        )
        return if (fitted.fittedSp < configuredSp) fitted.paint else paint
    }

    private fun wrapSecondaryText(
        content: AodCanvasContent,
        text: String,
        paint: Paint,
        preferredLines: Int,
        availableWidth: Float,
        lineAlignment: Alignment = alignment
    ): List<TextLine> =
        // 换行统一委托 LyricLayoutEngine(与预览同源);定位 X 按行级对齐(默认主对齐)解析。
        layoutSecondaryLines(
            text = text,
            paint = paint,
            availableWidth = availableWidth,
            preferredLines = preferredLines,
            wrap = content.overflowMode == "Wrap",
            adaptiveSectioning = content.adaptiveSectioning
        ).map { textLine(it.text, it.width, paint, lineAlignment) }

    /**
     * 歌曲信息（歌名/歌手）专用换行：委托 LyricLayoutEngine.layoutMetadataLines
     * (与预览同源,最多 MAX_SECONDARY_LAYOUT_LINES 行);定位 X 按 metadata 对齐解析。
     *
     * 歌曲图片槽:图片显示时文本可用宽先扣掉前置宽度(槽+间距,公式同源
     * AodCanvasTextMetrics),[alignment] 作用于「图片+文本块」整组——图片恒在
     * 文本块左侧,组按行对齐落位;文本块内部各行按块宽继续对齐。
     */
    private fun wrapMetadataText(
        content: AodCanvasContent,
        text: String,
        paint: Paint,
        availableWidth: Float
    ): List<TextLine> {
        val lineAlignment = alignmentFor(content, RowKind.METADATA)
        val leading = if (artworkSlotActive(content)) {
            artworkLeadingPx(
                paint.textSize,
                density,
                content.artworkAdaptiveScale,
                content.artworkSizeDp
            )
        } else {
            0f
        }
        val lines = layoutMetadataLines(
            text = text,
            paint = paint,
            availableWidth = (availableWidth - leading).coerceAtLeast(1f)
        )
        if (leading <= 0f) {
            return lines.map { textLine(it.text, it.width, paint, lineAlignment) }
        }
        val blockWidth = lines.maxOfOrNull { it.width } ?: 0f
        val groupWidth = leading + blockWidth
        val groupLeft = alignedStart(groupWidth, lineAlignment, 0f, groupWidth)
        return lines.map { line ->
            val inBlock = when (lineAlignment) {
                Alignment.CENTER -> (blockWidth - line.width) / 2f
                Alignment.END -> blockWidth - line.width
                else -> 0f
            }
            TextLine(line.text, line.width, groupLeft + leading + inBlock)
        }
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

    private fun assignRuby(
        content: AodCanvasContent,
        lines: List<OriginalLine>
    ): List<OriginalLine> = lines.map { line ->
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
        // 辅助文字逐字效果:携带行窗口的辅助行整行委托逐字渲染(多行共享同一推进前缀)。
        val auxWindow = row.auxKaraokeWindow
        if (auxWindow != null) {
            drawAuxKaraokeRow(canvas, row, baseline, auxWindow)
            return
        }
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

    /**
     * 行级对齐(实机/预览同源 resolveRowAlignmentMode):歌曲信息与第二行歌词按各自独立
     * 对齐设置解析("auto" 跟随主对齐),其余行沿用主对齐。
     */
    private fun alignmentFor(content: AodCanvasContent, kind: RowKind): Alignment = when (kind) {
        RowKind.METADATA -> viewAlignment(
            resolveRowAlignmentMode(
                content.metadataAlignment,
                content.alignmentMode,
                content.alignedRight
            )
        )
        RowKind.NEXT_LINE -> viewAlignment(
            resolveRowAlignmentMode(
                content.nextLineAlignment,
                content.alignmentMode,
                content.alignedRight
            )
        )
        RowKind.DUET_ORIGINAL, RowKind.DUET_ROMANIZED, RowKind.DUET_TRANSLATED ->
            // 并发行按自己的分侧(alignedRight 经 mapper 的「对唱分侧」门控)解析,
            // 不随主行 alignedRight 翻转。
            viewAlignment(
                resolveAlignmentMode(
                    content.alignmentMode,
                    content.duetLine?.alignedRight ?: false
                )
            )
        else -> alignment
    }

    private fun viewAlignment(mode: String): Alignment = when (mode) {
        "center" -> Alignment.CENTER
        "end" -> Alignment.END
        else -> Alignment.START
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

    /**
     * 过渡时钟采样,两条路径:
     * - 正常过渡:位置式——原始位置先过 [advanceTransitionPosition] 限速成平滑位置,再按
     *   「平滑位置 − 起点」换算三段进度(位置源 stall/resume 的毫秒级回漂不倒带动画,
     *   见 [lineTransitionClockAtPosition]);批投递的位置跳变按实时速率补齐,不在一帧内推完;
     * - 旧账压缩补播:挂钟式——按 [lineTransitionClockAtElapsed] 在压缩时间线上换算,
     *   首帧落帧才起算(低节拍/doze 下首帧可能晚到,保证整段可见)。
     * 两条路径都用原始位置高水位判 seek/拖动跳变:命中即 [LineTransitionClock.interrupted],
     * 由绘制入口立即结束过渡、静态落位。
     */
    private fun transitionClock(timeline: LineTransitionTimeline): LineTransitionClock {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val elapsedSinceLastSampleMs =
            if (transitionLastClockAtElapsedMs == 0L) {
                0L
            } else {
                (nowElapsedMs - transitionLastClockAtElapsedMs).coerceAtLeast(0L)
            }
        transitionLastClockAtElapsedMs = nowElapsedMs
        val rawPositionMs = projectedPosition()
        transitionPositionState = advanceTransitionPosition(
            rawPositionMs,
            transitionPositionState,
            elapsedSinceLastSampleMs,
            content.speed
        )
        if (!transitionWallClockDriven) {
            // round 3 起点连续:delta 首次越过退场段时把过渡起点重锚到「当前平滑位置 − 退场
            // 时长」(见 moveStartAnchorPosition),使位移段第一帧 moveProgress 恰为 0、画出
            // 上一帧几何;首帧晚到/批跳变不再把起步一帧跳进位移段。位置推进不限速
            // (暂停/seek)时不重锚,瞬间落位/立即结束语义保持。
            val rateBounded = content.speed.isFinite() && content.speed > 0f
            moveStartAnchorPosition(
                transitionStartPositionMs,
                transitionPositionState.smoothedPositionMs,
                timeline.exitMs,
                timeline.moveMs,
                moveStartAnchored,
                rateBounded
            )?.let { anchor ->
                transitionStartPositionMs = anchor
                moveStartAnchored = true
            }
        }
        val clock = if (transitionWallClockDriven) {
            if (transitionStartedAtElapsedMs == 0L) {
                transitionStartedAtElapsedMs = nowElapsedMs
            }
            lineTransitionClockAtElapsed(nowElapsedMs - transitionStartedAtElapsedMs, timeline)
        } else {
            lineTransitionClockAtPosition(
                transitionPositionState.smoothedPositionMs,
                transitionStartPositionMs,
                transitionPositionState.smoothedPositionMs,
                timeline
            )
        }
        return if (isTransitionSeekJump(rawPositionMs, transitionPositionState.rawHighWaterPositionMs)) {
            clock.copy(interrupted = true)
        } else {
            clock
        }
    }

    /** 结束换行过渡:清空旧行快照与过渡时钟状态,静态绘制立即接管。 */
    private fun endLineTransition() {
        pendingLineTransition = null
        transitionStartPositionMs = 0L
        transitionPositionState = TransitionPositionState(0L, 0L)
        moveStartAnchored = false
        transitionWallClockDriven = false
        transitionStartedAtElapsedMs = 0L
        transitionLastClockAtElapsedMs = 0L
        exitSnapshot = null
        transitionTimeline = null
        contentBoundsChangedListener?.invoke()
    }

    private fun lineProgress(): Float = progress(projectedPosition(), content.lineStartMs, content.lineEndMs)

    private fun progress(position: Long, start: Long, end: Long): Float =
        if (end <= start) if (position >= end) 1f else 0f
        else ((position - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun setTextAlpha(
        paint: Paint,
        factor: Float,
        brightness: Float,
        color: Int
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
        // 颜色恒走独立的「下一行颜色」(secondLineColorArgb 同源),不随呈现形态改用
        // 「辅助行颜色」,否则"下一行颜色"设置对辅助文字形态完全失效。
        val secondaryForm = secondLineRendersAsSecondary(
            content.secondaryNextLine,
            content.showNextLine,
            content.nextLine.isNotBlank(),
            hasFirstLineAuxText(content.secondaryMode, content.romanized, content.translated),
            content.nextLineAux
        )
        val color = secondLineColorArgb(
            if (secondaryForm) {
                SecondLinePresentation.AS_SECONDARY
            } else {
                SecondLinePresentation.STANDALONE
            },
            resolvedPalette
        )
        if (secondaryForm) {
            // 辅助文字形态只借辅助文字的亮度档(随「高亮辅助文字」),不借它的颜色。
            setTextAlpha(paint, staticSecondaryTextFactor(content.secondaryTextBright), 1f, color)
        } else {
            paint.color = color
            paint.alpha = (255f * staticNextLineTextFactor()).toInt()
        }
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
    // cacheContext 必须是视图自己的上下文(SystemUI):自定义字体经 Provider 取流后的副本
    // 只能落在本进程可写的 cacheDir,模块包 cacheDir 对 SystemUI 无写权限。
    private fun resolveTypeface(family: String, weight: String): Typeface =
        LyricTypefaceResolver.resolve(fontContext ?: context, family, weight, cacheContext = context)

    private // DUET_*:对唱并发行块(仅息屏内容携带);ROMANIZED/TRANSLATED 为并发行自己的辅助行。
    enum class RowKind { METADATA, ORIGINAL, ROMANIZED, TRANSLATED, NEXT_LINE, DUET_ORIGINAL, DUET_ROMANIZED, DUET_TRANSLATED }
    private data class Row(
        val kind: RowKind,
        val text: String,
        val paint: Paint,
        val height: Float,
        val gapBefore: Float,
        val lines: List<TextLine>,
        val lineHeight: Float,
        /**
         * 辅助文字逐字效果的行窗口(空=不参与,静态绘制):仅第一行辅助文字行与并发行
         * 辅助行在「辅助文字逐字效果」开启时携带;第二行歌词及其辅助行恒为空(它们的
         * 播放窗口尚未开始,不能借当前行窗口点亮)。
         */
        val auxKaraokeWindow: LongRange? = null,
        /**
         * 并发行行标记(并发行块各行,含和声走辅助行车道的行):主行换行过渡不带走它们
         * (见 [drawFrozenDuetRows]),它们只随并发行自己的过渡(加入淡入)出现/切换。
         */
        val duet: Boolean = false
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
    /**
     * 待落定过渡(见 [resolvePendingLineTransition]):起点快照/档位/起点位置在换行到达时
     * 定死,位移段时长等目标布局重建后按实际行位差换算。
     */
    private data class PendingLineTransition(
        val snapshot: CanvasSnapshot,
        val promoting: Boolean,
        val transitionMode: String,
        val lineTransitionSpeed: String,
        val snapshotAgeMs: Long?,
        val startPositionMs: Long,
        val startedAtElapsedMs: Long
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
        /** 该行是否带真实词窗(见 [hasTimedWordWindows]):渲染路径决策与效果余量共用。 */
        val timed: Boolean
    ) {
        val lineCount: Int
            get() = lines.size
        val rubyHeight: Float
            get() = lines.sumOf { it.rubyHeight.toDouble() }.toFloat()
    }

    private data class LayoutState(
        val rows: List<PositionedRow>,
        val original: OriginalLayout,
        val duet: OriginalLayout? = null
    )

    companion object {
        /** 对唱并发行加入淡入时长(毫秒);期间静音态绘制,完成恢复共享发光管线。 */
        private const val DUET_JOIN_FADE_MS = 180L


        /**
         * 同一帧到达窗口(≈60Hz 一帧):两条换行快照的到达间隔不超过它即按同帧计
         * (doze 批投递实测 1–3ms 间隔、19ms 内 12 条)。超过则视为不同拍,重新计 1,
         * 避免画布停绘期间把不同拍的内容累计成同帧。
         */
        private const val SAME_FRAME_ARRIVAL_WINDOW_MS = 16L
        private const val CADENCE_DIAGNOSTIC_WINDOW_MS = 10_000L
        private const val CADENCE_DIAGNOSTIC_TAG = "AodCanvasCadence"
        private const val GLOW_HALO_ALPHA = 235
        private const val GLOW_HALO_RADIUS = 0.52f
    }
}
