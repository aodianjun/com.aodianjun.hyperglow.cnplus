package com.eza.hyperglow.aod

import com.eza.hyperglow.producer.LyricProducerState

/**
 * 共享的位置投影逻辑：把「采样时刻的位置 + 经过的时间 + 速度」前向投影到 [now]。
 *
 * 这是 [AodProjectionEngine.project] 历史上的 `projectedPosition(state, now)` 的纯函数提取，
 * 现在同时服务于：
 * - 引擎侧（[AodProjectionEngine.projectedPosition] 委托给它，Spicy 路径仍走 SpicyBridgeState）
 * - [AodStateProjector]（消费 [LyricProducerState]，Phase 3 引擎切换后的唯一入口）
 * - Phase 2 的 `SpicyLyricProducer` 100ms 重投影定时器
 *
 * 行为与原实现严格一致：playing 时按 speed 线性外推，非 playing 时取采样位置；最后 clamp 到
 * `[0, durationMs]`（原实现即 `coerceIn(0L, durationMs)`，`durationMs == 0` 时结果恒为 0，
 * 保留该语义以保证零回归）。
 *
 * 注意：lyricon 的 `onPositionChanged` 回调本身就是最新位置，但 `sampledAtElapsedMs` 也是
 * emit 时刻的 now，且 `speed` 为 1f/0f，因此对其投影是安全的近似 no-op —— 统一走本函数可让
 * 两条路径共享同一套位置语义，无需在引擎里区分 producer 类型（spec clause 8）。
 */
internal fun projectedPosition(
    positionMs: Long,
    sampledAtElapsedMs: Long,
    speed: Float,
    playing: Boolean,
    durationMs: Long,
    now: Long
): Long {
    val projected = if (playing) {
        positionMs + ((now - sampledAtElapsedMs).coerceAtLeast(0L) * speed).toLong()
    } else {
        positionMs
    }
    return projected.coerceIn(0L, durationMs)
}

/** [LyricProducerState] 适配重载——[AodStateProjector] 与 Phase 2 生产者使用。 */
internal fun projectedPosition(state: LyricProducerState, now: Long): Long = projectedPosition(
    positionMs = state.positionMs,
    sampledAtElapsedMs = state.sampledAtElapsedMs,
    speed = state.speed,
    playing = state.playing,
    durationMs = state.durationMs,
    now = now
)

/**
 * 判断线性外推是否仍可信（息屏时数据源停写位置后的防护）。
 *
 * 背景：网易云等数据源息屏后主动停止写入歌词位置，HyperGlow 只能靠
 * `positionMs + (now - sampledAtElapsedMs) * speed` 外推。匀速播放时准；但歌曲播完自动
 * 切歌/重播时真实位置从 0 重计、数据源不通知，外推仍按旧歌累加，会被 [projectedPosition]
 * 的 `coerceIn(0, durationMs)` 钳制在末行——AOD 因此长期显示错误的旧歌末尾行。
 *
 * 本函数在 playing 且确有外推时判两点，任一命中即为不可信，调用方应清空活动行而非继续显示
 * 被钳制到末行的旧歌词：
 * - **外推越界**：仅靠外推越过歌曲结尾（`positionMs + 外推量 > durationMs`）→ 歌曲多半已
 *   结束/重播/切歌。
 * - **外推过长**：外推时长超过整首歌时长仍无新采样 → 数据源已停更，位置不可信。
 *
 * 两条都相对歌曲时长判定，因此正常息屏匀速播放（外推到歌曲结尾即被真实过渡打断）不会误触发；
 * 非 playing 或无外推时始终可信（返回的是真实采样位置）。
 */
internal fun extrapolationReliable(
    positionMs: Long,
    sampledAtElapsedMs: Long,
    speed: Float,
    playing: Boolean,
    durationMs: Long,
    now: Long
): Boolean {
    if (!playing) return true
    val extrapolatedMs = (now - sampledAtElapsedMs).coerceAtLeast(0L)
    if (extrapolatedMs <= 0L) return true
    if (durationMs <= 0L) return true
    // 外推越界（包含外推超过整首歌时长这一类，speed 归一化后即越界）。
    if (positionMs + (extrapolatedMs * speed).toLong() > durationMs) return false
    // 外推过长：即使 speed<1 未越界，外推时长已超过整首歌，数据源必然已停更。
    if (extrapolatedMs > durationMs) return false
    return true
}
