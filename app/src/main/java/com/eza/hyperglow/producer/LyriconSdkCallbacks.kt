package com.eza.hyperglow.producer

import com.eza.hyperglow.AppLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo

/**
 * SDK callback mapping for [LyriconLyricProducer]: lyricon subscriber callbacks ->
 * producer ingress state. Extracted from the producer class body (issue #18); the
 * listener objects capture the producer as the enclosing extension receiver, so
 * member references resolve unchanged against its internal state.
 */
internal fun LyriconLyricProducer.createConnectionListener(): ConnectionListener =
    object : ConnectionListener {
        override fun onConnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "connected")
            // issue #56 判定窗口只在「订阅动作」处武装(start/强制重建订阅/断连/连接超时),
            // 不在这里复位:真机实测 SDK 会先投递补发的 onSongChanged、12ms 后才回调本方法
            // (2026-09-29《淑女的品格》整首无歌词),迟到的连接回调复位窗口会把已被补发
            // 消费的窗口重新打开,下一首真·切歌被误判为重同步(保留旧位置 + 门控敞开),
            // 旧歌冻结残留被当真实位置接受、跳歌尾钳制清行,余下整首只剩占位。
            mutableConnection.value = ProducerConnection.CONNECTED
        }

        override fun onReconnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "reconnected")
            // 同 onConnected:窗口已在断连处武装(见 onDisconnected),这里不动 —— 补发
            // 无论先于还是晚于本回调到达都按重同步处理。
            mutableConnection.value = ProducerConnection.RECONNECTED
        }

        override fun onDisconnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "disconnected")
            // 重连后 SDK 会补发当前歌 —— 在断连点武装重同步判定窗口(issue #56)。
            songSeenSinceSubscribe = false
            mutableConnection.value = ProducerConnection.DISCONNECTED
            mutableState.value = null
        }

        override fun onConnectTimeout(s: LyriconSubscriber) {
            AppLog.w("LyriconLyricProducer", "connect timeout")
            // 同 onDisconnected:之后若连接成功,SDK 补发的当前歌按重同步处理。
            songSeenSinceSubscribe = false
            mutableConnection.value = ProducerConnection.CONNECT_TIMEOUT
            mutableState.value = null
        }
    }

