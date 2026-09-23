package com.eza.hyperglow.producer

import com.eza.hyperglow.AppLog

/**
 * Advance [currentPositionMs] by wall-clock extrapolation from [lastRealPositionMs], unless the
 * player is paused (freeze) or the extrapolation budget has been exhausted (mark position
 * unknown). Called whenever the shared-memory position is still instead of a real update
 * (residual / seek-residual / stalled).
 *
 * - Paused: the real position is frozen, so the lyric position must not advance. The position
 *   base is re-based onto the displayed (extrapolated) pause point (issue #10: the
 *   shared-memory writer may have been stale long before the pause; a pause is a real stop
 *   point, so the resume must continue from there instead of re-crawling from a stale base).
 * - Past the song end (duration known): clamp [currentPositionMs] to the duration and hold —
 *   the song-end branch in [recomputeAndEmit] clears the line and shows a stable placeholder
 *   exactly once (issue #9: previously each stalled callback re-entered extrapolation and
 *   re-cleared the line every frame at 60 Hz).
 * - Over [LyriconLyricProducer.MAX_EXTRAPOLATION_MS] while the extrapolation is still within the song duration:
 *   the writer is Doze-frozen but playback is still active (the canonical AOD scenario) —
 *   keep advancing (issue #3).
 * - Over [LyriconLyricProducer.MAX_EXTRAPOLATION_MS] with no duration known: the writer is dead (not merely
 *   screen-off frozen) — mark [positionUnknown] and stop advancing.
 */
internal fun LyriconLyricProducer.advanceExtrapolation(now: Long, reason: String) {
    if (!isPlayingState) {
        if (extrapolating) {
            // 暂停即真实停点(issue #10 追加实测):暂停事件可能晚于首个 stalled 回调
            // 到达,此处与 onPlaybackStateChanged 的暂停分支做同样的 re-base —— 把展示
            // (外推)位置固化为新基准,并记录旧陈旧基准用于拒绝写入端的残留回传。
            pauseStaleRejectMs = lastRealPositionMs
            lastRealPositionMs = currentPositionMs
            lastRealPositionClockMs = now
            extrapolating = false
            AppLog.i(
                "LyriconLyricProducer",
                "pause: extrapolation frozen ($reason), re-based to ${currentPositionMs}ms " +
                    "(rejecting stale ${pauseStaleRejectMs}ms)"
            )
        } else {
            // 未在推进:展示位置即暂停点,保持不动(幂等)。
            currentPositionMs = lastRealPositionMs
        }
        return
    }
    if (lastRealPositionClockMs < 0L) return
    val sinceRealMs = now - lastRealPositionClockMs
    val duration = currentSong?.duration ?: 0L
    val projected = lastRealPositionMs + sinceRealMs
    // 歌尾稳定钳制(issue #9):外推一旦越过歌曲时长,位置直接钳到 duration 并保持,
    // 不翻转 extrapolating、不清行 —— 清行/日志/占位由 recomputeAndEmit 的歌尾分支
    // 统一处理且只处理一次。否则每个 stalled 回调都会重走
    // "越界→清空→占位" 重建循环:60Hz 日志刷屏 + extrapolating 每帧反复翻转。
    if (duration > 0L && projected >= duration) {
        currentPositionMs = duration
        return
    }
    if (sinceRealMs > LyriconLyricProducer.MAX_EXTRAPOLATION_MS) {
        // Doze 冻结共享内存写入端(音乐仍在播)是 AOD 最常见场景:写入端可能整首歌都不
        // 恢复。只要外推仍在歌曲时长内,继续推进而不是 45s 一到就清空歌词行——否则
        // 每次息屏约 45s 后歌词必然消失(issue #3)。到达此处且 duration>0 时必有
        // projected<duration(上方歌尾钳制已早退);越过歌尾不再标记 positionUnknown,
        // 而是钳在歌尾稳定占位,等真实位置恢复。
        if (duration > 0L) {
            currentPositionMs = projected
            if (!extrapolating) {
                extrapolating = true
                AppLog.w(
                    "LyriconLyricProducer",
                    "extrapolation past ${LyriconLyricProducer.MAX_EXTRAPOLATION_MS}ms budget ($reason) but within " +
                        "song (pos=${projected}ms duration=${duration}ms); continuing — " +
                        "Doze writer freeze with playback still active"
                )
            }
            return
        }
        // 无时长信息(如 LRC 行级源):写入端按死亡处理,位置冻结在预算值。
        if (!positionUnknown) {
            extrapolating = false
            positionUnknown = true
            currentPositionMs = lastRealPositionMs + LyriconLyricProducer.MAX_EXTRAPOLATION_MS
            AppLog.w(
                "LyriconLyricProducer",
                "extrapolation exceeded ${LyriconLyricProducer.MAX_EXTRAPOLATION_MS}ms ($reason); marking position unknown"
            )
        }
        return
    }
    currentPositionMs = lastRealPositionMs + sinceRealMs
    // Stale one-shot warning (writer may be dead) — observability only, does not gate behavior.
    if (lastRealPositionUpdateMs >= 0L &&
        now - lastRealPositionUpdateMs > LyriconLyricProducer.STALE_POSITION_THRESHOLD_MS &&
        lastRealPositionUpdateMs != Long.MAX_VALUE
    ) {
        lastRealPositionUpdateMs = Long.MAX_VALUE // one-shot log
        val staleSec = (now - lastRealPositionClockMs) / 1000
        val duration = currentSong?.duration ?: 0L
        AppLog.w(
            "LyriconLyricProducer",
            "position stale for ${staleSec}s (last real=${lastRealPositionMs}ms " +
                "extrapolated=${currentPositionMs}ms duration=${duration}ms)" +
                if (duration > 0L && currentPositionMs > duration) {
                    " — song may have looped"
                } else {
                    " — shared-memory writer may be dead"
                }
        )
    }
    if (!extrapolating) {
        extrapolating = true
        AppLog.i(
            "LyriconLyricProducer",
            "position stalled, extrapolating ($reason): base=${lastRealPositionMs}ms " +
                "elapsed=${sinceRealMs}ms -> ${currentPositionMs}ms"
        )
    }
}

