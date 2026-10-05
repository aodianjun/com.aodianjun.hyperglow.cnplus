package com.eza.hyperglow.root.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import com.eza.hyperglow.DiagnosticLoggingRuntime
import com.eza.hyperglow.aod.AodStateWireMessage
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.root.aod.AodLyricClient
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.lockscreen.RaiseToAodController
import com.eza.hyperglow.root.lockscreen.LockscreenEditorGestureController
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec.WirePayload
import java.util.IdentityHashMap

internal enum class LyricSurfaceKind { LOCKSCREEN, AOD }

internal const val LYRIC_SNAPSHOT_FRESH_MS = 5_000L

/**
 * Looser freshness window honored while media is actively playing. The shared 5 s window is
 * tighter than the producer's ~4 s keepalive spacing, so any missed/late keepalive made the
 * projection clear [latestSnapshot] at 5 s. That was catastrophic for both surfaces:
 *
 *  - AOD: clearing the snapshot stops the draw-wake renewal (playbackActive reads false) AND
 *    rejects subsequent keepalives (accept() returns early when latestSnapshot == null), so the
 *    doze surface stops compositing and lyrics freeze until the next line-change snapshot.
 *  - Lockscreen: onLyricProjectionStale -> hideSurface() fires at 5 s, overriding the
 *    controller's own 15 s playback window and making the card flap visible/hidden.
 *
 * While playback is active the lyric content and position are still valid well beyond 5 s, so a
 * 15 s window keeps both surfaces stable without retaining stale content after playback stops.
 */
internal const val LYRIC_PLAYBACK_FRESH_MS = 15_000L

/**
 * 投递边界的快照最大存活年龄:年龄超过它的快照按「过期旧账」丢弃(见
 * [shouldDropStaleSnapshot])。
 *
 * MIUI doze 会把 SystemUI 冻结成 ~20.15s 一批、每次解冻只跑 ~20ms,app 侧积压的快照于是
 * 成批投递(真机实测 19ms 内连用 12 条,卡拉OK 位置跨度约 18s,每条约 1.5s 播放内容)。
 * producer 的全量发布与心跳同为 1.5s 节奏(AodProjectionEngine.KEEP_ALIVE_INTERVAL_MS),
 * 因此超过一个发布周期的快照必然已有更新的状态在途;投递边界只保留最新,旧批不再逐个
 * 应用——内容/位置都来自过期批次时,渲染侧的过渡会追着旧账反复重置。
 */
internal const val STALE_SNAPSHOT_DROP_AGE_MS = 1_500L

private const val MAX_WIRE_FUTURE_SKEW_MS = 1_000L
private const val TAG = "SystemUiProjection"

internal fun lyricFreshnessWindowMs(playbackActive: Boolean): Long =
    if (playbackActive) LYRIC_PLAYBACK_FRESH_MS else LYRIC_SNAPSHOT_FRESH_MS

internal fun isPlausibleWireTimestamp(updatedAtElapsedMs: Long, nowElapsedMs: Long): Boolean =
    updatedAtElapsedMs >= 0L && nowElapsedMs >= 0L &&
        updatedAtElapsedMs - nowElapsedMs <= MAX_WIRE_FUTURE_SKEW_MS

/**
 * 投递边界判据:是否丢弃一条过期快照(纯函数,接入 [SystemUiLyricProjection.accept])。
 *
 * - 只过滤可见内容快照:隐藏消息不携带内容/位置,只表达可见性、传输间隙与暂停驻留边沿,
 *   丢了会破坏边沿语义,一律放行;
 * - `trackGeneration` 变化(换歌/换源)恒放行:新歌内容不是旧账,即使批投递迟到也必须落;
 * - 尚无持有快照时放行:首条内容不丢——否则随后同 revision 的心跳会因「无快照可续」整链
 *   被拒,恢复要等下一次全量重发;
 * - 其余按年龄:年龄严格大于 [STALE_SNAPSHOT_DROP_AGE_MS] 才丢(恰好等于阈值仍接受,
 *   按 `<=` 口径)。
 */
