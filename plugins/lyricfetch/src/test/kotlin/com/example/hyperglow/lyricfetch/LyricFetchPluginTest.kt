package com.example.hyperglow.lyricfetch

import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginStorage
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 处理器行为：升级策略、缓存（同会话不重复打网络 / 跨"进程重启"仍在 / 可被宿主缓存页删除）、
 * 回写形态（REPLACE + 角色 metadata + 分侧继承），以及"失败必须静默透传"。
 */
class LyricFetchPluginTest {

    private val yrcFixture = """
        [6190,4440](6190,2190,0)Hello(8380,540,0), (8920,360,0)it's (9280,1350,0)me
        [11770,6090](11770,360,0)I (12130,210,0)was (12340,1350,0)wondering
    """.trimIndent()

    private val lrcFixture = """
        [00:06.22] Hello, it's me
        [00:11.84] I was wondering
    """.trimIndent()

    // ---- 夹具 -------------------------------------------------------------

    private class FakeProvider(
        private val result: RawLyrics?,
        private val fail: Boolean = false,
    ) : LyricProvider {
        override val id: String = "fake"
        override val displayName: String = "FakeSource"
        var calls: Int = 0

        override fun fetch(query: TrackQuery, budgetMs: Int): RawLyrics? {
            calls++
            if (fail) throw IllegalStateException("network down")
            return result
        }
    }

    private class FakePluginCache : PluginCache {
        val map = HashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getBytes(key: String): ByteArray? = map[key]?.toByteArray(Charsets.UTF_8)
        override fun putBytes(key: String, value: ByteArray) { map[key] = String(value, Charsets.UTF_8) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun remove(key: String) { map.remove(key) }
        override fun clear() = map.clear()
    }

    private class Harness(
        val provider: FakeProvider,
        val hostCache: FakePluginCache = FakePluginCache(),
        now: () -> Long = System::currentTimeMillis,
        upgradeOnly: Boolean = true,
        translation: Boolean = true,
        mergeSyllables: Boolean = true,
    ) {
        val lyricCache = LyricCache(hostCache, now)
        val processor = LyricFetchProcessor(
            FakePluginContext(
                mapOf(
                    "lyricfetch_upgrade_only" to upgradeOnly,
                    "lyricfetch_translation" to translation,
                    "lyricfetch_merge_syllables" to mergeSyllables,
                    "lyricfetch_provider" to "auto",
                ),
                hostCache,
            ),
            lyricCache,
        ) { listOf(provider) }
        val extension = LyricCacheExtension(lyricCache)
    }

    private fun song(
        rows: List<PluginLyricLine>,
        name: String = "Hello",
        durationMs: Long = 295_000L,
    ): PluginSong =
        PluginSong(name = name, artist = "Adele", album = "25", duration = durationMs, lyrics = rows)

    private fun mediaInfo(durationMs: Long? = 295_000L) = PluginProcessingContext(
        mediaInfo = PluginMediaInfo(title = "Hello", artist = "Adele", album = "25", duration = durationMs)
    )

    private fun lineLevelRow() = PluginLyricLine(
        begin = 6_190L,
        end = 11_000L,
        duration = 4_810L,
        isAlignedRight = true,
        metadata = PluginMetadata(values = mapOf("role" to "LEAD")),
        text = "Hello, it's me",
    )

    private fun wordLevelRow() = PluginLyricLine(
        begin = 6_190L,
        end = 10_630L,
        text = "Hello",
        words = listOf(
            PluginWord(begin = 6_190L, end = 8_380L, text = "Hello"),
            PluginWord(begin = 8_380L, end = 10_630L, text = " there"),
        ),
    )

    // ---- 处理器行为 -------------------------------------------------------