/**
 * Find the active line for [currentPositionMs] via [TimingNavigator], rebuild the per-word
 * cache only when the line changes, then emit a fresh [LyricProducerState].
 *
 * Called at ~60 Hz from [onPositionChanged]; the word-list allocation is amortized by
 * caching across position-only updates within the same line.
 */
internal fun LyriconLyricProducer.recomputeAndEmit() {
    val nav = navigator ?: return emit() // no lyrics yet; emit metadata-only state
    val song = currentSong ?: return
    val pos = currentPositionMs

    // 位置未知(外推超过预算且数据源尚未恢复):清空活动行,稳定显示占位,而不是把歌词
    // 一路推进到歌尾。等真实位置恢复或 onSongChanged / onSeekTo 到来清除 positionUnknown
    // 后再重新选行。
    if (positionUnknown) {
        if (currentLineIndex != -1) {
            currentLineIndex = -1
            cachedWords = null
        }
        emit()
        return
    }

    // 歌曲边界处理:息屏后数据源(如网易云)停止写位置,外推会越过歌曲时长继续累加。
    //
    // 旧实现用模运算把位置回绕到时长内(pos % duration),但这会让位置在 [0, duration) 间
    // 反复循环累加:每次回绕到 ~0ms 时 findTargetIndex 选不到行、活动行被清空,而投影层
    // 因 sampledAtElapsedMs==now 又把回绕后的低位置判为「回到开头」的有效位置
    // (extrapolationReliable 判定可信),于是行被反复选中/清空 → AOD '🎶' 占位闪烁 +
    // SystemUI 对相同占位 state 无去重的重建风暴(错误清单 #2/#3/#4)。
    //
    // 正确语义:外推一旦越过歌曲时长,说明当前这首歌已播完,之后不再有更多行。此时应
    // 清空活动行并结束外推,让投影层稳定显示占位;同时保持位置不变以触发状态去重,
    // 避免 60Hz 重复投递。等数据源写回真实位置(重播/切歌)或 onSongChanged 到来时再校正。
    val duration = song.duration
    if (duration > 0L && pos >= duration) {
        // 真实或外推位置越过歌曲时长(外推路径已在 advanceExtrapolation 钳到 duration;
        // 真实位置也可能直接上报越界值 —— Doze 下共享内存残留/未回绕的循环基准,
        // issue #9 日志中 base 远超 duration 的场景)。统一钳制 + 清行 + 稳定占位,
        // 且只在状态实际变化时记日志,避免 60Hz 每帧 "越界→清空→占位" 重建循环刷屏。
        val changed = extrapolating || currentLineIndex != -1
        currentPositionMs = duration
        extrapolating = false
        if (currentLineIndex != -1) {
            currentLineIndex = -1
            cachedWords = null
        }
        if (changed) {
            AppLog.i(
                "LyriconLyricProducer",
                "position reached song end: pos=${pos}ms capped=${duration}ms " +
                    "(duration=${duration}ms); holding stable placeholder"
            )
        }
        emit()
        return
    }

    val idx = nav.findTargetIndex(currentPositionMs)
    if (idx < 0) {
        // Before the first line: no current line yet.
        if (currentLineIndex != -1) {
            currentLineIndex = -1
            cachedWords = null
        }
        emit()
        return
    }
    // 最后一句歌词唱完后（position 越过其 end，歌曲进入尾奏/纯器乐段落），清空活动行
    // 让投影显示 🎶 占位，而不是把最后一句滞留到歌曲结束。TimingNavigator 选择的是
    // 「最后一条 begin <= pos」的行，不检查 end，所以这里显式兜住结尾。
    if (idx == nav.source.size - 1 && currentPositionMs >= nav.source[idx].end) {
        if (currentLineIndex != -1) {
            currentLineIndex = -1
            cachedWords = null
        }
        emit()
        return
    }
    if (idx != currentLineIndex) {
        currentLineIndex = idx
        val line = nav.source[idx]
        cachedWords = line.toLyricWords()
        AppLog.i(
            "LyriconLyricProducer",
            "line changed: idx=$idx begin=${line.begin} end=${line.end} text=${line.text?.take(24)}"
        )
    }
    emit()
}

