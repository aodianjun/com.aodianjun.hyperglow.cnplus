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
        if (session != input.session) {
            session = input.session
            phase = Phase.PENDING
            startedAtElapsedMs = 0L
        }
        // 常驻显示歌名:metadata 可用时始终显示,不再受歌词状态(ACTIVE/INTERLUDE)或时长限制。
        return input.metadataAvailable
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
