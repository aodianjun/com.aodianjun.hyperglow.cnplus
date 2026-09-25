package com.eza.hyperglow.root.lockscreen

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.aod.AodLyricCanvasView
import com.eza.hyperglow.root.aod.AodCanvasVerticalAlignment
import com.eza.hyperglow.root.aod.metadataWidgetHeightDp
import com.eza.hyperglow.root.aod.toAodCanvasContent
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.projection.LyricKeepAliveSignal
import com.eza.hyperglow.root.projection.LYRIC_SNAPSHOT_FRESH_MS
import com.eza.hyperglow.root.projection.LyricRenderContent
import com.eza.hyperglow.root.projection.LyricRetentionAnchor
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.projection.SystemUiLyricSubscriber
import com.eza.hyperglow.root.projection.nextLyricRetentionAnchor
import com.eza.hyperglow.root.projection.pauseLingerRemainingMs
import com.eza.hyperglow.root.aod.AodSurfaceController
import com.eza.hyperglow.root.transition.LinkageSceneRole
import com.eza.hyperglow.root.transition.LinkageSurface
import com.eza.hyperglow.root.transition.LinkageTransitionCoordinator
import com.eza.hyperglow.root.transition.TransitionRect
import com.eza.hyperglow.root.transition.animateLinkageView
import com.eza.hyperglow.root.transition.fadeOutLinkageView
import com.eza.hyperglow.root.transition.presentationRectInWindow
import com.eza.hyperglow.root.transition.resetLinkageView
import com.eza.hyperglow.root.transition.transitionRectInWindow
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.WidgetMeasurement
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.math.roundToInt

internal const val MIN_VISIBLE_ALPHA = 0.01f

private const val MAX_NOTIFICATION_TRACE_CHILDREN = 6

private const val VISIBILITY_DIAGNOSTIC_INTERVAL_MS = 2_000L

private const val NOTIFICATION_TRACE_INTERVAL_MS = 1_000L

internal const val LOCKSCREEN_INSERTION_INDEX = 0

