package com.eza.hyperglow.producer

/** Mirrors [android.media.session.PlaybackState] state constants for JVM-testable classification. */
internal object MediaPlayback {
    const val NONE = 0
    const val STOPPED = 1
    const val PAUSED = 2
    const val PLAYING = 3
    const val FAST_FORWARDING = 4
    const val REWINDING = 5
    const val BUFFERING = 6
    const val ERROR = 7
    const val CONNECTING = 8
    const val SKIPPING_TO_PREVIOUS = 9
    const val SKIPPING_TO_NEXT = 10
    const val SKIPPING_TO_QUEUE_ITEM = 11
}

/** Playback classification used by the issue #27 MediaSession stop detector. */
internal enum class ActivePlayerPlayback {
    PLAYING,
    PAUSED,
    STOPPED,
    UNKNOWN
}

/**
 * issue #27 classification of a MediaSession playback state:
 * - [MediaPlayback.PAUSED] → PAUSED: retain (existing pause-retention handles the eventual clear);
 * - [MediaPlayback.NONE] / [MediaPlayback.STOPPED] / null → STOPPED: the quoted session is
 *   "active" but not actually playing — release the track (NetEase reports state=null, not
 *   STATE_STOPPED, when stopped; the SDK never delivers `onPlaybackStateChanged(false)` for it);
 * - everything actively transporting (playing/buffering/seeking/connecting) → PLAYING: keep.
 * - [MediaPlayback.ERROR] and anything unexpected → UNKNOWN: never clears on ambiguity.
 */
internal fun classifyActivePlayerPlayback(state: Int?): ActivePlayerPlayback = when (state) {
    MediaPlayback.PLAYING, MediaPlayback.FAST_FORWARDING, MediaPlayback.REWINDING,
    MediaPlayback.BUFFERING, MediaPlayback.CONNECTING,
    MediaPlayback.SKIPPING_TO_PREVIOUS, MediaPlayback.SKIPPING_TO_NEXT,
    MediaPlayback.SKIPPING_TO_QUEUE_ITEM -> ActivePlayerPlayback.PLAYING
    MediaPlayback.PAUSED -> ActivePlayerPlayback.PAUSED
    MediaPlayback.NONE, MediaPlayback.STOPPED, null -> ActivePlayerPlayback.STOPPED
    else -> ActivePlayerPlayback.UNKNOWN
}