internal fun LyriconLyricProducer.createPlayerListener(): ActivePlayerListener =
    object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            val pkg = providerInfo?.providerPackageName
            AppLog.i("LyriconLyricProducer", "provider=$pkg")
            resetStopDetection()
            if (providerInfo == null) {
                activeProviderPackage = null
                // No active player: clear state, let arbiter fall back / go idle.
                resetToIdle("onActiveProviderChanged: null (no active player)")
            } else {
                // issue #64:provider 切换后 SDK 应补发 onSongChanged;若始终不到(歌曲
                // 通道半死),歌曲侧看门狗会在宽限期后强制重建订阅。相同包名的重复回调
                // 不重开宽限。
                if (pkg != activeProviderPackage) {
                    providerSyncPendingSinceMs = clock()
                }
                activeProviderPackage = pkg
            }
        }

        override fun onSongChanged(song: Song?) {
            if (song == null) {
                AppLog.i("LyriconLyricProducer", "onSongChanged: null (cleared)")
                resetStopDetection()
                resetToIdle("onSongChanged: null")
                return
            }
            resetStopDetection()
            AppLog.i(
                "LyriconLyricProducer",
                "onSongChanged: id=${song.id} name=${song.name} artist=${song.artist} " +
                    "duration=${song.duration}ms lines=${song.lyrics?.size ?: 0}"
            )
            // normalize() deep-copies and sorts lyrics by begin (asc, required by TimingNavigator),
            // dropping invalid lines. Safe to call on the SDK's instance (it doesn't mutate it).
            val normalized = song.normalize()
            // P0 时间轴可信化(2026-09-28 真机「时间轴对不上」定论):逐字合成/强制对齐会把
            // 乐器间隙吞进行窗(实测下一句提前 15s 上屏)、把假词级时间铺满间隙,这里在
            // 选行/分侧/快照同源消费之前修复(只动明显损坏的行,见 LyriconTimelineRepair)。
            val sourceLyrics = normalized.lyrics
            val repairedLyrics = LyriconTimelineRepair.repair(sourceLyrics.orEmpty())
            val current = if (sourceLyrics != null && repairedLyrics !== sourceLyrics) {
                normalized.copy(lyrics = repairedLyrics)
            } else {
                normalized
            }
            // 记录本次回调前的歌曲身份:补发窗口内若 id 变了,说明切歌恰落在订阅/重连窗口,
            // 旧歌时间线必须作废(见下方 issue #56 分支)。
            val priorSongId = currentSong?.id
            currentSong = current
            // issue #64:歌曲通道恢复 —— 结束缺席纪元与 provider 等歌宽限,复位去重日志。
            songAbsentSinceMs = -1L
            providerSyncPendingSinceMs = -1L
            noSongDropLogged = false
            generation++
            val lyrics = current.lyrics
            navigator = if (!lyrics.isNullOrEmpty()) {
                TimingNavigator(lyrics.toTypedArray())
            } else {
                null
            }
            currentLineIndex = -1
            cachedWords = null
            // 对唱左右分侧快照随歌重算(身份排序依赖整首出现顺序,不能按单行现算);
            // 无歌词时空数组,emit() 侧按越界回落到未分侧。
            refreshDuetAlignment(lyrics.orEmpty())
            // issue #56:区分「真的切歌」与「(重)连后 SDK 补发的当前歌」。冷启动/重连后 SDK
            // 会对正在播放的歌回调一次 onSongChanged —— 此时位置可能已到歌中途;若按切歌处理
            // (归零 + 关闸),首个真实位置会被合理性门控当旧时间线残留拒绝,歌词从第 1 句重新
            // 开始、整条时间轴平移「已播时长」。只有本次连接会话内已见过歌时才算切歌。
            val songChangedInSession = songSeenSinceSubscribe
            songSeenSinceSubscribe = true
            // 但补发携带的歌与上一首不同(切歌恰好落在订阅/重连窗口内)时,旧歌时间线必须
            // 作废:归零并登记旧末位置精确拒收。门控仍保持敞开 —— 新歌可能已播到歌中途,
            // 首个真实位置仍须直接采信(与重同步同语义,只是时间轴基准换到新歌)。
            val songChangedAcrossReconnect = priorSongId != null && priorSongId != song.id
            if (songChangedInSession || songChangedAcrossReconnect) {
                // Reset position tracking for the new song. The shared memory may still hold the
                // previous song's position until the player writes the new one, which caused the
                // active line to jump to a stale index (e.g. idx=64 on song change).
                //
                // Capture the previous song's last position so onPositionChanged can reject the
                // residual value (it will keep arriving at ~60 Hz until the player writes new progress).
                // Enable extrapolation from 0 so lyrics advance during the write gap if playing.
                previousSongLastPositionMs = lastRealPositionMs
                currentPositionMs = 0L
                lastRealPositionMs = 0L
                lastRealPositionClockMs = clock()
                extrapolating = false
                positionUnknown = false
                pauseStaleRejectMs = -1L
                // Close the post-song-change plausibility gate (issue #11) for an in-session
                // change (the song starts from 0): the next real position must be plausible for a
                // song that starts now, or it is old-timeline residual. A backfill carrying a
                // changed id keeps the gate open — the new song may already be mid-playback.
                songStartGateOpen = !songChangedInSession
                songStartClockMs = lastRealPositionClockMs
                gateRateAnchorPosMs = -1L
                gateRateAnchorClockMs = 0L
                gateRateX = 1.0
                gateFrozenRejectMs = -1L
                if (songChangedAcrossReconnect && !songChangedInSession) {
                    AppLog.i(
                        "LyriconLyricProducer",
                        "onSongChanged: re-sync window but song changed ($priorSongId -> ${song.id}); " +
                            "timeline reset, residual filter armed, plausibility gate open"
                    )
                }
            } else {
                // (Re)connect re-sync (issue #56): the song may already be mid-playback, so the
                // shared-memory position IS this song's timeline. Keep it, keep the plausibility
                // gate open — the next real position locates the active line directly instead of
                // restarting the timeline from 0.
                //
                // 防御纵深:精确匹配的残留过滤器(previousSongLastPositionMs/seekRejectPositionMs/
                // pauseStaleRejectMs)保持武装,不再清空 —— 切歌/暂停/seek 后写入端冻结时旧值
                // 会以 ~60Hz 续吐,即便判定被误入或切歌恰落在重连窗口内,该值仍须继续被拒;
                // 真·重同步的首个真实位置与残留值不同,立即放行,不受影响。
                songStartGateOpen = true
                gateFrozenRejectMs = -1L
                AppLog.i(
                    "LyriconLyricProducer",
                    "onSongChanged: (re)connect re-sync; keeping position ${lastRealPositionMs}ms, " +
                        "plausibility gate open"
                )
            }
            gateRejectLogged = false
            refreshRenderModes()
            emit()
        }

        override fun onReceiveText(text: String?) {
            // Plain-text lyrics (no timestamps). Out of scope for karaoke AOD; ignore.
            AppLog.i("LyriconLyricProducer", "onReceiveText: len=${text?.length} (ignored)")
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            AppLog.i("LyriconLyricProducer", "onPlaybackStateChanged: playing=$isPlaying")
            isPlayingState = isPlaying
            if (isPlaying) {
                // When resuming playback after a pause, reset the extrapolation clock so we don't
                // jump forward by the entire pause duration on the next stalled position callback.
                if (lastRealPositionClockMs >= 0L) {
                    lastRealPositionClockMs = clock()
                }
            } else {
                // When paused, freeze extrapolation: the real position is frozen, so
                // currentPositionMs must stop advancing too. Previously a long pause kept
                // extrapolating the lyric all the way to the song end.
                if (extrapolating) {
                    // 暂停即真实停点(issue #10 追加实测):位置源在 AOD 下可能早已 stalled,
                    // lastRealPositionMs 停在陈旧值(实测 16665ms),而媒体真实暂停点已推进到
                    // ~23435ms(≈此刻的外推展示位置)。把展示位置固化为新基准,继续播放后从
                    // 该点起跑,而不是从陈旧值重新外推导致歌词行跳回更早的行再爬行。
                    // 记录旧陈旧基准:写入端若仍冻结,会持续回传该值,须在
                    // onPositionChanged 中拒绝(见 pauseStaleRejectMs)。
                    pauseStaleRejectMs = lastRealPositionMs
                    lastRealPositionMs = currentPositionMs
                    lastRealPositionClockMs = clock()
                    extrapolating = false
                    AppLog.i(
                        "LyriconLyricProducer",
                        "pause: extrapolation frozen, re-based to ${lastRealPositionMs}ms " +
                            "(rejecting stale ${pauseStaleRejectMs}ms)"
                    )
                }
            }
            // Re-emit so the engine sees the new playing/speed without waiting for next position.
            emit()
        }

        override fun onPositionChanged(position: Long) {
            // High-frequency (~60 Hz) callback on Dispatchers.Default. This IS the SharedMemory
            // position, delivered by the SDK's internal poller. Compute the active line and emit.
            val now = clock()
            // Feed heartbeat for the position-silence watchdog (see maybeResubscribeOnSilence).
            lastPositionCallbackElapsedMs = now
            // issue #64:歌曲侧看门狗的「位置在推进」心跳 —— 值在变化证明播放器真在播、
            // SDK 位置通道活着(冻结的写入端只会重复同一值)。独立于下方所有残留/门控分支。
            if (position != lastPositionFeedValueMs) {
                lastPositionFeedValueMs = position
                lastAdvancingPositionClockMs = now
            }
            // issue #64 建议三:位置到了但没有歌曲数据时留一条去重告警,现场可直接区分
            // 「位置没来」与「歌曲没来」;每个无歌纪元只输出一次。
            if (currentSong == null && !noSongDropLogged) {
                noSongDropLogged = true
                AppLog.w(
                    "LyriconLyricProducer",
                    "no song loaded; dropping position ${position}ms " +
                        "(playing=$isPlayingState provider=$activeProviderPackage)"
                )
            }
            // Reject residual values from the previous song: after onSongChanged, the shared
            // memory may keep returning the old position until the player writes new progress.
            // The residual matches the previous song's last position exactly (same bytes in memory).
            // We keep rejecting it until a different (real) position arrives — not just within a
            // fixed window — because NetEase's outro + intro can leave the position source silent
            // for the whole prelude (~60s) and the first value on resume is still the old song's
            // position. Accepting it would locate an old-song position against the new lyric table.
            val isResidual = previousSongLastPositionMs >= 0L &&
                position == previousSongLastPositionMs
            if (isResidual) {
                // Reject the stale value: advance by wall-clock extrapolation from the last real
                // position (freezing when paused / marking unknown once the budget is exceeded),
                // instead of advancing regardless of the playing flag — that is what previously
                // let a long pause extrapolate all the way to the song end.
                advanceExtrapolation(now, "residual ${position}ms matches previous song")
                recomputeAndEmit()
                return
            }
            // Reject the pre-seek stale value that lingers right after a seek. The old value
            // (still in shared memory) != the seek target, so without this it would be accepted
            // by the "resumed" branch below and snap the active line back to the old position.
            val isSeekResidual = seekRejectPositionMs >= 0L &&
                (now - seekClockMs) < LyriconLyricProducer.SEEK_RESIDUAL_REJECTION_WINDOW_MS &&
                position == seekRejectPositionMs
            if (isSeekResidual) {
                advanceExtrapolation(now, "seek residual ${position}ms matches pre-seek")
                recomputeAndEmit()
                return
            }
            // Reject the pre-pause stale value (issue #10): after pausing we re-based the position
            // onto the displayed (extrapolated) pause point, but the shared-memory writer may be
            // still frozen and keep delivering the *older* stale value at ~60 Hz. That value now
            // differs from the re-based base and would otherwise be accepted as a real post-pause
            // update, snapping the lyric back to a stale line. Reject until a different (real)
            // position arrives.
            val isPauseStaleResidual = pauseStaleRejectMs >= 0L &&
                position == pauseStaleRejectMs
            if (isPauseStaleResidual) {
                advanceExtrapolation(now, "pause-stale residual ${position}ms")
                recomputeAndEmit()
                return
            }
            if (position != lastRealPositionMs) {
                // Real position update from shared memory.
                // Accept wrap-around: when the song loops (single-track repeat), the shared
                // memory position resets to 0 while our extrapolated position may be at/beyond
                // duration. Treat a significantly lower position as a wrap-around rather than
                // rejecting it.

                // --- Post-song-change plausibility gate (issue #11) ---
                // 切歌后写入端 base 可能仍是旧歌时间线:残留 ≈ 旧歌时长,与真实位置同速
                // 推进、恒定偏移。门控要求切歌后首个真实位置 ≤ 切歌后墙钟 × 观测速率 +
                // 容差 —— 新歌从切歌时刻起播,位置不可能更多;残留因偏移 ≈ 旧歌时长
                // (远大于容差)被持续拒绝,期间从基准 0 外推(新歌正确推进)。
                if (!songStartGateOpen) {
                    val frozenResidual = gateFrozenRejectMs >= 0L && position == gateFrozenRejectMs
                    val sinceStartMs = (now - songStartClockMs).coerceAtLeast(0L)
                    val boundMs = (sinceStartMs * gateRateX +
                        LyriconLyricProducer.SONG_START_PLAUSIBILITY_TOLERANCE_MS).toLong()
                    if (frozenResidual || position > boundMs) {
                        if (!gateRejectLogged) {
                            gateRejectLogged = true
                            AppLog.i(
                                "LyriconLyricProducer",
                                "post-song-change residual rejected: pos=${position}ms " +
                                    "bound=${boundMs}ms sinceStart=${sinceStartMs}ms " +
                                    "duration=${currentSong?.duration ?: 0L}ms " +
                                    "song=${currentSong?.name}"
                            )
                        }
                        // Track the residual's advance rate: the residual advances at the true
                        // playback speed, so its cumulative Δpos/Δwall gives the rate for the
                        // bound — without this, 1.25x~3x 倍速用户的真实位置会在容差耗尽后
                        // 被 1x 上界误拒。冻结残留(Δpos=0)不更新速率。
                        if (gateFrozenRejectMs < 0L) gateFrozenRejectMs = position
                        if (gateRateAnchorPosMs < 0L) {
                            gateRateAnchorPosMs = position
                            gateRateAnchorClockMs = now
                        } else {
                            val dPosMs = position - gateRateAnchorPosMs
                            val dWallMs = now - gateRateAnchorClockMs
                            if (dPosMs > 0L && dWallMs >= LyriconLyricProducer.GATE_RATE_MIN_DELTA_MS) {
                                gateRateX = (dPosMs.toDouble() / dWallMs)
                                    .coerceIn(LyriconLyricProducer.GATE_RATE_MIN_X, LyriconLyricProducer.GATE_RATE_MAX_X)
                            }
                        }
                        // Frozen residuals keep repeating the same value even after the growing
                        // bound passes it (pause-then-skip scenario) — gateFrozenRejectMs above
                        // keeps rejecting them.
                        advanceExtrapolation(
                            now,
                            "post-song-change residual ${position}ms implausible (bound ${boundMs}ms)"
                        )
                        recomputeAndEmit()
                        return
                    }
                    // Plausible for a song that started at song-change time: fall through and
                    // accept (the gate opens below).
                }

                // A position beyond the song duration (+ tolerance) is never trustworthy: the
                // writer is on a stale/unwrapped timeline (issue #11 实测:越界值持续累加,
                // 487520ms vs 188718ms)。Treat it exactly like a stalled callback — extrapolate
                // from the last good base so lyrics keep advancing to the projected end and then
                // hold the stable song-end placeholder (issue #9) — instead of capping straight
                // to the duration, which cleared the line for the rest of the song (整首无歌词)
                // and spammed the capping log at ~60 Hz.
                val duration = currentSong?.duration ?: 0L
                if (duration > 0L && position > duration + LyriconLyricProducer.BEYOND_DURATION_TOLERANCE_MS) {
                    advanceExtrapolation(now, "position ${position}ms beyond duration ${duration}ms")
                    recomputeAndEmit()
                    return
                }
                val wasExtrapolating = extrapolating
                // When the player's position stream resumes after a stall it can briefly report a
                // value slightly *below* the position we extrapolated to (shared-memory latency /
                // stall-to-resume race). NetEase's ~60 Hz feed stalls and resumes constantly, so
                // snapping backward on every such resume rewinds the active line and makes it
                // flicker back and forth across a boundary. Within a small tolerance we keep the
                // monotonic extrapolated display value while re-basing the position base onto
                // the real value (see below); only a materially-lower real position (seek, song
                // wrap-around, or a genuine pause) is honored as a rewind.
                // Within the overshoot tolerance the song is genuinely at its end (metadata
                // duration can slightly underestimate the audio) → cap to the duration.
                val realPosition = if (duration > 0L && position > duration) {
                    if (lastRealPositionMs != duration) {
                        AppLog.i(
                            "LyriconLyricProducer",
                            "position ${position}ms beyond duration ${duration}ms; capping"
                        )
                    }
                    duration
                } else {
                    position
                }
                val realBehindMs = currentPositionMs - realPosition
                val monotonicResume = wasExtrapolating &&
                    realBehindMs in 1..LyriconLyricProducer.EXTRAPOLATION_RESUME_TOLERANCE_MS
                if (monotonicResume) {
                    // Keep the monotonic extrapolated display position (anti-flicker), but
                    // re-base onto the REAL position: shift the extrapolation clock back by the
                    // lead so extrapolating from the real base reproduces the current display.
                    // The real value always wins — the base must never be inflated by the
                    // extrapolated lead, otherwise repeated stall/resume cycles accumulate the
                    // lead and the base drifts far past the song duration (issue #10 追加实测2:
                    // lastRealPositionMs 累积到 596840ms,歌长仅 276000ms).
                    lastRealPositionMs = realPosition
                    lastRealPositionClockMs = now - realBehindMs
                    lastRealPositionUpdateMs = now
                } else {
                    lastRealPositionMs = realPosition
                    lastRealPositionClockMs = now
                    lastRealPositionUpdateMs = now
                    currentPositionMs = realPosition
                }
                // A different value means the player has started writing the new song's progress.
                // Disable residual filtering — subsequent positions are from the new song.
                previousSongLastPositionMs = -1L
                // A real (different) position means the player has written the post-seek value;
                // stop rejecting the pre-seek position.
                seekRejectPositionMs = -1L
                // A real (different) position means the writer is alive again: stop rejecting
                // the pre-pause stale value (issue #10).
                pauseStaleRejectMs = -1L
                // A plausible/real position arrived: the writer is on this song's timeline —
                // open the post-song-change gate (issue #11).
                songStartGateOpen = true
                gateRejectLogged = false
                // A real value also means the position source is alive again: clear the
                // unknown-position marker so recomputeAndEmit re-selects the active line.
                positionUnknown = false
                if (wasExtrapolating && !monotonicResume) {
                    extrapolating = false
                    AppLog.i(
                        "LyriconLyricProducer",
                        "position resumed: pos=${position}ms (extrapolation stopped)"
                    )
                }
            } else if (lastRealPositionClockMs >= 0L) {
                // Position stalled (shared-memory writer frozen by MIUI screen-off). Advance via
                // wall-clock extrapolation — freeze when paused; past the budget keep advancing
                // while within the song (Doze writer freeze, playback active), and only declare
                // the writer dead (mark position unknown) once past the song end or without a
                // known duration.
                //
                // The SDK also re-delivers the last written value between writer updates
                // (on-device 2026-09-28: writes every ~40 ms, a duplicate ~20 ms after each).
                // Below [LyriconLyricProducer.STALL_EXTRAPOLATION_MIN_MS] the repeat is not a
                // stall: hold the last real position and skip the state emission — nothing
                // observable changed, and a fresh state would only bump the sequence so the
                // arbiter re-logs the same producer as "active changed" every frame. Genuine
                // stalls (Doze writer freeze) persist for seconds, far above the floor.
                val sinceRealMs = now - lastRealPositionClockMs
                if (sinceRealMs >= LyriconLyricProducer.STALL_EXTRAPOLATION_MIN_MS) {
                    advanceExtrapolation(now, "position stalled")
                    recomputeAndEmit()
                }
                return
            }
            recomputeAndEmit()
        }

        override fun onSeekTo(position: Long) {
            AppLog.i("LyriconLyricProducer", "onSeekTo: pos=${position}ms old=${lastRealPositionMs}ms")
            applySeek(position)
        }

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
            // HyperGlow controls translation display via its own CustomizationRepository; ignore
            // the lyricon-side toggle to avoid double-toggling.
            AppLog.i("LyriconLyricProducer", "onDisplayTranslationChanged: $isDisplayTranslation (ignored, owned by HyperGlow)")
        }

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
            // Same as above: romanization display is owned by HyperGlow's render modes.
            AppLog.i("LyriconLyricProducer", "onDisplayRomaChanged: $isDisplayRoma (ignored, owned by HyperGlow)")
        }
    }

