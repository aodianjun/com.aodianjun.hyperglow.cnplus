package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.readHierarchyField
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_PORTRAIT
import com.eza.hyperglow.aod.DEFAULT_CANVAS_PADDING_PERCENT
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.projection.LyricKeepAliveSignal
import com.eza.hyperglow.root.projection.LyricRenderContent
import com.eza.hyperglow.root.projection.LyricRetentionAnchor
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.projection.SystemUiLyricSubscriber
import com.eza.hyperglow.root.projection.edgeFor
import com.eza.hyperglow.root.projection.freezeAt
import com.eza.hyperglow.root.projection.isAuthorizedForPresentation
import com.eza.hyperglow.root.projection.nextLyricRetentionAnchor
import com.eza.hyperglow.root.projection.pauseLingerRemainingMs
import com.eza.hyperglow.root.projection.shouldRenewAodDraw
import com.eza.hyperglow.root.projection.shouldRequestAodWake
import com.eza.hyperglow.root.surface.SurfaceEnvironment
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.WidgetMeasurement
import com.eza.hyperglow.root.transition.LinkageSceneRole
import com.eza.hyperglow.root.transition.LinkageSurface
import com.eza.hyperglow.root.transition.LinkageTransitionCoordinator
import com.eza.hyperglow.root.transition.SystemUiClockMorphHook
import com.eza.hyperglow.root.transition.TransitionRect
import com.eza.hyperglow.root.transition.animateLinkageView
import com.eza.hyperglow.root.transition.fadeOutLinkageView
import com.eza.hyperglow.root.transition.isDimmedAodDisplayState
import com.eza.hyperglow.root.transition.presentationRectInWindow
import com.eza.hyperglow.root.transition.resetLinkageView
import com.eza.hyperglow.root.transition.transitionRectInWindow
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal data class AodSurfaceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 横屏(非全屏)画布 rect 的宽高交换(纯函数):以 rect 中心为锚交换宽与高,再整体钳进
 * [rootWidth]×[rootHeight]。竖屏放置算法给出的歌词 rect 通常「宽≥高」(贴在时钟下方);
 * 横屏逻辑帧 ow=视图高、oh=视图宽,旋转 90° 后用户在横持视角看到的画布反而「高>宽」,
 * 歌词行在视觉横向上变得很短。交换后画布在用户视角呈横宽形,行布局空间与横屏语义一致。
 * 中心锚定保证交换前后画布覆盖同一块屏幕区域(仍避开时钟所在一侧),钳制兜底极端尺寸。
 */
internal fun swapAodSurfaceRectForLandscape(
    rect: AodSurfaceRect,
    rootWidth: Int,
    rootHeight: Int
): AodSurfaceRect {
    val newWidth = rect.height.coerceIn(0, rootWidth)
    val newHeight = rect.width.coerceIn(0, rootHeight)
    val centerX = (rect.left + rect.right) / 2
    val centerY = (rect.top + rect.bottom) / 2
    val left = (centerX - newWidth / 2).coerceIn(0, (rootWidth - newWidth).coerceAtLeast(0))
    val top = (centerY - newHeight / 2).coerceIn(0, (rootHeight - newHeight).coerceAtLeast(0))
    return AodSurfaceRect(left, top, left + newWidth, top + newHeight)
}

internal data class AodRenderedClockBounds(
    val top: Int,
    val bottom: Int
) {
    val height: Int get() = bottom - top
}

/** draw wake 锁脉冲的可观测结局;AOD 冻结类问题的现场取证就靠它区分失败层次。 */
internal enum class AodDrawWakePulseResult {
    SUCCESS,
    MISSING_WAKE_LOCK,
    MISSING_METHOD,
    INVOCATION_FAILED
}

/** 去重规则:结果不变就不重复记录,保持 renew 循环每 2.75s 一次的静默。 */
internal fun shouldLogDrawWakePulseResult(
    previous: AodDrawWakePulseResult?,
    current: AodDrawWakePulseResult
): Boolean = previous != current

private const val BRIGHT_LINKAGE_CLOCK_RESERVE_FRACTION = 0.35f

/**
 * zone 翻转滞回的相对阈值:CLOCK_TOP↔CLOCK_BOTTOM 的可用空间差须超过该比例,才允许翻转。
 * 避免系统时钟在自由空间几乎相等的临界点附近微移时,导致画布在全高与"一条"之间反复跳动
 * (issue #46)。值按根高比例计算,吸收临界抖动,同时允许真实、显著的下移触发切换。
 */
private const val AOD_ZONE_FLIP_HYSTERESIS_FRACTION = 0.10f

internal fun resolveRenderedAodSceneZone(
    managedZone: AodSceneZone,
    renderedBounds: AodRenderedClockBounds?,
    rootHeight: Int,
    margin: Int,
    hysteresis: Int = 0
): AodSceneZone {
    if (managedZone == AodSceneZone.STOCK || renderedBounds == null ||
        renderedBounds.height <= 0 || rootHeight <= 0
    ) return managedZone
    val freeAbove = (renderedBounds.top - margin).coerceAtLeast(0)
    val freeBelow = (rootHeight - renderedBounds.bottom - margin).coerceAtLeast(0)
    // 滞回(hysteresis):在临界点附近,必须让一侧可用空间明显超过另一侧(超过阈值)才翻转 zone。
    // 没有滞回时,系统时钟在 freeAbove≈freeBelow 处微小移动即可令 CLOCK_TOP↔CLOCK_BOTTOM 反复
    // 翻转,可用画布在全高与"一条"之间骤变,导致歌词跳变到屏幕另一处(issue #46)。
    return when {
        freeAbove - freeBelow > hysteresis -> AodSceneZone.CLOCK_BOTTOM
        freeBelow - freeAbove > hysteresis -> AodSceneZone.CLOCK_TOP
        else -> managedZone
    }
}

/**
 * @param rememberedPhysicalBounds the last physical measurement taken on a root of the same height.
 *   The physical clock cannot be measured while the panel is dark, so every re-attach in that state
 *   falls through to the managed position — which is where the clock was asked to go, not where the
 *   stock AOD clock actually is. On this device those differ by hundreds of pixels, so the lyrics
 *   appeared far from their configured place until the panel lit and the real bounds resolved. A
 *   measurement already taken is better evidence than a position we merely requested.
 */
internal fun resolvedAodClockBounds(
    renderedBounds: AodRenderedClockBounds?,
    controlledTop: Int?,
    controlledBottom: Int?,
    measuredTop: Int,
    measuredBottom: Int,
    exactPhysicalBounds: AodRenderedClockBounds? = null,
    rememberedPhysicalBounds: AodRenderedClockBounds? = null
): AodRenderedClockBounds {
    val controlled = if (controlledTop != null && controlledBottom != null) {
        AodRenderedClockBounds(controlledTop, controlledBottom)
    } else {
        null
    }
    val validRendered = renderedBounds?.takeIf { it.height > 0 }
    val validControlled = controlled?.takeIf { it.height > 0 }
    val validPhysical = exactPhysicalBounds?.takeIf { it.height > 0 }
    val validRemembered = rememberedPhysicalBounds?.takeIf { it.height > 0 }
    return when {
        validPhysical != null -> validPhysical
        validRemembered != null -> validRemembered
        validControlled != null -> validControlled
        validRendered != null -> validRendered
        else -> AodRenderedClockBounds(measuredTop, measuredBottom)
    }
}

internal fun selectPhysicalAodClockBounds(
    systemUiBounds: AodRenderedClockBounds?,
    aodControllerBounds: AodRenderedClockBounds?
): AodRenderedClockBounds? = systemUiBounds ?: aodControllerBounds

/**
 * Held stable position of the AOD clock, used as an anchor for lyric placement.
 *
 * @param sinceElapsedMs monotonic time the held position was last confirmed (set to the current
 *   bounds).
 */
internal data class AodClockAnchor(
    val top: Int,
    val bottom: Int,
    val sinceElapsedMs: Long
)

/** How long a held clock position may go unconfirmed before it is treated as a genuine move. */
internal const val AOD_CLOCK_ANCHOR_HOLD_MS = 40_000L

/**
 * 重挂载时 remembered 物理时钟测量可被当作 fallback 的最长年龄(毫秒)。
 *
 * 物理时钟在面板熄灭时无法读取,remembered 缓存只用于覆盖「重挂载→面板点亮」这个短暂暗窗。
 * 但它只是一个原始采样,没有时效性就是隐患:#38 里一次瞬时错误的探针读数(布局用几何一度出现
 * `1160..2240`,而实测时钟稳定在 `493..1574`)被缓存后,在物理读取间歇反复回退,把歌词压到低位
 * 锁死。超过该窗口的旧值不再是可靠的时钟证据,直接弃用。
 */
internal const val REMEMBERED_CLOCK_MAX_AGE_MS = 10_000L

/** Minimum downward clock drift (px) the settle watchdog reacts to. */
internal const val STOCK_SETTLE_DRIFT_PX = 24

/** Recheck cadence after the initial settle schedule is exhausted. */
internal const val STOCK_SETTLE_RETRY_MS = 300_000L

/**
 * Stabilizes the clock bounds used for lyric placement against fast upward oscillation (e.g. the
 * media header toggling the AOD layout, which squeezes the clock upward by hundreds of pixels). The
 * anchor holds the last *confirmed* clock position: as long as the raw bounds keep returning to it
 * (oscillation), the anchor stays put so the lyric never jumps.
 *
 * 仅对**上行**(clock 顶部/底部高于 anchor)做 [holdMs] 防抖:持定期内保持旧锚,仅当持久上移
 * 离开持定位未再确认满 [holdMs] 才重锚。**下行(clock 底部低于 anchor 底部)立即硬同步、不做
 * 防抖**:歌词 surface 位于 anchor 底部之下,若时钟向下漂移(小米 burn-in 每次唤醒步进
 * 90-160px)却等防抖窗口,时钟会在滞后期间叠在歌词上;#24 曾把下行也纳入 holdMs(issue #23),
 * 实机(0.3.86)验证反而导致「歌词不跟随时钟下移、长期错位」,故回退——下移防抖只能依靠布局层
 * 的 [avoidStockClockOverlap] 避让,不能压制 anchor 更新。[seedSinceElapsedMs] 用于锚定被丢弃
 * 后继承防抖记忆。
 */
internal fun stabilizeAodClockAnchor(
    previous: AodClockAnchor?,
    raw: AodRenderedClockBounds,
    nowElapsedMs: Long,
    holdMs: Long = AOD_CLOCK_ANCHOR_HOLD_MS,
    seedSinceElapsedMs: Long = -1L
): AodClockAnchor {
    if (raw.top >= raw.bottom) return previous ?: AodClockAnchor(raw.top, raw.bottom, nowElapsedMs)
    if (previous == null) {
        val since = if (seedSinceElapsedMs >= 0L) seedSinceElapsedMs else nowElapsedMs
        return AodClockAnchor(raw.top, raw.bottom, since)
    }
    if (raw.top == previous.top && raw.bottom == previous.bottom) {
        // Held position reconfirmed: refresh so oscillation never ages it out.
        return previous.copy(sinceElapsedMs = nowElapsedMs)
    }
    // 下行立即硬同步:避免防抖持有期内时钟向下漂移时压在歌词上(issue #23 补充)。
    if (raw.bottom > previous.bottom) {
        return AodClockAnchor(raw.top, raw.bottom, nowElapsedMs)
    }
    // 上行参与 holdMs 防抖:持定期内保持旧锚,过期才重锚。
    return if (nowElapsedMs - previous.sinceElapsedMs >= holdMs) {
        AodClockAnchor(raw.top, raw.bottom, nowElapsedMs)
    } else {
        previous
    }
}

/**
 * 锚定模式(实时时钟跟随关闭)下决定本帧歌词布局采用的时钟几何锚。
 *
 * 物理时钟在面板熄灭等场景会瞬时不可读。**读数缺失不等于时钟真的移动了**:此时若用
 * (可能是过期的) remembered/managed 值取代上一帧喂进锚定器,一次瞬时错误采样会被当成
 * "下行"硬同步进锚,把歌词压到低位;而真实位置上行恢复又被 [AOD_CLOCK_ANCHOR_HOLD_MS]
 * 防抖压住,长期锁死(#38)。因此只要有已建立的稳定锚且本帧无新鲜物理读数,就直接沿用该锚。
 *
 * @param hasFreshPhysical 本帧是否拿到了物理时钟读数(而非回退来源)。
 * @return 本帧应采用的 [AodClockAnchor];调用方负责回写该值。
 */
internal fun resolveAnchoredAodClockBounds(
    hasFreshPhysical: Boolean,
    previousAnchor: AodClockAnchor?,
    rawClockBounds: AodRenderedClockBounds,
    nowElapsedMs: Long,
    seedSinceElapsedMs: Long = -1L
): AodClockAnchor {
    if (!hasFreshPhysical && previousAnchor != null) {
        return previousAnchor
    }
    return stabilizeAodClockAnchor(
        previousAnchor,
        rawClockBounds,
        nowElapsedMs,
        seedSinceElapsedMs = seedSinceElapsedMs
    )
}

internal fun brightLinkageClockBounds(rootHeight: Int): AodRenderedClockBounds =
    AodRenderedClockBounds(0, (rootHeight * BRIGHT_LINKAGE_CLOCK_RESERVE_FRACTION).roundToInt())

internal fun shouldUseBrightClockMorphGeometry(
    linkageMode: Boolean,
    morphingToAod: Boolean,
    linkageAwaitingDim: Boolean,
    displayState: Int
): Boolean = linkageMode && !isDimmedAodDisplayState(displayState) &&
    (morphingToAod || linkageAwaitingDim)

