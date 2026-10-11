package com.eza.hyperglow.producer

/**
 * Decision rule for the position-silence watchdog. Fires only when ALL hold:
 * - `playing`: during a real pause the position stream going quiet is expected, not a fault;
 * - silence beyond [LyriconLyricProducer.POSITION_SILENCE_RESUBSCRIBE_MS]: the ~60 Hz feed
 *   stopping entirely (a frozen writer still fires callbacks with the stalled value, so total
 *   silence means the callback path itself died — the 12:26 capture sat at age=519s);
 * - the previous attempt is older than `cooldownMs` (default
 *   [LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS], backed off after repeated ineffective
 *   rebuilds — see [resubscribeCooldownMs]) so a persistent failure retries at most once per
 *   cooldown window instead of hammering IPC every poll.
 */
internal fun shouldForceResubscribePositionFeed(
    silenceMs: Long,
    playing: Boolean,
    sinceLastAttemptMs: Long,
    cooldownMs: Long = LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS
): Boolean = playing &&
    silenceMs > LyriconLyricProducer.POSITION_SILENCE_RESUBSCRIBE_MS &&
    sinceLastAttemptMs > cooldownMs

/**
 * Decision rule for the song-feed watchdog (issue #64). Fires only when ALL hold:
 * - `playing`: during a real pause/stop the absence of new song data is expected, not a
 *   fault — same guard as the position-silence watchdog;
 * - `positionAdvancing`: the position VALUE changed within the freshness window. A frozen
 *   value means the player is stopped/frozen, so no new song should be expected and the
 *   watchdog must not fire (this is what keeps the issue #27 stop-detector teardown —
 *   which clears the song while the stale feed keeps repeating the old value — from
 *   triggering pointless resubscribes);
 * - the song channel is provably stale: `songAbsentMs` beyond
 *   [LyriconLyricProducer.SONG_ABSENCE_RESUBSCRIBE_MS] (no song data at all while the
 *   position feed is alive — the #64 half-dead state; -1 while a song is loaded), or
 *   `providerSyncPendingMs` beyond [LyriconLyricProducer.PROVIDER_SYNC_GRACE_MS] (the
 *   provider switched but the SDK never delivered the new song; -1 when none is pending);
 * - the previous forced resubscribe (any watchdog) is older than `cooldownMs` (default
 *   [LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS], backed off after repeated ineffective
 *   rebuilds — see [resubscribeCooldownMs]), so a persistent failure retries at
 *   most once per cooldown window instead of hammering IPC every poll.
 */
internal fun shouldForceResubscribeSongFeed(
    playing: Boolean,
    songAbsentMs: Long,
    providerSyncPendingMs: Long,
    positionAdvancing: Boolean,
    sinceLastAttemptMs: Long,
    cooldownMs: Long = LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS
): Boolean {
    if (!playing || !positionAdvancing) return false
    if (sinceLastAttemptMs <= cooldownMs) return false
    val songMissing = songAbsentMs > LyriconLyricProducer.SONG_ABSENCE_RESUBSCRIBE_MS
    val providerSyncStale = providerSyncPendingMs > LyriconLyricProducer.PROVIDER_SYNC_GRACE_MS
    return songMissing || providerSyncStale
}

/**
 * 看门狗的「在播」判定。回调链死亡时 `onPlaybackStateChanged` 不再来,
 * [LyriconLyricProducer.isPlayingState] 会冻结在 false,两个重建看门狗就都成了盲区
 * (0.3.120 真机:订阅后回调全聋、playing 冻结,看门狗永不触发,只能重启 app)。因此以
 * MediaSession 观测到的真实播放态兜底:任一为在播即视为在播;会话未知(null)不改变结论。
 */
internal fun watchdogPlaying(
    statePlaying: Boolean,
    sessionPlaying: Boolean?
): Boolean = statePlaying || sessionPlaying == true

/** [classifyResubscribeOutcome] 的判定结果。 */
internal enum class ResubscribeOutcome {
    /** 验证窗未结束,还判不出成败。 */
    PENDING,

    /** 重建后收到了 onPositionChanged —— 位置通道已被拉活。 */
    RECOVERED,

    /** 重建无效:SDK 拒绝订阅,或验证窗内播放中仍无 onPositionChanged。 */
    FAILED,

    /** 验证窗结束时不在播 —— 位置流安静是预期行为,本窗判不出成败(不计数)。 */
    INCONCLUSIVE
}

/**
 * 2.10:强制重建订阅的成败判定 —— 「重建后 [LyriconLyricProducer.RESUBSCRIBE_VERIFY_WINDOW_MS]
 * 内是否收到 onPositionChanged」。纯函数(时钟由调用方注入),驱动失败计数与冷却退避。
 *
 * 旧实现丢弃 `subscribeActivePlayer` 返回值并无条件重锚静默基线,重建失败与成功无从区分;
 * 这里把两个判据都纳入:
 * - [subscribeAccepted] 为 false(SDK 拒绝订阅)时本次重建不可能恢复回调链 —— 无需等
 *   验证窗,直接判 [ResubscribeOutcome.FAILED];
 * - [positionArrivedSinceResubscribe]:位置流(~60Hz)一旦被拉活毫秒级就有回调,验证窗内
 *   没有即 [ResubscribeOutcome.FAILED];
 * - [playing] 为 false 时位置流安静是预期行为,判 [ResubscribeOutcome.INCONCLUSIVE] ——
 *   暂停导致的「无位置回调」不得计为重建失败。
 */
internal fun classifyResubscribeOutcome(
    subscribeAccepted: Boolean,
    positionArrivedSinceResubscribe: Boolean,
    sinceRebuildMs: Long,
    playing: Boolean
): ResubscribeOutcome = when {
    positionArrivedSinceResubscribe -> ResubscribeOutcome.RECOVERED
    !subscribeAccepted -> ResubscribeOutcome.FAILED
    sinceRebuildMs < LyriconLyricProducer.RESUBSCRIBE_VERIFY_WINDOW_MS -> ResubscribeOutcome.PENDING
    playing -> ResubscribeOutcome.FAILED
    else -> ResubscribeOutcome.INCONCLUSIVE
}

/**
 * 2.10:连续无效重建后的冷却退避。未达
 * [LyriconLyricProducer.RESUBSCRIBE_FAILURE_ESCALATION_N] 次时保持基础冷却(健康路径
 * 节奏不变);达到阈值后按 2 的幂增长(阈值处即 2×,之后 4×→8×…),上限
 * [LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MAX_MS] —— 已证实无效的重建不再按基础
 * 节奏轰炸 IPC,但重试不会停止。
 */
internal fun resubscribeCooldownMs(consecutiveFailures: Int): Long {
    val escalationN = LyriconLyricProducer.RESUBSCRIBE_FAILURE_ESCALATION_N
    if (consecutiveFailures < escalationN) return LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS
    val shift = (consecutiveFailures - escalationN + 1).coerceAtMost(3)
    return (LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS shl shift)
        .coerceAtMost(LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MAX_MS)
}