/**
 * Seek 统一处理:SDK 的 onSeekTo 与其他生产者转发的跨源 seek([LyricProducer.onExternalSeek])
 * 共用同一入口。跨源转发的意义:各位置源独立,Lyricon 共享内存可能整段冻结、onSeekTo 不来
 * (2026-09-28 真机实测拖动进度条后歌词 14s 不跟手,而 LyricInfo 的 SeekDetector 当场测到),
 * 由先观测到的一方把权威位置递过来立即跟手。
 */
internal fun LyriconLyricProducer.applySeek(position: Long) {
    val now = clock()
    // Record the pre-seek position so onPositionChanged can reject the stale shared-memory
    // value that lingers right after the seek (old != seek target would otherwise be
    // accepted as a "real" update and snap the active line back to the old position).
    seekRejectPositionMs = lastRealPositionMs
    seekClockMs = now
    lastRealPositionMs = position
    lastRealPositionClockMs = now
    lastRealPositionUpdateMs = now
    currentPositionMs = position
    extrapolating = false
    // A seek is a deliberate position change — the position is known again.
    positionUnknown = false
    // A seek is a deliberate position change — clear residual filtering so the new
    // position is accepted even if it coincidentally matches the previous song's last.
    previousSongLastPositionMs = -1L
    // A seek also invalidates any pre-pause stale rejection (issue #10): the seek
    // target is the new authoritative position.
    pauseStaleRejectMs = -1L
    // A seek is a deliberate, authoritative position: open the post-song-change gate
    // (issue #11) — the seek target defines the timeline from here on.
    songStartGateOpen = true
    gateRejectLogged = false
    // Seek invalidates the navigator's sequential cache (playback jumped).
    navigator?.resetCache()
    currentLineIndex = -1
    cachedWords = null
    recomputeAndEmit()
}
