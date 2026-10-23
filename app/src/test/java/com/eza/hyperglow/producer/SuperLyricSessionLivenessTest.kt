package com.eza.hyperglow.producer

import android.media.session.PlaybackState
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * [SuperLyricLyricProducer] 的会话活性补丁（P3）测试：
 * - 跨源 seek（[LyricProducer.onExternalSeek]）按位置从全曲包重选行，不刷新推送心跳；
 * - 1s 级 MediaSession 探测：暂停驻留（playing=false 但保留行）/ 越过行尾本地前进 /
 *   位置正常时不动；
 * - 无 allLyrics 时两条路径都 no-op；
 * - 暂停驻留不会让第三方 publisher 抢走所有权。
 *
 * 所有 MediaSession 查询都走注入缝（[SuperLyricLyricProducer.mediaSessionPlaybackStateReader]
 * / [SuperLyricLyricProducer.mediaSessionPositionReader]），不依赖真实 MediaSessionManager。
 * Robolectric 同既有 SuperLyric 用例：receiver stub 继承 android.os.Binder。
 *
 * 注意 [com.hchen.superlyricapi.SuperLyricCache] 是**静态**缓存，各用例的 lyricId/标题互不相同。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SuperLyricSessionLivenessTest {

    private var nowMs = 1_000L
    private lateinit var producer: SuperLyricLyricProducer

    private var playbackState: Int? = null
    private var sessionPositionMs: Long? = null
    private val queriedPackages: MutableList<String> =
        Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        producer = SuperLyricLyricProducer { nowMs }
        producer.mediaSessionPlaybackStateReader = { pkg ->
            queriedPackages.add(pkg)
            playbackState
        }
        producer.mediaSessionPositionReader = { pkg ->
            queriedPackages.add(pkg)
            sessionPositionMs
        }
        // 探测判据含 started；start() 在无 SuperLyric 服务时静默降级（既有用例已钉住）。
        // 后台循环在 Robolectric 下会把注入的歌词判成「服务消失」清掉，单测关掉、只直调探测。
        producer.backgroundLoopsEnabled = false
        producer.start(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        producer.stop()
    }

    private fun line(
        text: String,
        startMs: Long,
        endMs: Long,
        translation: String? = null,
        secondary: String? = null,
        words: Array<SuperLyricWord>? = null
    ): SuperLyricLine = SuperLyricLine(text, words, translation, secondary, startMs, endMs)

    /** 官方发布端约定：全量包携带整首行表、当前行下标，`lyric` 填首行。 */
    private fun fullPayload(
        lyricId: String,
        lines: Array<SuperLyricLine>,
        currentIndex: Int = 0,
        durationMs: Long = 0L,
        positionMs: Long = -1L,
        title: String = TITLE,
        artist: String = ARTIST
    ): SuperLyricData = SuperLyricData().apply {
        setTitle(title)
        setArtist(artist)
        setLyricId(lyricId)
        setAllLyrics(lines)
        setCurrentLyricIndex(currentIndex)
        setLyric(lines.first())
        if (durationMs > 0L) setDuration(durationMs)
        if (positionMs >= 0L) setPosition(positionMs)
    }

    private fun fiveLines(): Array<SuperLyricLine> = arrayOf(
        line("l0", 0L, 2_000L),
        line(
            "l1", 2_000L, 4_000L,
            translation = "译1", secondary = "ro1",
            words = arrayOf(
                SuperLyricWord("l", 2_000L, 3_000L),
                SuperLyricWord("1", 3_000L, 4_000L)
            )
        ),
        line("l2", 4_000L, 6_000L, translation = "译2", secondary = "ro2"),
        line("l3", 6_000L, 8_000L),
        line("l4", 8_000L, 10_000L)
    )

    /** 推一笔全量包：lyric=首行，下标指向 l1，position=2000（l1 起点）。 */
    private fun pushFullPayload(lyricId: String, currentIndex: Int = 1, positionMs: Long = 2_000L) {
        producer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                lyricId,
                fiveLines(),
                currentIndex = currentIndex,
                durationMs = 60_000L,
                positionMs = positionMs
            )
        )
    }

    // --- 跨源 seek ---

    @Test
    fun externalSeekSelectsLineByPositionNotPayloadFirstLineOrIndex() {
        // 全量包 lyric=首行 l0、index=1（l1）；seek 到 5000ms → 必须选 l2。
        // 直接拿 lyric（l0）或沿用推送下标（l1）都是错的。
        pushFullPayload("p3-seek-1")

        producer.onExternalSeek(5_000L)

        val state = producer.state.value!!
        assertEquals("l2", state.line)
        assertEquals(2, state.lineIndex)
        assertEquals(5_000L, state.positionMs)
        assertTrue(state.playing)
        assertEquals(1f, state.speed)
        assertEquals(nowMs, state.sampledAtElapsedMs)
        // 行级翻译/罗马音/词表随重选行保留。
        assertEquals("译2", state.translatedLine)
        assertEquals("ro2", state.romanizedLine)
        assertEquals("l3", state.nextLine)
        // 元数据/歌长保留。
        assertEquals(TITLE, state.title)
        assertEquals(ARTIST, state.artist)
        assertEquals(60_000L, state.durationMs)
    }

    @Test
    fun externalSeekSelectsWordTimedLineWithWords() {
        pushFullPayload("p3-seek-2")

        producer.onExternalSeek(2_500L)

        val state = producer.state.value!!
        assertEquals("l1", state.line)
        assertEquals(1, state.lineIndex)
        assertEquals(LyricKind.SYLLABLE, state.lyricKind)
        assertEquals(2, state.words?.size)
    }

    @Test
    fun externalSeekDoesNotRefreshPushHeartbeat() {
        // seek 来自其他源，不代表 SuperLyric 推送通道还活着：不得刷新 lastLyricElapsedMs，
        // 否则 60s 停滞重挂会被一次 seek 掩盖。
        pushFullPayload("p3-seek-3")
        nowMs = 1_000L + SuperLyricLyricProducer.PUBLISHER_SWITCH_GRACE_MS + 500L

        producer.onExternalSeek(5_000L)
        assertEquals("l2", producer.state.value?.line)

        // 心跳仍停在 1000（已过 8s 宽限）：第三方 publisher 可以合法接管。
        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload("p3-seek-3-foreign", arrayOf(line("foreign", 0L, 2_000L)))
        )
        assertEquals("foreign", producer.state.value?.line)
    }

    @Test
    fun externalSeekNoOpWithoutAllLyrics() {
        val legacy = SuperLyricData().apply {
            setTitle("P3 Legacy Seek Song")
            setArtist("P3 Legacy Seek Artist")
            setLyric(SuperLyricLine("legacy line", 10_000L, 12_000L))
        }
        producer.receiver.onLyric(OWNER_PUBLISHER, legacy)
        val before = producer.state.value!!

        producer.onExternalSeek(5_000L)

        val after = producer.state.value!!
        assertEquals(before.sequence, after.sequence)
        assertEquals(before.line, after.line)
        assertEquals(before.positionMs, after.positionMs)
        assertEquals(before.playing, after.playing)
    }

    @Test
    fun externalSeekNoOpForInvalidPosition() {
        pushFullPayload("p3-seek-4")
        val before = producer.state.value!!

        producer.onExternalSeek(-1L)
        assertEquals(before.sequence, producer.state.value?.sequence)

        // 歌长 60000 + 2s 容差之外。
        producer.onExternalSeek(62_001L)
        assertEquals(before.sequence, producer.state.value?.sequence)
    }

    // --- MediaSession 活性探测 ---

    @Test
    fun probePausedEmitsPausedResidencyAndKeepsLine() {
        // 当前行 l1(2000-4000)，推送点位 2000 → 逾期阈值 2000+1000=3000ms。
        pushFullPayload("p3-probe-1")
        val before = producer.state.value!!
        nowMs = 1_000L + 3_000L
        playbackState = PlaybackState.STATE_PAUSED
        sessionPositionMs = 2_500L

        producer.maybeProbeSessionLiveness(nowMs)

        val state = producer.state.value!!
        assertFalse(state.playing)
        assertEquals(0f, state.speed)
        assertEquals("l1", state.line)
        assertEquals(1, state.lineIndex)
        assertEquals(before.positionMs, state.positionMs)
        assertEquals(nowMs, state.sampledAtElapsedMs)
        assertTrue(state.sequence > before.sequence)
        // 只查当前 owner 的包名（后台 1s 循环可能已先跑一拍，故不钉查询次数）。
        assertTrue(queriedPackages.isNotEmpty())
        assertTrue(queriedPackages.all { it == OWNER_PUBLISHER })
    }

    @Test
    fun probePlayingPastLineEndAdvancesLine() {
        pushFullPayload("p3-probe-2")
        nowMs = 1_000L + 3_000L
        playbackState = PlaybackState.STATE_PLAYING
        sessionPositionMs = 4_600L // l1 end 4000 + 500 之外

        producer.maybeProbeSessionLiveness(nowMs)

        val state = producer.state.value!!
        assertEquals("l2", state.line)
        assertEquals(2, state.lineIndex)
        assertEquals(4_600L, state.positionMs)
        assertTrue(state.playing)
        assertEquals(1f, state.speed)
        assertEquals(nowMs, state.sampledAtElapsedMs)
        assertEquals("l3", state.nextLine)
    }

    @Test
    fun probePlayingSeekJumpReselectsEarlierLine() {
        // 当前 l2（push position=4500），MediaSession 位置 2000：差 2500 > 2000 → 重选 l1。
        pushFullPayload("p3-probe-3", currentIndex = 2, positionMs = 4_500L)
        nowMs = 1_000L + (6_000L - 4_500L) + 1_000L
        playbackState = PlaybackState.STATE_PLAYING
        sessionPositionMs = 2_000L

        producer.maybeProbeSessionLiveness(nowMs)

        val state = producer.state.value!!
        assertEquals("l1", state.line)
        assertEquals(1, state.lineIndex)
        assertEquals(2_000L, state.positionMs)
        assertTrue(state.playing)
    }

    @Test
    fun probeNoOpWhenPositionAdvancesNormally() {
        pushFullPayload("p3-probe-4")
        val before = producer.state.value!!
        nowMs = 1_000L + 3_000L
        playbackState = PlaybackState.STATE_PLAYING
        sessionPositionMs = 2_500L // 仍在 l1 内，差 500 < 2000

        producer.maybeProbeSessionLiveness(nowMs)

        val after = producer.state.value!!
        assertEquals(before.sequence, after.sequence)
        assertEquals(before.line, after.line)
        assertEquals(before.positionMs, after.positionMs)
        assertTrue(after.playing)
    }

    @Test
    fun probeNoOpBeforePushIsOverdue() {
        pushFullPayload("p3-probe-5")
        val before = producer.state.value!!
        // 未到「行剩余时长 + 1s」：只过 1.5s，不得查询/发射。
        nowMs = 1_000L + 1_500L
        playbackState = PlaybackState.STATE_PAUSED
        sessionPositionMs = 2_500L

        producer.maybeProbeSessionLiveness(nowMs)

        assertEquals(before.sequence, producer.state.value?.sequence)
        assertTrue(producer.state.value!!.playing)
        assertTrue(queriedPackages.isEmpty())
    }

    @Test
    fun probeNoOpWithoutAllLyrics() {
        val legacy = SuperLyricData().apply {
            setTitle("P3 Legacy Probe Song")
            setArtist("P3 Legacy Probe Artist")
            setLyric(SuperLyricLine("legacy probe line", 0L, 2_000L))
        }
        producer.receiver.onLyric(OWNER_PUBLISHER, legacy)
        val before = producer.state.value!!
        nowMs = 20_000L
        playbackState = PlaybackState.STATE_PAUSED
        sessionPositionMs = 1_000L

        producer.maybeProbeSessionLiveness(nowMs)

        val after = producer.state.value!!
        assertEquals(before.sequence, after.sequence)
        assertEquals(before.line, after.line)
        assertTrue(after.playing)
        assertTrue(queriedPackages.isEmpty())
    }

    @Test
    fun probeThrottlesToOneSecond() {
        pushFullPayload("p3-probe-6")
        val before = producer.state.value!!
        nowMs = 1_000L + 3_000L
        playbackState = PlaybackState.STATE_PLAYING
        sessionPositionMs = 2_500L

        // 第一拍：已逾期，查询但不发射（位置仍在 l1 内）。
        producer.maybeProbeSessionLiveness(nowMs)
        assertEquals(before.sequence, producer.state.value?.sequence)

        // 同一秒内把位置推到行尾之外：节流必须挡住这一拍。
        nowMs += 500L
        sessionPositionMs = 4_600L
        producer.maybeProbeSessionLiveness(nowMs)
        assertEquals(before.sequence, producer.state.value?.sequence)

        // 越过 1s 后同一位置才允许发射。
        nowMs += SuperLyricLyricProducer.SESSION_LIVENESS_PROBE_MS
        producer.maybeProbeSessionLiveness(nowMs)
        assertEquals("l2", producer.state.value?.line)
    }

    // --- publisher 所有权不回归 ---

    @Test
    fun pausedResidencyKeepsOwnerAgainstForeignPublisher() {
        pushFullPayload("p3-owner-1")
        nowMs = 1_000L + 3_000L
        playbackState = PlaybackState.STATE_PAUSED
        sessionPositionMs = 2_500L
        producer.maybeProbeSessionLiveness(nowMs)
        assertFalse(producer.state.value!!.playing)

        // 已过 8s 推送宽限（last push=1000），但仍在 30s 暂停驻留窗口内：
        // playing=false 不得被误判为 owner 不新鲜。
        nowMs = 1_000L + SuperLyricLyricProducer.PUBLISHER_SWITCH_GRACE_MS + 6_000L
        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload("p3-owner-1-foreign", arrayOf(line("foreign paused", 0L, 2_000L)))
        )
        assertEquals("l1", producer.state.value?.line)
        assertEquals(TITLE, producer.state.value?.title)
        assertFalse(producer.state.value!!.playing)

        // 驻留窗口过后允许其他播放器接管。
        nowMs = 4_000L + SuperLyricLyricProducer.PAUSED_RESIDENCY_OWNER_HOLD_MS + 1L
        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload("p3-owner-1-foreign-2", arrayOf(line("foreign takeover", 0L, 2_000L)))
        )
        assertEquals("foreign takeover", producer.state.value?.line)
    }

    @Test
    fun liveOwnerStillRejectsForeignPublisher() {
        // 回归既有所有权语义：owner 在播且心跳新鲜时，第三方整首包不得顶掉。
        pushFullPayload("p3-owner-2")
        val ownerState = producer.state.value!!

        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload("p3-owner-2-foreign", arrayOf(line("foreign live", 0L, 2_000L)))
        )

        val state = producer.state.value!!
        assertEquals("l1", state.line)
        assertEquals(TITLE, state.title)
        assertEquals(ownerState.sequence, state.sequence)
        assertEquals(ownerState.generation, state.generation)
    }

    private companion object {
        const val TITLE = "P3 Session Liveness Song"
        const val ARTIST = "P3 Session Liveness Artist"

        /** 真实播放器发布端。 */
        const val OWNER_PUBLISHER = "netease"

        /** SuperLyric App 自带的测试发布端包名。 */
        const val TEST_PUBLISHER = "com.hchen.superlyric"
    }
}
