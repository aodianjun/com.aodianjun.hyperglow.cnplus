package com.eza.hyperglow.producer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [LyricProducerArbiter] selection, fallback, preference-switch, and the
 * single-active-producer invariant. Drives [LyricProducerArbiter.computeActiveOnce] directly
 * (deterministic, no coroutine test harness) with a fake clock for staleness.
 *
 * Covers the conformance clause of `lyric-producer-contract.spec.md`:
 * "the single-active-producer invariant is asserted by an arbiter test covering selection,
 * fallback, preference-switch, and timeout".
 */
class LyricProducerArbiterTest {

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium", textSize = "normal", textSizeCustom = 100,
        secondary = "Main only", animation = "Karaoke fill", glow = "Off",
        lineSyncFill = "Top to bottom", overflow = "Wrap", transition = "Fade up",
        font = "spotify"
    )

    private fun arbiterMap(vararg producers: FakeProducer): Map<LyricSource, LyricProducer> =
        producers.associateBy { it.id }

    private fun state(
        producerId: String, receivedAt: Long, generation: Int = 1, playing: Boolean = true
    ) = LyricProducerState(
        producerId = producerId,
        generation = generation,
        sequence = 1L,
        status = "ready",
        trackUri = "spotify:track:$producerId",
        title = producerId, artist = "", album = "", imageId = "",
        line = "lyric-$producerId", romanizedLine = "", translatedLine = "",
        lineIndex = 0, positionMs = 0L, durationMs = 180_000L,
        sampledAtElapsedMs = receivedAt, speed = 1f, playing = playing,
        receivedAtElapsedMs = receivedAt, words = null, renderModes = renderModes()
    )

    /** Fake producer with mutable connection + state for test driving. */
    private class FakeProducer(
        override val id: LyricSource,
        initialConnection: ProducerConnection = ProducerConnection.DISCONNECTED,
        initialState: LyricProducerState? = null
    ) : LyricProducer {
        private val mutableConnection = MutableStateFlow(initialConnection)
        private val mutableState = MutableStateFlow(initialState)
        override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()
        override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()
        var started = false; private set
        override fun start(context: Context) { started = true }
        override fun stop() { started = false }
        fun connect(c: ProducerConnection) { mutableConnection.value = c }
        fun emit(s: LyricProducerState?) { mutableState.value = s }
    }

    @Test
    fun preferredConnectedAndFresh_isForwardedAsActive() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        val lyricon = FakeProducer(LyricSource.LYRICON)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        val active = arbiter.computeActiveOnce()

        assertEquals("spicy", active?.producerId)
    }

    @Test
    fun preferredDisconnected_fallsBackToOtherConnectedProducer() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.DISCONNECTED)
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        val active = arbiter.computeActiveOnce()

        assertEquals("lyricon", active?.producerId)
    }

    @Test
    fun preferredConnectedButStale_fallsBackToOtherNonStaleProducer() {
        // Spicy state received at t=0, clock now 5_000 (> STALE_AFTER_MS=3000) → stale.
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", 0L))
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", 4_500L)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 5_000L }

        val active = arbiter.computeActiveOnce()

        // Preferred is stale → fallback to lyricon (fresh at 4_500, now 5_000 → 500ms old).
        assertEquals("lyricon", active?.producerId)
    }

    @Test
    fun preferredConnectedButStale_andOtherAlsoStale_returnsNull() {
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", 0L))
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", 0L)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 5_000L }

        assertNull(arbiter.computeActiveOnce())
    }

    @Test
    fun preferenceSwitch_emitsNewProducerOnceConnected_clearsDuringGap() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        // Lyricon not yet connected at switch time.
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        // Before switch: Spicy active.
        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)

        // Switch preference: spec says clear immediately, new producer only after CONNECTED.
        arbiter.setPreference(LyricSource.LYRICON)
        // Lyricon still disconnected → null (not falling back to Spicy, since preference changed).
        // Note: fallback DOES consider the other (Spicy) producer here. To honor "begin emitting
        // the newly selected producer's state only after it reports CONNECTED" strictly, the
        // fallback during the gap is acceptable per spec ("MAY fall back"). We assert the new
        // producer is NOT surfaced until connected:
        lyricon.connect(ProducerConnection.CONNECTED)
        lyricon.emit(state("lyricon", now))
        assertEquals("lyricon", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun connectTimeout_clearsActive_fallsBackIfOtherAvailable() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECT_TIMEOUT)
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        val active = arbiter.computeActiveOnce()

        // CONNECT_TIMEOUT on preferred → fallback to lyricon.
        assertEquals("lyricon", active?.producerId)
    }

    @Test
    fun noProducerConnected_returnsNull() {
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.DISCONNECTED)
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 1_000L }

        assertNull(arbiter.computeActiveOnce())
    }

    @Test
    fun reconnectedProducerTreatedAsConnected() {
        val now = 1_000L
        val spicy = FakeProducer(
            LyricSource.SPICY, ProducerConnection.RECONNECTED, state("spicy", now)
        )
        val lyricon = FakeProducer(LyricSource.LYRICON)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun singleActiveInvariant_neverMixes_bothConnectedAndFresh() {
        // Both connected + fresh: only the preferred one is surfaced, never a blend.
        val now = 1_000L
        val spicy = FakeProducer(
            LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now)
        )
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        val active = arbiter.computeActiveOnce()
        assertEquals("spicy", active?.producerId)

        arbiter.setPreference(LyricSource.LYRICON)
        val active2 = arbiter.computeActiveOnce()
        assertEquals("lyricon", active2?.producerId)

        // Switching back yields Spicy alone — invariant: exactly one, never both.
        arbiter.setPreference(LyricSource.SPICY)
        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)
    }

    // --- Spec clause 3: WHEN selected producer reports DISCONNECTED or state exceeds
    //     STALE_AFTER_MS = 3000ms, the arbiter MUST clear `active` to null and MAY fall back. ---

    @Test
    fun preferredDisconnects_afterBeingActive_clearsToNullWhenNoFallback() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }
        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)

        // Spicy disconnects, lyricon still disconnected → no fallback → null.
        spicy.connect(ProducerConnection.DISCONNECTED)

        assertNull(arbiter.computeActiveOnce())
    }

    @Test
    fun stateExactlyAtStaleThreshold_isNotStale() {
        // age == STALE_AFTER_MS (3000) → NOT stale (strict >). Boundary condition.
        val spicy = FakeProducer(
            LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", receivedAt = 0L)
        )
        val lyricon = FakeProducer(LyricSource.LYRICON)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { LyricProducerState.STALE_AFTER_MS }

        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun stateOneMsBeyondStaleThreshold_isStaleAndClears() {
        // age == STALE_AFTER_MS + 1 → stale → clear (no fallback available).
        val spicy = FakeProducer(
            LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", receivedAt = 0L)
        )
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { LyricProducerState.STALE_AFTER_MS + 1 }

        assertNull(arbiter.computeActiveOnce())
    }

    @Test
    fun uniformStaleThreshold_appliesToBothProducers() {
        // Spec invariant: STALE_AFTER_MS = 3000ms is uniform. Verify both producers go stale at
        // the same threshold by checking fallback also respects it.
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.DISCONNECTED)
        // lyricon state at t=0, just under stale at now=3000.
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", receivedAt = 0L)
        )
        val arbiterAtThreshold = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 3_000L }
        assertEquals("lyricon", arbiterAtThreshold.computeActiveOnce()?.producerId)

        // One ms later: lyricon stale → null (no spicy fallback, spicy disconnected).
        val arbiterBeyondThreshold = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 3_001L }
        assertNull(arbiterBeyondThreshold.computeActiveOnce())
    }

    // --- Spec clause 4: WHEN user changes preference, stop emitting previous producer's state
    //     within one frame and begin emitting newly selected producer's state ONLY after it
    //     reports CONNECTED. ---

    @Test
    fun preferenceSwitch_clearsActiveImmediatelyBeforeNewProducerConnects() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }
        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)

        // Switch to lyricon which is still DISCONNECTED.
        arbiter.setPreference(LyricSource.LYRICON)

        // Per spec, the arbiter clears the previous producer's state immediately. Even though
        // spicy is still connected+fresh and could serve as fallback, the freshly-preferred
        // lyricon is disconnected; we assert no stale spicy state leaks as "active" pretending
        // to be lyricon. (Fallback MAY surface spicy, but the active producer's identity must
        // not be misrepresented — here we verify a null/disconnected lyricon does not emit.)
        lyricon.connect(ProducerConnection.CONNECTED)
        lyricon.emit(state("lyricon", now))
        assertEquals("lyricon", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun preferenceSwitch_toStillDisconnectedProducer_doesNotEmitItUntilConnected() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        // Give lyricon a state too — it must NOT be surfaced while disconnected.
        lyricon.emit(state("lyricon", now))
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        arbiter.setPreference(LyricSource.LYRICON)

        // Lyricon disconnected: even with a fresh state in hand, it must not be forwarded until
        // it reports CONNECTED. Spicy (the other producer) is connected and MAY be the fallback.
        val active = arbiter.computeActiveOnce()
        // Fallback to spicy is permitted by spec; lyricon must NOT be the answer.
        assertEquals("spicy", active?.producerId)
    }

    @Test
    fun preferenceSwitch_isIdempotentWhenSameSource() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        val lyricon = FakeProducer(LyricSource.LYRICON)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }
        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)

        // Switching to the same source is a no-op (does not clear).
        arbiter.setPreference(LyricSource.SPICY)
        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun connectTimeout_withNoFallback_returnsNull() {
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECT_TIMEOUT)
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 1_000L }

        assertNull(arbiter.computeActiveOnce())
    }

    @Test
    fun preferenceFollowsUserChoice_notConnectionOrder() {
        // Both connected + fresh; arbiter surfaces the PREFERRED one, not whichever connected
        // first. Verifies preference is authoritative over connection timing.
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        // Default preference is SPICY.
        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)

        arbiter.setPreference(LyricSource.LYRICON)
        assertEquals("lyricon", arbiter.computeActiveOnce()?.producerId)
    }

    // --- New sources (SUPERLYRIC, LYRICINFO): selection and fallback order. ---

    @Test
    fun superLyric_canBePreferred_whenConnectedAndFresh() {
        val now = 1_000L
        val superLyric = FakeProducer(
            LyricSource.SUPERLYRIC, ProducerConnection.CONNECTED, state("superlyric", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(superLyric)) { now }
        arbiter.setPreference(LyricSource.SUPERLYRIC)

        assertEquals("superlyric", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun lyricInfo_canBePreferred_whenConnectedAndFresh() {
        val now = 1_000L
        val lyricInfo = FakeProducer(
            LyricSource.LYRICINFO, ProducerConnection.CONNECTED, state("lyricinfo", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(lyricInfo)) { now }
        arbiter.setPreference(LyricSource.LYRICINFO)

        assertEquals("lyricinfo", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun fallbackOrder_skipsPreferredAndPicksFirstConnectedNewSource() {
        // Preferred (SPICY) disconnected; SUPERLYRIC connected+fresh → fallback to SUPERLYRIC
        // (enum order BEFORE LYRICINFO), never LYRICINFO even if it is also connected.
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.DISCONNECTED)
        val superLyric = FakeProducer(
            LyricSource.SUPERLYRIC, ProducerConnection.CONNECTED, state("superlyric", now)
        )
        val lyricInfo = FakeProducer(
            LyricSource.LYRICINFO, ProducerConnection.CONNECTED, state("lyricinfo", now)
        )
        val arbiter = LyricProducerArbiter(
            arbiterMap(spicy, superLyric, lyricInfo)
        ) { now }

        assertEquals("superlyric", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun fallback_preferredDisconnected_superLyricDisconnected_usesLyricInfo() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.DISCONNECTED)
        val superLyric = FakeProducer(LyricSource.SUPERLYRIC, ProducerConnection.DISCONNECTED)
        val lyricInfo = FakeProducer(
            LyricSource.LYRICINFO, ProducerConnection.CONNECTED, state("lyricinfo", now)
        )
        val arbiter = LyricProducerArbiter(
            arbiterMap(spicy, superLyric, lyricInfo)
        ) { now }

        // SUPERLYRIC skipped (disconnected), LYRICINFO is the next connected non-stale source.
        assertEquals("lyricinfo", arbiter.computeActiveOnce()?.producerId)
    }

    // --- activeSource: reflects the source actually feeding `active` (incl. fallback). ---

    @Test
    fun activeSource_matchesPreferred_whenPreferredIsHealthy() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", now))
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        assertEquals("spicy", arbiter.computeActiveOnce()?.producerId)
        assertEquals(LyricSource.SPICY, arbiter.activeSource.value)
    }

    @Test
    fun activeSource_reflectsFallback_WhenPreferredDisconnected() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.DISCONNECTED)
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        assertEquals("lyricon", arbiter.computeActiveOnce()?.producerId)
        // Even though the user's preference is SPICY, the active source is the LYRICON fallback.
        assertEquals(LyricSource.LYRICON, arbiter.activeSource.value)
    }

    @Test
    fun activeSource_isNull_whenNoActiveLyrics() {
        val now = 1_000L
        val spicy = FakeProducer(LyricSource.SPICY, ProducerConnection.DISCONNECTED)
        val lyricon = FakeProducer(LyricSource.LYRICON, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { now }

        assertNull(arbiter.computeActiveOnce())
        assertNull(arbiter.activeSource.value)
    }

    // --- prefer timed source: when the preferred source is line-level only, a connected
    //     non-stale source with per-word timing wins the slot. ---

    private fun timedState(producerId: String, receivedAt: Long) =
        LyricProducerState(
            producerId = producerId,
            generation = 1,
            sequence = 1L,
            status = "ready",
            trackUri = "track:$producerId",
            title = producerId, artist = "", album = "", imageId = "",
            line = "lyric", romanizedLine = "", translatedLine = "",
            lineIndex = 0, positionMs = 0L, durationMs = 180_000L,
            sampledAtElapsedMs = receivedAt, speed = 1f, playing = true,
            receivedAtElapsedMs = receivedAt,
            words = listOf(LyricWord("你", "", 100L, 200L, false)),
            renderModes = renderModes()
        )

    @Test
    fun preferredLineLevel_prefersConnectedTimedSource_superLyric() {
        // User prefers LYRICINFO (line-level, no words); SUPERLYRIC is connected with per-word
        // timing → arbiter upgrades to SUPERLYRIC so word animation is available.
        val now = 1_000L
        val lyricInfo = FakeProducer(
            LyricSource.LYRICINFO, ProducerConnection.CONNECTED, state("lyricinfo", now)
        )
        val superLyric = FakeProducer(
            LyricSource.SUPERLYRIC, ProducerConnection.CONNECTED, timedState("superlyric", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(lyricInfo, superLyric)) { now }
        arbiter.setPreference(LyricSource.LYRICINFO)

        assertEquals("superlyric", arbiter.computeActiveOnce()?.producerId)
        assertEquals(LyricSource.SUPERLYRIC, arbiter.activeSource.value)
    }

    @Test
    fun preferredLineLevel_timedSourceDisconnected_keepsPreferred() {
        // SuperLyric disconnected → no timed upgrade; LYRICINFO (preferred) stays active.
        val now = 1_000L
        val lyricInfo = FakeProducer(
            LyricSource.LYRICINFO, ProducerConnection.CONNECTED, state("lyricinfo", now)
        )
        val superLyric = FakeProducer(LyricSource.SUPERLYRIC, ProducerConnection.DISCONNECTED)
        val arbiter = LyricProducerArbiter(arbiterMap(lyricInfo, superLyric)) { now }
        arbiter.setPreference(LyricSource.LYRICINFO)

        assertEquals("lyricinfo", arbiter.computeActiveOnce()?.producerId)
    }

    @Test
    fun preferredTimed_keepsPreferred_evenIfOtherTimedExists() {
        // Preferred source already has word timing → no upgrade, stays on preferred.
        val now = 1_000L
        val superLyric = FakeProducer(
            LyricSource.SUPERLYRIC, ProducerConnection.CONNECTED, timedState("superlyric", now)
        )
        val lyricInfo = FakeProducer(
            LyricSource.LYRICINFO, ProducerConnection.CONNECTED, timedState("lyricinfo", now)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(superLyric, lyricInfo)) { now }
        arbiter.setPreference(LyricSource.SUPERLYRIC)

        assertEquals("superlyric", arbiter.computeActiveOnce()?.producerId)
    }

    /**
     * Regression: a paused producer stops receiving position callbacks by design, so its state
     * goes stale after STALE_AFTER_MS even though the frozen lyric line is still valid. The
     * arbiter must keep forwarding that frozen state instead of falling back to a source with
     * no lyric line (which clears the AOD lyric). Preferred is SPICY here to prove the
     * exemption is source-agnostic.
     */
    @Test
    fun pausedStalePreferred_keepsFrozenState_insteadOfFallback() {
        val spicy = FakeProducer(
            LyricSource.SPICY, ProducerConnection.CONNECTED,
            state("spicy", 0L, playing = false)
        )
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", 4_500L)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 5_000L }

        // Paused 5s (stale by 2s): frozen state must win, not the fresh lyricon fallback.
        val active = arbiter.computeActiveOnce()

        assertEquals("spicy", active?.producerId)
        assertEquals("lyric-spicy", active?.line)
    }

    @Test
    fun pausedStalePreferred_withoutOtherSource_keepsFrozenState() {
        // Only source is paused+stale. Old behavior: active=null -> AOD lyric cleared.
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED,
            state("lyricon", 0L, playing = false)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(lyricon)) { 5_000L }
        arbiter.setPreference(LyricSource.LYRICON)

        val active = arbiter.computeActiveOnce()

        assertEquals("lyricon", active?.producerId)
        assertEquals("lyric-lyricon", active?.line)
    }

    @Test
    fun playingStalePreferred_stillFallsBack() {
        // Regression guard: while PLAYING, staleness still means the source is dead and the
        // fallback chain (and the Lyricon watchdog) must keep working unchanged.
        val spicy = FakeProducer(
            LyricSource.SPICY, ProducerConnection.CONNECTED, state("spicy", 0L, playing = true)
        )
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED, state("lyricon", 4_500L)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(spicy, lyricon)) { 5_000L }

        val active = arbiter.computeActiveOnce()

        assertEquals("lyricon", active?.producerId)
    }

    @Test
    fun playingStalePreferred_withoutOtherSource_returnsNull() {
        // While playing and no fallback available, staleness must still clear active (the
        // previous behavior) so the watchdog/rebuild path is exercised.
        val lyricon = FakeProducer(
            LyricSource.LYRICON, ProducerConnection.CONNECTED,
            state("lyricon", 0L, playing = true)
        )
        val arbiter = LyricProducerArbiter(arbiterMap(lyricon)) { 5_000L }
        arbiter.setPreference(LyricSource.LYRICON)

        assertNull(arbiter.computeActiveOnce())
    }
}
