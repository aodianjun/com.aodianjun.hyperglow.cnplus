package com.eza.hyperglow.producer

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SongArtworkRepository] 的缓存上限与既有语义。
 *
 * 修复前 frames/lastAttemptAt 真无界(500 首 ≈ 12MiB 且随播放继续增长);本用例钉住:
 * 帧缓存按字节 LRU + 条数上限淘汰、失败表按条数上限淘汰最旧,以及并发去重、出帧回调补挂、
 * pending 完成后移除、同曲目命中/未命中这些**既有语义不回归**。
 *
 * 三个注入缝(fetch/时钟/执行器)只被本用例使用,生产默认行为不变:执行器换成本用例自己
 * 驱动的替身,取图时序完全确定(不用等真实线程池、不用 sleep)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SongArtworkRepositoryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** 手动驱动的执行器:入队的取图任务由用例自己 run。 */
    private val queued = ArrayDeque<Runnable>()

    private var nowMs = 1_000L
    private val fetchCalls = AtomicInteger()

    @Before
    fun setUp() {
        SongArtworkRepository.resetForTest()
        SongArtworkRepository.elapsedRealtime = { nowMs }
        SongArtworkRepository.artworkExecutor = Executor { queued.addLast(it) }
    }

    @After
    fun tearDown() {
        SongArtworkRepository.resetForTest()
    }

    // ---- 帧缓存:按字节 LRU + 条数上限 ----

    @Test
    fun frameCacheEvictsLeastRecentlyUsedEntryWhenByteBudgetExceeded() {
        val a = frame("track-a", jpegSize = 400)
        val b = frame("track-b", jpegSize = 400)
        val c = frame("track-c", jpegSize = 400)
        val cache = ArtworkFrameCache(
            maxEntries = 8,
            maxBytes = artworkFrameWeightBytes(a) + artworkFrameWeightBytes(b) + 1
        )

        cache.put("a", a)
        cache.put("b", b)
        assertSame(a, cache.get("a")) // a 命中即刷新使用序,b 变成最久未用
        cache.put("c", c) // 超字节预算:淘汰最久未用的 b,而不是刚用过的 a

        assertSame(a, cache.get("a"))
        assertSame(c, cache.get("c"))
        assertNull(cache.get("b"))
    }

    @Test
    fun frameCacheEvictsOldestEntryWhenCountLimitExceeded() {
        val cache = ArtworkFrameCache(maxEntries = 3, maxBytes = Int.MAX_VALUE)
        val first = frame("track-1")
        val second = frame("track-2")
        val third = frame("track-3")
        cache.put("k1", first)
        cache.put("k2", second)
        cache.put("k3", third)
        cache.get("k1") // 刷新 k1 的使用序:k2 成为最久未用

        cache.put("k4", frame("track-4"))

        assertNull(cache.get("k2"))
        assertSame(first, cache.get("k1"))
        assertSame(third, cache.get("k3"))
        assertNotNull(cache.get("k4"))
    }

    @Test
    fun frameCacheReplacementDoesNotDoubleCountBytes() {
        val a = frame("track-a", jpegSize = 400)
        val b = frame("track-b", jpegSize = 200)
        val cache = ArtworkFrameCache(
            maxEntries = 8,
            maxBytes = artworkFrameWeightBytes(a) + artworkFrameWeightBytes(b)
        )

        cache.put("a", a)
        cache.put("b", b)
        // 同键覆盖:旧条目先扣账再记新账。若重复计费,总量会超预算并把 b 挤掉。
        cache.put("a", frame("track-a", jpegSize = 400))

        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("a"))
    }

    @Test
    fun frameCacheKeepsSoleFrameEvenWhenItAloneExceedsBudget() {
        val frame = frame("track-a", jpegSize = 4_000)
        val cache = ArtworkFrameCache(maxEntries = 8, maxBytes = 100)

        cache.put("a", frame)

        // 刚写入的条目永不淘汰:出帧了就必须能命中,否则同曲目会被拖回每 5s 重取。
        assertSame(frame, cache.get("a"))
    }

    @Test
    fun frameWeightCountsJpegBytesAndStringPayload() {
        val frame = ArtworkFrame(
            key = "k".repeat(41),
            jpeg = ByteArray(1_000),
            title = "t".repeat(10),
            artist = "a".repeat(20),
            sourcePackage = "com.example.player" // 18 字符
        )

        // 权重 = JPEG 字节 + 四个字符串的 UTF-16 载荷(字符数 × 2)。
        assertEquals(1_000 + (41 + 10 + 20 + 18) * 2, artworkFrameWeightBytes(frame))
    }

    @Test
    fun defaultBudgetsFitTypicalSmallFramesUpToEntryLimit() {
        val cache = ArtworkFrameCache()
        repeat(MAX_ARTWORK_FRAME_ENTRIES) { index ->
            cache.put("k$index", frame("track-$index", jpegSize = 8 * 1024))
        }

        // 默认预算口径自洽:典型 8KiB 小帧能装满条数上限(由条数上限兜底)。
        assertNotNull(cache.get("k0"))
        assertNotNull(cache.get("k${MAX_ARTWORK_FRAME_ENTRIES - 1}"))
        // 最坏(每帧 24KiB)时字节预算先于条数上限触发,所以两个上限都真正生效。
        assertTrue(MAX_ARTWORK_FRAME_ENTRIES * MAX_ARTWORK_JPEG_BYTES > MAX_ARTWORK_FRAME_BYTES)
    }

    // ---- 失败表:条数上限 + 按时间淘汰最旧 ----

    @Test
    fun attemptThrottleBlocksRepeatWithinWindowAndAllowsAfterIt() {
        val throttle = ArtworkAttemptThrottle(throttleMs = 5_000L, maxEntries = 8, clock = { nowMs })

        assertTrue(throttle.tryAcquire("k"))
        nowMs += 4_999L
        assertFalse(throttle.tryAcquire("k"))
        nowMs += 1L
        assertTrue(throttle.tryAcquire("k"))
    }

    @Test
    fun attemptThrottleEvictsOldestEntryBeyondCountLimit() {
        val throttle = ArtworkAttemptThrottle(throttleMs = 5_000L, maxEntries = 3, clock = { nowMs })
        throttle.tryAcquire("oldest")
        nowMs += 1_000L
        throttle.tryAcquire("mid")
        nowMs += 1_000L
        throttle.tryAcquire("new")
        nowMs += 1_000L
        throttle.tryAcquire("newest") // 第 4 条:超限,按时间淘汰最旧的 oldest

        // 最旧条目已被淘汰:同一时刻可立刻重新尝试(节流记录被丢弃)。
        assertTrue(throttle.tryAcquire("oldest"))
        // 最近条目仍在窗口内:继续被节流。
        assertFalse(throttle.tryAcquire("newest"))
    }

    // ---- 仓库语义:去重 / 回调补挂 / pending 移除 / 命中 ----

    @Test
    fun ensureDeduplicatesInFlightFetchAndPublishesFrameWithTrackGeneration() {
        SongArtworkRepository.fetchArtwork = { _, _, _ ->
            fetchCalls.incrementAndGet()
            frame("Track A")
        }
        val ready = ArrayList<Pair<Long, ArtworkFrame>>()
        SongArtworkRepository.onFrameReady = { generation, frame -> ready += generation to frame }

        SongArtworkRepository.ensure(context, trackGeneration = 7L, title = "Track A", artist = "Artist")
        SongArtworkRepository.ensure(context, trackGeneration = 7L, title = "Track A", artist = "Artist")

        // 同一曲目第二次 ensure 在 pending 上被去重:只入队一次取图。
        assertEquals(1, queued.size)
        assertEquals(0, fetchCalls.get())
        queued.removeFirst().run()

        assertEquals(1, fetchCalls.get())
        val published = SongArtworkRepository.frameFor("Track A", "Artist")
        assertNotNull(published)
        assertSame(published, SongArtworkRepository.current.value)
        assertEquals(listOf(7L to published), ready) // 出帧后按发起时的曲目代号补挂回调
    }

    @Test
    fun ensureRemovesPendingAfterFetchCompletesSoLaterAttemptsCanRefetch() {
        SongArtworkRepository.fetchArtwork = { _, _, _ ->
            fetchCalls.incrementAndGet()
            null // 取图失败(无在播会话/校对不过):不缓存,只留节流记录
        }

        SongArtworkRepository.ensure(context, 1L, "Track A", "Artist")
        queued.removeFirst().run()
        assertEquals(1, fetchCalls.get())

        // 节流窗口过后再取:若 pending 没在完成后移除,这里会被去重挡掉、不再入队取图。
        nowMs += 5_000L
        SongArtworkRepository.ensure(context, 1L, "Track A", "Artist")
        assertEquals(1, queued.size)
        queued.removeFirst().run()
        assertEquals(2, fetchCalls.get())
    }

    @Test
    fun ensureThrottlesRefetchWithinWindowAfterFailedFetch() {
        SongArtworkRepository.fetchArtwork = { _, _, _ ->
            fetchCalls.incrementAndGet()
            null
        }

        SongArtworkRepository.ensure(context, 1L, "Track A", "Artist")
        queued.removeFirst().run()
        SongArtworkRepository.ensure(context, 1L, "Track A", "Artist") // 同一 5s 窗口内

        assertTrue(queued.isEmpty())
        assertEquals(1, fetchCalls.get())
    }

    @Test
    fun ensureSkipsFetchWhenFrameAlreadyCached() {
        SongArtworkRepository.fetchArtwork = { _, _, _ ->
            fetchCalls.incrementAndGet()
            frame("Track A")
        }
        SongArtworkRepository.ensure(context, 1L, "Track A", "Artist")
        queued.removeFirst().run()

        SongArtworkRepository.ensure(context, 2L, "Track A", "Artist")

        assertTrue(queued.isEmpty())
        assertEquals(1, fetchCalls.get())
    }

    @Test
    fun ensureIgnoresBlankTitle() {
        SongArtworkRepository.fetchArtwork = { _, _, _ -> error("blank title must not fetch") }

        SongArtworkRepository.ensure(context, 1L, "   ", "Artist")

        assertTrue(queued.isEmpty())
    }

    @Test
    fun frameForReturnsNullForBlankTitleAndUnrelatedTrack() {
        SongArtworkRepository.fetchArtwork = { _, _, _ -> frame("Track A") }
        SongArtworkRepository.ensure(context, 1L, "Track A", "Artist")
        queued.removeFirst().run()

        assertNotNull(SongArtworkRepository.frameFor("Track A", "Artist"))
        // 对不上当前曲目的查询一律不显示(不显示错的图)。
        assertNull(SongArtworkRepository.frameFor("Other Track", "Artist"))
        assertNull(SongArtworkRepository.frameFor("   ", "Artist"))
    }

    /** 键长度恒定(哈希补零到 8 位),使缓存权重只随 [jpegSize] 变化,淘汰断言可精确计算。 */
    private fun frame(title: String, jpegSize: Int = 64): ArtworkFrame =
        ArtworkFrame(
            key = "com.example.player|" + title.hashCode().toUInt().toString(16).padStart(8, '0'),
            jpeg = ByteArray(jpegSize),
            title = title,
            artist = "artist",
            sourcePackage = "com.example.player"
        )
}