/**
 * Build and emit a [LyricProducerState] from the current ingress fields. Cheap: reuses
 * [cachedWords] (only rebuilt on line change) and [renderModesSnapshot] (only rebuilt on
 * song change). Safe to call at 60 Hz.
 */
internal fun LyriconLyricProducer.emit() {
    val song = currentSong ?: run { mutableState.value = null; return }
    val now = clock()
    val lyrics = song.lyrics
    val line = currentLineIndex.let { idx ->
        if (idx < 0) null else navigator?.source?.getOrNull(idx)
    }
    // Active-row fields (spec clause 6: producer computes the active line before emitting).
    // lyricKind is per-active-line when a line is active; otherwise song-level, so the engine
    // can still tell "timed lyrics exist, between lines" (INTERLUDE) from "no lyrics" (NONE).
    val hasTimedLyrics = !lyrics.isNullOrEmpty() &&
        lyrics.any { it.end > it.begin }
    val lyricKind = when {
        lyrics.isNullOrEmpty() -> LyricKind.NONE
        line != null -> if (!line.words.isNullOrEmpty()) LyricKind.SYLLABLE else LyricKind.LINE
        else -> if (lyrics.any { !it.words.isNullOrEmpty() }) LyricKind.SYLLABLE
            else LyricKind.LINE
    }
    val nextLineStartMs = lyrics
        ?.asSequence()
        ?.map { it.begin }
        ?.filter { it > currentPositionMs }
        ?.minOrNull()
    val nextLineText = lyrics
        ?.asSequence()
        ?.firstOrNull { it.begin > currentPositionMs }
        ?.text
        .orEmpty()
    sequence++
    mutableState.value = LyricProducerState(
        producerId = LyriconLyricProducer.PRODUCER_ID,
        generation = generation,
        sequence = sequence,
        status = "ready",
        trackUri = "lyricon:${song.id ?: song.name}",
        title = song.name.orEmpty(),
        artist = song.artist.orEmpty(),
        album = "",
        imageId = "",
        line = line?.text.orEmpty(),
        romanizedLine = line?.roma.orEmpty(),
        translatedLine = line?.translation.orEmpty(),
        lineIndex = currentLineIndex,
        positionMs = currentPositionMs,
        durationMs = song.duration,
        sampledAtElapsedMs = now,
        speed = if (isPlayingState) 1f else 0f,
        playing = isPlayingState,
        receivedAtElapsedMs = now,
        words = cachedWords,
        renderModes = renderModesSnapshot,
        lyricKind = lyricKind,
        // Lyricon carries no alignment / ruby / layout-group concepts; defaults are correct.
        alignedRight = false,
        lineStartMs = line?.begin ?: 0L,
        lineEndMs = line?.end ?: 0L,
        ruby = emptyList(),
        layoutGroups = emptyList(),
        hasTimedLyrics = hasTimedLyrics,
        nextLineStartMs = nextLineStartMs,
        nextLine = nextLineText
    )
}
