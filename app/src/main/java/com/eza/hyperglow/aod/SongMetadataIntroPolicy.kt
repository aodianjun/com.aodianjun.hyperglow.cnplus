package com.eza.hyperglow.aod

internal enum class SongIntroLyricState {
    UNKNOWN,
    ACTIVE,
    INTERLUDE,
    NONE
}

internal data class SongMetadataIntroInput(
    val session: ProjectionSessionIdentity,
    val metadataAvailable: Boolean,
    val lyricState: SongIntroLyricState,
    val positionMs: Long,
    val nextLyricStartMs: Long?,
    val speed: Float,
    val nowElapsedMs: Long
)

internal class SongMetadataIntroPolicy(
    private val durationMs: Long = DEFAULT_DURATION_MS
) {
    private enum class Phase { PENDING, SHOWING, DEFERRED, COMPLETE }

    private var session: ProjectionSessionIdentity? = null
    private var phase = Phase.PENDING
    private var startedAtElapsedMs = 0L

    @Synchronized
    fun shouldShowLargeMetadata(input: SongMetadataIntroInput): Boolean {
        if (!input.metadataAvailable) return false
        if (session != input.session) {
            session = input.session
            phase = Phase.PENDING
            startedAtElapsedMs = 0L
        }
        // 只有无歌词/间奏(没有活动歌词行)时才显示大元数据(歌名 · 歌手),
        // 歌词 ACTIVE 时优先显示歌词本身,避免歌名顶掉歌词行。
        when (phase) {
            Phase.PENDING -> if (canStartInitial(input)) {
                phase = Phase.SHOWING
                startedAtElapsedMs = input.nowElapsedMs
            }
            Phase.DEFERRED -> if (canStartDeferred(input)) {
                phase = Phase.SHOWING
                startedAtElapsedMs = input.nowElapsedMs
            }
            Phase.SHOWING -> {
                val remainingMs = durationMs - (input.nowElapsedMs - startedAtElapsedMs)
                if (!canContinue(input, remainingMs)) phase = Phase.COMPLETE
            }
            Phase.COMPLETE -> {}
        }
        return phase == Phase.SHOWING
    }

    private fun canStartInitial(input: SongMetadataIntroInput): Boolean = when (input.lyricState) {
        SongIntroLyricState.UNKNOWN,
        SongIntroLyricState.NONE -> true
        SongIntroLyricState.ACTIVE -> {
            phase = Phase.DEFERRED
            false
        }
        SongIntroLyricState.INTERLUDE -> {
            val availableMs = availableInterludeMs(input)
            if (availableMs >= durationMs) true else {
                phase = Phase.DEFERRED
                false
            }
        }
    }

    private fun canStartDeferred(input: SongMetadataIntroInput): Boolean = when (input.lyricState) {
        SongIntroLyricState.NONE -> true
        SongIntroLyricState.INTERLUDE -> availableInterludeMs(input) >= durationMs
        SongIntroLyricState.UNKNOWN,
        SongIntroLyricState.ACTIVE -> false
    }

    private fun canContinue(input: SongMetadataIntroInput, remainingMs: Long): Boolean =
        when (input.lyricState) {
            SongIntroLyricState.ACTIVE -> false
            SongIntroLyricState.INTERLUDE -> availableInterludeMs(input) >= remainingMs
            SongIntroLyricState.UNKNOWN,
            SongIntroLyricState.NONE -> true
        }

    private fun availableInterludeMs(input: SongMetadataIntroInput): Long {
        val nextStartMs = input.nextLyricStartMs ?: return Long.MAX_VALUE
        val playbackGapMs = (nextStartMs - input.positionMs).coerceAtLeast(0L)
        if (input.speed <= 0f || !input.speed.isFinite()) return Long.MAX_VALUE
        return (playbackGapMs / input.speed).toLong()
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 3_000L
    }
}
