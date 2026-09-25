package com.eza.hyperglow.producer

import android.os.SystemClock

/**
 * Derives whether a pushed playback position is a seek, so callers only push positions
 * and never re-implement jump heuristics (issue #68 section 13, item #19).
 *
 * 判定规则(每次判定后立即 rebase,连续推送互不干扰):
 * - 进度倒退恒判跳转;
 * - 播放中应有推进量 = 物理时钟增量,容差 max(150ms, 物理增量×0.5)——容纳倍速与推送节奏不均;
 * - 暂停中应有推进量 = 0,容差固定 150ms——任何超限前进都是跳转;
 * - 单次判定的物理时钟跨度信任上限 800ms,防挂起恢复后把整段停滞误判;
 * - 首次推送只建基线、不判跳转——重建歌词/页面恢复等强制对齐场景由调用方显式传 seek,
 *   不由本类代判(把本质连续的恢复播放误判成跳转)。
 *
 * [now] 注入单调时钟,单测注入假时钟即可,无 Robolectric 依赖。
 */
class LyricSeekDetector(private val now: () -> Long = SystemClock::elapsedRealtime) {

    private var lastPositionMs = 0L
    private var lastWallMs = 0L
    private var hasBaseline = false

    /** @return true 当本次推送应按跳转处理(snap 选中行,不做追赶动画)。 */
    fun detect(positionMs: Long, isPlaying: Boolean): Boolean {
        val wall = now()
        if (!hasBaseline) {
            rebase(positionMs, wall)
            return false
        }
        if (positionMs < lastPositionMs) {
            rebase(positionMs, wall)
            return true
        }
        val mediaDelta = positionMs - lastPositionMs
        val wallDelta = (wall - lastWallMs).coerceIn(0L, MAX_TRUSTED_GAP_MS)
        rebase(positionMs, wall)
        val expected = if (isPlaying) wallDelta else 0L
        val tolerance = if (isPlaying) {
            maxOf(JITTER_TOLERANCE_MS, (wallDelta * DRIFT_SLACK).toLong())
        } else {
            JITTER_TOLERANCE_MS
        }
        return mediaDelta - expected > tolerance
    }

    /** 换歌/停止推送时调用,下次推送重新建基线。 */
    fun reset() {
        hasBaseline = false
        lastPositionMs = 0L
        lastWallMs = 0L
    }

    private fun rebase(positionMs: Long, wallMs: Long) {
        lastPositionMs = positionMs
        lastWallMs = wallMs
    }

    companion object {
        internal const val JITTER_TOLERANCE_MS = 150L
        internal const val DRIFT_SLACK = 0.5
        internal const val MAX_TRUSTED_GAP_MS = 800L
    }
}
