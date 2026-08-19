package com.eza.hyperglow.producer

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [pickMediaSession]: the LyricInfo producer must follow a session that carries injected
 * lyrics when available, but fall back to any active media session when it does not — the recovery
 * path for issue #5 (Lyricon shared-memory position source dies after screen-off).
 *
 * [MediaController] is created from a [MediaSession] because its constructor is not public; the
 * test only inspects selection decisions, not playback-state timing. Robolectric's shadow does not
 * expose [MediaMetadata.Builder.setExtras], so the "lyricInfo preferred" branch is verified by the
 * production integration; this suite pins the critical fallback behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LyricInfoSessionSelectionTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun session(tag: String): MediaSession {
        return MediaSession(context, "LyricInfoSessionSelectionTest-$tag")
    }

    private fun MediaSession.controllerWithoutLyricInfo(): MediaController {
        setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "No Lyrics")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 120_000L)
                .build()
        )
        return controller
    }

    @Test
    fun emptySessionList_returnsNull() {
        assertNull(pickMediaSession(emptyList()))
    }

    @Test
    fun sessionWithoutLyricInfo_isPickedWhenItIsTheOnlyOption() {
        val session = session("nolyrics")
        val controller = session.controllerWithoutLyricInfo()

        val picked = pickMediaSession(listOf(controller))

        assertSame(controller, picked)
        session.release()
    }

    @Test
    fun picksFirstSessionWhenNeitherHasLyricInfo() {
        val firstSession = session("first")
        val first = firstSession.controllerWithoutLyricInfo()
        val secondSession = session("second")
        val second = secondSession.controllerWithoutLyricInfo()

        assertSame(first, pickMediaSession(listOf(first, second)))
        assertSame(second, pickMediaSession(listOf(second, first)))
        firstSession.release()
        secondSession.release()
    }
}