internal fun calculateAodSurfaceRect(
    rootWidth: Int,
    rootHeight: Int,
    stockBottom: Int,
    margin: Int,
    desiredWidth: Int,
    desiredHeight: Int,
    translationX: Int = 0,
    safeBottom: Int? = null,
    anchor: String = "below_stock_clock",
    verticalBias: Float = 0.5f
): AodSurfaceRect {
    val boundedWidth = desiredWidth.coerceIn(0, rootWidth.coerceAtLeast(0))
    val maxLeft = (rootWidth - boundedWidth).coerceAtLeast(0)
    val left = ((rootWidth - boundedWidth) / 2 + translationX).coerceIn(0, maxLeft)
    val visibleBottom = (minOf(rootHeight, safeBottom ?: rootHeight) - margin).coerceAtLeast(0)
    val safeTop = (stockBottom + margin).coerceIn(0, visibleBottom)
    val height = desiredHeight.coerceIn(0, visibleBottom - safeTop)
    val top = when (anchor) {
        "screen_center" -> safeTop + (visibleBottom - safeTop - height) / 2
        "screen_bottom_safe" -> visibleBottom - height
        "custom_vertical_bias" -> safeTop +
            ((visibleBottom - safeTop - height) * verticalBias.coerceIn(0f, 1f)).roundToInt()
        else -> safeTop
    }
    return AodSurfaceRect(left, top, left + boundedWidth, top + height)
}

internal fun stockBottomInRoot(rootWindowY: Int, childWindowY: Int, childHeight: Int): Int =
    childWindowY - rootWindowY + childHeight

internal fun hasUsableAodRootSize(width: Int, height: Int): Boolean = width > 0 && height > 0

internal fun aodSceneSafeCanvas(
    rootWidth: Int,
    rootHeight: Int,
    clockTop: Int,
    lyricTopSafe: Int,
    margin: Int,
    zone: AodSceneZone
): PlacementRect = if (zone == AodSceneZone.CLOCK_BOTTOM) {
    val top = lyricTopSafe.coerceIn(0, rootHeight)
    val bottom = (clockTop - margin).coerceIn(top, rootHeight)
    PlacementRect(0f, top.toFloat(), rootWidth.toFloat(), bottom.toFloat())
} else {
    PlacementRect(0f, 0f, rootWidth.toFloat(), rootHeight.toFloat())
}

internal fun aodPlacementMaxHeightFraction(
    configuredFraction: Float,
    zone: AodSceneZone
): Float = if (zone == AodSceneZone.CLOCK_BOTTOM) 1f else configuredFraction

internal fun shouldRenderAodSnapshot(
    sceneActive: Boolean,
    snapshotVisible: Boolean,
    featureEnabled: Boolean,
    profileEnabled: Boolean,
    transitionFailed: Boolean,
    spotifyAuthorized: Boolean = true
): Boolean = sceneActive && snapshotVisible && spotifyAuthorized && featureEnabled &&
    profileEnabled && !transitionFailed

internal fun isNewAodWakeSignal(previous: Long, incoming: Long): Boolean =
    incoming != 0L && incoming != previous

/**
 * 是否应抑制系统息屏内容的纯函数。
 *
 * 两种情况命中即抑制:
 *  1. [suppressBase] 全局「隐藏系统息屏内容」开启;
 *  2. [landscapeHideStock] 开启 且 当前处于横屏步进([landscapeStep]) 且 歌词随设备旋转
 *     ([rotateWithDevice]) —— 用于实现「横屏自动隐藏系统息屏内容,回落竖屏自动恢复」。
 */
internal fun shouldHideStockAodContent(
    suppressBase: Boolean,
    landscapeStep: Boolean,
    rotateWithDevice: Boolean,
    landscapeHideStock: Boolean
): Boolean = suppressBase || (landscapeStep && rotateWithDevice && landscapeHideStock)

internal fun retainedAodSnapshotAfterUpdate(
    incoming: LyricSnapshot,
    lastVisible: LyricSnapshot?,
    retained: LyricSnapshot?,
    anchor: LyricRetentionAnchor?,
    mediaPlayerPresent: Boolean,
    nowElapsedMs: Long,
    pauseLingerMs: Long = 5_000L,
    pauseRetentionEnabled: Boolean = true
): LyricSnapshot? = when {
    incoming.visible -> null
    !mediaPlayerPresent -> null
    // 「暂停时显示歌曲信息、歌词」关闭时,暂停驻留边按终止态处理:不冻结歌词,
    // 避免任何设置组合下暂停仍漏出歌曲信息驻留。
    incoming.pauseRetentionEligible && pauseRetentionEnabled -> {
        val pauseAtElapsedMs = anchor.edgeFor(
            pauseRetentionEligible = true,
            fallbackElapsedMs = incoming.updatedAtElapsedMs.coerceIn(0L, nowElapsedMs)
        )
        val candidate = retained?.takeIf { it.pauseRetentionEligible } ?: lastVisible?.freezeAt(
            pauseAtElapsedMs,
            keepAliveWhileFrozen = false
        )?.copy(playbackActive = false, pauseRetentionEligible = true)
        candidate?.takeIf {
            pauseLingerRemainingMs(it.sampledAtElapsedMs, pauseLingerMs, nowElapsedMs) != null
        }
    }
    incoming.playbackActive -> {
        val gapAtElapsedMs = anchor.edgeFor(
            pauseRetentionEligible = false,
            fallbackElapsedMs = nowElapsedMs
        )
        val candidate = retained?.takeIf { it.playbackActive } ?: lastVisible?.freezeAt(
            gapAtElapsedMs,
            keepAliveWhileFrozen = lastVisible.keepAlive
        )?.copy(playbackActive = true, pauseRetentionEligible = false)
        // 仍在播放的隐藏边是传输间隙,间隙自身的界就是电源宽限。没有它,producer 持续重发
        // 间隙时冻结歌词会活过所有计时器,因为没有任何东西给一条从不停歇的快照计时。
        candidate?.takeIf { nowElapsedMs - gapAtElapsedMs < PAUSED_AOD_KEEP_ALIVE_MS }
            ?.let { expirePausedAodKeepAlive(it, nowElapsedMs) }
    }
    else -> null
}

internal fun expirePausedAodKeepAlive(
    retained: LyricSnapshot,
    nowElapsedMs: Long
): LyricSnapshot {
    if (!retained.keepAlive) return retained
    val pausedForMs = (nowElapsedMs - retained.sampledAtElapsedMs).coerceAtLeast(0L)
    return if (pausedForMs >= PAUSED_AOD_KEEP_ALIVE_MS) {
        retained.copy(keepAlive = false)
    } else {
        retained
    }
}

internal const val PAUSED_AOD_KEEP_ALIVE_MS = 30_000L

internal fun smoothAodRevealProgress(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * (3f - 2f * value)
}

internal fun shouldRetryManagedAodPosition(attempts: Int, maximumAttempts: Int): Boolean =
    attempts < maximumAttempts

internal object AodSurfaceController : SystemUiLyricSubscriber, LinkageSurface {
    private const val TAG = "AodSurfaceController"
    private const val SURFACE_TAG = "hyper_aod_lyrics_surface"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionUpdates = AodPositionUpdateCoalescer()
    private var attachmentGeneration = 0L
    private var environment = SurfaceEnvironment(LyricSurfaceKind.AOD, 0L)
    private var rootRef = WeakReference<ViewGroup>(null)
    private var burnInContainerRef = WeakReference<ViewGroup>(null)
    private var surface: LinearLayout? = null
    private var lyricCanvas: AodLyricCanvasView? = null
    private var spicyAnimationView: AodSpicyAnimationView? = null
    private var latestSnapshot: LyricSnapshot? = null
    private var lastVisibleSnapshot: LyricSnapshot? = null
    private var retainedMediaSnapshot: LyricSnapshot? = null
    private var retentionAnchor: LyricRetentionAnchor? = null
    private var stockMediaPlayerPresent = false
    private var customization: CompiledCustomization? = null
    private var runtimeProfile: CompiledSurfaceProfile? = null
    private var lastRenderContent: LyricRenderContent? = null
    private var lastWakeSignal = Long.MIN_VALUE
    private var sceneRole = LinkageSceneRole.INACTIVE
    private var handoffActive = false
    private var transitionFailedHidden = false
    private var lastLayoutBlockTrace: String? = null
    private var lastSnapshotTrace: String? = null
    private var lastBrightClockMorphPhase: Boolean? = null
    private var lastClockGeometryAuthority: String? = null
    private var lastDrawWakePulseResult: AodDrawWakePulseResult? = null
    private var lastDrawWakeRuntimeClass = ""
    private var postHandoffDiagnosticGeneration = 0L
    private var postHandoffEarlyGeneration = -1L
    private var postHandoffLateGeneration = -1L
    private var rememberedPhysicalClockBounds: AodRenderedClockBounds? = null
    private var rememberedPhysicalClockRootHeight = 0
    /** rememberedPhysicalClockBounds 最近一次写入的单调时钟,用于判断缓存是否已过期。 */
    private var rememberedPhysicalClockBoundsSinceElapsedMs = Long.MIN_VALUE
    @Volatile private var stockWidgetControlActive = false
    @Volatile private var suppressStockAodContent = false
    @Volatile private var suppressGateActive = false
    @Volatile private var clockPinActive = false
    @Volatile private var currentRotationStep = AodOrientationStep.PORTRAIT
    private var aodRotateWithDevice = false
    private var aodLandscapeFullscreen = false
    private var aodRotationMode = AOD_ROTATION_MODE_PORTRAIT
    private var aodRotationSettleMs = 1_000L
    /** 系统时钟保留区:抑制系统内容前最后一次实测的物理时钟顶部位置。 */
    private var stockClockReserveTop: Int? = null
    @Volatile private var burnInPattern = "static_bottom"
    private var burnInIntervalMs = 60_000L
    private var sceneZone = AodSceneZone.STOCK
    private var controlledClockTop: Int? = null
    private var controlledClockBottom: Int? = null
    private var controlledLyricTopSafe: Int? = null
    private var systemUiClockBounds: AodRenderedClockBounds? = null
    private var aodControllerClockBounds: AodRenderedClockBounds? = null
    private var renderedClockBounds: AodRenderedClockBounds? = null
    private var clockAnchor: AodClockAnchor? = null

