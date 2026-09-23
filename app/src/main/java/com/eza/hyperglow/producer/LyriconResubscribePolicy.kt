package com.eza.hyperglow.producer

/**
 * Decision rule for the position-silence watchdog. Fires only when ALL hold:
 * - `playing`: during a real pause the position stream going quiet is expected, not a fault;
 * - silence beyond [LyriconLyricProducer.POSITION_SILENCE_RESUBSCRIBE_MS]: the ~60 Hz feed
 *   stopping entirely (a frozen writer still fires callbacks with the stalled value, so total
 *   silence means the callback path itself died — the 12:26 capture sat at age=519s);
 * - the previous attempt is older than [LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS] so a
 *   persistent failure retries at most once per cooldown window instead of hammering IPC every
 *   poll.
 */
internal fun shouldForceResubscribePositionFeed(
    silenceMs: Long,
    playing: Boolean,
    sinceLastAttemptMs: Long
): Boolean = playing &&
    silenceMs > LyriconLyricProducer.POSITION_SILENCE_RESUBSCRIBE_MS &&
    sinceLastAttemptMs > LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS

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
 * - the previous forced resubscribe (any watchdog) is older than
 *   [LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS], so a persistent failure retries at
 *   most once per cooldown window instead of hammering IPC every poll.
 */
internal fun shouldForceResubscribeSongFeed(
    playing: Boolean,
    songAbsentMs: Long,
    providerSyncPendingMs: Long,
    positionAdvancing: Boolean,
    sinceLastAttemptMs: Long
): Boolean {
    if (!playing || !positionAdvancing) return false
    if (sinceLastAttemptMs <= LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS) return false
    val songMissing = songAbsentMs > LyriconLyricProducer.SONG_ABSENCE_RESUBSCRIBE_MS
    val providerSyncStale = providerSyncPendingMs > LyriconLyricProducer.PROVIDER_SYNC_GRACE_MS
    return songMissing || providerSyncStale
}