internal object LockscreenSurfaceController : SystemUiLyricSubscriber, LinkageSurface {
    private const val TAG = "LockscreenSurface"
    private const val SURFACE_TAG = "hyper_aod_lyrics_lockscreen_surface"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachmentGeneration = 0L
    private var controllerRef = WeakReference<Any>(null)
    private var rootRef = WeakReference<ViewGroup>(null)
    private var hostRef = WeakReference<FrameLayout>(null)
    private var surface: FrameLayout? = null
    private var sceneCard: FrameLayout? = null
    private var cardBackgroundView: AdaptiveLyricCardBackgroundView? = null
    private var lyricCanvas: AodLyricCanvasView? = null
    private var progressView: MediaProgressView? = null
    private var latestSnapshot: LyricSnapshot? = null
    private var lastVisibleSnapshot: LyricSnapshot? = null
    private var retainedMediaSnapshot: LyricSnapshot? = null
    private var retentionAnchor: LyricRetentionAnchor? = null
    private val pauseLingerExpiry = object : Runnable {
        override fun run() {
            val retained = retainedMediaSnapshot ?: return
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
            requestRefresh()
        }
    }
    private var stockMediaPlayerObserved = false
    private var customization: CompiledCustomization? = null
    private var runtimeProfile: CompiledSurfaceProfile? = null
    private var lastRenderedProfile: CompiledSurfaceProfile? = null
    private var lastRenderContent: LyricRenderContent? = null
    private var layoutDiagnostic = "not-computed"
    private var lastVisibilityDiagnostic: String? = null
    private var lastVisibilityStateKey: String? = null
    private var lastVisibilityDiagnosticAt = 0L
    private var lastNotificationBounds: LockscreenNotificationBounds? = null
    private var lastNotificationTrace: String? = null
    private var lastNotificationTraceAt = 0L
    private var lastCardBackgroundStyle: String? = null
    private var lastCardAlpha: Int = -1
    private var lastCardColor: String? = null
    private var sceneRole = LinkageSceneRole.INACTIVE
    private var handoffActive = false
    private var transitionFailedHidden = false
    private val reverseAnchorGate = LockscreenAnchorStabilityGate(
        REVERSE_ANCHOR_MINIMUM_DELAY_MS,
        REVERSE_ANCHOR_QUIET_PERIOD_MS,
        REVERSE_ANCHOR_FALLBACK_DEADLINE_MS
    )
    private val settledRectTracker = LockscreenSettledRectTracker(REVERSE_ANCHOR_QUIET_PERIOD_MS)
    private var reverseAnchorGateActive = false
    private var reverseExpectedRect: LockscreenSceneRect? = null
    private var freshnessGeneration = -1L
    private val observedViews = ArrayList<View>(4)
    private val refreshGate = LatestFrameRequestGate()
    private var refreshPostOwner = WeakReference<View>(null)
    private var notificationMotionView = WeakReference<ViewGroup>(null)
    private val notificationMotionTracker = LockscreenMotionChangeTracker()
    private val motionClipRect = Rect()
    private val notificationMotionMethods = object : ClassValue<NotificationMotionMethods>() {
        override fun computeValue(type: Class<*>): NotificationMotionMethods =
            NotificationMotionMethods(
                findNotificationContentClassName(type),
                publicNoArgMethod(type, "getActualHeight"),
                publicNoArgMethod(type, "getClipTopAmount"),
                publicNoArgMethod(type, "getClipBottomAmount")
            )
    }
    private val refreshFrame = Runnable {
        refreshPostOwner.clear()
        if (refreshGate.consume()) refreshCurrent()
    }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        requestRefresh()
    }
    private val freshnessExpiry = Runnable {
        if (freshnessGeneration == attachmentGeneration) requestRefresh()
    }
    private val notificationPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        val notification = notificationMotionView.get()
        if (notification != null && shouldSampleNotificationMotion()) {
            val fingerprint = notificationMotionFingerprint(notification)
            if (notificationMotionTracker.update(fingerprint)) requestRefresh()
        }
        true
    }
    private val reverseAnchorProbe = object : Runnable {
        override fun run() {
            if (!reverseAnchorGateActive) return
            refreshCurrent()
            if (reverseAnchorGateActive) {
                mainHandler.postDelayed(this, REVERSE_ANCHOR_PROBE_MS)
            }
        }
    }

    override val surfaceKind = LyricSurfaceKind.LOCKSCREEN
    override val linkageSurfaceKind = LyricSurfaceKind.LOCKSCREEN

    fun attach(controller: Any, root: ViewGroup?) {
        mainHandler.post {
            val candidateRoot = root ?: readField(controller, "keyguardRootView") as? ViewGroup
                ?: return@post
            XiaomiCapabilityResolver.observeContext(candidateRoot.context)
            val supported = XiaomiCapabilityResolver.hasCapability(XiaomiCapability.LOCKSCREEN_HOST) &&
                XiaomiCapabilityResolver.hasCapability(XiaomiCapability.LOCKSCREEN_GEOMETRY)
            if (!supported) {
                if (controllerRef.get() === controller) detachCurrent()
                return@post
            }
            val candidateHost = findHost(controller, candidateRoot) ?: return@post
            if (shouldReuseLockscreenHost(hostRef.get(), candidateHost, surface != null)) {
                controllerRef = WeakReference(controller)
                rootRef = WeakReference(candidateRoot)
                requestRefresh()
                return@post
            }
            detachCurrent()
            attachmentGeneration++
            controllerRef = WeakReference(controller)
            rootRef = WeakReference(candidateRoot)
            hostRef = WeakReference(candidateHost)
            candidateHost.findViewWithTag<View>(SURFACE_TAG)?.let(candidateHost::removeView)
            val directSurface = buildSurface(candidateHost)
            surface = directSurface
            candidateHost.addView(
                directSurface,
                LOCKSCREEN_INSERTION_INDEX,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            LinkageTransitionCoordinator.registerSurface(this)
            observeLayouts(controller, candidateRoot, candidateHost)
            val generation = attachmentGeneration
            candidateHost.post {
                if (generation == attachmentGeneration && hostRef.get() === candidateHost) {
                    refreshCurrent()
                }
            }
            SystemUiLyricProjectionRuntime.projection.attach(this, candidateRoot.context)
            SystemUiLyricProjectionRuntime.projection.reportCapabilities()
            HookLogger.i(TAG, "Lockscreen surface attached")
        }
    }

    fun detach(controller: Any?, root: ViewGroup? = null) {
        mainHandler.post {
            if (controller != null && controllerRef.get() !== controller) return@post
            if (root != null && rootRef.get() !== root) return@post
            detachCurrent()
        }
    }

    fun refresh(controller: Any?) {
        mainHandler.post {
            if (controller != null && controllerRef.get() !== controller) return@post
            requestRefresh()
        }
    }

    fun refreshNotificationState(controller: Any?) {
        mainHandler.post {
            if (controller != null && controllerRef.get() !== controller) return@post
            val present = hasStockMediaPlayer(controllerRef.get())
            if (present) {
                stockMediaPlayerObserved = true
            } else if (stockMediaPlayerObserved) {
                stockMediaPlayerObserved = false
                lastVisibleSnapshot = null
                retainedMediaSnapshot = null
                retentionAnchor = null
                cancelPauseLingerExpiry()
            }
            AodSurfaceController.onStockMediaPlayerPresenceChanged(present)
            requestRefresh()
        }
    }

    override fun onLyricSnapshot(snapshot: LyricSnapshot) {
        val resolvedSnapshot = LinkageTransitionCoordinator.resolveSnapshot(snapshot)
        latestSnapshot = resolvedSnapshot
        if (resolvedSnapshot.visible) {
            lastVisibleSnapshot = resolvedSnapshot
            retainedMediaSnapshot = null
            retentionAnchor = null
            scheduleFreshnessExpiry(resolvedSnapshot)
        } else {
            cancelFreshnessExpiry()
            if (lastVisibleSnapshot == null) {
                lastVisibleSnapshot = SystemUiLyricProjectionRuntime.projection
                    .cachedVisibleSnapshot()
            }
            val nowElapsedMs = SystemClock.elapsedRealtime()
            retentionAnchor = nextLyricRetentionAnchor(
                resolvedSnapshot, retentionAnchor, nowElapsedMs
            )
            retainedMediaSnapshot = retainedLockscreenSnapshotAfterUpdate(
                resolvedSnapshot,
                lastVisibleSnapshot,
                retainedMediaSnapshot,
                retentionAnchor,
                nowElapsedMs,
                customization?.pauseLingerMs ?: 5_000L,
                pauseRetentionEnabled = customization?.pauseShowContent ?: false
            )
            if (retainedMediaSnapshot == null && !resolvedSnapshot.playbackActive) {
                lastVisibleSnapshot = null
            }
        }
        schedulePauseLingerExpiry(retainedMediaSnapshot)
        requestRefresh()
    }

    override fun onLyricKeepAlive(signal: LyricKeepAliveSignal) {
        latestSnapshot = latestSnapshot?.copy(
            updatedAtElapsedMs = signal.updatedAtElapsedMs,
            playbackActive = signal.playbackActive,
            pauseRetentionEligible = signal.pauseRetentionEligible
        )
        latestSnapshot?.let(::scheduleFreshnessExpiry)
        requestRefresh()
    }

    override fun onLyricProjectionDisconnected() {
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        retentionAnchor = null
        cancelPauseLingerExpiry()
        customization = null
        runtimeProfile = null
        lastRenderedProfile = null
        cancelFreshnessExpiry()
        hideSurface()
    }

    override fun onLyricProjectionStale() {
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        retentionAnchor = null
        cancelPauseLingerExpiry()
        cancelFreshnessExpiry()
        hideSurface()
    }

    override fun onCustomization(configuration: CompiledCustomization) {
        customization = configuration
        // 「暂停时显示歌曲信息、歌词」关闭(或驻留时长已过)时,丢弃暂停驻留快照并立即隐藏,
        // 与 AOD 侧同一语义:开关切换立即生效,不等下一条暂停边。
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
            cancelFreshnessExpiry()
            hideSurface()
            return
        }
        runtimeProfile = null
        lastRenderedProfile = null
        lastRenderContent = null
        latestSnapshot?.let(::onLyricSnapshot)
        schedulePauseLingerExpiry(retainedMediaSnapshot)
    }

    private fun requestRefresh() {
        if (!refreshGate.request()) return
        val owner = surface ?: hostRef.get()
        if (owner != null && owner.isAttachedToWindow) {
            refreshPostOwner = WeakReference(owner)
            owner.postOnAnimation(refreshFrame)
        } else {
            refreshPostOwner.clear()
            mainHandler.post(refreshFrame)
        }
    }

    private fun cancelPendingRefresh() {
        refreshPostOwner.get()?.removeCallbacks(refreshFrame)
        refreshPostOwner.clear()
        mainHandler.removeCallbacks(refreshFrame)
        refreshGate.cancel()
    }

    private fun schedulePauseLingerExpiry(retained: LyricSnapshot?) {
        cancelPauseLingerExpiry()
        retained ?: return
        val remaining = pauseLingerRemainingMs(
            retained.sampledAtElapsedMs,
            customization?.pauseLingerMs ?: 5_000L,
            SystemClock.elapsedRealtime()
        ) ?: run {
            retainedMediaSnapshot = null
            retentionAnchor = null
            lastVisibleSnapshot = null
            requestRefresh()
            return
        }
        if (remaining != Long.MAX_VALUE) mainHandler.postDelayed(pauseLingerExpiry, remaining)
    }

    private fun cancelPauseLingerExpiry() {
        mainHandler.removeCallbacks(pauseLingerExpiry)
    }

    private fun refreshCurrent() {
        val controller = controllerRef.get() ?: return
        val host = hostRef.get() ?: return
        val directSurface = surface ?: return
        val canvas = lyricCanvas ?: return
        val mediaPlayerPresent = isStockMediaPlayerVisible(controller)
        if (hasStockMediaPlayer(controller)) {
            stockMediaPlayerObserved = true
            AodSurfaceController.onStockMediaPlayerPresenceChanged(true)
        }
        val snapshot = resolveLockscreenMediaSnapshot(
            latestSnapshot,
            retainedMediaSnapshot,
            mediaPlayerPresent,
            transitionSourceActive = handoffActive &&
                sceneRole == LinkageSceneRole.TRANSITION_SOURCE
        )
        val wasVisible = directSurface.visibility == View.VISIBLE && canvas.visibility == View.VISIBLE
        val layoutResult = layoutCanvas(controller, host, canvas)
        val rect = layoutResult?.rect
        val supported = XiaomiCapabilityResolver.hasCapability(XiaomiCapability.LOCKSCREEN_HOST) &&
            XiaomiCapabilityResolver.hasCapability(XiaomiCapability.LOCKSCREEN_GEOMETRY)
        val eligibleSnapshot = snapshot?.takeIf {
            canRenderLockscreen(it, allowExpired = it === retainedMediaSnapshot)
        }
        val visibilityInputs = LockscreenVisibilityInputs(
            featureEnabled = true,
            supported = supported,
            defaultTheme = readBoolean(controller, "isDefaultTheme", false),
            primaryDisplay = host.display?.displayId == Display.DEFAULT_DISPLAY,
            keyguardShowing = readBoolean(controller, "keyguardVisibility", false),
            bouncerShowing = isSecurityOrEditorObscured(controller),
            freshSnapshot = eligibleSnapshot != null,
            usableArea = rect != null && rect.width >= minWidth(host) &&
                rect.height >= minHeight(host)
        )
        val baseVisible = eligibleSnapshot != null && shouldShowLockscreen(visibilityInputs)
        val anchorReady = !reverseAnchorGateActive ||
            (rect != null && reverseAnchorGate.observe(rect, SystemClock.elapsedRealtime()))
        val anchorPending = reverseAnchorGateActive && eligibleSnapshot != null && !anchorReady
        if (reverseAnchorGateActive && anchorReady) {
            val anchorMode = if (reverseExpectedRect != null) "expected" else "quiet-fallback"
            reverseAnchorGateActive = false
            mainHandler.removeCallbacks(reverseAnchorProbe)
            reverseAnchorGate.clear()
            reverseExpectedRect = null
            HookLogger.i(TAG, "Reverse anchor stable mode=$anchorMode rect=$rect")
        }
        val visible = baseVisible && anchorReady
        directSurface.keepScreenOn = shouldKeepLockscreenAwake(
            enabled = customization?.lockscreenKeepAwake == true,
            visible = visible,
            bouncerShowing = visibilityInputs.bouncerShowing,
            mediaPlayerPresent = mediaPlayerPresent,
            playbackSpeed = eligibleSnapshot?.speed ?: 0f
        )
        if (visible && rect != null && sceneRole == LinkageSceneRole.AUTHORITATIVE) {
            settledRectTracker.observe(rect, SystemClock.elapsedRealtime())
        }
        logVisibilityDiagnostic(visible, visibilityInputs)
        directSurface.visibility = when {
            visible -> View.VISIBLE
            anchorPending -> View.INVISIBLE
            else -> View.GONE
        }
        sceneCard?.visibility = when {
            visible -> View.VISIBLE
            anchorPending -> View.INVISIBLE
            else -> View.GONE
        }
        canvas.visibility = when {
            visible -> View.VISIBLE
            anchorPending -> View.INVISIBLE
            else -> View.GONE
        }
        val progressEnabled = layoutResult?.progressVisible == true
        val showProgress = visible && progressEnabled && eligibleSnapshot.durationMs > 0L
        progressView?.visibility = if (showProgress) View.VISIBLE else View.GONE
        cardBackgroundView?.invalidate()
        if (showProgress) {
            progressView?.setPlayback(
                eligibleSnapshot.durationMs,
                eligibleSnapshot.positionMs,
                eligibleSnapshot.sampledAtElapsedMs,
                eligibleSnapshot.speed
            )
        } else {
            progressView?.stop()
        }
        if (!visible) {
            canvas.stop()
            progressView?.stop()
        } else {
            val renderContent = eligibleSnapshot.renderContent()
            val renderProfile = checkNotNull(layoutResult) {
                "visible lockscreen implies a layout result"
            }.profile
            if (!wasVisible || renderContent != lastRenderContent ||
                renderProfile != lastRenderedProfile
            ) {
                canvas.setContent(eligibleSnapshot.toAodCanvasContent(renderProfile))
                lastRenderContent = renderContent
                lastRenderedProfile = renderProfile
            }
            if (!wasVisible && !handoffActive) {
                animateFrom(
                    source = null,
                    fadeIn = false,
                    preserveAlpha = true,
                    durationMs = renderProfile.transition.durationMs.toLong(),
                    token = -attachmentGeneration,
                    onComplete = {}
                )
                HookLogger.i(TAG, "Fallback lockscreen reveal animation started")
            }
        }
        if (visible) LinkageTransitionCoordinator.onSurfaceReady(LyricSurfaceKind.LOCKSCREEN)
    }

    private fun layoutCanvas(
        controller: Any,
        host: FrameLayout,
        canvas: AodLyricCanvasView
    ): LockscreenLayoutResult? {
        if (host.width <= 0 || host.height <= 0) {
            layoutDiagnostic = "host=${host.width}x${host.height}"
            return null
        }
        val density = host.resources.displayMetrics.density
        val margin = (TOP_MARGIN_DP * density).roundToInt()
        val notificationGap = (NOTIFICATION_GAP_DP * density).roundToInt()
        val clockBottom = resolveClockBottom(controller, host)
        val notificationBounds = resolveNotificationBounds(controller, host)
        val profile = currentLockscreenProfile()
        if (profile.collisionPolicy == "hide_scene" && notificationBounds != null) {
            layoutDiagnostic = "notification-hide-scene bounds=$notificationBounds"
            return null
        }
        val avoidsNotifications = profile.collisionPolicy == "avoid"
        val freeRegion = if (avoidsNotifications) {
            lockscreenCardRegionAfterNotifications(
                host.width,
                host.height,
                clockBottom,
                margin,
                notificationGap,
                (BOTTOM_RESERVE_DP * density).roundToInt(),
                notificationBounds
            )
        } else {
            largestLockscreenFreeRegion(
                host.width,
                host.height,
                clockBottom,
                margin,
                (BOTTOM_RESERVE_DP * density).roundToInt(),
                notificationBounds
            )
        }
        val metadataHeight = if (profile.metadataVisible &&
            profile.widgets.any { it.type == "metadata" }
        ) metadataWidgetHeightDp(profile.metadataSizePercent) * density else 0f
        val progressHeightWithGap = if (profile.widgets.any { it.type == "media_progress" }) {
            (PROGRESS_HEIGHT_DP + PROGRESS_GAP_DP) * density
        } else {
            0f
        }
        val fontScale = host.resources.displayMetrics.scaledDensity / density.coerceAtLeast(0.1f)
        val desiredHeight = minOf(
            host.height * profile.maxHeightFraction,
            estimatedLockscreenSceneHeight(profile, density, fontScale)
        )
        val measurements = profile.widgets.mapNotNull { widget ->
            when (widget.type) {
                "lyrics" -> WidgetMeasurement(
                    widget,
                    (desiredHeight - metadataHeight - progressHeightWithGap)
                        .coerceAtLeast(MIN_HEIGHT_DP * density)
                )
                "metadata" -> WidgetMeasurement(widget, metadataHeight)
                "media_progress" -> WidgetMeasurement(widget, progressHeightWithGap)
                else -> null
            }
        }
        val placementWidthFraction = if (profile.backgroundStyle == "card") {
            maxOf(profile.widthFraction, LOCKSCREEN_CARD_WIDTH_FRACTION)
        } else {
            profile.widthFraction
        }
        val placementProfile = if (avoidsNotifications) {
            profile.copy(
                anchor = "below_stock_clock",
                maxHeightFraction = 1f,
                widthFraction = placementWidthFraction
            )
        } else {
            profile.copy(widthFraction = placementWidthFraction)
        }
        val placement = PlacementEngine.resolve(
            placementProfile,
            PlacementEnvironment(
                safeCanvas = freeRegion,
                stockClockBottom = freeRegion.top,
                bottomReserveTop = freeRegion.bottom
            ),
            measurements,
            minimumLyricHeight = MIN_HEIGHT_DP * density
        )
        val placed = placement.contentRect ?: run {
            layoutDiagnostic =
                "no-placement host=${host.width}x${host.height} clock=$clockBottom " +
                "notification=${notificationBounds ?: "none"} free=$freeRegion"
            return null
        }
        val visibleTypes = placement.visibleWidgets.mapTo(HashSet()) { it.type }
        val renderProfile = profile.copy(
            widgets = profile.widgets.filter { it.type in visibleTypes },
            metadataVisible = profile.metadataVisible && "metadata" in visibleTypes
        )
        runtimeProfile = renderProfile
        progressView?.setPalette(renderProfile.palette)
        val notificationLeft = notificationBounds?.left ?: 0
        val notificationRight = notificationBounds?.right ?: 0
        val matchesNotificationWidth = renderProfile.backgroundStyle == "card" &&
            notificationRight - notificationLeft >= minWidth(host)
        val rect = LockscreenSceneRect(
            if (matchesNotificationWidth) notificationLeft else placed.left.roundToInt(),
            placed.top.roundToInt(),
            if (matchesNotificationWidth) notificationRight else placed.right.roundToInt(),
            placed.bottom.roundToInt()
        )
        if (rect.width <= 0 || rect.height <= 0) {
            layoutDiagnostic = "invalid-rect=$rect"
            return null
        }
        layoutDiagnostic =
            "rect=$rect clock=$clockBottom notification=${notificationBounds ?: "none"} " +
            "free=$freeRegion"
        val progressEnabled = "media_progress" in visibleTypes
        val progressHeight = if (progressEnabled) (PROGRESS_HEIGHT_DP * density).roundToInt() else 0
        val progressGap = if (progressEnabled) (PROGRESS_GAP_DP * density).roundToInt() else 0
        val cardEnabled = renderProfile.backgroundStyle == "card"
        val horizontalInset = if (cardEnabled) (CARD_HORIZONTAL_PADDING_DP * density).roundToInt() else 0
        val verticalInset = if (cardEnabled) (CARD_VERTICAL_PADDING_DP * density).roundToInt() else 0
        val contentWidth = (rect.width - horizontalInset * 2).coerceAtLeast(0)
        val lyricHeight = (rect.height - progressHeight - progressGap - verticalInset * 2)
            .coerceAtLeast(0)
        val card = sceneCard ?: return null
        applyCardBackground(
            style = renderProfile.backgroundStyle,
            alpha = renderProfile.cardAlpha,
            color = renderProfile.cardColor
        )
        applyFrameLayoutGeometry(card, rect.width, rect.height, rect.left, rect.top)
        applyFrameLayoutGeometry(canvas, contentWidth, lyricHeight, horizontalInset, verticalInset)
        progressView?.let { progress ->
            applyFrameLayoutGeometry(
                progress,
                contentWidth,
                progressHeight,
                horizontalInset,
                verticalInset + lyricHeight + progressGap
            )
        }
        return LockscreenLayoutResult(rect, renderProfile, progressEnabled)
    }

    private fun applyFrameLayoutGeometry(
        view: View,
        width: Int,
        height: Int,
        left: Int,
        top: Int
    ) {
        val current = view.layoutParams as? FrameLayout.LayoutParams
        if (current != null && !frameLayoutGeometryChanged(
                current.width,
                current.height,
                current.leftMargin,
                current.topMargin,
                width,
                height,
                left,
                top
            )
        ) return
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }
    }

    private fun resolveClockBottom(controller: Any, host: ViewGroup): Int {
        val injector = readField(controller, "keyguardClockInjector")
        val preferred = invokeNoArgInt(injector, "getClockBottom")
        val clockView = readField(injector, "keyguardClockView") as? View
        val direct = invokeNoArgInt(clockView, "getClockBottom")
        val root = rootRef.get() ?: host
        val clockContainer = root.findViewById<View>(
            root.resources.getIdentifier(
                "miui_keyguard_clock_container",
                "id",
                "com.android.systemui"
            )
        )
        val foregroundClockContainer = root.findViewById<View>(
            root.resources.getIdentifier(
                "miui_keyguard_foreground_clock_container",
                "id",
                "com.android.systemui"
            )
        )
        return preferredLockscreenClockBottom(
            host.height,
            preferred,
            listOf(
                direct,
                visibleBottomInHost(host, clockView),
                visibleBottomInHost(host, clockContainer),
                visibleBottomInHost(host, foregroundClockContainer)
            )
        )
    }

    private fun resolveNotificationBounds(
        controller: Any,
        host: ViewGroup
    ): LockscreenNotificationBounds? {
        val hasNotification = readBoolean(controller, "hasNotification", false)
        val notification = if (hasNotification) {
            readField(controller, "notificationStackScrollLayout") as? ViewGroup
        } else {
            null
        }
        val traceDue = HookLogger.traceEnabled &&
            SystemClock.elapsedRealtime() - lastNotificationTraceAt >=
            NOTIFICATION_TRACE_INTERVAL_MS
        val childTraces = if (traceDue) ArrayList<String>(4) else null
        val current = if (notification != null && notification.visibility != View.GONE) {
            val stackPosition = stableLayoutPositionInHost(host, notification)
            val linkageActive = sceneRole == LinkageSceneRole.TRANSITION_SOURCE ||
                sceneRole == LinkageSceneRole.TRANSITION_TARGET
            val candidates = if (stackPosition == null) emptyList() else buildList {
                for (index in 0 until notification.childCount) {
                    val child = notification.getChildAt(index)
                    val className = notificationContentClassName(child) ?: continue
                    if (childTraces != null && childTraces.size < MAX_NOTIFICATION_TRACE_CHILDREN) {
                        childTraces += describeNotificationChild(child, className, host)
                    }
                    if (!shouldIncludeLockscreenNotificationChild(
                            child.visibility,
                            child.alpha,
                            linkageActive
                        )
                    ) continue
                    val clip = child.clipBounds?.let {
                        LockscreenNotificationClipBounds(it.left, it.top, it.right, it.bottom)
                    }
                    lockscreenNotificationCandidateFromLayout(
                        className = className,
                        stackLeft = stackPosition.first - notification.scrollX,
                        stackTop = stackPosition.second - notification.scrollY,
                        childX = child.x,
                        childY = child.y,
                        layoutWidth = child.width,
                        layoutHeight = child.height,
                        actualHeight = invokePublicNoArgInt(child, "getActualHeight"),
                        clipTopAmount = invokePublicNoArgInt(child, "getClipTopAmount"),
                        clipBottomAmount = invokePublicNoArgInt(child, "getClipBottomAmount"),
                        clipBounds = clip
                    )?.let(::add)
                }
            }
            lockscreenNotificationBounds(candidates, host.height)
        } else {
            null
        }
        // Dead-band stabilize the freshly computed bounds against the last applied one so that
        // per-frame animation jitter (a few px) doesn't re-derive the lyric card's placement every
        // frame and make it "jump". Only a drift beyond the dead band is adopted as a real change.
        val deadBandPx = (NOTIFICATION_GEOMETRY_DEAD_BAND_DP * host.resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(1)
        val stabilizedCurrent = stabilizeNotificationBounds(
            current,
            lastNotificationBounds,
            deadBandPx
        )
        val geometry = resolveLockscreenNotificationGeometry(
            hasNotification,
            stabilizedCurrent,
            lastNotificationBounds,
            host.height
        )
        lastNotificationBounds = geometry.cachedBounds
        if (traceDue) {
            traceNotificationGeometry(
                controller,
                host,
                notification,
                hasNotification,
                current,
                geometry,
                childTraces.orEmpty()
            )
        }
        return geometry.effectiveBounds
    }

    private fun describeNotificationChild(
        child: View,
        className: String,
        host: ViewGroup
    ): String {
        val hostLocation = IntArray(2)
        host.getLocationInWindow(hostLocation)
        val visible = Rect()
        val hasGlobalRect = child.getGlobalVisibleRect(visible)
        val actualHeight = invokePublicNoArgInt(child, "getActualHeight")
        val intrinsicHeight = invokePublicNoArgInt(child, "getIntrinsicHeight")
        val clipTop = invokePublicNoArgInt(child, "getClipTopAmount")
        val clipBottom = invokePublicNoArgInt(child, "getClipBottomAmount")
        val animateHeight = (readHierarchyField(child, "mAnimateHeight") as? Number)?.toInt()
        val lockscreenHeight = (readHierarchyField(child, "mediaLockScreenHeight") as? Number)?.toInt()
        val global = if (hasGlobalRect) {
            "${visible.left - hostLocation[0]},${visible.top - hostLocation[1]}.." +
                "${visible.right - hostLocation[0]},${visible.bottom - hostLocation[1]}"
        } else {
            "none"
        }
        return "${className.substringAfterLast('.')} vis=${child.visibility}/${child.isShown} " +
            "alpha=${child.alpha}/${child.transitionAlpha} layout=${child.left},${child.top}.." +
            "${child.right},${child.bottom} y=${child.y} ty=${child.translationY} " +
            "height=${child.height}/${child.measuredHeight} actual=$actualHeight " +
            "intrinsic=$intrinsicHeight clip=$clipTop/$clipBottom animate=$animateHeight " +
            "lockHeight=$lockscreenHeight global=$global clipBounds=${child.clipBounds}"
    }

    private fun stableLayoutPositionInHost(host: View, target: View): Pair<Int, Int>? {
        fun positionFromRoot(view: View): Triple<View, Int, Int> {
            var current = view
            var x = 0
            var y = 0
            while (true) {
                x += current.left
                y += current.top
                val parent = current.parent as? View ?: return Triple(current, x, y)
                x -= parent.scrollX
                y -= parent.scrollY
                current = parent
            }
        }
        val hostPosition = positionFromRoot(host)
        val targetPosition = positionFromRoot(target)
        if (hostPosition.first !== targetPosition.first) return null
        return (targetPosition.second - hostPosition.second) to
            (targetPosition.third - hostPosition.third)
    }

    private fun traceNotificationGeometry(
        controller: Any,
        host: ViewGroup,
        notification: ViewGroup?,
        hasNotification: Boolean,
        current: LockscreenNotificationBounds?,
        geometry: LockscreenNotificationGeometry,
        childTraces: List<String>
    ) {
        val injector = readField(controller, "notificationStackScrollLayoutControllerInjector")
        val visibleCount = invokePublicNoArgInt(injector, "getVisibleNotificationCount")
        val stackTrace = notification?.let { stack ->
            val hostLocation = IntArray(2)
            val stackLocation = IntArray(2)
            host.getLocationInWindow(hostLocation)
            stack.getLocationInWindow(stackLocation)
            "stack=${stackLocation[0] - hostLocation[0]},${stackLocation[1] - hostLocation[1]} " +
                "size=${stack.width}x${stack.height} shown=${stack.isShown} " +
                "alpha=${stack.alpha}/${stack.transitionAlpha} ty=${stack.translationY} " +
                "padding=${readHierarchyField(stack, "mTopPadding")}/" +
                "${readHierarchyField(stack, "mIntrinsicPadding")} " +
                "scroll=${readHierarchyField(stack, "mOwnScrollY")} clip=${stack.clipBounds}"
        } ?: "stack=none"
        val trace = "Notification system has=$hasNotification visibleCount=$visibleCount $stackTrace " +
            "current=$current cached=${geometry.cachedBounds} effective=${geometry.effectiveBounds} " +
            "children=[${childTraces.joinToString("; ")}]"
        lastNotificationTraceAt = SystemClock.elapsedRealtime()
        if (trace == lastNotificationTrace) return
        lastNotificationTrace = trace
        HookLogger.i(TAG, trace)
    }

    private fun notificationContentClassName(view: View): String? =
        findNotificationContentClassName(view.javaClass)

    private fun findNotificationContentClassName(startType: Class<*>): String? {
        var type: Class<*>? = startType
        while (type != null) {
            if (isLockscreenNotificationContentClass(type.name)) return type.name
            type = type.superclass
        }
        return null
    }

    private fun logVisibilityDiagnostic(
        visible: Boolean,
        inputs: LockscreenVisibilityInputs
    ) {
        if (!HookLogger.traceEnabled) return
        val diagnostic =
            "visible=$visible eligible=${inputs.freshSnapshot} supported=${inputs.supported} " +
            "theme=${inputs.defaultTheme} display=${inputs.primaryDisplay} " +
            "keyguard=${inputs.keyguardShowing} bouncer=${inputs.bouncerShowing} " +
            "area=${inputs.usableArea} $layoutDiagnostic"
        if (diagnostic == lastVisibilityDiagnostic) return
        val stateKey = "$visible/${inputs.freshSnapshot}/${inputs.keyguardShowing}/" +
            "${inputs.bouncerShowing}/${inputs.usableArea}"
        val now = SystemClock.elapsedRealtime()
        if (stateKey == lastVisibilityStateKey &&
            now - lastVisibilityDiagnosticAt < VISIBILITY_DIAGNOSTIC_INTERVAL_MS
        ) return
        lastVisibilityDiagnostic = diagnostic
        lastVisibilityStateKey = stateKey
        lastVisibilityDiagnosticAt = now
        HookLogger.i(TAG, "Visibility $diagnostic")
    }

    private fun visibleBottomInHost(host: ViewGroup, view: View?): Int {
        if (view == null || view.visibility == View.GONE) return 0
        if (view is ViewGroup) {
            var bottom = 0
            for (index in 0 until view.childCount) {
                bottom = maxOf(bottom, visibleBottomInHost(host, view.getChildAt(index)))
            }
            if (bottom > 0) return bottom
        }
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return 0
        val hostLocation = IntArray(2)
        host.getLocationInWindow(hostLocation)
        return (visible.bottom - hostLocation[1]).coerceIn(0, host.height)
    }

    private fun buildSurface(host: FrameLayout): FrameLayout {
        val density = host.resources.displayMetrics.density
        return FrameLayout(host.context).apply {
            tag = SURFACE_TAG
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false
            isLongClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            sceneCard = FrameLayout(context).also { card ->
                card.visibility = View.GONE
                card.isClickable = false
                card.isLongClickable = false
                card.isFocusable = false
                card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                cardBackgroundView = AdaptiveLyricCardBackgroundView(context).also { background ->
                    background.visibility = View.GONE
                    background.isClickable = false
                    background.isFocusable = false
                    background.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    card.addView(
                        background,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                lyricCanvas = AodLyricCanvasView(
                    context,
                    useDozeHandlerCadence = true,
                    refreshRateCapProvider = { customization?.aodRefreshRateCap ?: 0 }
                ).also { canvas ->
                    canvas.visibility = View.GONE
                    canvas.isClickable = false
                    canvas.isLongClickable = false
                    canvas.isFocusable = false
                    canvas.isFocusableInTouchMode = false
                    canvas.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    canvas.setVerticalAlignment(AodCanvasVerticalAlignment.TOP)
                    canvas.setPadding((8f * density).roundToInt(), 0, (8f * density).roundToInt(), 0)
                    card.addView(canvas, FrameLayout.LayoutParams(0, 0))
                }
                progressView = MediaProgressView(context).also { progress ->
                    progress.visibility = View.GONE
                    progress.isClickable = false
                    progress.isFocusable = false
                    progress.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    card.addView(progress, FrameLayout.LayoutParams(0, 0))
                }
                cardBackgroundView?.bind(checkNotNull(lyricCanvas), checkNotNull(progressView))
                addView(card, FrameLayout.LayoutParams(0, 0))
            }
        }
    }

    private fun applyCardBackground(style: String, alpha: Int, color: String) {
        val enabled = style == "card"
        // style 变了必须重设 enabled;color/alpha 变了只在 enabled 时重绘外观。
        val styleChanged = lastCardBackgroundStyle != style
        val appearanceChanged = enabled && (lastCardAlpha != alpha || lastCardColor != color)
        if (!styleChanged && !appearanceChanged) return
        lastCardBackgroundStyle = style
        lastCardAlpha = alpha
        lastCardColor = color
        sceneCard?.background = null
        cardBackgroundView?.let { view ->
            view.setCardEnabled(enabled)
            if (enabled) view.setCardAppearance(alpha, color)
        }
    }

    private fun observeLayouts(controller: Any, root: ViewGroup, host: FrameLayout) {
        setNotificationMotionView(null)
        observedViews.clear()
        observedViews += root
        observedViews += host
        val injector = readField(controller, "keyguardClockInjector")
        (readField(injector, "keyguardClockView") as? View)?.let(observedViews::add)
        (readField(controller, "notificationStackScrollLayout") as? ViewGroup)?.let {
            observedViews += it
            setNotificationMotionView(it)
        }
        observedViews.distinct().forEach { it.addOnLayoutChangeListener(layoutChangeListener) }
    }

    private fun setNotificationMotionView(view: ViewGroup?) {
        notificationMotionView.get()?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(notificationPreDrawListener)
        notificationMotionView = WeakReference(view)
        notificationMotionTracker.clear()
        view?.viewTreeObserver?.takeIf { it.isAlive }
            ?.addOnPreDrawListener(notificationPreDrawListener)
    }

    private fun shouldSampleNotificationMotion(): Boolean {
        val snapshot = latestSnapshot ?: return false
        val notification = notificationMotionView.get() ?: return false
        return isSceneActive() && canRenderLockscreen(snapshot) &&
            (notification.childCount > 0 || lastNotificationBounds != null) &&
            notification.isAttachedToWindow && notification.windowVisibility == View.VISIBLE
    }

    private fun notificationMotionFingerprint(notification: ViewGroup): Long {
        var hash = 0xcbf29ce484222325uL.toLong()
        hash = mixMotionFingerprint(hash, notification.visibility)
        hash = mixMotionFingerprint(hash, if (notification.isShown) 1 else 0)
        hash = mixMotionFingerprint(hash, notification.alpha.toBits())
        hash = mixMotionFingerprint(hash, notification.transitionAlpha.toBits())
        hash = mixMotionFingerprint(hash, notification.translationX.toBits())
        hash = mixMotionFingerprint(hash, notification.translationY.toBits())
        hash = mixMotionFingerprint(hash, notification.scrollX)
        hash = mixMotionFingerprint(hash, notification.scrollY)
        hash = mixMotionFingerprint(hash, notification.width)
        hash = mixMotionFingerprint(hash, notification.height)
        hash = mixClipBounds(hash, notification)
        hash = mixMotionFingerprint(hash, notification.childCount)
        for (index in 0 until notification.childCount) {
            val child = notification.getChildAt(index)
            val methods = notificationMotionMethods.get(child.javaClass)
            if (methods.contentClassName == null) continue
            hash = mixMotionFingerprint(hash, System.identityHashCode(child))
            hash = mixMotionFingerprint(hash, child.visibility)
            hash = mixMotionFingerprint(hash, if (child.isShown) 1 else 0)
            hash = mixMotionFingerprint(hash, child.alpha.toBits())
            hash = mixMotionFingerprint(hash, child.transitionAlpha.toBits())
            hash = mixMotionFingerprint(hash, child.x.toBits())
            hash = mixMotionFingerprint(hash, child.y.toBits())
            hash = mixMotionFingerprint(hash, child.width)
            hash = mixMotionFingerprint(hash, child.height)
            hash = mixClipBounds(hash, child)
            hash = mixMotionFingerprint(hash, invokeMotionInt(methods.actualHeight, child))
            hash = mixMotionFingerprint(hash, invokeMotionInt(methods.clipTopAmount, child))
            hash = mixMotionFingerprint(hash, invokeMotionInt(methods.clipBottomAmount, child))
        }
        return hash
    }

    private fun mixClipBounds(initial: Long, view: View): Long {
        var hash = initial
        val clipped = view.getClipBounds(motionClipRect)
        hash = mixMotionFingerprint(hash, if (clipped) 1 else 0)
        if (clipped) {
            hash = mixMotionFingerprint(hash, motionClipRect.left)
            hash = mixMotionFingerprint(hash, motionClipRect.top)
            hash = mixMotionFingerprint(hash, motionClipRect.right)
            hash = mixMotionFingerprint(hash, motionClipRect.bottom)
        }
        return hash
    }

    private fun mixMotionFingerprint(hash: Long, value: Int): Long =
        (hash xor value.toLong()) * 0x100000001b3L

    private fun publicNoArgMethod(type: Class<*>, name: String): Method? = runCatching {
        type.getMethod(name).apply { isAccessible = true }
    }.getOrNull()

    private fun invokeMotionInt(method: Method?, owner: Any): Int = try {
        (method?.invoke(owner) as? Number)?.toInt() ?: -1
    } catch (_: Exception) {
        -1
    }

    private fun hideSurface() {
        surface?.keepScreenOn = false
        surface?.visibility = View.GONE
        sceneCard?.visibility = View.GONE
        lyricCanvas?.stop()
        progressView?.stop()
        progressView?.visibility = View.GONE
        lyricCanvas?.visibility = View.GONE
        lastRenderContent = null
    }

    private fun detachCurrent() {
        attachmentGeneration++
        cancelPendingRefresh()
        setNotificationMotionView(null)
        clearReverseAnchorGate()
        cancelFreshnessExpiry()
        LinkageTransitionCoordinator.unregisterSurface(this)
        SystemUiLyricProjectionRuntime.projection.detach(this)
        observedViews.distinct().forEach { it.removeOnLayoutChangeListener(layoutChangeListener) }
        observedViews.clear()
        lyricCanvas?.setContentBoundsChangedListener(null)
        lyricCanvas?.stop()
        progressView?.stop()
        surface?.keepScreenOn = false
        surface?.let { directSurface ->
            (directSurface.parent as? ViewGroup)?.removeView(directSurface)
        }
        surface = null
        sceneCard = null
        cardBackgroundView = null
        lyricCanvas = null
        progressView = null
        latestSnapshot = null
        lastVisibleSnapshot = null
        retainedMediaSnapshot = null
        retentionAnchor = null
        stockMediaPlayerObserved = false
        cancelPauseLingerExpiry()
        customization = null
        runtimeProfile = null
        lastRenderedProfile = null
        lastRenderContent = null
        layoutDiagnostic = "not-computed"
        lastVisibilityDiagnostic = null
        lastVisibilityStateKey = null
        lastVisibilityDiagnosticAt = 0L
        lastNotificationBounds = null
        lastNotificationTrace = null
        lastNotificationTraceAt = 0L
        lastCardBackgroundStyle = null
        lastCardAlpha = -1
        lastCardColor = null
        sceneRole = LinkageSceneRole.INACTIVE
        handoffActive = false
        transitionFailedHidden = false
        settledRectTracker.clear()
        reverseExpectedRect = null
        controllerRef.clear()
        rootRef.clear()
        hostRef.clear()
    }

    override fun transitionRectInWindow(): TransitionRect? =
        if (reverseAnchorGateActive) null else transitionRectInWindow(sceneCard)

    override fun presentationRectInWindow(): TransitionRect? =
        presentationRectInWindow(sceneCard)

    override fun setSceneRole(role: LinkageSceneRole) {
        if (sceneRole == role) return
        sceneRole = role
        val active = isSceneActive()
        lyricCanvas?.setSceneActive(active)
        progressView?.setSceneActive(active)
        if (!active) hideSurface()
    }

    override fun setHandoffActive(active: Boolean) {
        handoffActive = active
        lyricCanvas?.setHandoffActive(active)
        val reverse = active && sceneRole == LinkageSceneRole.TRANSITION_TARGET
        if (reverse) {
            reverseExpectedRect = settledRectTracker.settledRect(SystemClock.elapsedRealtime())
            reverseAnchorGateActive = true
            reverseAnchorGate.start(SystemClock.elapsedRealtime(), reverseExpectedRect)
            surface?.visibility = View.INVISIBLE
            sceneCard?.visibility = View.INVISIBLE
            lyricCanvas?.visibility = View.INVISIBLE
            mainHandler.removeCallbacks(reverseAnchorProbe)
            mainHandler.post(reverseAnchorProbe)
        } else {
            if (active && sceneRole == LinkageSceneRole.TRANSITION_SOURCE) {
                settledRectTracker.settledRect(SystemClock.elapsedRealtime())
            }
            clearReverseAnchorGate()
        }
    }

    override fun animateFrom(
        source: TransitionRect?,
        fadeIn: Boolean,
        preserveAlpha: Boolean,
        durationMs: Long,
        token: Long,
        onComplete: (Long) -> Unit
    ) {
        animateLinkageView(
            view = sceneCard,
            source = source,
            fadeIn = true,
            preserveAlpha = preserveAlpha,
            durationMs = durationMs,
            token = token,
            onComplete = onComplete,
            additionalStartTranslationY = if (preserveAlpha) {
                LOCKSCREEN_ENTRY_SLIDE_DP * (sceneCard?.resources?.displayMetrics?.density ?: 1f)
            } else {
                0f
            },
            initialAlpha = 0f
        )
    }

    override fun fadeOut(durationMs: Long) {
        fadeOutLinkageView(sceneCard, durationMs)
    }

    override fun resetTransition() {
        clearReverseAnchorGate()
        transitionFailedHidden = false
        resetLinkageView(sceneCard)
        resetLinkageView(lyricCanvas)
        resetLinkageView(cardBackgroundView)
        resetLinkageView(progressView)
    }

    override fun hideForFailedTransition() {
        transitionFailedHidden = true
        hideSurface()
    }

    override fun applyTransitionSnapshot(snapshot: LyricSnapshot) {
        onLyricSnapshot(snapshot)
    }

    private fun lockscreenProfile() =
        customization?.profiles?.get(SceneCompiler.SURFACE_LOCKSCREEN)

    private fun currentLockscreenProfile(): CompiledSurfaceProfile =
        lockscreenProfile() ?: DEFAULT_LOCKSCREEN_PROFILE

    private fun isSceneActive(): Boolean = sceneRole != LinkageSceneRole.INACTIVE

    private fun canRenderLockscreen(
        snapshot: LyricSnapshot,
        allowExpired: Boolean = false
    ): Boolean =
        isSceneActive() && shouldRenderLockscreenSnapshot(
            if (allowExpired) snapshot.copy(updatedAtElapsedMs = SystemClock.elapsedRealtime()) else snapshot,
            profileEnabled = lockscreenProfile()?.enabled != false,
            transitionFailed = transitionFailedHidden,
            nowElapsedMs = SystemClock.elapsedRealtime()
        )

    private fun hasStockMediaPlayer(controller: Any?): Boolean {
        controller ?: return false
        if (!readBoolean(controller, "hasNotification", false)) return false
        val notification = readField(controller, "notificationStackScrollLayout") as? ViewGroup
            ?: return false
        for (index in 0 until notification.childCount) {
            val child = notification.getChildAt(index)
            if (notificationContentClassName(child)?.substringAfterLast('.') ==
                "MiuiMediaHeaderView" && child.visibility != View.GONE
            ) return true
        }
        return false
    }

    private fun isStockMediaPlayerVisible(controller: Any?): Boolean {
        controller ?: return false
        if (!readBoolean(controller, "hasNotification", false)) return false
        val notification = readField(controller, "notificationStackScrollLayout") as? ViewGroup
            ?: return false
        for (index in 0 until notification.childCount) {
            val child = notification.getChildAt(index)
            if (notificationContentClassName(child)?.substringAfterLast('.') ==
                "MiuiMediaHeaderView" && child.visibility == View.VISIBLE &&
                child.alpha > MIN_VISIBLE_ALPHA
            ) return true
        }
        return false
    }

    private fun scheduleFreshnessExpiry(snapshot: LyricSnapshot) {
        cancelFreshnessExpiry()
        if (!snapshot.visible) return
        freshnessGeneration = attachmentGeneration
        val freshMs = if (snapshot.playbackActive) LOCKSCREEN_PLAYBACK_FRESH_MS
        else LYRIC_SNAPSHOT_FRESH_MS
        val delay = (snapshot.updatedAtElapsedMs + freshMs -
            SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 1L
        mainHandler.postDelayed(freshnessExpiry, delay)
    }

    private fun cancelFreshnessExpiry() {
        freshnessGeneration = -1L
        mainHandler.removeCallbacks(freshnessExpiry)
    }

    private fun clearReverseAnchorGate() {
        reverseAnchorGateActive = false
        reverseAnchorGate.clear()
        reverseExpectedRect = null
        mainHandler.removeCallbacks(reverseAnchorProbe)
    }

    private fun findHost(controller: Any, root: ViewGroup): FrameLayout? {
        (readField(controller, "keyguardTranslationInfo") as? FrameLayout)?.let { return it }
        val id = root.resources.getIdentifier(
            "keyguard_translation_info",
            "id",
            "com.android.systemui"
        )
        return root.findViewById(id)
    }

    private fun readField(owner: Any?, name: String): Any? = runCatching {
        owner ?: return null
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }.getOrNull()

    private fun readHierarchyField(owner: Any?, name: String): Any? {
        owner ?: return null
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val currentType = type
            val value = runCatching {
                currentType.getDeclaredField(name).apply { isAccessible = true }.get(owner)
            }.getOrNull()
            if (value != null) return value
            type = currentType.superclass
        }
        return null
    }

    private fun invokePublicNoArgInt(owner: Any?, name: String): Int = runCatching {
        owner ?: return -1
        (owner.javaClass.getMethod(name).invoke(owner) as? Number)?.toInt() ?: -1
    }.getOrDefault(-1)

    private fun readBoolean(owner: Any, name: String, fallback: Boolean): Boolean =
        (readField(owner, name) as? Boolean) ?: fallback

    private fun readFloat(owner: Any, name: String, fallback: Float): Float =
        (readField(owner, name) as? Number)?.toFloat() ?: fallback

    private fun isSecurityOrEditorObscured(controller: Any): Boolean {
        val editorState = readField(controller, "editorState")?.toString()
        val keyguardStateController = readField(controller, "keyguardStateController")
        val quickSettingsController = readField(controller, "quickSettingsControllerImpl")
        return readBoolean(controller, "keyguardBouncerShowing", false) ||
            readFloat(controller, "keyguardBouncerFraction", 0f) > 0.01f ||
            isEditorActivelyObscuring(editorState) ||
            (readField(keyguardStateController, "mKeyguardGoingAway") as? Boolean) == true ||
            invokeNoArgBoolean(quickSettingsController, "getExpanded", false)
    }

    /**
     * editorState may be an enum name, an int ordinal, or absent. Only explicitly recognized
     * editing/dragging states obscure playback; numeric ordinals and unknown values fail open so
     * a type mismatch never hides lyrics during normal lockscreen use.
     */
    private fun isEditorActivelyObscuring(editorState: String?): Boolean {
        val state = editorState?.uppercase() ?: return false
        if (state in setOf("IDLE", "IDEL", "0", "NULL", "")) return false
        return state.contains("EDIT") || state.contains("DRAG")
    }

    private fun invokeNoArgInt(owner: Any?, name: String): Int = runCatching {
        owner ?: return 0
        (owner.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(owner) as? Number)
            ?.toInt() ?: 0
    }.getOrDefault(0)

    private fun invokeNoArgBoolean(owner: Any?, name: String, fallback: Boolean): Boolean =
        runCatching {
            owner ?: return fallback
            owner.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(owner) as? Boolean
                ?: fallback
        }.getOrDefault(fallback)

    private fun minWidth(view: View): Int =
        (MIN_WIDTH_DP * view.resources.displayMetrics.density).roundToInt()

    private fun minHeight(view: View): Int =
        (MIN_HEIGHT_DP * view.resources.displayMetrics.density).roundToInt()

    private val DEFAULT_LOCKSCREEN_PROFILE =
        SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
}