    /** 根高度变化丢锚时继承的防抖记忆(旧锚 sinceElapsedMs),消费一次后清空。 */
    private var droppedAnchorHoldSinceMs: Long? = null
    private val renderedClockRootLocation = IntArray(2)
    private val renderedClockUnion = Rect()
    private val renderedClockScratch = Rect()
    private var displayManager: DisplayManager? = null
    private var observedDisplayId = -1
    private var lastObservedDisplayState = -1
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != observedDisplayId) return
            val state = rootRef.get()?.display?.state ?: return
            val changed = state != lastObservedDisplayState
            if (changed) {
                lastObservedDisplayState = state
                HookLogger.i(TAG, "AOD root display state=$state displayId=$displayId")
            }
            LinkageTransitionCoordinator.onAodDisplayState(state)
            AodPowerCoordinator.onAodDisplayState(state)
            if (changed) requestGeometryUpdate()
        }
    }
    private var pendingStockMotionUpdate: AodPositionUpdate? = null
    private var stockMotionRevealPending = false
    private var stockMotionTransitionActive = false
    private var stockMotionAlphaAnimationActive = false
    private var stockMotionAlphaCompletesTransition = false
    private var stockMotionAlphaStartedAt = 0L
    private var stockMotionAlphaDurationMs = 0L
    private var stockMotionAlphaFrom = 1f
    private var stockMotionAlphaTo = 1f
    private var drawWakeRenewalActive = false
    // Stock settle drift watchdog: MIUI moves the AOD clock to a burn-in initial
    // position about 10s after AOD entry (AODUpdatePositionController, translated via
    // setTranslationY, which never fires OnLayoutChange). The follow-up hook can miss it,
    // so we re-check the physical clock bounds on a schedule and force a geometry refresh.
    private var stockSettleCheckScheduled = false
    private var stockSettleCheckIndex = 0
    private val stockSettleCheckIntervals = longArrayOf(
        10_000L, 10_000L, 15_000L, 20_000L, 30_000L,
        40_000L, 60_000L, 80_000L, 120_000L, 160_000L, 240_000L
    )
    private val stockSettleCheck = object : Runnable {
        override fun run() {
            stockSettleCheckScheduled = false
            checkStockSettleDrift()
        }
    }

    private var renderStallWatchdogScheduled = false
    private var lastPlacedTrace: String? = null
    private var managedPositionRetryCount = 0
    private var initialRevealPending = true
    private var initialRevealActive = false
    private var initialRevealStartedAt = 0L
    private var initialRevealDurationMs = 0L
    private val pausedKeepAliveExpiry = Runnable {
        val retained = retainedMediaSnapshot ?: return@Runnable
        val expired = expirePausedAodKeepAlive(retained, SystemClock.elapsedRealtime())
        if (expired.keepAlive == retained.keepAlive) return@Runnable
        retainedMediaSnapshot = expired
        latestSnapshot = latestSnapshot?.copy(keepAlive = expired.keepAlive)
        HookLogger.i(TAG, "Paused AOD keepalive grace expired")
        updateLifetimeGuard()
    }
    private val pauseLingerExpiry = object : Runnable {
        override fun run() {
            val retained = retainedMediaSnapshot?.takeIf { it.pauseRetentionEligible } ?: return
            val remaining = pauseLingerRemainingMs(
                retained.sampledAtElapsedMs,
                customization?.pauseLingerMs ?: 5_000L,
                SystemClock.elapsedRealtime()
            )
            if (remaining != null) {
                if (remaining != Long.MAX_VALUE) mainHandler.postDelayed(this, remaining)
                return
            }
            retainedMediaSnapshot = null
            retentionAnchor = null
            lastVisibleSnapshot = null
            latestSnapshot = latestSnapshot?.takeUnless { it === retained }
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
        }
    }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        requestGeometryUpdate()
    }
    private val clockGeometryPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        val root = rootRef.get()
        val burnInContainer = burnInContainerRef.get()
        root?.display?.state?.let(LinkageTransitionCoordinator::onAodDisplayState)
        val snapshot = latestSnapshot
        val brightClockMorph = root?.let(::isBrightClockMorphPhase) == true
        val exactSystemUiBounds = root?.let(SystemUiClockMorphHook::renderedBoundsInRoot)
        val exactAodControllerBounds = root?.let(AodPositionHook::renderedTargetBoundsInRoot)
        val exactBoundsChanged = exactSystemUiBounds != systemUiClockBounds ||
            exactAodControllerBounds != aodControllerClockBounds
        if (exactBoundsChanged) {
            systemUiClockBounds = exactSystemUiBounds
            aodControllerClockBounds = exactAodControllerBounds
            requestGeometryUpdate()
        } else if (!brightClockMorph &&
            !stockWidgetControlActive && root != null && burnInContainer != null &&
            snapshot != null && canRenderAod(snapshot)
        ) {
            val nextBounds = renderedStockClockBounds(
                root,
                burnInContainer,
                renderedClockBounds
            )
            if (nextBounds != null && nextBounds != renderedClockBounds) {
                renderedClockBounds = nextBounds
                requestGeometryUpdate()
            }
        }
        true
    }
    private val geometryUpdate = Runnable {
        val update = positionUpdates.drain(attachmentGeneration) ?: return@Runnable
        environment = environment.copy(
            burnInTranslationX = update.translationX,
            burnInTranslationY = update.translationY,
            safeBottom = update.safeBottom
        )
        sceneZone = update.zone
        controlledClockTop = update.clockTop
        controlledClockBottom = update.clockBottom
        controlledLyricTopSafe = update.lyricTopSafe
        val root = rootRef.get() ?: return@Runnable
        val burnInContainer = burnInContainerRef.get() ?: return@Runnable
        val directSurface = surface ?: return@Runnable
        layoutSurface(root, burnInContainer, directSurface)
    }
    private val stockMotionSettleTimeout = Runnable {
        settleStockMotion("timeout")
    }

    private fun settleStockMotion(source: String) {
        val update = pendingStockMotionUpdate ?: return
        pendingStockMotionUpdate = null
        if (update.generation != attachmentGeneration) return
        stockMotionRevealPending = true
        HookLogger.i(TAG, "Stock scene motion settled source=$source zone=${update.zone}")
        enqueueGeometryUpdate(update)
    }
    private val stockMotionAlphaFrame = object : Runnable {
        override fun run() {
            if (!stockMotionAlphaAnimationActive) return
            val directSurface = surface ?: return cancelStockMotionTransition(resetAlpha = false)
            val elapsed = (SystemClock.elapsedRealtime() - stockMotionAlphaStartedAt)
                .coerceAtLeast(0L)
            val linear = (elapsed / stockMotionAlphaDurationMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)
            val progress = smoothAodRevealProgress(linear)
            directSurface.alpha = stockMotionAlphaFrom +
                (stockMotionAlphaTo - stockMotionAlphaFrom) * progress
            directSurface.invalidate()
            rootRef.get()?.invalidate()
            if (linear < 1f) {
                mainHandler.postDelayed(this, AOD_ANIMATION_FRAME_MS)
            } else {
                stockMotionAlphaAnimationActive = false
                directSurface.alpha = stockMotionAlphaTo
                if (stockMotionAlphaCompletesTransition) {
                    stockMotionTransitionActive = false
                    stockMotionAlphaCompletesTransition = false
                }
            }
        }
    }
    private val drawWakeRenewal = object : Runnable {
        override fun run() {
            if (!drawWakeRenewalActive) return
            val root = rootRef.get()
            if (root == null || !isSceneActive()) {
                setDrawWakeRenewalActive(false)
                return
            }
            pulseDrawWakeLock(root)
            mainHandler.postDelayed(this, DRAW_WAKE_RENEW_INTERVAL_MS)
        }
    }
    /**
     * 渲染停摆看门狗(issue #6):切歌 + 锁屏过渡并发窗口里,快照应用链路可能停摆、
     * Draw wake renewal 被关掉后没有任何事件再把它拉起来,画布节律也随之停止,
     * AOD 只能等下一次锁屏 attach 重放才恢复。看门狗在「息屏 + 已附着 + 场景激活 +
     * 快照可渲染 + 仍在播放」时周期自检,发现停摆就强制重放快照并补画布唤醒脉冲。
     */
    private val renderStallWatchdog = object : Runnable {
        override fun run() {
            if (!renderStallWatchdogScheduled) return
            mainHandler.postDelayed(this, RENDER_STALL_WATCHDOG_INTERVAL_MS)
            runCatching { recoverRenderStallIfNeeded() }
        }
    }
    private val postHandoffDiagnosticEarly = Runnable {
        logPostHandoffSurfaceState("+1s", postHandoffEarlyGeneration)
    }
    private val postHandoffDiagnosticLate = Runnable {
        logPostHandoffSurfaceState("+7s", postHandoffLateGeneration)
    }
    private val initialRevealFrame = object : Runnable {
        override fun run() {
            if (!initialRevealActive) return
            val directSurface = surface ?: return finishInitialReveal()
            val elapsed = (SystemClock.elapsedRealtime() - initialRevealStartedAt).coerceAtLeast(0L)
            val linear = (elapsed / initialRevealDurationMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)
            directSurface.alpha = smoothAodRevealProgress(linear)
            directSurface.invalidate()
            rootRef.get()?.invalidate()
            if (linear < 1f) mainHandler.postDelayed(this, AOD_ANIMATION_FRAME_MS)
            else finishInitialReveal()
        }
    }
    private val managedBurnInStart = object : Runnable {
        override fun run() {
            if (!stockWidgetControlActive) return
            if (AodPositionHook.hasManagedPosition() ||
                AodPositionHook.advanceManagedPosition(burnInPattern, animated = false)
            ) {
                managedPositionRetryCount = 0
                if (managedAodPatternRepeats(burnInPattern)) {
                    mainHandler.postDelayed(managedBurnInAdvance, burnInIntervalMs)
                }
            } else {
                managedPositionRetryCount++
                if (shouldRetryManagedAodPosition(
                        managedPositionRetryCount,
                        MAX_MANAGED_POSITION_RETRIES
                    )
                ) {
                    mainHandler.postDelayed(this, MANAGED_BURN_IN_RETRY_MS)
                } else {
                    HookLogger.i(TAG, "Managed AOD position unavailable; using stock geometry")
                }
            }
        }
    }
    private val managedBurnInAdvance = object : Runnable {
        override fun run() {
            if (!stockWidgetControlActive) return
            if (!managedAodPatternRepeats(burnInPattern)) return
            val moved = AodPositionHook.advanceManagedPosition(burnInPattern)
            mainHandler.postDelayed(
                this,
                if (moved) burnInIntervalMs else MANAGED_BURN_IN_RETRY_MS
            )
        }
    }

    override val surfaceKind = LyricSurfaceKind.AOD
    override val linkageSurfaceKind = LyricSurfaceKind.AOD

    fun attach(root: ViewGroup) {
        mainHandler.post {
            runCatching {
                HookLogger.i(
                    TAG,
                    "attach requested root=${root.javaClass.name} ${root.width}x${root.height}"
                )
                XiaomiCapabilityResolver.observeContext(root.context)
                SystemUiLyricProjectionRuntime.projection.reportCapabilities()
                if (!XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_SURFACE)) {
                    if (rootRef.get() != null || surface != null) detachCurrent()
                    val report = XiaomiCapabilityResolver.snapshot()
                    HookLogger.w(
                        TAG,
                        "AOD surface capability unavailable; surface disabled " +
                            "aodSurface=${report.symbols.aodSurface} " +
                            "aodHostContainer=${report.symbols.aodHostContainer} " +
                            "profile=${report.profileState.wireValue}"
                    )
                    return@runCatching
                }

                val burnInContainer = findBurnInContainer(root) ?: run {
                    val fields = runCatching {
                        root.javaClass.declaredFields
                            .joinToString(",") { "${it.name}:${it.type.simpleName}" }
                    }.getOrDefault("<unreadable>")
                    HookLogger.w(
                        TAG,
                        "mTableModeContainer unavailable; surface disabled. Fields: $fields"
                    )
                    return@runCatching
                }
                HookLogger.i(TAG, "Burn-in container resolved; building surface")
                if (rootRef.get() === root && surface != null) return@runCatching
                detachCurrent()
                attachmentGeneration++
                environment = SurfaceEnvironment(
                    LyricSurfaceKind.AOD,
                    attachmentGeneration,
                    fullAodSupported = XiaomiCapabilityResolver.hasCapability(
                        XiaomiCapability.FULL_AOD
                    ),
                    videoDepthSupported = XiaomiCapabilityResolver.hasCapability(
                        XiaomiCapability.VIDEO_DEPTH
                    )
                )
                rootRef = WeakReference(root)
                burnInContainerRef = WeakReference(burnInContainer)
                observeDisplayState(root)
                AodPositionHook.observeAodRoot(root)
                if (clockAnchor == null) {
                    val nowSeedingElapsedMs = SystemClock.elapsedRealtime()
                    rememberedPhysicalClockBounds
                        ?.takeIf { rememberedPhysicalClockRootHeight == root.height }
                        ?.takeIf {
                            nowSeedingElapsedMs - rememberedPhysicalClockBoundsSinceElapsedMs <=
                                REMEMBERED_CLOCK_MAX_AGE_MS
                        }
                        ?.let { b ->
                            clockAnchor = AodClockAnchor(b.top, b.bottom, nowSeedingElapsedMs)
                            HookLogger.i(TAG, "Anchor seeded from remembered bounds ${b.top}..${b.bottom} (re-attach throttle)")
                        }
                } else if (rememberedPhysicalClockRootHeight != root.height) {
                    // 根高度变化丢弃锚时继承防抖记忆(issue #23 建议三):新锚的 hold 窗口
                    // 从旧锚的确认时刻起算,而不是重新计满 40s。
                    droppedAnchorHoldSinceMs = clockAnchor?.sinceElapsedMs
                    clockAnchor = null
                    HookLogger.i(
                        TAG,
                        "Display root height changed; anchor dropped (hold since inherited=" +
                            "$droppedAnchorHoldSinceMs)"
                    )
                }
                val directSurface = buildSurface(root)
                surface = directSurface
                root.overlay.add(directSurface)
                root.addOnLayoutChangeListener(layoutChangeListener)
                burnInContainer.addOnLayoutChangeListener(layoutChangeListener)
                burnInContainer.viewTreeObserver.addOnPreDrawListener(
                    clockGeometryPreDrawListener
                )
                LinkageTransitionCoordinator.registerSurface(this)
                AodPowerCoordinator.onSurfaceAttached()
                startRenderStallWatchdog()
                startStockSettleWatchdog()
                LinkageTransitionCoordinator.onAodSurfaceMode(AodPositionHook.isLinkageMode())
                applySuppressionAndRotation(latestSnapshot)
                val generation = attachmentGeneration
                root.post {
                    if (generation == attachmentGeneration && rootRef.get() === root) {
                        root.display?.state?.let(LinkageTransitionCoordinator::onAodDisplayState)
                        val laidOut = layoutSurface(root, burnInContainer, directSurface)
                        HookLogger.i(
                            TAG,
                            "Attach layout replay result=$laidOut root=${root.width}x${root.height} " +
                                "snapshot=${latestSnapshot?.revision}"
                        )
                    }
                }
                SystemUiLyricProjectionRuntime.projection.attach(this, root.context)
                HookLogger.i(TAG, "Surface attached")
            }.onFailure {
                runCatching { detachCurrent() }
                HookLogger.e(TAG, "Attach failed", it)
            }
        }
    }

    fun detach(root: ViewGroup) {
        mainHandler.post {
            if (rootRef.get() !== root) return@post
            runCatching { detachCurrent() }
        }
    }

    fun onStockPositionUpdated(
        translationX: Float,
        translationY: Float,
        safeBottom: Int?,
        clockTop: Int?,
        clockBottom: Int?,
        lyricTopSafe: Int?,
        zone: AodSceneZone,
        zoneChanged: Boolean,
        animated: Boolean
    ) {
        val dispatch = dispatch@{
            val positionEnabled = latestSnapshot?.positionFollowingEnabled == true &&
                XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_POSITION_UPDATES)
            if (!positionEnabled && sceneZone == AodSceneZone.STOCK &&
                zone == AodSceneZone.STOCK && !zoneChanged &&
                pendingStockMotionUpdate == null
            ) return@dispatch
            val update = AodPositionUpdate(
                attachmentGeneration,
                translationX,
                translationY,
                safeBottom,
                clockTop,
                clockBottom,
                lyricTopSafe,
                zone
            )
            if (zoneChanged) {
                HookLogger.i(
                    TAG,
                    "Stock scene zone=$zone translation=($translationX,$translationY) " +
                        "clock=${clockTop ?: "?"}..${clockBottom ?: "?"}"
                )
            }
            if (zoneChanged && animated) {
                pendingStockMotionUpdate = update
                mainHandler.removeCallbacks(stockMotionSettleTimeout)
                fadeForStockMotion()
                mainHandler.postDelayed(stockMotionSettleTimeout, STOCK_MOTION_SETTLE_TIMEOUT_MS)
            } else if (pendingStockMotionUpdate != null && animated) {
                pendingStockMotionUpdate = update
            } else {
                if (!animated) {
                    pendingStockMotionUpdate = null
                    mainHandler.removeCallbacks(stockMotionSettleTimeout)
                    cancelStockMotionTransition(resetAlpha = true)
                }
                enqueueGeometryUpdate(update)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch()
        } else {
            mainHandler.post { dispatch() }
        }
    }

    fun isStockWidgetControlActive(): Boolean = stockWidgetControlActive

    fun onStockPositionSettled() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainHandler.removeCallbacks(stockMotionSettleTimeout)
            settleStockMotion("callback")
        } else {
            mainHandler.post {
                mainHandler.removeCallbacks(stockMotionSettleTimeout)
                settleStockMotion("callback")
            }
        }
    }

    fun managedBurnInPattern(): String = burnInPattern

    fun onStockMediaPlayerPresenceChanged(present: Boolean) {
        val update = update@{
            if (stockMediaPlayerPresent == present) return@update
            stockMediaPlayerPresent = present
            if (present) {
                latestSnapshot?.takeUnless { it.visible }?.let(::onLyricSnapshot)
                return@update
            }
            if (retainedMediaSnapshot == null) return@update
            cancelPausedKeepAliveExpiry()
            cancelPauseLingerExpiry()
            retainedMediaSnapshot = null
            retentionAnchor = null
            lastVisibleSnapshot = null
            latestSnapshot = null
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            mainHandler.post(update)
        }
    }

    override fun onLyricSnapshot(snapshot: LyricSnapshot) {
        val incomingSnapshot = LinkageTransitionCoordinator.resolveSnapshot(snapshot)
        if (incomingSnapshot.visible) lastVisibleSnapshot = incomingSnapshot
        else if (lastVisibleSnapshot == null) {
            lastVisibleSnapshot = SystemUiLyricProjectionRuntime.projection.cachedVisibleSnapshot()
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        retentionAnchor = nextLyricRetentionAnchor(incomingSnapshot, retentionAnchor, nowElapsedMs)
        retainedMediaSnapshot = retainedAodSnapshotAfterUpdate(
            incomingSnapshot,
            lastVisibleSnapshot,
            retainedMediaSnapshot,
            retentionAnchor,
            stockMediaPlayerPresent,
            nowElapsedMs,
            customization?.pauseLingerMs ?: 5_000L,
            pauseRetentionEnabled = customization?.pauseShowContent ?: false
        )
        schedulePausedKeepAliveExpiry(retainedMediaSnapshot)
        schedulePauseLingerExpiry(retainedMediaSnapshot)
        val resolvedSnapshot = if (incomingSnapshot.visible) {
            incomingSnapshot
        } else {
            retainedMediaSnapshot ?: incomingSnapshot
        }
        val wasFollowingPosition = latestSnapshot?.positionFollowingEnabled == true
        latestSnapshot = resolvedSnapshot
        if (!incomingSnapshot.visible && retainedMediaSnapshot == null &&
            !incomingSnapshot.playbackActive
        ) {
            lastVisibleSnapshot = null
        }
        if (HookLogger.traceEnabled) {
            val snapshotTrace =
                "Snapshot revision=${resolvedSnapshot.revision} visible=${resolvedSnapshot.visible} " +
                    "retained=${retainedMediaSnapshot != null && !incomingSnapshot.visible} " +
                    "lineLevel=${resolvedSnapshot.lineLevelSync} " +
                    "keepAlive=${resolvedSnapshot.keepAlive} " +
                    "render=${canRenderAod(resolvedSnapshot)} " +
                    "surface=${surface != null}/${surface?.visibility} " +
                    "effAlpha=${surface?.let(::effectiveSurfaceAlpha)} " +
                    "alphaChain=${surface?.let(::surfaceAlphaChain)} " +
                    "root=${rootRef.get()?.width}x${rootRef.get()?.height} " +
                    "failed=$transitionFailedHidden"
            if (snapshotTrace != lastSnapshotTrace) {
                lastSnapshotTrace = snapshotTrace
                HookLogger.i(TAG, snapshotTrace)
            }
        }
        val burnInScheduleChanged = burnInPattern != resolvedSnapshot.burnInPattern ||
            managedAodPatternRepeats(resolvedSnapshot.burnInPattern) &&
            burnInIntervalMs != resolvedSnapshot.burnInIntervalMs
        burnInPattern = resolvedSnapshot.burnInPattern
        burnInIntervalMs = resolvedSnapshot.burnInIntervalMs
        setStockWidgetControlActive(
            resolvedSnapshot.positionFollowingEnabled &&
                canRenderAod(resolvedSnapshot) &&
                XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_POSITION_UPDATES),
            restartSchedule = burnInScheduleChanged
        )
        applySuppressionAndRotation(resolvedSnapshot)
        if (!resolvedSnapshot.positionFollowingEnabled && wasFollowingPosition) {
            environment = environment.copy(
                burnInTranslationX = 0f,
                burnInTranslationY = 0f,
                safeBottom = null
            )
            requestGeometryUpdate()
        } else if (resolvedSnapshot.positionFollowingEnabled && !wasFollowingPosition &&
            XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_POSITION_UPDATES)
        ) {
            val burnInContainer = burnInContainerRef.get()
            enqueueGeometryUpdate(
                burnInContainer?.translationX ?: 0f,
                burnInContainer?.translationY ?: 0f,
                null
            )
        }
        if (!canRenderAod(resolvedSnapshot)) {
            hideSurfaceOnly()
            return
        }
        val demo = resolvedSnapshot.metadata.startsWith("AOD DEMO")
        val renderContent = resolvedSnapshot.renderContent()
        val directSurface = surface ?: return
        val root = rootRef.get() ?: return
        val burnInContainer = burnInContainerRef.get() ?: return
        val wakeRequired = isNewAodWakeSignal(lastWakeSignal, resolvedSnapshot.wakeSignal)
        lastWakeSignal = resolvedSnapshot.wakeSignal
        if (renderContent == lastRenderContent && directSurface.visibility == View.VISIBLE) {
            updateLifetimeGuard()
            requestWakeIfAllowed(root, directSurface, wakeRequired)
            return
        }
        if (!layoutSurface(root, burnInContainer, directSurface)) return
        lyricCanvas?.setContent(resolvedSnapshot.toAodCanvasContent(effectiveAodProfile()))
        lastRenderContent = renderContent
        lyricCanvas?.visibility = if (demo) View.GONE else View.VISIBLE
        spicyAnimationView?.visibility = if (demo) View.VISIBLE else View.GONE
        if (demo) spicyAnimationView?.start() else spicyAnimationView?.stop()
        directSurface.visibility = View.VISIBLE
        updateLifetimeGuard()
        LinkageTransitionCoordinator.onSurfaceReady(LyricSurfaceKind.AOD)
        requestWakeIfAllowed(root, directSurface, wakeRequired)
    }

    override fun onLyricKeepAlive(signal: LyricKeepAliveSignal) {
        val retained = retainedMediaSnapshot?.let {
            expirePausedAodKeepAlive(it, SystemClock.elapsedRealtime())
        }
        if (retained != retainedMediaSnapshot) retainedMediaSnapshot = retained
        schedulePausedKeepAliveExpiry(retained)
        val effectiveKeepAlive = retained?.keepAlive ?: signal.keepAlive
        latestSnapshot = latestSnapshot?.copy(
            updatedAtElapsedMs = signal.updatedAtElapsedMs,
            keepAlive = effectiveKeepAlive,
            wakeSignal = signal.wakeSignal,
            playbackActive = signal.playbackActive,
            pauseRetentionEligible = signal.pauseRetentionEligible
        )
        updateLifetimeGuard()
        val wakeRequired = isNewAodWakeSignal(lastWakeSignal, signal.wakeSignal)
        lastWakeSignal = signal.wakeSignal
        // Playback-active keepalives must still pulse the draw wake even when the keepAlive flag
        // is false: some producers report playbackActive without keepAlive, and the immediate
        // pulse on each ~4 s keepalive complements the 2.75 s periodic renewal to keep the doze
        // surface compositing a fresh frame while the screen is off.
        if (!effectiveKeepAlive && !wakeRequired && !signal.playbackActive) return
        val directSurface = surface ?: return
        val root = rootRef.get() ?: return
        requestWakeIfAllowed(root, directSurface, wakeRequired)
    }

    override fun onLyricProjectionDisconnected() {
        cancelPausedKeepAliveExpiry()
        cancelPauseLingerExpiry()
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        retentionAnchor = null
        customization = null
        runtimeProfile = null
        setStockWidgetControlActive(false)
        hideSurfaceOnly(pulse = false)
    }

    override fun onLyricProjectionStale() {
        cancelPausedKeepAliveExpiry()
        cancelPauseLingerExpiry()
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        retentionAnchor = null
        setStockWidgetControlActive(false)
        hideSurfaceOnly(pulse = false)
    }

    override fun onCustomization(configuration: CompiledCustomization) {
        customization = configuration
        AodBrightnessController.setBoostEnabled(configuration.aodBrightnessBoost)
        AodBrightnessController.setBrightnessOverride(
            configuration.aodBrightnessOverride,
            configuration.aodBrightnessLevel
        )
        val retained = retainedMediaSnapshot?.takeIf { snapshot ->
            !snapshot.pauseRetentionEligible || configuration.pauseShowContent &&
                pauseLingerRemainingMs(
                    snapshot.sampledAtElapsedMs,
                    configuration.pauseLingerMs,
                    SystemClock.elapsedRealtime()
                ) != null
        }
        if (retainedMediaSnapshot != null && retained == null) {
            retainedMediaSnapshot = null
            retentionAnchor = null
            lastVisibleSnapshot = null
            latestSnapshot = null
            cancelPauseLingerExpiry()
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
            return
        }
        runtimeProfile = null
        lastRenderContent = null
        latestSnapshot?.let(::onLyricSnapshot)
        if (retained != null) {
            retainedMediaSnapshot = retained
            latestSnapshot = retained
            schedulePausedKeepAliveExpiry(retained)
            schedulePauseLingerExpiry(retained)
        }
    }

    private fun schedulePausedKeepAliveExpiry(retained: LyricSnapshot?) {
        mainHandler.removeCallbacks(pausedKeepAliveExpiry)
        if (retained?.keepAlive != true) return
        val pausedForMs = (SystemClock.elapsedRealtime() - retained.sampledAtElapsedMs)
            .coerceAtLeast(0L)
        val delayMs = (PAUSED_AOD_KEEP_ALIVE_MS - pausedForMs).coerceAtLeast(0L)
        mainHandler.postDelayed(pausedKeepAliveExpiry, delayMs)
    }

    private fun cancelPausedKeepAliveExpiry() {
        mainHandler.removeCallbacks(pausedKeepAliveExpiry)
    }

    private fun schedulePauseLingerExpiry(retained: LyricSnapshot?) {
        cancelPauseLingerExpiry()
        retained?.takeIf { it.pauseRetentionEligible } ?: return
        val remaining = pauseLingerRemainingMs(
            retained.sampledAtElapsedMs,
            customization?.pauseLingerMs ?: 5_000L,
            SystemClock.elapsedRealtime()
        ) ?: run {
            retainedMediaSnapshot = null
            retentionAnchor = null
            lastVisibleSnapshot = null
            latestSnapshot = latestSnapshot?.takeUnless { it === retained }
            setStockWidgetControlActive(false)
            hideSurfaceOnly(pulse = false)
            return
        }
        if (remaining != Long.MAX_VALUE) mainHandler.postDelayed(pauseLingerExpiry, remaining)
    }

    private fun cancelPauseLingerExpiry() {
        mainHandler.removeCallbacks(pauseLingerExpiry)
    }

    private fun hideSurfaceOnly(pulse: Boolean = true) {
        if (latestSnapshot?.let(::canRenderAod) != true) {
            setStockWidgetControlActive(false)
        }
        finishInitialReveal()
        surface?.animate()?.cancel()
        surface?.alpha = 1f
        val wasVisible = surface?.visibility == View.VISIBLE
        surface?.visibility = View.GONE
        lyricCanvas?.stop()
        lyricCanvas?.visibility = View.GONE
        spicyAnimationView?.stop()
        spicyAnimationView?.visibility = View.GONE
        lastRenderContent = null
        updateLifetimeGuard()
        if (pulse && wasVisible && isSceneActive()) rootRef.get()?.let(::pulseDrawWakeLock)
    }

    private fun detachCurrent() {
        attachmentGeneration++
        stopRenderStallWatchdog()
        stopStockSettleWatchdog()
        mainHandler.removeCallbacks(geometryUpdate)
        mainHandler.removeCallbacks(stockMotionSettleTimeout)
        mainHandler.removeCallbacks(managedBurnInStart)
        mainHandler.removeCallbacks(managedBurnInAdvance)
        cancelPostHandoffDiagnostics()
        cancelPausedKeepAliveExpiry()
        cancelPauseLingerExpiry()
        pendingStockMotionUpdate = null
        stockMotionRevealPending = false
        cancelStockMotionTransition(resetAlpha = false)
        positionUpdates.clear()
        stockWidgetControlActive = false
        suppressStockAodContent = false
        suppressGateActive = false
        stockClockReserveTop = null
        AodPositionHook.restoreStockTranslation()
        AodPositionHook.abandonManagedSession()
        AodPositionHook.setSuppressActive(false)
        AodPositionHook.setHoldStockPosition(false)
        AodPositionHook.setIntegralClockPin(false)
        // 不在每次 detach 时清空跨 controller 锚定(issue #36):旋转/LinkageTransition 会
        // 导致同一 AOD 会话内 surface 高频重建,清锚会让锚点落到已漂移的请求值,
        // 防下移失效、旋转回竖屏回不到原位。锚定只在 AOD 显示真正关闭后清空
        // (见 AodPowerCoordinator.onAodDisplayState)。
        clockPinActive = false
        AodSurfaceHook.clearSuppressedState()
        AodOrientationMonitor.detach()
        currentRotationStep = AodOrientationStep.PORTRAIT
        setDrawWakeRenewalActive(false)
        finishInitialReveal()
        AodPowerCoordinator.onSurfaceDetached()
        LinkageTransitionCoordinator.unregisterSurface(this)
        SystemUiLyricProjectionRuntime.projection.detach(this)
        rootRef.get()?.removeOnLayoutChangeListener(layoutChangeListener)
        burnInContainerRef.get()?.removeOnLayoutChangeListener(layoutChangeListener)
        burnInContainerRef.get()?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(clockGeometryPreDrawListener)
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        observedDisplayId = -1
        lastObservedDisplayState = -1
        surface?.let { directSurface ->
            rootRef.get()?.overlay?.remove(directSurface)
            (directSurface.parent as? ViewGroup)?.removeView(directSurface)
        }
        surface = null
        lyricCanvas = null
        spicyAnimationView = null
        rootRef.clear()
        burnInContainerRef.clear()
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        retentionAnchor = null
        stockMediaPlayerPresent = false
        customization = null
        runtimeProfile = null
        lastRenderContent = null
        lastWakeSignal = Long.MIN_VALUE
        sceneRole = LinkageSceneRole.INACTIVE
        handoffActive = false
        initialRevealPending = true
        initialRevealActive = false
        initialRevealStartedAt = 0L
        initialRevealDurationMs = 0L
        transitionFailedHidden = false
        lastLayoutBlockTrace = null
        lastPlacedTrace = null
        lastSnapshotTrace = null
        lastBrightClockMorphPhase = null
        lastClockGeometryAuthority = null
        lastDrawWakePulseResult = null
        lastDrawWakeRuntimeClass = ""
        sceneZone = AodSceneZone.STOCK
        controlledClockTop = null
        controlledClockBottom = null
        controlledLyricTopSafe = null
        systemUiClockBounds = null
        aodControllerClockBounds = null
        renderedClockBounds = null
        environment = SurfaceEnvironment(LyricSurfaceKind.AOD, attachmentGeneration)
    }

    private fun setStockWidgetControlActive(active: Boolean, restartSchedule: Boolean = false) {
        if (stockWidgetControlActive == active) {
            if (active && restartSchedule) startManagedBurnInSchedule()
            return
        }
        stockWidgetControlActive = active
        // 位置决策路由在此切换(managed ↔ 原厂透传),issue #33 建议三:转换必须可见。
        HookLogger.i(TAG, "Stock widget control active=$active")
        if (active) {
            startManagedBurnInSchedule()
        } else {
            pendingStockMotionUpdate = null
            stockMotionRevealPending = false
            mainHandler.removeCallbacks(stockMotionSettleTimeout)
            cancelStockMotionTransition(resetAlpha = true)
            managedPositionRetryCount = 0
            mainHandler.removeCallbacks(managedBurnInStart)
            mainHandler.removeCallbacks(managedBurnInAdvance)
            AodPositionHook.restoreStockTranslation()
        }
    }

    private fun startManagedBurnInSchedule() {
        mainHandler.removeCallbacks(managedBurnInStart)
        mainHandler.removeCallbacks(managedBurnInAdvance)
        AodPositionHook.restartManagedPattern()
        managedPositionRetryCount = 0
        mainHandler.post(managedBurnInStart)
    }

    /** suppress 空场:保留系统时钟占位区,让后续关闭抑制时布局不塌陷。 */
    private fun holdStockSuppression(snapshot: LyricSnapshot?) {
        val root = rootRef.get()
        val physical = selectPhysicalAodClockBounds(systemUiClockBounds, aodControllerClockBounds)
            ?: AodPositionHook.renderedTargetBoundsInRoot(root ?: return)
        stockClockReserveTop = physical?.top
        if (snapshot != null && snapshot.visible) AodPositionHook.setHoldStockPosition(true)
    }

    private fun applyStockSuppression(suppress: Boolean, snapshot: LyricSnapshot?) {
        if (suppressGateActive == suppress) {
            if (suppress) holdStockSuppression(snapshot)
            return
        }
        suppressGateActive = suppress
        suppressStockAodContent = suppress
        AodPositionHook.setSuppressActive(suppress)
        AodSurfaceHook.setSuppressionGate(suppress)
        if (suppress) {
            burnInContainerRef.get()?.let(AodSurfaceHook::registerSuppressedRoot)
            holdStockSuppression(snapshot)
        } else {
            AodSurfaceHook.clearSuppressedState()
            stockClockReserveTop = null
            AodPositionHook.setHoldStockPosition(false)
        }
        HookLogger.i(TAG, "Stock content suppression active=$suppress")
    }

    /** 是否应抑制系统息屏内容:全局开关 或 (横屏 && 随设备旋转 && 横屏隐藏开关)。 */
    private fun shouldSuppressStock(snapshot: LyricSnapshot?, renderable: Boolean): Boolean {
        if (!renderable || snapshot == null) return false
        return shouldHideStockAodContent(
            suppressBase = snapshot.suppressStockAodContent,
            landscapeStep = currentRotationStep != AodOrientationStep.PORTRAIT,
            rotateWithDevice = snapshot.aodRotateWithDevice,
            landscapeHideStock = snapshot.aodLandscapeHideStock
        )
    }

    private fun onOrientationStepResolved(step: AodOrientationStep) {
        if (currentRotationStep == step) return
        currentRotationStep = step
        lyricCanvas?.setRotationStep(step)
        // 旋转步进变化会影响「横屏隐藏系统息屏内容」的求值:进横屏时隐藏、回落竖屏时恢复。
        val latest = latestSnapshot
        applyStockSuppression(
            shouldSuppressStock(latest, latest != null && canRenderAod(latest)),
            latest
        )
        requestGeometryUpdate()
    }

    /** 从快照应用抑制 + 旋转配置;不足一次渲染时两者均关闭。 */
    private fun applySuppressionAndRotation(snapshot: LyricSnapshot?) {
        val renderable = snapshot != null && canRenderAod(snapshot)
        val playbackActive = snapshot?.playbackActive ?: false
        val suppress = shouldSuppressStock(snapshot, renderable)
        applyStockSuppression(suppress, snapshot)
        // 关闭「实时跟随系统时钟」(锚定模式)且模块在渲染 AOD 时,钉住系统时钟位置
        // (不随防烧屏沉降下移),但保留时钟显示 —— 与「隐藏系统时钟」解耦(issue #26)。
        // 暂停/没有播放(!playbackActive)时不钉住,让时钟回到系统位置、随防烧屏正常移动。
        val pinClock = renderable && playbackActive && !currentAodProfile().aodClockFollow
        // 自定义时钟 Y 偏移仅作用于「钉住」状态:把 App 端滑块值推到 AodPositionHook,
        // 叠加到被钉住的系统时钟 Y 上;未钉住时清零,确保不施加偏移。
        AodPositionHook.setClockYOffset(if (pinClock) (customization?.aodClockYOffset ?: 0) else 0)
        applyClockPin(pinClock)
        applyRotation(snapshot?.takeIf { renderable })
    }

    /** 系统时钟 Y 钉住:仅由 [applySuppressionAndRotation] 驱动,带变化检测避免反复调用。 */
    private fun applyClockPin(active: Boolean) {
        if (clockPinActive == active) return
        clockPinActive = active
        AodPositionHook.setIntegralClockPin(active)
        HookLogger.i(TAG, "System clock position pin=$active")
    }

    private fun applyRotation(snapshot: LyricSnapshot?) {
        val rotate = snapshot?.aodRotateWithDevice == true
        val mode = snapshot?.aodRotationMode ?: AOD_ROTATION_MODE_PORTRAIT
        val settle = snapshot?.aodRotationSettleMs ?: 1_000L
        val anchorLandscape = snapshot?.aodCanvasAnchorLandscape ?: 0.5f
        val textScale = snapshot?.aodLandscapeTextScale ?: 1f
        val fullscreen = snapshot?.aodLandscapeFullscreen == true
        val debugShowCanvasFrame = snapshot?.aodDebugShowCanvasFrame == true
        val padPX = snapshot?.aodCanvasPaddingPortraitXPercent ?: DEFAULT_CANVAS_PADDING_PERCENT
        val padPY = snapshot?.aodCanvasPaddingPortraitYPercent ?: DEFAULT_CANVAS_PADDING_PERCENT
        val padLX = snapshot?.aodCanvasPaddingLandscapeXPercent ?: DEFAULT_CANVAS_PADDING_PERCENT
        val padLY = snapshot?.aodCanvasPaddingLandscapeYPercent ?: DEFAULT_CANVAS_PADDING_PERCENT
        aodRotateWithDevice = rotate
        aodLandscapeFullscreen = fullscreen
        aodRotationMode = mode
        aodRotationSettleMs = settle
        lyricCanvas?.updateOrientation(
            rotate = rotate,
            mode = mode,
            landscapeTextScale = textScale,
            landscapeAnchor = anchorLandscape,
            landscapeFullscreen = fullscreen,
            debugShowCanvasFrame = debugShowCanvasFrame,
            paddingPortraitXPercent = padPX,
            paddingPortraitYPercent = padPY,
            paddingLandscapeXPercent = padLX,
            paddingLandscapeYPercent = padLY
        )
        if (rotate && !AodOrientationMonitor.isAttached()) {
            val context = rootRef.get()?.context
            if (context != null) {
                AodOrientationMonitor.attach(context, mode, settle) { step ->
                    mainHandler.post { onOrientationStepResolved(step) }
                }
            }
        } else if (!rotate) {
            AodOrientationMonitor.detach()
            onOrientationStepResolved(AodOrientationStep.PORTRAIT)
        } else {
            // 旋转已附着:刷新模式与防抖窗口。
            AodOrientationMonitor.attach(
                rootRef.get()?.context ?: return,
                mode,
                settle
            ) { step -> mainHandler.post { onOrientationStepResolved(step) } }
        }
    }

    private fun updateLifetimeGuard() {
        val snapshot = latestSnapshot
        // The draw-wake renewal pulses mWakeLock on the AOD root to force Xiaomi's doze
        // surface to composite a fresh frame, which is what keeps synced-lyric highlights
        // advancing while the screen is off. It must not be gated on the AOD_LIFETIME_GUARD
        // capability: that symbol is independent of mWakeLock, and gating on it silently
        // freezes AOD updates on versions where the probe fails. pulseDrawWakeLock is
        // guarded by runCatching, so an absent field fails harmlessly.
        // 播放态额外取 Lyricon 中心服务的真值:切歌 BUFFERING/位置未知窗口里 app 侧快照会
        // 短暂报 playbackActive=false(issue #6),若只信快照,续期会在缓冲窗口被关掉且
        // 没有事件再拉起,直到下次锁屏 attach 才恢复。
        val active = shouldRenewAodDraw(
            surfaceKind = surfaceKind,
            attached = rootRef.get() != null,
            sceneActive = isSceneActive(),
            effectivelyVisible = isSurfaceRenderActive() &&
                snapshot != null && canRenderAod(snapshot),
            pendingStockMotion = pendingStockMotionUpdate != null,
            keepAlive = snapshot?.keepAlive == true,
            playbackActive = snapshot?.playbackActive == true ||
                AodWakeBroker.isLyriconPlaybackActive()
        )
        setDrawWakeRenewalActive(active)
    }

    private fun setDrawWakeRenewalActive(active: Boolean) {
        if (drawWakeRenewalActive == active) return
        drawWakeRenewalActive = active
        mainHandler.removeCallbacks(drawWakeRenewal)
        // 续期被关时带上 alpha 链现场:0.3.83 的祖先 alpha 门控回归靠这行定位。
        val offContext = if (!active) {
            val directSurface = surface
            " effAlpha=${directSurface?.let(::effectiveSurfaceAlpha)}" +
                " alphaChain=${directSurface?.let(::surfaceAlphaChain)}"
        } else {
            ""
        }
        HookLogger.i(TAG, "Draw wake renewal active=$active$offContext")
        if (!active) return
        rootRef.get()?.let(::pulseDrawWakeLock)
        mainHandler.postDelayed(drawWakeRenewal, DRAW_WAKE_RENEW_INTERVAL_MS)
    }

    private fun startStockSettleWatchdog() {
        stopStockSettleWatchdog()
        stockSettleCheckIndex = 0
        scheduleNextStockSettleCheck()
    }

    private fun stopStockSettleWatchdog() {
        stockSettleCheckScheduled = false
        mainHandler.removeCallbacks(stockSettleCheck)
        stockSettleCheckIndex = 0
    }

    private fun scheduleNextStockSettleCheck() {
        if (stockSettleCheckScheduled) return
        stockSettleCheckScheduled = true
        val delay = if (stockSettleCheckIndex < stockSettleCheckIntervals.size) {
            stockSettleCheckIntervals[stockSettleCheckIndex++]
        } else {
            STOCK_SETTLE_RETRY_MS
        }
        mainHandler.postDelayed(stockSettleCheck, delay)
    }

    private fun checkStockSettleDrift() {
        val root = rootRef.get()
        val directSurface = surface
        if (root == null || directSurface == null ||
            directSurface.visibility != View.VISIBLE
        ) {
            scheduleNextStockSettleCheck()
            return
        }
        val physical = selectPhysicalAodClockBounds(
            systemUiClockBounds,
            aodControllerClockBounds
        ) ?: AodPositionHook.renderedTargetBoundsInRoot(root)
        val anchor = clockAnchor
        if (physical != null && anchor != null &&
            physical.bottom - anchor.bottom > STOCK_SETTLE_DRIFT_PX
        ) {
            // 为防止防烧屏沉降把时钟逐步下拖而 anchor 未及时跟随,这里仅请求一次几何刷新;
            // anchor 的更新完全交给 stabilizeAodClockAnchor:下行立即硬同步(issue #23 补充),
            // 因此刷新后 anchor 会立刻吸附到新的 physical 底部,24px 阈值不会再把锚困在旧位。
            HookLogger.i(
                TAG,
                "Stock settle drift detected +${physical.bottom - anchor.bottom}px " +
                    "anchor=${anchor.top}..${anchor.bottom} " +
                    "physical=${physical.top}..${physical.bottom}; requesting geometry refresh"
            )
            requestGeometryUpdate()
        }
        scheduleNextStockSettleCheck()
    }

    private fun startRenderStallWatchdog() {
        if (renderStallWatchdogScheduled) return
        renderStallWatchdogScheduled = true
        mainHandler.postDelayed(renderStallWatchdog, RENDER_STALL_WATCHDOG_INTERVAL_MS)
    }

    private fun stopRenderStallWatchdog() {
        renderStallWatchdogScheduled = false
        mainHandler.removeCallbacks(renderStallWatchdog)
    }

    private fun recoverRenderStallIfNeeded() {
        val root = rootRef.get() ?: return
        val directSurface = surface ?: return
        if (!isSceneActive() || !directSurface.isAttachedToWindow) return
        // 亮屏时 AOD 本来就不呈现,不做任何恢复。
        if (root.display?.state == android.view.Display.STATE_ON) return
        val snapshot = latestSnapshot ?: return
        if (!canRenderAod(snapshot)) return
        val playbackLive = snapshot.playbackActive || snapshot.keepAlive ||
            AodWakeBroker.isLyriconPlaybackActive()
        if (!playbackLive) return
        val now = SystemClock.elapsedRealtime()
        // 恢复 1:续期掉线。过渡/缓冲窗口把 renewal 关掉后,若期间没有新的快照/心跳
        // 事件到达,updateLifetimeGuard 不会再被调用——看门狗直接补一次评估和脉冲。
        if (!drawWakeRenewalActive) {
            HookLogger.i(
                TAG,
                "Render stall watchdog: draw renewal inactive while playing; re-arming rev=${snapshot.revision}"
            )
            updateLifetimeGuard()
            if (!drawWakeRenewalActive) pulseDrawWakeLock(root)
        }
        // 恢复 2:行级时间轴内容在播放中本应持续重绘,但画布长时间没有 onDraw——
        // 强制重放当前快照,走完整 setContent/syncCadence 路径重启画布节律。
        val lastDrawAt = lyricCanvas?.lastDrawAtElapsedMs() ?: 0L
        if (lyricCanvas?.isTimingEffectActive() == true && lastDrawAt > 0L &&
            now - lastDrawAt > RENDER_STALL_THRESHOLD_MS
        ) {
            HookLogger.i(
                TAG,
                "Render stall watchdog: canvas stalled for ${now - lastDrawAt}ms; " +
                    "replaying snapshot rev=${snapshot.revision}"
            )
            lastRenderContent = null
            onLyricSnapshot(snapshot)
            rootRef.get()?.let(::pulseDrawWakeLock)
            return
        }
        // 恢复 3:投影缓存里已有更新的可见快照却没被本 surface 应用(应用链路在过渡窗口
        // 停摆)——重放投影最新可见快照,不等下一次 attach。
        val cachedVisible = SystemUiLyricProjectionRuntime.projection.cachedVisibleSnapshot()
        if (cachedVisible != null && cachedVisible.visible &&
            cachedVisible.revision > snapshot.revision
        ) {
            HookLogger.i(
                TAG,
                "Render stall watchdog: projection ahead " +
                    "rev=${cachedVisible.revision}>${snapshot.revision}; replaying"
            )
            lastRenderContent = null
            onLyricSnapshot(cachedVisible)
            rootRef.get()?.let(::pulseDrawWakeLock)
        }
    }

    private fun startInitialReveal(directSurface: View, durationMs: Long) {
        initialRevealPending = false
        initialRevealActive = true
        initialRevealStartedAt = SystemClock.elapsedRealtime()
        initialRevealDurationMs = durationMs.coerceIn(150L, 600L)
        directSurface.animate().cancel()
        directSurface.alpha = 0f
        mainHandler.removeCallbacks(initialRevealFrame)
        mainHandler.post(initialRevealFrame)
    }

    private fun finishInitialReveal() {
        mainHandler.removeCallbacks(initialRevealFrame)
        initialRevealActive = false
        initialRevealStartedAt = 0L
        initialRevealDurationMs = 0L
        surface?.alpha = 1f
    }

    private fun buildSurface(root: ViewGroup): LinearLayout {
        val density = root.resources.displayMetrics.density
        return LinearLayout(root.context).apply {
            tag = SURFACE_TAG
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            setPadding((8f * density).roundToInt(), 0, (8f * density).roundToInt(), 0)
            val lyricContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                lyricCanvas = AodLyricCanvasView(context, useDozeHandlerCadence = true).also {
                    it.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    addView(it)
                }
                spicyAnimationView = AodSpicyAnimationView(context).apply {
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(this)
                }
            }
            addView(lyricContent)
        }
    }

    private fun layoutSurface(
        root: ViewGroup,
        burnInContainer: ViewGroup,
        directSurface: View
    ): Boolean {
        if (!hasUsableAodRootSize(root.width, root.height)) {
            failClosedLayout(directSurface, "root=${root.width}x${root.height}")
            return false
        }
        val rootLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        var stockTop = root.height
        var stockBottom = 0
        var foundStockContent = false
        val childLocation = IntArray(2)
        for (index in 0 until burnInContainer.childCount) {
            val child = burnInContainer.getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.getLocationInWindow(childLocation)
            foundStockContent = true
            stockTop = minOf(stockTop, childLocation[1] - rootLocation[1])
            stockBottom = maxOf(
                stockBottom,
                stockBottomInRoot(rootLocation[1], childLocation[1], child.height)
            )
        }
        if (!foundStockContent) stockTop = 0
        val density = root.resources.displayMetrics.density
        val margin = (SURFACE_MARGIN_DP * density).roundToInt()
        val brightLinkage = isBrightClockMorphPhase(root)
        val physicalClockBounds = selectPhysicalAodClockBounds(
            systemUiClockBounds,
            aodControllerClockBounds
        )
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (physicalClockBounds != null && root.height > 0) {
            rememberedPhysicalClockBounds = physicalClockBounds
            rememberedPhysicalClockRootHeight = root.height
            rememberedPhysicalClockBoundsSinceElapsedMs = nowElapsedMs
        }
        // A remembered measurement only describes this layout and only for a short time. The
        // physical clock cannot be read while the panel is dark, but a stale raw sample (e.g. from
        // one transiently wrong probe) must not keep being reused as "fresh" evidence during later
        // read gaps — that is what locked the lyrics low in #38.
        val rememberedBounds = rememberedPhysicalClockBounds
            ?.takeIf { rememberedPhysicalClockRootHeight == root.height }
            ?.takeIf {
                nowElapsedMs - rememberedPhysicalClockBoundsSinceElapsedMs <= REMEMBERED_CLOCK_MAX_AGE_MS
            }
        val rawClockBounds = if (brightLinkage && physicalClockBounds == null) {
            brightLinkageClockBounds(root.height)
        } else {
            resolvedAodClockBounds(
                renderedClockBounds,
                controlledClockTop,
                controlledClockBottom,
                stockTop,
                stockBottom,
                physicalClockBounds,
                rememberedBounds
            )
        }
        // Stabilize against fast clock-bound oscillation (e.g. the media header toggling the AOD
        // layout). The anchor holds the outer clock extent so the lyric placement below/above the
        // clock no longer jumps; it only relocates on a genuinely sustained move.
        //
        // "aodClockFollow" (实时时钟跟随) 开启时,直接采用本次实际测得的时钟位置(rawClockBounds),
        // 跳过锚定防抖,让歌词布局立即跟上系统时钟的真实移动(不因长锚定而滞后错位)。
        //
        // 锚定模式:物理读数缺失是**瞬时缺口**,不是时钟真的移动了。此时沿用已锚定的稳定位置,
        // 不再用 (可能是过期的) remembered 值去喂锚定器——否则 stale 的低位值会被当成"下行"硬
        // 同步进锚、把歌词压到低位,且真实位置上行恢复又被 40s 防抖压住,长期锁死(#38)。
        val aodClockFollow = currentAodProfile().aodClockFollow
        val effectiveClockBounds = if (aodClockFollow) {
            rawClockBounds
        } else {
            val anchor = resolveAnchoredAodClockBounds(
                hasFreshPhysical = physicalClockBounds != null,
                previousAnchor = clockAnchor,
                rawClockBounds = rawClockBounds,
                nowElapsedMs = nowElapsedMs,
                seedSinceElapsedMs = droppedAnchorHoldSinceMs ?: -1L
            )
            droppedAnchorHoldSinceMs = null
            clockAnchor = anchor
            AodRenderedClockBounds(anchor.top, anchor.bottom)
        }
        val effectiveClockTop = effectiveClockBounds.top
        val effectiveClockBottom = effectiveClockBounds.bottom
        val clockGeometryAuthority = when {
            systemUiClockBounds != null -> "physical-systemui"
            aodControllerClockBounds != null -> "physical-aod"
            brightLinkage -> "bright-fallback"
            rememberedBounds != null -> "physical-remembered"
            controlledClockTop != null && controlledClockBottom != null -> "managed"
            renderedClockBounds != null -> "rendered-fallback"
            else -> "measured-fallback"
        }
        if (clockGeometryAuthority != lastClockGeometryAuthority) {
            lastClockGeometryAuthority = clockGeometryAuthority
            HookLogger.i(
                TAG,
                "Clock geometry authority=$clockGeometryAuthority bounds=" +
                    "$effectiveClockTop..$effectiveClockBottom managed=" +
                    "${controlledClockTop ?: "?"}..${controlledClockBottom ?: "?"}"
            )
        }
        val layoutZone = if (brightLinkage || physicalClockBounds != null) {
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                effectiveClockBounds,
                root.height,
                margin,
                (root.height * AOD_ZONE_FLIP_HYSTERESIS_FRACTION).toInt()
            )
        } else if (controlledClockTop != null && controlledClockBottom != null &&
            sceneZone != AodSceneZone.STOCK
        ) {
            sceneZone
        } else {
            resolveRenderedAodSceneZone(
                sceneZone,
                effectiveClockBounds,
                root.height,
                margin,
                (root.height * AOD_ZONE_FLIP_HYSTERESIS_FRACTION).toInt()
            )
        }
        val profile = currentAodProfile()
        // 横屏全屏激活时,placement 借用「自定义位置」的整屏自由几何路径并居中,使画布 rect 覆盖
        // 整屏(不再受 maxHeightFraction 限制),内容在画布内经旋转+自适应缩放铺满(issue #49)。
        // 仅影响 placement rect;渲染内容继续用用户原始 profile,竖屏/普通横屏行为不变。
        val fullscreenPlacement =
            aodRotateWithDevice &&
            currentRotationStep != AodOrientationStep.PORTRAIT &&
            aodLandscapeFullscreen
        val layoutProfile = if (fullscreenPlacement) {
            profile.copy(
                maxHeightFraction = 1f,
                anchor = "custom_vertical_bias",
                verticalBias = 0.5f
            )
        } else {
            profile
        }
        val metadataHeight = if (layoutProfile.metadataVisible &&
            layoutProfile.widgets.any { it.type == "metadata" }
        ) {
            metadataWidgetHeightDp(layoutProfile.metadataSizePercent) * density
        } else {
            0f
        }
        val desiredHeight = root.height * layoutProfile.maxHeightFraction
        val measurements = profile.widgets.mapNotNull { widget ->
            when (widget.type) {
                "lyrics" -> WidgetMeasurement(
                    widget,
                    (desiredHeight - metadataHeight).coerceAtLeast(MIN_LYRIC_HEIGHT_DP * density)
                )
                "metadata" -> WidgetMeasurement(widget, metadataHeight)
                else -> null
            }
        }
        // The custom bias anchor is user-controlled and should roam the entire screen Y range,
        // including above the stock clock and mid-screen, so give it a full-screen canvas.
        val safeCanvas = if (layoutProfile.anchor == "custom_vertical_bias") {
            PlacementRect(0f, 0f, root.width.toFloat(), root.height.toFloat())
        } else {
            aodSceneSafeCanvas(
                root.width,
                root.height,
                effectiveClockTop,
                minOf(controlledLyricTopSafe ?: margin, effectiveClockTop).coerceAtLeast(0),
                margin,
                layoutZone
            )
        }
        val placement = PlacementEngine.resolve(
            layoutProfile.copy(
                maxHeightFraction = aodPlacementMaxHeightFraction(
                    layoutProfile.maxHeightFraction,
                    layoutZone
                )
            ),
            PlacementEnvironment(
                safeCanvas = safeCanvas,
                stockClockBottom = if (layoutZone == AodSceneZone.CLOCK_BOTTOM) {
                    safeCanvas.top
                } else {
                    (effectiveClockBottom + margin).toFloat()
                },
                bottomReserveTop = if (layoutZone == AodSceneZone.CLOCK_BOTTOM) {
                    safeCanvas.bottom
                } else {
                    ((environment.safeBottom ?: root.height) - margin)
                        .coerceAtLeast(0).toFloat()
                }
            ),
            measurements,
            minimumLyricHeight = MIN_LYRIC_HEIGHT_DP * density
        )
        val placed = placement.contentRect
        if (placed != null) {
            val visibleTypes = placement.visibleWidgets.mapTo(HashSet()) { it.type }
            val nextRuntimeProfile = profile.copy(
                widgets = profile.widgets.filter { it.type in visibleTypes },
                metadataVisible = profile.metadataVisible && "metadata" in visibleTypes
            )
            if (runtimeProfile != nextRuntimeProfile) {
                runtimeProfile = nextRuntimeProfile
                lastRenderContent = null
                latestSnapshot?.takeIf {
                    !it.metadata.startsWith("AOD DEMO")
                }?.let {
                    lyricCanvas?.setContent(it.toAodCanvasContent(nextRuntimeProfile))
                    lastRenderContent = it.renderContent()
                }
            }
        }
        val horizontalShift = environment.burnInTranslationX.roundToInt()
        val placedWidth = placed?.width?.roundToInt() ?: 0
        val maxLeft = (root.width - placedWidth).coerceAtLeast(0)
        val shiftedLeft = ((placed?.left?.roundToInt() ?: 0) + horizontalShift).coerceIn(0, maxLeft)
        val placedRect = AodSurfaceRect(
            shiftedLeft,
            placed?.top?.roundToInt() ?: 0,
            shiftedLeft + placedWidth,
            placed?.bottom?.roundToInt() ?: 0
        )
        // 横屏(非全屏):交换画布 rect 宽高,使横持视角下的画布呈横宽形(否则旋转 90° 后
        // 用户看到的画布仍沿竖屏放置的「宽>高」变成「高>宽」,长宽没有交换)。交换先于
        // 时钟避让,让交换后更高的 rect 仍能被整体下推出时钟区;全屏横屏已是整屏画布,无需交换。
        val landscapeRectSwap =
            aodRotateWithDevice &&
                currentRotationStep != AodOrientationStep.PORTRAIT &&
                !fullscreenPlacement
        val orientedRect = if (landscapeRectSwap) {
            swapAodSurfaceRectForLandscape(placedRect, root.width, root.height)
        } else {
            placedRect
        }
        // 自定义位置由用户通过 verticalBias 主动设定(全屏画布)。此模式下歌词应无视系统时钟的
        // 下移,固定在用户选择的位置,不做硬避让("自定义位置"即用户已按自身喜好摆放)。
        val rect = if (profile.anchor == "custom_vertical_bias") {
            orientedRect
        } else {
            avoidStockClockOverlap(
                orientedRect,
                physicalClockBounds,
                margin,
                root.height
            )
        }
        if (rect.width <= 0 || rect.height <= 0) {
            failClosedLayout(
                directSurface,
                "invalid-rect=$rect stock=$effectiveClockTop..$effectiveClockBottom " +
                    "zone=$layoutZone managed=$sceneZone placed=$placed"
            )
            return false
        }
        if (lastLayoutBlockTrace != null) {
            HookLogger.i(
                TAG,
                "Layout ready after=$lastLayoutBlockTrace rect=$rect zone=$layoutZone"
            )
            lastLayoutBlockTrace = null
        }
        directSurface.measure(
            View.MeasureSpec.makeMeasureSpec(rect.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(rect.height, View.MeasureSpec.EXACTLY)
        )
        directSurface.layout(rect.left, rect.top, rect.right, rect.bottom)
        val placedTrace = "rect=${rect.left}..${rect.bottom} " +
            "stock=$effectiveClockTop..$effectiveClockBottom zone=$layoutZone " +
            "rot=$currentRotationStep fs=$fullscreenPlacement " +
            "w=${rect.width} h=${rect.height} mhf=${layoutProfile.maxHeightFraction}"
        if (placedTrace != lastPlacedTrace) {
            lastPlacedTrace = placedTrace
            HookLogger.i(TAG, "Lyric surface placed $placedTrace")
        }
        if (stockMotionRevealPending) {
            stockMotionRevealPending = false
            directSurface.alpha = 0f
            startStockMotionAlphaAnimation(
                targetAlpha = 1f,
                durationMs = STOCK_MOTION_FADE_IN_MS,
                completesTransition = true
            )
        }
        if (!handoffActive && !initialRevealActive && !stockMotionTransitionActive) {
            directSurface.alpha = 1f
        }
        val snapshot = latestSnapshot
        val visible = snapshot != null && canRenderAod(snapshot)
        directSurface.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && !snapshot.metadata.startsWith("AOD DEMO") &&
            lyricCanvas?.visibility != View.VISIBLE
        ) {
            lyricCanvas?.setContent(snapshot.toAodCanvasContent(effectiveAodProfile()))
            lyricCanvas?.visibility = View.VISIBLE
        }
        if (visible && initialRevealPending && !handoffActive) {
            startInitialReveal(
                directSurface,
                effectiveAodProfile().transition.durationMs.toLong()
            )
        }
        environment = environment.copy(
            rootWidth = root.width,
            rootHeight = root.height,
            stockBottom = effectiveClockBottom
        )
        updateLifetimeGuard()
        if (visible) LinkageTransitionCoordinator.onSurfaceReady(LyricSurfaceKind.AOD)
        return true
    }

    /**
     * Guards against the stock AOD clock drifting over the lyric surface. MIUI translates the
     * clock to a rotating burn-in position roughly 10s after AOD entry; when the follow-up
     * chain misses that move the lyric rect ends up underneath the clock. If the rect overlaps
     * the physically measured clock bounds, relocate it below the clock when there is room.
     */
    private fun avoidStockClockOverlap(
        rect: AodSurfaceRect,
        physicalClockBounds: AodRenderedClockBounds?,
        margin: Int,
        rootHeight: Int
    ): AodSurfaceRect {
        val clock = physicalClockBounds ?: return rect
        if (clock.height <= 0) return rect
        val overlaps = rect.top < clock.bottom && rect.bottom > clock.top
        if (!overlaps) return rect
        val belowTop = clock.bottom + margin
        val height = rect.height
        if (belowTop + height > rootHeight - margin) return rect
        HookLogger.i(
            TAG,
            "Stock clock overlap avoided: clock=${clock.top}..${clock.bottom} " +
                "lyric=${rect.top}..${rect.bottom} -> below@$belowTop"
        )
        return AodSurfaceRect(rect.left, belowTop, rect.right, belowTop + height)
    }

    private fun renderedStockClockBounds(
        root: ViewGroup,
        burnInContainer: ViewGroup,
        previous: AodRenderedClockBounds?
    ): AodRenderedClockBounds? {
        root.getLocationInWindow(renderedClockRootLocation)
        renderedClockUnion.setEmpty()
        var found = false
        for (index in 0 until burnInContainer.childCount) {
            found = collectRenderedClockBounds(
                burnInContainer.getChildAt(index),
                burnInContainer.alpha,
                root
            ) || found
        }
        if (!found || renderedClockUnion.bottom <= renderedClockUnion.top) return null
        return previous?.takeIf {
            it.top == renderedClockUnion.top && it.bottom == renderedClockUnion.bottom
        } ?: AodRenderedClockBounds(renderedClockUnion.top, renderedClockUnion.bottom)
    }

    private fun collectRenderedClockBounds(
        view: View,
        inheritedAlpha: Float,
        root: ViewGroup
    ): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        val effectiveAlpha = inheritedAlpha * view.alpha
        if (effectiveAlpha <= MIN_RENDERED_CLOCK_ALPHA) return false
        if (view is ViewGroup && view.childCount > 0) {
            var childRendered = false
            for (index in 0 until view.childCount) {
                childRendered = collectRenderedClockBounds(
                    view.getChildAt(index),
                    effectiveAlpha,
                    root
                ) || childRendered
            }
            if (childRendered) return true
            if (view.background == null && view.willNotDraw()) return false
        }
        if (!view.getGlobalVisibleRect(renderedClockScratch) ||
            renderedClockScratch.height() <= 0
        ) return false
        renderedClockScratch.offset(
            -renderedClockRootLocation[0],
            -renderedClockRootLocation[1]
        )
        if (!renderedClockScratch.intersect(0, 0, root.width, root.height)) return false
        if (renderedClockUnion.isEmpty) {
            renderedClockUnion.set(renderedClockScratch)
        } else {
            renderedClockUnion.union(renderedClockScratch)
        }
        return true
    }

    private fun traceLayoutBlock(reason: String) {
        if (!HookLogger.traceEnabled) return
        if (lastLayoutBlockTrace == reason) return
        lastLayoutBlockTrace = reason
        HookLogger.i(TAG, "Layout blocked reason=$reason snapshot=${latestSnapshot?.revision}")
    }

    private fun failClosedLayout(directSurface: View, reason: String) {
        traceLayoutBlock(reason)
        directSurface.visibility = View.GONE
        lyricCanvas?.stop()
        lyricCanvas?.visibility = View.GONE
        spicyAnimationView?.stop()
        spicyAnimationView?.visibility = View.GONE
        lastRenderContent = null
        updateLifetimeGuard()
    }

    private fun fadeForStockMotion() {
        if (surface == null) return
        stockMotionTransitionActive = true
        startStockMotionAlphaAnimation(
            targetAlpha = 0f,
            durationMs = STOCK_MOTION_FADE_OUT_MS,
            completesTransition = false
        )
    }

    private fun startStockMotionAlphaAnimation(
        targetAlpha: Float,
        durationMs: Long,
        completesTransition: Boolean
    ) {
        val directSurface = surface ?: return
        mainHandler.removeCallbacks(stockMotionAlphaFrame)
        stockMotionAlphaFrom = directSurface.alpha
        stockMotionAlphaTo = targetAlpha.coerceIn(0f, 1f)
        stockMotionAlphaStartedAt = SystemClock.elapsedRealtime()
        stockMotionAlphaDurationMs = durationMs.coerceAtLeast(1L)
        stockMotionAlphaCompletesTransition = completesTransition
        stockMotionAlphaAnimationActive = true
        mainHandler.post(stockMotionAlphaFrame)
    }

    private fun cancelStockMotionTransition(resetAlpha: Boolean) {
        mainHandler.removeCallbacks(stockMotionAlphaFrame)
        stockMotionTransitionActive = false
        stockMotionAlphaAnimationActive = false
        stockMotionAlphaCompletesTransition = false
        stockMotionAlphaStartedAt = 0L
        stockMotionAlphaDurationMs = 0L
        if (resetAlpha) surface?.alpha = 1f
    }

    override fun transitionRectInWindow(): TransitionRect? =
        if (awaitingInitialManagedLinkageGeometry()) null else transitionRectInWindow(surface)

    override fun presentationRectInWindow(): TransitionRect? =
        presentationRectInWindow(surface)

    override fun setSceneRole(role: LinkageSceneRole) {
        if (sceneRole == role) return
        sceneRole = role
        lyricCanvas?.setSceneActive(isSceneActive())
        if (!isSceneActive()) {
            pendingStockMotionUpdate = null
            stockMotionRevealPending = false
            mainHandler.removeCallbacks(stockMotionSettleTimeout)
            setStockWidgetControlActive(false)
            resetLinkageView(surface)
            hideSurfaceOnly(pulse = false)
        }
        updateLifetimeGuard()
    }

    override fun setHandoffActive(active: Boolean) {
        val wasActive = handoffActive
        handoffActive = active
        if (active) {
            initialRevealPending = false
            finishInitialReveal()
        }
        lyricCanvas?.setHandoffActive(active)
        if (wasActive && !active && isSceneActive()) schedulePostHandoffDiagnostics()
    }

    override fun animateFrom(
        source: TransitionRect?,
        fadeIn: Boolean,
        preserveAlpha: Boolean,
        durationMs: Long,
        token: Long,
        onComplete: (Long) -> Unit
    ) {
        val preserveStockLinkageAlpha = AodPositionHook.isLinkageMode()
        animateLinkageView(
            surface,
            source,
            fadeIn,
            preserveAlpha || preserveStockLinkageAlpha,
            durationMs,
            token,
            onComplete
        )
    }

    override fun fadeOut(durationMs: Long) {
        fadeOutLinkageView(surface, durationMs)
    }

    override fun resetTransition() {
        transitionFailedHidden = false
        resetLinkageView(surface)
    }

    override fun hideForFailedTransition() {
        transitionFailedHidden = true
        hideSurfaceOnly()
    }

    override fun applyTransitionSnapshot(snapshot: LyricSnapshot) {
        onLyricSnapshot(snapshot)
    }

    private fun aodProfile() = customization?.profiles?.get(SceneCompiler.SURFACE_AOD)

    private fun currentAodProfile(): CompiledSurfaceProfile =
        aodProfile() ?: DEFAULT_AOD_PROFILE

    private fun effectiveAodProfile(): CompiledSurfaceProfile =
        runtimeProfile ?: currentAodProfile()

    private fun canRenderAod(snapshot: LyricSnapshot): Boolean =
        XiaomiCapabilityResolver.hasCapability(XiaomiCapability.AOD_SURFACE) &&
            shouldRenderAodSnapshot(
                sceneActive = isSceneActive(),
                snapshotVisible = snapshot.visible,
                spotifyAuthorized = snapshot.isAuthorizedForPresentation(),
                featureEnabled = snapshot.aodEnabled,
                profileEnabled = aodProfile()?.enabled != false,
                transitionFailed = transitionFailedHidden
            )

    private fun requestGeometryUpdate() {
        enqueueGeometryUpdate(
            AodPositionUpdate(
                attachmentGeneration,
                environment.burnInTranslationX,
                environment.burnInTranslationY,
                environment.safeBottom,
                controlledClockTop,
                controlledClockBottom,
                controlledLyricTopSafe,
                sceneZone
            )
        )
    }

    private fun observeDisplayState(root: ViewGroup) {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = root.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        observedDisplayId = root.display?.displayId ?: -1
        lastObservedDisplayState = root.display?.state ?: -1
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        HookLogger.i(
            TAG,
            "AOD root display observer registered displayId=$observedDisplayId " +
                "state=$lastObservedDisplayState"
        )
        LinkageTransitionCoordinator.onAodDisplayState(lastObservedDisplayState)
        AodPowerCoordinator.onAodDisplayState(lastObservedDisplayState)
    }

    private fun enqueueGeometryUpdate(
        translationX: Float,
        translationY: Float,
        safeBottom: Int?
    ) = enqueueGeometryUpdate(
        AodPositionUpdate(
            attachmentGeneration,
            translationX,
            translationY,
            safeBottom,
            controlledClockTop,
            controlledClockBottom,
            controlledLyricTopSafe,
            sceneZone
        )
    )

    private fun enqueueGeometryUpdate(update: AodPositionUpdate) {
        if (surface == null || rootRef.get() == null) return
        val shouldSchedule = positionUpdates.offer(update)
        if (shouldSchedule) mainHandler.post(geometryUpdate)
    }

    private fun findBurnInContainer(root: ViewGroup): ViewGroup? {
        // Walk the class hierarchy: the field may be declared on AODView or a superclass,
        // and HyperOS may have renamed it. Try the canonical name first. HyperOS 3 declares
        // the field as plain android.view.View — the runtime instance is still the clock
        // container, so accept any ViewGroup (the surface attaches to root.overlay; the
        // container is only measured and observed).
        var klass: Class<*>? = root.javaClass
        while (klass != null && klass != Any::class.java) {
            runCatching {
                klass!!.getDeclaredField("mTableModeContainer").apply { isAccessible = true }
                    .get(root) as? ViewGroup
            }.getOrNull()?.let { return it }
            klass = klass!!.superclass
        }
        // Type-based fallback: find the first ViewGroup-typed declared field that holds
        // a non-null value. Catches HyperOS renames where the type is preserved.
        klass = root.javaClass
        while (klass != null && klass != Any::class.java) {
            runCatching {
                for (field in klass!!.declaredFields) {
                    if (!ViewGroup::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val value = field.get(root) as? ViewGroup
                    if (value != null) {
                        HookLogger.i(
                            TAG,
                            "Burn-in container resolved via fallback field: ${field.name}"
                        )
                        return value
                    }
                }
            }
            klass = klass!!.superclass
        }
        // Last resort: AODView typically extends FrameLayout. Use root itself so the
        // surface can still render; clock geometry falls back to measured bounds.
        if (root is FrameLayout) {
            HookLogger.i(TAG, "Burn-in container resolved via root fallback")
            return root
        }
        return null
    }

    private fun isSceneActive(): Boolean = sceneRole != LinkageSceneRole.INACTIVE

    private fun isBrightClockMorphPhase(root: ViewGroup): Boolean {
        val active = shouldUseBrightClockMorphGeometry(
            linkageMode = AodPositionHook.isLinkageMode(),
            morphingToAod = SystemUiClockMorphHook.isMorphingToAod(),
            linkageAwaitingDim = LinkageTransitionCoordinator.isAwaitingAodDimOwnership(),
            displayState = root.display?.state ?: -1
        )
        if (active != lastBrightClockMorphPhase) {
            lastBrightClockMorphPhase = active
            HookLogger.i(
                TAG,
                "Bright clock morph phase active=$active display=${root.display?.state ?: -1} " +
                    "physical=${SystemUiClockMorphHook.isMorphingToAod()} " +
                    "linkage=${LinkageTransitionCoordinator.isAwaitingAodDimOwnership()}"
            )
        }
        return active
    }

    private fun awaitingInitialManagedLinkageGeometry(): Boolean =
        stockWidgetControlActive &&
            AodPositionHook.isLinkageMode() &&
            (sceneZone == AodSceneZone.STOCK ||
                controlledClockTop == null || controlledClockBottom == null)

    private fun isSurfaceRenderActive(): Boolean {
        val directSurface = surface ?: return false
        if (!directSurface.isAttachedToWindow || directSurface.visibility != View.VISIBLE) return false
        if (lyricCanvas?.visibility != View.VISIBLE && spicyAnimationView?.visibility != View.VISIBLE) {
            return false
        }
        // Xiaomi can hide the whole AODView by ancestor alpha while our child stays VISIBLE.
        // Keep linkage handoff exception: transition alpha is intentionally zero during morph.
        // 只有 alpha 乘积恰为 0 才算隐藏;doze 期间的正常压暗/淡出(0 < a < 1)仍需
        // draw-wake 脉冲,否则 AodPositionHook 应用的时钟位移不会被合成渲染,
        // 表现为 AOD 位置固定失效(0.3.83 回归)。
        return handoffActive || effectiveSurfaceAlpha(directSurface) > 0f
    }

    private fun effectiveSurfaceAlpha(view: View): Float {
        var value = view.alpha * view.transitionAlpha
        var ancestor = view.parent as? View
        var depth = 0
        while (ancestor != null && depth++ < MAX_ALPHA_CHAIN_DEPTH) {
            value *= ancestor.alpha * ancestor.transitionAlpha
            if (value == 0f) return value
            ancestor = ancestor.parent as? View
        }
        return value
    }

    private fun surfaceAlphaChain(view: View): String {
        val parts = ArrayList<String>(MAX_ALPHA_CHAIN_DEPTH + 1)
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < MAX_ALPHA_CHAIN_DEPTH) {
            parts += "${current.javaClass.simpleName}:${current.visibility}:${current.alpha}/${current.transitionAlpha}"
            current = current.parent as? View
        }
        if (current != null) parts += "…"
        return parts.joinToString(">")
    }

    private fun requestWakeIfAllowed(root: ViewGroup, directSurface: View, wakeRequired: Boolean) {
        if (!shouldRequestAodWake(
                attached = rootRef.get() === root,
                sceneActive = isSceneActive(),
                effectivelyVisible = isSurfaceRenderActive()
            )
        ) return
        if (wakeRequired) wakeAodSurface(root, directSurface) else pulseDrawWakeLock(root)
    }

    private fun pulseDrawWakeLock(root: ViewGroup) {
        val wakeLock = readHierarchyField(root, "mWakeLock")
        if (wakeLock == null) {
            reportDrawWakePulse(AodDrawWakePulseResult.MISSING_WAKE_LOCK, "")
            return
        }
        val runtimeClass = wakeLock.javaClass.name
        val setMaximum = runCatching {
            wakeLock.javaClass.getMethod("setMaxAcquireTime", Long::class.javaPrimitiveType)
        }.getOrNull()
        val acquire = runCatching {
            wakeLock.javaClass.getMethod("acquire", String::class.java)
        }.getOrNull()
        if (setMaximum == null || acquire == null) {
            reportDrawWakePulse(AodDrawWakePulseResult.MISSING_METHOD, runtimeClass)
            return
        }
        try {
            setMaximum.invoke(wakeLock, DRAW_WAKE_LOCK_MS)
            acquire.invoke(wakeLock, "HyperGlowUpdate")
            reportDrawWakePulse(AodDrawWakePulseResult.SUCCESS, runtimeClass)
        } catch (error: Exception) {
            reportDrawWakePulse(AodDrawWakePulseResult.INVOCATION_FAILED, runtimeClass, error)
        }
    }

    private fun reportDrawWakePulse(
        result: AodDrawWakePulseResult,
        runtimeClass: String,
        error: Exception? = null
    ) {
        if (!shouldLogDrawWakePulseResult(lastDrawWakePulseResult, result) &&
            runtimeClass == lastDrawWakeRuntimeClass
        ) return
        lastDrawWakePulseResult = result
        lastDrawWakeRuntimeClass = runtimeClass
        val message = "Draw wake pulse result=${result.name.lowercase()} " +
            "class=${runtimeClass.ifEmpty { "none" }}"
        if (result == AodDrawWakePulseResult.SUCCESS) HookLogger.i(TAG, message)
        else HookLogger.w(TAG, message, error)
    }

    private fun schedulePostHandoffDiagnostics() {
        postHandoffDiagnosticGeneration++
        postHandoffEarlyGeneration = postHandoffDiagnosticGeneration
        postHandoffLateGeneration = postHandoffDiagnosticGeneration
        mainHandler.removeCallbacks(postHandoffDiagnosticEarly)
        mainHandler.removeCallbacks(postHandoffDiagnosticLate)
        mainHandler.postDelayed(postHandoffDiagnosticEarly, POST_HANDOFF_EARLY_MS)
        mainHandler.postDelayed(postHandoffDiagnosticLate, POST_HANDOFF_LATE_MS)
    }

    private fun cancelPostHandoffDiagnostics() {
        postHandoffDiagnosticGeneration++
        postHandoffEarlyGeneration = -1L
        postHandoffLateGeneration = -1L
        mainHandler.removeCallbacks(postHandoffDiagnosticEarly)
        mainHandler.removeCallbacks(postHandoffDiagnosticLate)
    }

    /**
     * handoff(AOD→锁屏联动)结束瞬间是竞态高发点:锁屏侧接管、小米收表面、draw wake
     * 续期三件事在此交汇。+1s/+7s 两次快照把可见性、alpha 链、几何与 wake 结局一行
     * 打齐;generation 匹配保证只记录最近一次 handoff 的结论。
     */
    private fun logPostHandoffSurfaceState(label: String, generation: Long) {
        if (!HookLogger.traceEnabled || generation != postHandoffDiagnosticGeneration) return
        val root = rootRef.get()
        val directSurface = surface
        val canvas = lyricCanvas
        HookLogger.i(
            TAG,
            "Post-handoff $label role=$sceneRole handoff=$handoffActive " +
                "display=${root?.display?.state ?: -1} " +
                "root=${root?.width}x${root?.height} attached=${directSurface?.isAttachedToWindow} " +
                "surface=${directSurface?.visibility} alpha=${directSurface?.alpha}/" +
                "${directSurface?.transitionAlpha} rect=${directSurface?.left},${directSurface?.top}.." +
                "${directSurface?.right},${directSurface?.bottom} scale=${directSurface?.scaleX}/" +
                "${directSurface?.scaleY} translation=${directSurface?.translationX}/" +
                "${directSurface?.translationY} canvas=${canvas?.visibility} " +
                "effAlpha=${directSurface?.let(::effectiveSurfaceAlpha)} " +
                "alphaChain=${directSurface?.let(::surfaceAlphaChain)} " +
                "render=${latestSnapshot?.let(::canRenderAod)} wake=$lastDrawWakePulseResult"
        )
    }

    private fun wakeAodSurface(root: ViewGroup, directSurface: View) {
        if (!isSceneActive() || surface !== directSurface) return
        directSurface.visibility = View.VISIBLE
        if (!handoffActive && !initialRevealActive) directSurface.alpha = 1f
        directSurface.invalidate()
        root.invalidate()
        pulseDrawWakeLock(root)
        HookLogger.i(TAG, "AOD wake signal applied")
    }

    private const val DRAW_WAKE_LOCK_MS = 5_500L
    private const val DRAW_WAKE_RENEW_INTERVAL_MS = DRAW_WAKE_LOCK_MS / 2L
    /** 渲染停摆看门狗自检周期:足够频繁以在 stale 窗口内恢复,又不会喧宾夺主。 */
    private const val RENDER_STALL_WATCHDOG_INTERVAL_MS = 5_000L
    private const val POST_HANDOFF_EARLY_MS = 1_000L
    private const val POST_HANDOFF_LATE_MS = 7_000L
    /** 行级时间轴内容播放中超过该时长没有 onDraw 即视为画布停摆。 */
    private const val RENDER_STALL_THRESHOLD_MS = 8_000L
    private const val AOD_ANIMATION_FRAME_MS = 16L
    private const val SURFACE_MARGIN_DP = 12f
    private const val MIN_LYRIC_HEIGHT_DP = 96f
    private const val STOCK_MOTION_SETTLE_TIMEOUT_MS = 1_500L
    private const val STOCK_MOTION_FADE_OUT_MS = 150L
    private const val STOCK_MOTION_FADE_IN_MS = 180L
    private const val MIN_RENDERED_CLOCK_ALPHA = 0.02f
    private const val MAX_ALPHA_CHAIN_DEPTH = 6
    private const val MANAGED_BURN_IN_RETRY_MS = 1_000L
    private const val MAX_MANAGED_POSITION_RETRIES = 5
    private val DEFAULT_AOD_PROFILE = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        .profiles.getValue(SceneCompiler.SURFACE_AOD)
}