internal fun shouldDropStaleSnapshot(
    snapshot: LyricSnapshot,
    heldTrackGeneration: Long?,
    nowElapsedMs: Long
): Boolean {
    if (!snapshot.visible) return false
    if (heldTrackGeneration == null) return false
    if (snapshot.trackGeneration != heldTrackGeneration) return false
    return nowElapsedMs - snapshot.updatedAtElapsedMs > STALE_SNAPSHOT_DROP_AGE_MS
}

/**
 * 被丢弃的过期快照是否携带与持有态不同的租约/可见性标量:是则返回等价 KeepAlive 信号
 * (只续期/更新标量字段、不重放内容),与持有态完全一致时返回 null——既不续期也不打扰
 * 订阅者。这是投递边界丢弃的兜底:一条被丢的快照可能是 KeepAlive 的唯一来源时,租约链
 * 不能断。
 */
internal fun droppedSnapshotKeepAliveSignal(
    dropped: LyricSnapshot,
    held: LyricSnapshot
): LyricKeepAliveSignal? {
    val changed = dropped.keepAlive != held.keepAlive ||
        dropped.wakeSignal != held.wakeSignal ||
        dropped.playbackActive != held.playbackActive ||
        dropped.pauseRetentionEligible != held.pauseRetentionEligible
    if (!changed) return null
    return LyricKeepAliveSignal(
        revision = dropped.revision,
        updatedAtElapsedMs = dropped.updatedAtElapsedMs,
        keepAlive = dropped.keepAlive,
        wakeSignal = dropped.wakeSignal,
        playbackActive = dropped.playbackActive,
        pauseRetentionEligible = dropped.pauseRetentionEligible,
        userId = dropped.userId
    )
}

internal fun currentProcessUserId(): Int =
    UserHandle.getUserHandleForUid(Process.myUid()).hashCode()

internal fun shouldRenewAodDraw(
    surfaceKind: LyricSurfaceKind,
    attached: Boolean,
    sceneActive: Boolean,
    effectivelyVisible: Boolean,
    pendingStockMotion: Boolean,
    keepAlive: Boolean,
    playbackActive: Boolean = false
): Boolean = surfaceKind == LyricSurfaceKind.AOD &&
    attached &&
    sceneActive &&
    (keepAlive || playbackActive) &&
    (effectivelyVisible || pendingStockMotion)

internal fun shouldRequestAodWake(
    attached: Boolean,
    sceneActive: Boolean,
    effectivelyVisible: Boolean
): Boolean = attached && sceneActive && effectivelyVisible

internal interface SystemUiLyricSubscriber {
    val surfaceKind: LyricSurfaceKind

    fun onLyricSnapshot(snapshot: LyricSnapshot)

    fun onLyricKeepAlive(signal: LyricKeepAliveSignal) = Unit

    fun onLyricProjectionDisconnected() = Unit

    fun onLyricProjectionStale() = Unit

    fun onCustomization(configuration: CompiledCustomization) = Unit
}

internal interface LyricProjectionClient {
    fun bind(hostContext: Context?, userId: Int)

    fun unbind()

    fun reportCapabilities() = Unit
}

internal interface LyricExpiryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancel()
}

private class MainThreadLyricExpiryScheduler : LyricExpiryScheduler {
    private val handler = try {
        Handler(Looper.getMainLooper())
    } catch (_: Exception) {
        null
    }
    private var pending: Runnable? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
        cancel()
        val runnable = Runnable(action)
        pending = runnable
        handler?.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    override fun cancel() {
        pending?.let { handler?.removeCallbacks(it) }
        pending = null
    }
}

