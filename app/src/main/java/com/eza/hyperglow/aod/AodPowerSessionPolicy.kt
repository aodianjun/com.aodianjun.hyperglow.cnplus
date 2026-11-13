package com.eza.hyperglow.aod

internal data class AodPowerSessionDecision(
    val keepAlive: Boolean,
    val presentationLeaseActive: Boolean,
    /**
     * 有限时长保活（「保持息屏活动 N 分钟」）的本次连续播放会话已到点。
     *
     * 与 [keepAlive]=false 区分开：后者只说明本拍没有保活请求（暂停/传输间隙/功能关闭），
     * 渲染面仍可按 playbackActive 续期绘制；到点则必须同步停掉绘制唤醒与 AOD 重唤醒，
     * 否则表面会一直把 AOD 点亮到播放停止（AodPowerCoordinator / AodSurfaceController 消费）。
     * 只有真正的会话边界（非播放边）会重置计时，换歌不重置。
     */
    val keepAliveExpired: Boolean = false
)

internal class AodPowerSessionPolicy(
    private val songChangeLeaseMs: Long = DEFAULT_SONG_CHANGE_LEASE_MS
) {
    private var session: ProjectionSessionIdentity? = null
    private var leaseUntilElapsedMs = 0L
    private var keepAliveStartedAtElapsedMs: Long? = null

    @Synchronized
    fun resolve(
        state: SpicyPowerSessionState,
        nowElapsedMs: Long,
        persistentKeepAlive: Boolean
    ): AodPowerSessionDecision {
        if (!state.playing || !state.aodEnabled || !state.keepAwake) {
            clear()
            return AodPowerSessionDecision(false, false)
        }
        if (session != state.session) {
            session = state.session
            leaseUntilElapsedMs = nowElapsedMs + songChangeLeaseMs.coerceAtLeast(0L)
        }
        val leaseActive = nowElapsedMs < leaseUntilElapsedMs
        val requested = persistentKeepAlive || leaseActive
        // 呈现租约结束只说明本拍不再保活（未计时歌、歌词加载间隙等），不等于有限时长到点：
        // 绘制续期仍应按 playbackActive 继续，否则播放中会提前卡顿/熄灭。
        if (!requested) return AodPowerSessionDecision(false, false)
        val startedAt = keepAliveStartedAtElapsedMs ?: nowElapsedMs.also {
            keepAliveStartedAtElapsedMs = it
        }
        val durationMs = normalizeKeepAwakeDurationMs(state.keepAliveDurationMs)
        if (durationMs > 0L && nowElapsedMs - startedAt >= durationMs) {
            return AodPowerSessionDecision(
                keepAlive = false,
                presentationLeaseActive = false,
                keepAliveExpired = true
            )
        }
        return AodPowerSessionDecision(
            keepAlive = true,
            presentationLeaseActive = leaseActive && !persistentKeepAlive,
            keepAliveExpired = false
        )
    }

    @Synchronized
    fun clear() {
        session = null
        leaseUntilElapsedMs = 0L
        keepAliveStartedAtElapsedMs = null
    }

    companion object {
        const val DEFAULT_SONG_CHANGE_LEASE_MS = 8_000L
    }
}

internal data class SpicyPowerSessionState(
    val session: ProjectionSessionIdentity,
    val playing: Boolean,
    val aodEnabled: Boolean,
    val keepAwake: Boolean,
    val keepAliveDurationMs: Long = -1L
)