    @Test
    fun upgradesLineLevelToWordLevel() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture, matchedTitle = "Hello")))
        val result = assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))

        assertEquals(setOf(PluginSongField.LYRICS), result.changedFields)
        assertEquals(PluginLyricsUpdateMode.REPLACE, result.lyricsUpdateMode)
        val rows = assertNotNull(result.song.lyrics)
        assertEquals(2, rows.size)
        assertEquals("Hello, it's me", rows[0].text)
        assertTrue((rows[0].words?.size ?: 0) >= 2, "应带词级时间轴")
        assertEquals("LEAD", rows[0].metadata?.values?.get("role"))
        assertTrue(rows[0].isAlignedRight, "对唱分侧应从同起始时间的原行继承")
        assertEquals(1, h.provider.calls)
    }

    @Test
    fun skipsFetchWhenCurrentLyricsAlreadyWordLevel() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNull(h.processor.processResult(song(listOf(wordLevelRow())), mediaInfo()))
        assertEquals(0, h.provider.calls, "已有逐字时不该打网络")
    }

    @Test
    fun rejectsWhenOnlineVersionHasNoWordLevelInUpgradeMode() {
        val h = Harness(FakeProvider(RawLyrics("fake", lrcFixture)))
        assertNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
    }

    @Test
    fun replacesWithLineLevelWhenUpgradeOnlyDisabled() {
        val h = Harness(FakeProvider(RawLyrics("fake", lrcFixture)), upgradeOnly = false)
        val result = assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        val rows = assertNotNull(result.song.lyrics)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.words.isNullOrEmpty() })
    }

    @Test
    fun cachesFetchPerSong() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(1, h.provider.calls, "同一首歌的重复链运行必须走缓存")
    }

    @Test
    fun cachesNegativeResultToo() {
        val h = Harness(FakeProvider(null))
        assertNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(1, h.provider.calls, "未命中也要负缓存，避免每 15s 重打")
    }

    @Test
    fun reusesCacheWhenHostDurationChangesPerRun() {
        // 逐行源（SuperLyric）把「当前行结束时间」当 duration 上报：同一首歌每次链重跑
        // 拿到的时长都不同。缓存身份与时长无关，因此必须照样命中、且只留一条条目。
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNotNull(
            h.processor.processResult(
                song(listOf(lineLevelRow()), durationMs = 5_220L),
                mediaInfo(durationMs = 5_220L),
            )
        )
        assertNotNull(
            h.processor.processResult(
                song(listOf(lineLevelRow()), durationMs = 239_349L),
                mediaInfo(durationMs = 239_349L),
            )
        )
        assertEquals(1, h.provider.calls, "时长随播放推进变化时仍必须走缓存")
        assertEquals(1, h.extension.listEntries().size, "同一首歌只应有一条缓存条目")
    }

    @Test
    fun providerFailureIsSilent() {
        val h = Harness(FakeProvider(null, fail = true))
        assertNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(1, h.provider.calls)
    }

    @Test
    fun returnsNullWithoutTitle() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNull(h.processor.processResult(PluginSong(lyrics = listOf(lineLevelRow())), PluginProcessingContext()))
        assertEquals(0, h.provider.calls)
    }

    @Test
    fun fillsEmptyLyricsTableWhenOnlineHasWordLevel() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        val result = assertNotNull(
            h.processor.processResult(PluginSong(name = "Hello", artist = "Adele"), mediaInfo())
        )
        assertEquals(2, assertNotNull(result.song.lyrics).size)
        assertEquals(1, h.provider.calls)
    }

    // ---- 缓存管理（宿主缓存页契约）----------------------------------------

    @Test
    fun cacheEntriesAreListedWithMetadata() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture, matchedTitle = "Hello")))
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))

        val entries = h.extension.listEntries()
        assertEquals(1, entries.size, "取词结果应作为一条缓存条目暴露给宿主")
        val entry = entries.single()
        assertEquals("Hello — Adele", entry.title)
        assertTrue(entry.summary?.contains("FakeSource") == true, "summary=${entry.summary}")
        assertTrue((entry.sizeBytes ?: 0) > 0)
        assertTrue((entry.updatedAtEpochMs ?: 0) > 0)
    }

    @Test
    fun negativeCacheIsListedAsMiss() {
        val h = Harness(FakeProvider(null))
        assertNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        val entry = h.extension.listEntries().single()
        assertTrue(entry.title.endsWith("（未命中）"), "title=${entry.title}")
    }

    @Test
    fun clearingEntryForcesRefetch() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        val entry = h.extension.listEntries().single()

        assertTrue(h.extension.clearEntry(entry.id), "删除应报告条目存在")
        assertTrue(h.extension.listEntries().isEmpty(), "删除后不应再列出")
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(2, h.provider.calls, "删除条目后必须重新取词（内存副本也要失效）")
    }

    @Test
    fun clearingUnknownEntryReportsFalse() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertTrue(!h.extension.clearEntry("not-a-key"))
    }

    @Test
    fun legacyFormatEntriesArePurgedFromCachePage() {
        // 1.0.0 的键含时长，真机上同一首歌在缓存页堆了几十条（实测 21 条）。格式升版后
        // 这些条目既查不到（键变了）也不该继续占着列表——读列表时顺手把正文清掉。
        val hostCache = FakePluginCache()
        val legacyKey = "hello|adele|295000|auto|true|true|true"
        hostCache.map[legacyKey] = """{"v":1,"savedAt":1,"miss":true}"""
        hostCache.map["__lyricfetch_index__"] =
            """[{"id":"$legacyKey","title":"Hello — Adele（未命中）","summary":"旧格式","size":43,"updatedAt":1}]"""

        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)), hostCache)
        assertTrue(h.extension.listEntries().isEmpty(), "旧格式条目不应再列出")
        assertTrue(!hostCache.map.containsKey(legacyKey), "旧格式条目的正文应被清除")
    }

    @Test
    fun currentFormatEntriesSurviveTheListing() {
        // 反向钉子：格式校验不能把当前格式的条目也一起清掉
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        val entry = h.extension.listEntries().single()
        assertNotNull(h.extension.listEntries().singleOrNull(), "重复列出不应清掉当前格式条目")
        assertTrue(h.hostCache.map.containsKey(entry.id))
    }

    @Test
    fun clearAllDropsEverything() {
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        h.extension.clearAll()
        assertTrue(h.extension.listEntries().isEmpty())
        assertTrue(h.hostCache.map.isEmpty(), "宿主缓存也应被清空")
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(2, h.provider.calls)
    }

    @Test
    fun cacheSurvivesPluginReload() {
        // 模拟进程重启：同一份宿主缓存、全新的处理器实例与来源实例
        val hostCache = FakePluginCache()
        val first = Harness(FakeProvider(RawLyrics("fake", yrcFixture)), hostCache)
        assertNotNull(first.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(1, first.provider.calls)

        val second = Harness(FakeProvider(RawLyrics("fake", yrcFixture)), hostCache)
        val result = assertNotNull(second.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(0, second.provider.calls, "重启后应命中持久缓存，不再联网")
        assertEquals(2, assertNotNull(result.song.lyrics).size)
        assertEquals(1, second.extension.listEntries().size)
    }

    @Test
    fun staleEntriesExpire() {
        var now = 1_000_000L
        val h = Harness(FakeProvider(RawLyrics("fake", yrcFixture)), now = { now })
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(1, h.provider.calls)

        now += 8L * 24 * 60 * 60 * 1000 // 8 天后：超过 7 天 TTL
        assertNotNull(h.processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(2, h.provider.calls, "过期条目应被忽略并重新取词")
    }

    // ---- 宿主服务的假实现 -------------------------------------------------

    private class FakePluginContext(
        private val values: Map<String, Any>,
        private val cacheBackend: PluginCache,
    ) : PluginContext {
        override val pluginId: String = "com.example.hyperglow.lyricfetch"
        override val hostApiVersion: Int = 1
        override val config: PluginConfig = object : PluginConfig {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
                values[key] as? Boolean ?: defaultValue

            override fun getString(key: String, defaultValue: String?): String? =
                values[key] as? String ?: defaultValue

            override fun getLong(key: String, defaultValue: Long): Long =
                values[key] as? Long ?: defaultValue

            override fun getFloat(key: String, defaultValue: Float): Float =
                values[key] as? Float ?: defaultValue

            override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
                defaultValue
        }

        override val logger: PluginLogger = object : PluginLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String, throwable: Throwable?) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }

        override val storage: PluginStorage = object : PluginStorage {
            private val map = HashMap<String, String>()
            override fun getString(key: String, defaultValue: String?): String? =
                map[key] ?: defaultValue

            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun clear() = map.clear()
        }

        override fun registerExtension(extension: HyperLyricExtension) = Unit

        override val cache: PluginCache get() = cacheBackend
    }
}