internal class SystemUiLyricProjection(
    private val expiryScheduler: LyricExpiryScheduler = MainThreadLyricExpiryScheduler(),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val processUserId: () -> Int = ::currentProcessUserId,
    private val setDiagnosticLogging: (Boolean) -> Unit = DiagnosticLoggingRuntime::setEnabled,
    private val setRaiseToAod: (Boolean) -> Unit = RaiseToAodController::setEnabled,
    private val setSuppressLockscreenEditorLongPress: (Boolean) -> Unit =
        LockscreenEditorGestureController::setEnabled,
    clientFactory: ((WirePayload) -> Unit, (AodStateWireMessage) -> Unit, () -> Unit) ->
        LyricProjectionClient = { onConfiguration, onState, onDisconnected ->
            AodLyricClient(onConfiguration, onState, onDisconnected)
        }
) {
    private val subscribers = IdentityHashMap<SystemUiLyricSubscriber, Unit>()
    private val client = clientFactory(::handleConfiguration, ::handleState, ::handleDisconnected)
    private var latestSnapshot: LyricSnapshot? = null
    private var latestVisibleSnapshot: LyricSnapshot? = null
    private var latestConfiguration: CompiledCustomization? = null
    private var lastRevision = -1L
    private var lastUpdatedAt = -1L
    private var lastRejection = ""
    private var bindingContext: Context? = null
    private var clientBound = false
    private var bootstrapped = false
    private var expectedUserId: Int? = null

    @Synchronized
    fun bootstrap(context: Context?) {
        bootstrapped = true
        if (context != null) {
            bindingContext = context
            expectedUserId = processUserId()
        }
        ensureBound()
        client.reportCapabilities()
    }

    @Synchronized
    fun attach(subscriber: SystemUiLyricSubscriber, context: Context?) {
        if (context != null) bindingContext = context
        subscribers[subscriber] = Unit
        ensureBound()
        latestConfiguration?.let(subscriber::onCustomization)
        latestSnapshot?.let(subscriber::onLyricSnapshot)
    }

    @Synchronized
    fun detach(subscriber: SystemUiLyricSubscriber) {
        if (subscribers.remove(subscriber) == null) return
        if (subscribers.isEmpty() && !bootstrapped) {
            client.unbind()
            clientBound = false
            clearCachedState()
        }
    }

    @Synchronized
    internal fun accept(message: LyricProjectionMessage): Boolean {
        if (expectedUserId?.let { it != message.userId } == true) {
            return rejected("user=${message.userId} expected=$expectedUserId")
        }
        if (message.revision < lastRevision) {
            return rejected("revision=${message.revision} held=$lastRevision")
        }
        if (message.revision == lastRevision && message.updatedAtElapsedMs <= lastUpdatedAt) {
            return rejected("stamp=${message.updatedAtElapsedMs} held=$lastUpdatedAt")
        }
        // 投递边界丢弃过期旧账:doze 批投递里除最新一条外的快照内容/位置都是过去时,
        // 逐个应用会让渲染侧过渡反复重置(owner 报的「换行后跳两次」的真根因)。
        if (message is LyricProjectionMessage.Snapshot &&
            shouldDropStaleSnapshot(message.value, latestSnapshot?.trackGeneration, elapsedRealtime())
        ) {
            return dropStaleSnapshot(message)
        }
        return when (message) {
            is LyricProjectionMessage.Snapshot -> {
                lastRevision = message.revision
                lastUpdatedAt = message.updatedAtElapsedMs
                val acceptedSnapshot = stampTransportGapEdge(latestSnapshot, message.value)
                latestSnapshot = acceptedSnapshot
                if (acceptedSnapshot.visible) latestVisibleSnapshot = acceptedSnapshot
                // 终止隐藏态清空缓存可见快照。留着它会让缓存变成无界的重建源:surface 很晚
                // 附着时从这补齐自己的 last-visible 槽,可能呈现一个早已结束会话的歌词。
                else if (acceptedSnapshot.isTerminalHidden()) latestVisibleSnapshot = null
                scheduleExpiry(acceptedSnapshot)
                subscribers.keys.toList().forEach { it.onLyricSnapshot(acceptedSnapshot) }
                true
            }
            is LyricProjectionMessage.KeepAlive -> {
                if (message.revision != lastRevision) {
                    return rejected("keepalive revision=${message.revision} held=$lastRevision")
                }
                val current = latestSnapshot ?: return rejected("keepalive with no snapshot")
                lastUpdatedAt = message.updatedAtElapsedMs
                latestSnapshot = current.copy(
                    updatedAtElapsedMs = message.updatedAtElapsedMs,
                    keepAlive = message.value.keepAlive,
                    wakeSignal = message.value.wakeSignal,
                    playbackActive = message.value.playbackActive,
                    pauseRetentionEligible = message.value.pauseRetentionEligible
                )
                if (current.visible) latestVisibleSnapshot = latestSnapshot
                latestSnapshot?.let(::scheduleExpiry)
                subscribers.keys.toList().forEach { it.onLyricKeepAlive(message.value) }
                true
            }
        }
    }

    /**
     * 丢弃一条过期快照:不应用其内容,但保留两样东西 ——
     * 1) revision/updatedAt 水位照常推进:producer 的心跳携带同一 revision,不推进会让
     *    随后整条心跳链被「keepalive revision=... held=...」拒绝,恢复只能等下一次全量重发;
     * 2) 若被丢快照携带与持有态不同的租约标量(keepAlive/wakeSignal/playbackActive/
     *    pauseRetentionEligible),按 KeepAlive 等价路径只更新标量、通知订阅者,绝不重放内容。
     */
    @Synchronized
    private fun dropStaleSnapshot(message: LyricProjectionMessage.Snapshot): Boolean {
        val dropped = message.value
        lastRevision = message.revision
        lastUpdatedAt = message.updatedAtElapsedMs
        val held = latestSnapshot
        if (held != null) {
            droppedSnapshotKeepAliveSignal(dropped, held)?.let { signal ->
                val merged = held.copy(
                    updatedAtElapsedMs = signal.updatedAtElapsedMs,
                    keepAlive = signal.keepAlive,
                    wakeSignal = signal.wakeSignal,
                    playbackActive = signal.playbackActive,
                    pauseRetentionEligible = signal.pauseRetentionEligible
                )
                latestSnapshot = merged
                if (merged.visible) latestVisibleSnapshot = merged
                scheduleExpiry(merged)
                subscribers.keys.toList().forEach { it.onLyricKeepAlive(signal) }
            }
        }
        return rejected(
            "stale snapshot rev=${message.revision} " +
                "age=${elapsedRealtime() - dropped.updatedAtElapsedMs}ms " +
                "track=${dropped.trackGeneration}"
        )
    }

    /**
     * 被投影拒绝的消息与停止发布的生产者在表象上无法区分:两者都以投影几秒后过期、AOD
     * 生命期 guard 撤收告终。只有把拒绝原因说出来,才分得清是谁掐断了链路。
     */
    private fun rejected(reason: String): Boolean {
        if (reason != lastRejection) {
            lastRejection = reason
            HookLogger.i(TAG, "Projection message rejected $reason")
        }
        return false
    }

    @Synchronized
    internal fun subscriberCount(): Int = subscribers.size

    @Synchronized
    internal fun cachedSnapshot(): LyricSnapshot? = latestSnapshot

    @Synchronized
    internal fun cachedVisibleSnapshot(): LyricSnapshot? = latestVisibleSnapshot

    @Synchronized
    internal fun cachedCustomization(): CompiledCustomization? = latestConfiguration

    @Synchronized
    internal fun expireIfStale(nowElapsedMs: Long): Boolean {
        val snapshot = latestSnapshot ?: return false
        if ((!snapshot.visible && !snapshot.playbackActive) ||
            nowElapsedMs - snapshot.updatedAtElapsedMs <= lyricFreshnessWindowMs(snapshot.playbackActive)
        ) {
            return false
        }
        latestSnapshot = null
        latestVisibleSnapshot = null
        expiryScheduler.cancel()
        subscribers.keys.toList().forEach(SystemUiLyricSubscriber::onLyricProjectionStale)
        return true
    }

    @Synchronized
    fun onUserChanged(userId: Int? = null) {
        expectedUserId = userId
        client.unbind()
        clientBound = false
        clearCachedState()
        subscribers.keys.toList().forEach(SystemUiLyricSubscriber::onLyricProjectionDisconnected)
        if (bootstrapped || subscribers.isNotEmpty()) ensureBound()
    }

    fun reportCapabilities() {
        client.reportCapabilities()
    }

    @Synchronized
    internal fun acceptConfiguration(configuration: CompiledCustomization): Boolean {
        setDiagnosticLogging(configuration.diagnosticLogging)
        setRaiseToAod(configuration.raiseToAod)
        setSuppressLockscreenEditorLongPress(configuration.suppressLockscreenEditorLongPress)
        val current = latestConfiguration
        if (current != null && current.revision == configuration.revision &&
            current.hash == configuration.hash
        ) {
            // 配置已持有相同 revision/hash 时静默返回,此前无任何痕迹;「设置改了实机不变」
            // 类反馈要靠这条区分「没收到」与「收到但判定为重复而丢弃」。
            HookLogger.w(
                TAG,
                "Configuration deduplicated rev=${configuration.revision} " +
                    "hash=${configuration.hash.take(8)}"
            )
            return false
        }
        latestConfiguration = configuration
        val animationSummary = configuration.profiles.entries.joinToString(",") { (surface, profile) ->
            "$surface=${profile.animation}"
        }
        HookLogger.w(
            TAG,
            "Configuration applied rev=${configuration.revision} " +
                "hash=${configuration.hash.take(8)} subscribers=${subscribers.size} " +
                "anim=[$animationSummary]"
        )
        subscribers.keys.toList().forEach { it.onCustomization(configuration) }
        return true
    }

    private fun handleConfiguration(configuration: WirePayload) {
        // 实验模式开关由 app 端随 customization payload 推送;hook 端据此让
        // XiaomiCapabilityResolver 在 EXPERIMENTAL_ELIGIBLE profile 上按符号探测
        // 放开 capability,否则 surface/位置更新/保活链路全被 hasCapability 卡死。
        XiaomiCapabilityResolver.setExperimentalMode(configuration.experimentalMode)
        val parsed = CompiledCustomizationBundleCodec.fromWirePayload(
            configuration,
            expectedUserId,
            onReject = { reason ->
                HookLogger.w(TAG, "Customization payload rejected: $reason")
            }
        ) ?: return
        acceptConfiguration(parsed)
    }

    private fun handleState(state: AodStateWireMessage) {
        if (!isPlausibleWireTimestamp(state.updatedAtElapsedMs, elapsedRealtime())) return
        accept(state.toLyricProjectionMessage())
    }

    @Synchronized
    private fun handleDisconnected() {
        clearCachedState()
        subscribers.keys.toList().forEach(SystemUiLyricSubscriber::onLyricProjectionDisconnected)
    }

    private fun clearCachedState() {
        setDiagnosticLogging(false)
        setRaiseToAod(false)
        setSuppressLockscreenEditorLongPress(false)
        XiaomiCapabilityResolver.setExperimentalMode(false)
        expiryScheduler.cancel()
        latestSnapshot = null
        latestVisibleSnapshot = null
        latestConfiguration = null
        lastRevision = -1L
        lastUpdatedAt = -1L
    }

    private fun ensureBound() {
        if (clientBound) return
        clientBound = true
        val userId = expectedUserId ?: processUserId()
        val source = if (expectedUserId != null) "tracker" else "process"
        HookLogger.bootstrap(
            "SystemUiProjection",
            "bridge_bind_user=${userId.coerceIn(-1, 99_999)} source=$source"
        )
        client.bind(
            hostContext = bindingContext,
            userId = userId
        )
    }

    private fun scheduleExpiry(snapshot: LyricSnapshot) {
        expiryScheduler.cancel()
        if (!snapshot.visible && !snapshot.playbackActive) return
        val expectedRevision = snapshot.revision
        val expectedUpdatedAt = snapshot.updatedAtElapsedMs
        val freshMs = lyricFreshnessWindowMs(snapshot.playbackActive)
        val delay = (expectedUpdatedAt + freshMs -
            elapsedRealtime()).coerceAtLeast(0L) + 1L
        expiryScheduler.schedule(delay) {
            synchronized(this) {
                val current = latestSnapshot
                if (current?.revision == expectedRevision &&
                    current.updatedAtElapsedMs == expectedUpdatedAt
                ) {
                    expireIfStale(elapsedRealtime())
                }
            }
        }
    }
}

internal object SystemUiLyricProjectionRuntime {
    val projection = SystemUiLyricProjection()
}
