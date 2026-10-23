package com.eza.hyperglow.producer

import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SuperLyricLyricProducer] 的 SuperLyricApi-3.7 全曲包适配测试。
 *
 * 3.7 服务端改为「每首一次全量包（allLyrics + currentLyricIndex + duration + lyricId，行级
 * translation/secondary）+ 跨行增量包」。本文件钉住四件事：
 * - 活动行必须按 `currentLyricIndex` 取——官方发布端约定全量包的 `lyric` 是**首行**
 *   （Demo:「增量包为当前行，全量包为首行」），冷启动拿到全量包时按 lyric 取会把整首歌钉在首行；
 * - 行级 translation/secondary → translatedLine/romanizedLine，下一行从 allLyrics[idx+1] 取；
 * - duration/position/lyricId 的映射与缺省回落（旧模块下逐字节等价）；
 * - fullSongSnapshot 直读全曲 + 同会话缓存命中 + 无 allLyrics 返回 null（回落聚合器）。
 *
 * Robolectric 同 [SuperLyricNextLineTest]：receiver stub 继承 android.os.Binder。
 * 注意 [com.hchen.superlyricapi.SuperLyricCache] 是**静态**缓存，各用例的 lyricId/标题互不相同。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SuperLyricFullPayloadTest {

    private val producer = SuperLyricLyricProducer { 0L }

    // publisher 所有权用例使用可变 clock:固定 0 会让心跳落在「0 = 从未收到」哨兵上,
    // 无法区分「刚收到」与「从未收到」。每个用例的 nowMs 从 1000 起。
    private var nowMs = 1_000L
    private val publisherProducer = SuperLyricLyricProducer { nowMs }

    private fun line(
        text: String,
        startMs: Long,
        endMs: Long,
        translation: String? = null,
        secondary: String? = null,
        words: Array<SuperLyricWord>? = null
    ): SuperLyricLine = SuperLyricLine(text, words, translation, secondary, startMs, endMs)

    /** 官方发布端约定：全量包携带整首行表、当前行下标，`lyric` 填首行（兼容旧接收端）。 */
    private fun fullPayload(
        lyricId: String,
        lines: Array<SuperLyricLine>,
        currentIndex: Int = 0,
        durationMs: Long = 0L,
        positionMs: Long = -1L,
        title: String = FULL_TITLE,
        artist: String = FULL_ARTIST
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

    @Test
    fun fullPayloadSelectsLineByCurrentIndexNotFirstLine() {
        // 冷启动全曲包：lyric=首行（官方约定）但下标指向第 3 行 → 活动行必须按下标取，
        // 否则整首歌被钉在第一行（本适配最容易踩的坑）。
        val lines = arrayOf(
            line("line 0", 0L, 2_000L),
            line("line 1", 2_000L, 4_000L),
            line("line 2", 4_000L, 6_000L),
            line("line 3", 6_000L, 8_000L),
            line("line 4", 8_000L, 10_000L)
        )

        producer.receiver.onLyric("netease", fullPayload("idx-1", lines, currentIndex = 2))

        val state = producer.state.value
        assertEquals("line 2", state?.line)
        assertEquals(2, state?.lineIndex)
        assertEquals("line 3", state?.nextLine)
        assertEquals(6_000L, state?.nextLineStartMs)
    }

    @Test
    fun fullPayloadLineLevelTranslationSecondaryAndWordsFeedState() {
        // 行级 translation/secondary 直接映射；罗马音此前恒为空（旧版模块 data.secondary
        // 是下一行，不能当罗马音用，故只在全曲包路径取行级副歌词）。
        val lines = arrayOf(
            line("l0", 0L, 1_000L),
            line(
                "l1", 1_000L, 2_000L,
                translation = "译1", secondary = "ro1",
                words = arrayOf(
                    SuperLyricWord("l", 1_000L, 1_500L),
                    SuperLyricWord("1", 1_500L, 2_000L)
                )
            ),
            line("l2", 2_000L, 3_000L, translation = "译2", secondary = "ro2")
        )

        producer.receiver.onLyric("netease", fullPayload("lv-1", lines, currentIndex = 1))

        val state = producer.state.value
        assertEquals("l1", state?.line)
        assertEquals("译1", state?.translatedLine)
        assertEquals("ro1", state?.romanizedLine)
        assertEquals(LyricKind.SYLLABLE, state?.lyricKind)
        assertEquals(2, state?.words?.size)
        // 下一行的辅助文字随全曲行表一并下发（「显示第二行辅助文字」消费）。
        assertEquals("译2", state?.nextLineTranslated)
        assertEquals("ro2", state?.nextLineRomanized)
    }

    @Test
    fun fullPayloadMapsDurationPositionAndLyricIdTrackUri() {
        val lines = arrayOf(line("l0", 10_000L, 12_000L), line("l1", 12_000L, 14_000L))

        producer.receiver.onLyric(
            "netease",
            fullPayload(
                "dur-1", lines, currentIndex = 1,
                durationMs = 200_000L, positionMs = 45_000L
            )
        )

        val state = producer.state.value
        assertEquals(200_000L, state?.durationMs)
        assertEquals(45_000L, state?.positionMs)
        assertEquals("superlyric:dur-1", state?.trackUri)
    }

    @Test
    fun fullPayloadWithoutDurationPositionFallsBackToLineTimes() {
        // 缺省回落：服务端没给歌长/进度时保持旧值（行末/行首）——旧模块逐字节等价的行为面。
        val lines = arrayOf(line("l0", 10_000L, 12_000L), line("l1", 12_000L, 14_000L))

        producer.receiver.onLyric("netease", fullPayload("nodur-1", lines, currentIndex = 1))

        val state = producer.state.value
        assertEquals(14_000L, state?.durationMs)
        assertEquals(12_000L, state?.positionMs)
    }

    @Test
    fun fullPayloadClampsDurationAndPositionIntoValidFrame() {
        // AOD 快照校验要求 lineEndMs ≤ durationMs 且 positionMs ∈ [0, durationMs]：
        // 服务端歌长比行窗还短、点位越过歌尾时钳制，避免整帧被拒收（歌词整段不刷新）。
        val lines = arrayOf(
            line("l0", 0L, 5_000L),
            line(
                "l1", 5_000L, 9_000L,
                words = arrayOf(SuperLyricWord("尾", 9_500L, 12_000L))
            )
        )

        producer.receiver.onLyric(
            "netease",
            fullPayload(
                "clamp-1", lines, currentIndex = 1,
                durationMs = 10_000L, positionMs = 99_000L
            )
        )

        val state = producer.state.value
        assertEquals(12_000L, state?.lineEndMs)
        assertEquals(12_000L, state?.durationMs)
        assertEquals(12_000L, state?.positionMs)
    }

    @Test
    fun legacyPacketKeepsNextLineHeuristicAndBlankRoma() {
        // 旧模块（≤3.4）逐行包回归：data.secondary 仍是下一行、罗马音恒空、行下标恒 0、
        // 歌长/进度仍是行末/行首、trackUri 仍是标题——新字段全部按缺省回落。
        val current = SuperLyricLine("current line", 10_000L, 12_000L)
        val next = SuperLyricLine("next line", 12_500L, 14_000L)
        val legacy = SuperLyricData().apply {
            setTitle("Legacy Song")
            setArtist("Legacy Artist")
            setLyric(current)
            setSecondary(next)
        }

        producer.receiver.onLyric("netease", legacy)

        val state = producer.state.value
        assertEquals("current line", state?.line)
        assertEquals("next line", state?.nextLine)
        assertEquals(12_500L, state?.nextLineStartMs)
        assertEquals("", state?.romanizedLine)
        assertEquals("", state?.nextLineRomanized)
        assertEquals(0, state?.lineIndex)
        assertEquals(12_000L, state?.durationMs)
        assertEquals(10_000L, state?.positionMs)
        assertEquals("superlyric:Legacy Song", state?.trackUri)
        assertNull(producer.fullSongSnapshot())
    }

    @Test
    fun fullSongSnapshotCarriesRowsAndSessionTriple() {
        val lines = arrayOf(
            line("l0", 0L, 2_000L),
            line(
                "l1", 2_000L, 4_000L,
                translation = "译1", secondary = "ro1",
                words = arrayOf(
                    SuperLyricWord("l", 2_000L, 3_000L),
                    SuperLyricWord("1", 3_000L, 4_000L)
                )
            ),
            line("l2", 4_000L, 6_000L)
        )

        producer.receiver.onLyric(
            "netease",
            fullPayload("snap-1", lines, currentIndex = 1, durationMs = 120_000L)
        )

        val state = producer.state.value!!
        val snapshot = producer.fullSongSnapshot()
        assertNotNull(snapshot)
        assertEquals("superlyric", snapshot!!.producerId)
        assertEquals(state.generation, snapshot.generation)
        assertEquals(state.trackUri, snapshot.trackUri)
        assertEquals(120_000L, snapshot.durationMs)
        assertTrue(snapshot.matches(state))
        assertEquals(listOf("l0", "l1", "l2"), snapshot.rows.map { it.text })
        val row = snapshot.rows[1]
        assertEquals(2_000L, row.startMs)
        assertEquals(4_000L, row.endMs)
        assertEquals("译1", row.translation)
        assertEquals("ro1", row.roma)
        assertEquals(2, row.words?.size)
        assertEquals("l", row.words?.first()?.text)
    }

    @Test
    fun fullSongSnapshotIsCachedWithinSessionAndRebuiltOnTrackChange() {
        val lines = arrayOf(line("l0", 0L, 2_000L), line("l1", 2_000L, 4_000L))
        producer.receiver.onLyric(
            "netease",
            fullPayload("cache-1", lines, currentIndex = 1, durationMs = 60_000L)
        )

        val first = producer.fullSongSnapshot()
        // 同会话重复调用命中缓存（同一实例 → 不重新映射，接口 KDoc 要求廉价）。
        assertSame(first, producer.fullSongSnapshot())

        // 换歌（新 lyricId/新数组）→ 缓存失效并重建。
        val nextLines = arrayOf(line("n0", 0L, 2_000L))
        producer.receiver.onLyric(
            "netease",
            fullPayload("cache-2", nextLines, durationMs = 30_000L)
        )
        val rebuilt = producer.fullSongSnapshot()
        assertNotSame(first, rebuilt)
        assertEquals(1, rebuilt?.rows?.size)
        assertEquals("superlyric:cache-2", rebuilt?.trackUri)
    }

    @Test
    fun deltaAfterFullPayloadComposesIntoFullView() {
        // resolve 集成：先喂全量包（入静态缓存），再喂增量包（lyric=当前行 + 下标）→
        // 合成后仍有 allLyrics，活动行/下一行按下标取，快照仍是整首（元数据/歌长从缓存补齐）。
        val lines = arrayOf(
            line("d0", 0L, 2_000L),
            line("d1", 2_000L, 4_000L),
            line("d2", 4_000L, 6_000L),
            line("d3", 6_000L, 8_000L)
        )
        producer.receiver.onLyric(
            "netease",
            fullPayload("compose-1", lines, currentIndex = 0, durationMs = 60_000L)
        )
        val delta = SuperLyricData().apply {
            setTitle(FULL_TITLE)
            setArtist(FULL_ARTIST)
            setLyricId("compose-1")
            setLyric(lines[2])
            setCurrentLyricIndex(2)
        }

        producer.receiver.onLyric("netease", delta)

        val state = producer.state.value
        assertEquals("d2", state?.line)
        assertEquals(2, state?.lineIndex)
        assertEquals("d3", state?.nextLine)
        assertEquals(6_000L, state?.nextLineStartMs)
        assertEquals(60_000L, state?.durationMs)
        val snapshot = producer.fullSongSnapshot()
        assertNotNull(snapshot)
        assertEquals(4, snapshot!!.rows.size)
        assertEquals("superlyric:compose-1", snapshot.trackUri)
    }

    @Test
    fun deltaWithoutIndexKeepsPushedLineOverStaleCachedIndex() {
        // 增量包未带下标时，resolve 会用缓存里的旧下标补齐（缓存里是 0）；此时推送行
        // （lyric=当前行）更新鲜——活动行与派生字段必须整体退回旧逐行语义，不能被旧下标
        // 带回首行、也不能「行取推送行、下一行按旧下标取」。
        val lines = arrayOf(
            line("s0", 0L, 2_000L),
            line("s1", 2_000L, 4_000L),
            line("s2", 4_000L, 6_000L)
        )
        producer.receiver.onLyric("netease", fullPayload("stale-1", lines, currentIndex = 0))
        val delta = SuperLyricData().apply {
            setTitle(FULL_TITLE)
            setArtist(FULL_ARTIST)
            setLyricId("stale-1")
            setLyric(lines[2])
        }

        producer.receiver.onLyric("netease", delta)

        val state = producer.state.value
        assertEquals("s2", state?.line)
        assertEquals("", state?.nextLine)
        assertEquals(6_000L, state?.nextLineStartMs)
        // 下标来自缓存（旧逐行语义下恒为 0），只作活动行门；渲染行以上面钉的推送行为准。
        assertEquals(0, state?.lineIndex)
    }

    @Test
    fun startWithoutSuperLyricServiceStaysDisconnectedAndDoesNotCrash() {
        // 模块缺席（无 super_lyric 服务）是常态：注册被拒必须静默降级——连接保持
        // DISCONNECTED、状态保持 null，由仲裁者回退到别的源；主动拉取（getLatestLyric）
        // 与版本诊断失败同样静默。
        producer.start(RuntimeEnvironment.getApplication())

        assertEquals(ProducerConnection.DISCONNECTED, producer.connection.value)
        assertNull(producer.state.value)
        producer.stop()
    }

    // --- publisher 所有权 ---

    @Test
    fun foreignPublisherCannotReplaceLiveOwner() {
        // 真实源 netease 正在播时,测试 publisher(SuperLyric App 的 com.hchen.superlyric)
        // 推另一首全量包不得顶掉 owner 的歌词。
        publisherProducer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                "owner-1",
                arrayOf(line("owner line", 0L, 2_000L)),
                title = "Owner One Song"
            )
        )
        val ownerState = publisherProducer.state.value!!

        publisherProducer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload(
                "intruder-1",
                arrayOf(line("intruder line", 0L, 2_000L)),
                title = "Intruder One Song"
            )
        )

        val state = publisherProducer.state.value!!
        assertEquals("owner line", state.line)
        assertEquals("Owner One Song", state.title)
        // 丢弃的推送不得推进 sequence/generation(否则仲裁器会把旧状态当新帧)。
        assertEquals(ownerState.sequence, state.sequence)
        assertEquals(ownerState.generation, state.generation)
    }

    @Test
    fun foreignPublisherStopDoesNotClearLiveOwner() {
        publisherProducer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                "owner-2",
                arrayOf(line("owner stop line", 0L, 2_000L)),
                title = "Owner Two Song"
            )
        )

        publisherProducer.receiver.onStop(TEST_PUBLISHER, null)

        val state = publisherProducer.state.value
        assertNotNull(state)
        assertEquals("owner stop line", state?.line)
        assertEquals("Owner Two Song", state?.title)
    }

    @Test
    fun foreignEmptyPayloadDoesNotClearLiveOwner() {
        publisherProducer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                "owner-3",
                arrayOf(line("owner empty line", 0L, 2_000L)),
                title = "Owner Three Song"
            )
        )

        publisherProducer.receiver.onLyric(TEST_PUBLISHER, SuperLyricData())

        val state = publisherProducer.state.value
        assertNotNull(state)
        assertEquals("owner empty line", state?.line)
        assertEquals("Owner Three Song", state?.title)
    }

    @Test
    fun ownerStopClearsStateAndReleasesOwnership() {
        publisherProducer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                "owner-4",
                arrayOf(line("owner release line", 0L, 2_000L)),
                title = "Owner Four Song"
            )
        )

        publisherProducer.receiver.onStop(OWNER_PUBLISHER, null)
        assertNull(publisherProducer.state.value)

        // 所有权已释放:新 publisher 立即认领,不必等 grace。
        publisherProducer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload(
                "new-owner-4",
                arrayOf(line("new owner line", 0L, 2_000L)),
                title = "New Owner Four Song"
            )
        )

        val state = publisherProducer.state.value
        assertEquals("new owner line", state?.line)
        assertEquals("New Owner Four Song", state?.title)
    }

    @Test
    fun staleOwnerAllowsPublisherSwitchAfterGrace() {
        var now = 1_000L
        val producer = SuperLyricLyricProducer { now }
        producer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                "owner-5",
                arrayOf(line("stale owner line", 0L, 2_000L)),
                title = "Owner Five Song"
            )
        )

        now += SuperLyricLyricProducer.PUBLISHER_SWITCH_GRACE_MS + 1L
        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload(
                "switch-5",
                arrayOf(line("switch line", 0L, 2_000L)),
                title = "Switch Five Song"
            )
        )

        val state = producer.state.value
        assertEquals("switch line", state?.line)
        assertEquals("Switch Five Song", state?.title)
        // 合法切源:generation 递增,新会话 sequence 从 1 开始(归零后再 emit)。
        assertEquals(1, state?.generation)
        assertEquals(1L, state?.sequence)
    }

    @Test
    fun droppedForeignPushDoesNotRefreshOwnerHeartbeat() {
        var now = 1_000L
        val producer = SuperLyricLyricProducer { now }
        producer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                "owner-7",
                arrayOf(line("owner seven line", 0L, 2_000L)),
                title = "Owner Seven Song"
            )
        )

        // 外源推送被丢弃:不得把 owner 心跳刷新到 5000。
        now = 5_000L
        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload(
                "intruder-7",
                arrayOf(line("intruder seven line", 0L, 2_000L)),
                title = "Intruder Seven Song"
            )
        )
        assertEquals("Owner Seven Song", producer.state.value?.title)

        // 从 owner 最后一笔(1000)起已过 grace;若外源推送错误刷新了心跳(5000),
        // 此处仍会被判为 live 而拒绝,测试失败。
        now = 1_000L + SuperLyricLyricProducer.PUBLISHER_SWITCH_GRACE_MS + 1L
        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload(
                "switch-7",
                arrayOf(line("switch seven line", 0L, 2_000L)),
                title = "Switch Seven Song"
            )
        )
        assertEquals("Switch Seven Song", producer.state.value?.title)
    }

    @Test
    fun nullPublisherIsAcceptedButDoesNotTakeOwnership() {
        var now = 1_000L
        val producer = SuperLyricLyricProducer { now }
        producer.receiver.onLyric(
            OWNER_PUBLISHER,
            fullPayload(
                "owner-6",
                arrayOf(line("owner six line", 0L, 2_000L)),
                title = "Owner Six Song"
            )
        )

        now += 1_000L
        // 旧服务端/未知 publisher:兼容接受,但不抢占 owner。
        producer.receiver.onLyric(
            null,
            fullPayload(
                "null-6",
                arrayOf(line("null publisher line", 0L, 2_000L)),
                title = "Null Six Song"
            )
        )
        assertEquals("Null Six Song", producer.state.value?.title)

        // owner 仍是 netease 且仍 live:第三方推送继续被拒。
        producer.receiver.onLyric(
            TEST_PUBLISHER,
            fullPayload(
                "intruder-6",
                arrayOf(line("intruder six line", 0L, 2_000L)),
                title = "Intruder Six Song"
            )
        )
        assertEquals("Null Six Song", producer.state.value?.title)
        assertEquals("null publisher line", producer.state.value?.line)
    }

    private companion object {
        // 与既有 SuperLyric 用例（title=Test Song / artist=Test Artist）刻意不同：
        // SuperLyricCache 的元数据兜底键是 title##artist，标题撞车会让旧用例的逐行包
        // 意外命中本文件写入的全曲缓存。
        const val FULL_TITLE = "Full Payload Song"
        const val FULL_ARTIST = "Full Payload Artist"

        /** 真实播放器发布端(SuperLyric 服务端转发的 publisher)。 */
        const val OWNER_PUBLISHER = "netease"

        /** SuperLyric App 自带的测试发布端包名。 */
        const val TEST_PUBLISHER = "com.hchen.superlyric"
    }
}
