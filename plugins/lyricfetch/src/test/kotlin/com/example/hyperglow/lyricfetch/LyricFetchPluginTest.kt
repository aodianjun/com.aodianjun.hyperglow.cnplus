package com.example.hyperglow.lyricfetch

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
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
 * 处理器行为：升级策略、缓存（同会话不重复打网络）、回写形态（REPLACE + 角色 metadata +
 * 分侧继承），以及"失败必须静默透传"。
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

    private class FakeProvider(
        private val result: RawLyrics?,
        private val fail: Boolean = false,
    ) : LyricProvider {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        var calls: Int = 0

        override fun fetch(query: TrackQuery, budgetMs: Int): RawLyrics? {
            calls++
            if (fail) throw IllegalStateException("network down")
            return result
        }
    }

    private fun processor(
        provider: FakeProvider,
        upgradeOnly: Boolean = true,
        translation: Boolean = true,
        mergeSyllables: Boolean = true,
    ): Pair<LyricFetchProcessor, FakeProvider> {
        val context = FakePluginContext(
            mapOf(
                "lyricfetch_upgrade_only" to upgradeOnly,
                "lyricfetch_translation" to translation,
                "lyricfetch_merge_syllables" to mergeSyllables,
                "lyricfetch_provider" to "auto",
            )
        )
        return LyricFetchProcessor(context) { listOf(provider) } to provider
    }

    private fun song(rows: List<PluginLyricLine>, name: String = "Hello"): PluginSong =
        PluginSong(
            name = name,
            artist = "Adele",
            album = "25",
            duration = 295_000L,
            lyrics = rows,
        )

    private fun mediaInfo() = PluginProcessingContext(
        mediaInfo = PluginMediaInfo(title = "Hello", artist = "Adele", album = "25", duration = 295_000L)
    )

    private fun lineLevelRow() = PluginLyricLine(
        begin = 6_190L,
        end = 11_000L,
        duration = 4_810L,
        isAlignedRight = true,
        metadata = PluginMetadata(values = mapOf("role" to "LEAD")),
        text = "Hello, it's me",
    )

    @Test
    fun upgradesLineLevelToWordLevel() {
        val (processor, provider) = processor(
            FakeProvider(RawLyrics("fake", yrcFixture, matchedTitle = "Hello"))
        )
        val result = assertNotNull(processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))

        assertEquals(setOf(PluginSongField.LYRICS), result.changedFields)
        assertEquals(PluginLyricsUpdateMode.REPLACE, result.lyricsUpdateMode)
        val rows = assertNotNull(result.song.lyrics)
        assertEquals(2, rows.size)
        assertEquals("Hello, it's me", rows[0].text)
        assertTrue((rows[0].words?.size ?: 0) >= 2, "应带词级时间轴")
        assertEquals("LEAD", rows[0].metadata?.values?.get("role"))
        // 分侧按时间就近继承（宿主 diff 会把它当作变化覆盖回去，不能丢）
        assertTrue(rows[0].isAlignedRight, "对唱分侧应从同起始时间的原行继承")
        assertEquals(1, provider.calls)
    }

    @Test
    fun skipsFetchWhenCurrentLyricsAlreadyWordLevel() {
        val worded = listOf(
            PluginLyricLine(
                begin = 6_190L,
                end = 10_630L,
                text = "Hello",
                words = listOf(
                    PluginWord(begin = 6_190L, end = 8_380L, text = "Hello"),
                    PluginWord(begin = 8_380L, end = 10_630L, text = " there"),
                ),
            )
        )
        val (processor, provider) = processor(FakeProvider(RawLyrics("fake", yrcFixture)))
        assertNull(processor.processResult(song(worded), mediaInfo()))
        assertEquals(0, provider.calls, "已有逐字时不该打网络")
    }

    @Test
    fun rejectsWhenOnlineVersionHasNoWordLevelInUpgradeMode() {
        val (processor, _) = processor(FakeProvider(RawLyrics("fake", lrcFixture)))
        assertNull(processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
    }

    @Test
    fun replacesWithLineLevelWhenUpgradeOnlyDisabled() {
        val (processor, _) = processor(
            FakeProvider(RawLyrics("fake", lrcFixture)),
            upgradeOnly = false,
        )
        val result = assertNotNull(processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        val rows = assertNotNull(result.song.lyrics)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.words.isNullOrEmpty() })
    }

    @Test
    fun cachesFetchPerSong() {
        val (processor, provider) = processor(FakeProvider(RawLyrics("fake", yrcFixture)))
        val first = processor.processResult(song(listOf(lineLevelRow())), mediaInfo())
        val second = processor.processResult(song(listOf(lineLevelRow())), mediaInfo())
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1, provider.calls, "同一首歌的重复链运行必须走缓存")
    }

    @Test
    fun cachesNegativeResultToo() {
        val (processor, provider) = processor(FakeProvider(null))
        assertNull(processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertNull(processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(1, provider.calls, "未命中也要负缓存，避免每 15s 重打")
    }

    @Test
    fun providerFailureIsSilent() {
        val (processor, provider) = processor(FakeProvider(null, fail = true))
        assertNull(processor.processResult(song(listOf(lineLevelRow())), mediaInfo()))
        assertEquals(1, provider.calls)
    }

    @Test
    fun returnsNullWithoutTitle() {
        val (processor, provider) = processor(FakeProvider(RawLyrics("fake", yrcFixture)))
        val noTitle = PluginSong(lyrics = listOf(lineLevelRow()))
        assertNull(processor.processResult(noTitle, PluginProcessingContext()))
        assertEquals(0, provider.calls)
    }

    @Test
    fun fillsEmptyLyricsTableWhenOnlineHasWordLevel() {
        // 当前源没有歌词时也尝试补全（宿主链只在有行时调度，此路径主要防"半截快照"）。
        val (processor, provider) = processor(FakeProvider(RawLyrics("fake", yrcFixture)))
        val result = assertNotNull(processor.processResult(PluginSong(name = "Hello", artist = "Adele"), mediaInfo()))
        assertEquals(2, assertNotNull(result.song.lyrics).size)
        assertEquals(1, provider.calls)
    }

    // ---- fakes -----------------------------------------------------------

    private class FakePluginContext(private val values: Map<String, Any>) : PluginContext {
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

        override val logger = object : com.lidesheng.hyperlyric.plugin.api.PluginLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String, throwable: Throwable?) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }

        override val cache: PluginCache = object : PluginCache {
            private val map = HashMap<String, Any>()
            override fun getString(key: String): String? = map[key] as? String
            override fun putString(key: String, value: String) { map[key] = value }
            override fun getBytes(key: String): ByteArray? = map[key] as? ByteArray
            override fun putBytes(key: String, value: ByteArray) { map[key] = value }
            override fun contains(key: String): Boolean = map.containsKey(key)
            override fun remove(key: String) { map.remove(key) }
            override fun clear() = map.clear()
        }

        override val storage: PluginStorage = object : PluginStorage {
            private val map = HashMap<String, String>()
            override fun getString(key: String, defaultValue: String?): String? =
                map[key] ?: defaultValue

            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun clear() = map.clear()
        }

        override fun registerExtension(extension: com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension) = Unit
    }
}
