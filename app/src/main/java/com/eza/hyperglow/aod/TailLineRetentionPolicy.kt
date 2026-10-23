package com.eza.hyperglow.aod

import com.eza.hyperglow.customization.normalizeTailTimeoutDelaySeconds
import com.eza.hyperglow.producer.LyricProducerState

/**
 * 尾奏保留结果:[state] 是要继续投影的状态(主路径下是保留的最后一行 + 当前时钟),
 * [tailEligible] 表示当前处于尾奏上下文(最后一行已结束或即将结束、且没有下一行),
 * **不表示延迟已到点**。延迟由渲染面按各自 profile 用 [tailTimeoutActive] 计算。
 */
internal data class TailRetentionResult(
    val state: LyricProducerState,
    val tailEligible: Boolean
)

/**
 * 尾奏保留策略(纯逻辑,不依赖 Android/时钟):最后一句歌词结束后继续投影这一行,
 * 并标记 `tailEligible`;具体延迟到点与否由渲染面按 per-surface 配置计算。
 *
 * **不改 producer 的清行逻辑**——`LyricInfoLyricProducer`/`LyriconLineSelection`/
 * `SuperLyricLyricProducer` 仍按各自规则在行结束后把 `lineIndex` 置 -1;本策略在投影层
 * (PluginPipeline.enrich 之前)保存「最后一个有内容的 state」,清行后用它补齐内容字段,
 * 位置/时钟/速度取当前 state,避免画面跳回占位符。
 *
 * 两条路径:
 * - 主路径(producer 已清行):`lineIndex < 0 && hasTimedLyrics && status == "ready" &&
 *   playing`,且保存的 state 有非空行与可用时间(`lineStartMs > 0 || lineEndMs > 0`)
 *   → 用保存的内容投影,`tailEligible = true`。
 * - 次路径(producer 未清行,最后一行仍在):`lineIndex >= 0 && line 非空 &&
 *   (lineEndMs > lineStartMs || lineStartMs > 0) && nextLine 为空 &&
 *   nextLineStartMs == null && playing` → 不替换 state,`tailEligible = true`。
 *
 * 会话隔离:按 [ProjectionSessionIdentity](producerId/generation/trackUri) 分会话,换歌
 * (含同曲重播的新 generation)即清空;暂停、非 ready、seek 回退到保留行之前、以及
 * 纯音乐/真无歌词([allowTail] 为 false)一律不参与尾奏。所有方法同步,可在投影线程
 * 与 100ms ticker 之间共享。
 */
internal class TailLineRetentionPolicy {
    private var session: ProjectionSessionIdentity? = null
    private var retained: LyricProducerState? = null

    /** 释放/停止时清空(见 AodProjectionEngine.releaseNow)。 */
    @Synchronized
    fun reset() {
        session = null
        retained = null
    }

    /**
     * @param allowTail 曲目级门控:纯音乐或真无歌词时为 false(由引擎计算,优先于尾奏)。
     */
    @Synchronized
    fun apply(
        state: LyricProducerState,
        allowTail: Boolean = true
    ): TailRetentionResult {
        val identity = ProjectionSessionIdentity.from(state)
        if (identity != session) {
            session = identity
            retained = null
        }
        if (!allowTail || !state.playing || state.status != "ready") {
            retained = null
            return TailRetentionResult(state, false)
        }
        val saved = retained

        // 主路径:producer 已清行(lineIndex < 0),用保存的最后一行补内容。
        if (state.lineIndex < 0 && state.hasTimedLyrics) {
            if (saved == null || saved.line.isBlank() ||
                (saved.lineStartMs <= 0L && saved.lineEndMs <= 0L)
            ) {
                retained = null
                return TailRetentionResult(state, false)
            }
            // seek 回退到保留行之前:不再把旧行顶回来。
            if (state.positionMs < saved.lineStartMs) {
                retained = null
                return TailRetentionResult(state, false)
            }
            val content = saved.copy(
                positionMs = state.positionMs,
                sampledAtElapsedMs = state.sampledAtElapsedMs,
                speed = state.speed,
                playing = true,
                durationMs = state.durationMs.takeIf { it > 0L } ?: saved.durationMs
            )
            return TailRetentionResult(content, true)
        }

        // 次路径:producer 尚未清行,最后一行仍在(nextLine 为空且无下一行起点)。
        // 只有 begin 的行(lineEndMs <= lineStartMs 且 lineStartMs > 0)同样进入尾奏。
        val lastLine = state.lineIndex >= 0 && state.line.isNotBlank() &&
            (state.lineEndMs > state.lineStartMs || state.lineStartMs > 0L) &&
            state.nextLine.isBlank() && state.nextLineStartMs == null
        if (lastLine) {
            retained = state
            return TailRetentionResult(state, true)
        }

        // 普通活动行(还有下一行)或其他内容:刷新保留行,尾奏不生效。
        if (state.lineIndex >= 0 && state.line.isNotBlank()) {
            retained = state
        } else {
            retained = null
        }
        return TailRetentionResult(state, false)
    }
}

/**
 * 尾奏 deadline:普通行 `lineEndMs + delay*1000`;只有 begin 的行(终点缺失或退化,
 * `lineEndMs <= lineStartMs`)按上游额外 +5s:`lineStartMs + (delay + 5)*1000`。
 * delay 内部钳到 0-30。
 */
internal fun tailDeadlineMs(lineStartMs: Long, lineEndMs: Long, delaySeconds: Int): Long {
    val delay = normalizeTailTimeoutDelaySeconds(delaySeconds)
    return if (lineEndMs <= lineStartMs) {
        lineStartMs + (delay + TAIL_BEGIN_ONLY_EXTRA_SECONDS) * 1_000L
    } else {
        lineEndMs + delay * 1_000L
    }
}

/**
 * 本面尾奏是否已到点(共享纯函数):[tailEligible] 为 false 直接 false;否则用
 * [effectivePositionMs] 与 [tailDeadlineMs] 判定。息屏与锁屏各传自己的
 * `tailTimeoutDelaySeconds`,同一快照可以得出不同结论。
 */
internal fun tailTimeoutActive(
    tailEligible: Boolean,
    lineStartMs: Long,
    lineEndMs: Long,
    positionMs: Long,
    sampledAtElapsedMs: Long,
    speed: Float,
    nowElapsedMs: Long,
    delaySeconds: Int
): Boolean {
    if (!tailEligible) return false
    val effectivePosition = effectivePositionMs(
        positionMs = positionMs,
        sampledAtElapsedMs = sampledAtElapsedMs,
        speed = speed,
        nowElapsedMs = nowElapsedMs
    )
    return effectivePosition >= tailDeadlineMs(lineStartMs, lineEndMs, delaySeconds)
}

/**
 * 有效播放位置:`positionMs + max(0, now - sampledAtElapsedMs) * speed`。
 * `speed <= 0`(暂停冻结)时直接取 positionMs,位置不前进。
 */
internal fun effectivePositionMs(
    positionMs: Long,
    sampledAtElapsedMs: Long,
    speed: Float,
    nowElapsedMs: Long
): Long {
    if (speed <= 0f) return positionMs
    val elapsedMs = (nowElapsedMs - sampledAtElapsedMs).coerceAtLeast(0L)
    return positionMs + (elapsedMs * speed).toLong()
}

internal fun effectivePositionMs(state: LyricProducerState, nowElapsedMs: Long): Long =
    effectivePositionMs(
        positionMs = state.positionMs,
        sampledAtElapsedMs = state.sampledAtElapsedMs,
        speed = state.speed,
        nowElapsedMs = nowElapsedMs
    )

/** 只有 begin 的行额外等待的秒数(对齐上游 HyperLyric 7.9 `fdeb94f`)。 */
internal const val TAIL_BEGIN_ONLY_EXTRA_SECONDS = 5
