package com.example.hyperglow.amllttml

import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Processor 级回归（脚本化假 client，无网络）：标题带括号后缀时，原文搜索 0 结果 →
 * 回退到剥括号变体命中 → 以规范化键写 Hit 并输出 REPLACE(WORDS)；传输失败不写负缓存。
 */
class AmllTtmlProcessorTest {

    private val rawTitle = "蝴蝶 (Cocoon Broken)"
    private val cleanTitle = "蝴蝶"
    private val artist = "洛天依Official"
    private val candidateId = 696943504933499L

    /** 合成 TTML（形状与 TtmlMapperTest 的对唱样本一致，已验证可被 TTMLParser 解析）。 */
    private val wordLevelTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
        <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body><div>
        <p begin="00:01.000" end="00:03.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">蝴</span><span begin="00:02.000" end="00:03.000">蝶</span></p>
        </div></body></tt>
    """.trimIndent()

    private fun candidate() = AmllMatch.Candidate(
        id = candidateId,
        musicNames = listOf(cleanTitle),
        artistNames = listOf(artist),
        albumNames = listOf("再生"),
    )

    private fun song(title: String) = PluginSong(name = title, artist = artist)

    private fun processing(title: String) = PluginProcessingContext(
        mediaInfo = PluginMediaInfo(title = title, artist = artist),
    )

    private fun cacheKey(title: String) =
        AmllQuery(title, listOf(artist), null).cacheKey(AmllTtmlClient.DEFAULT_BASE_URL)

    @Test
    fun bracketSuffixTitleFallsBackToStrippedVariantAndSharesNormalizedCacheKey() {
        val store = MapCache()
        val logger = RecordingLogger()
        val api = FakeApi(
            searchAnswers = { musicName ->
                when (musicName) {
                    rawTitle -> emptyList()            // 原文（带括号后缀）：AMLL 库 0 结果
                    cleanTitle -> listOf(candidate())  // 剥括号变体：命中
                    else -> emptyList()
                }
            },
            ttml = wordLevelTtml,
        )
        val cache = AmllTtmlCache(store) { 1_000L }
        val processor = AmllTtmlProcessor(FakeContext(logger), cache) { api }

        val result = processor.processResult(song(rawTitle), processing(rawTitle))

        assertNotNull(result)
        assertEquals(PluginLyricsUpdateMode.REPLACE, result.lyricsUpdateMode)
        assertTrue(PluginSongField.LYRICS in result.changedFields)
        assertTrue(PluginLyricField.WORDS in result.changedLyricFields)
        assertTrue(result.song.lyrics.orEmpty().any { (it.words?.size ?: 0) >= 2 })
        // 假 client 确实按「原文 → 剥括号」两次不同 musicName 查询，命中在第二次。
        assertEquals(listOf(rawTitle, cleanTitle), api.searchedNames)
        assertEquals(listOf(candidateId), api.fetchedIds)

        // 命中写入规范化键：干净标题的会话共享同一条（同曲不同标题写法）。
        val key = cacheKey(rawTitle)
        assertEquals(key, cacheKey(cleanTitle))
        assertNotNull(cache.get(key))

        // 第二次（干净标题）会话直接吃缓存，不再联网。
        val second = processor.processResult(song(cleanTitle), processing(cleanTitle))
        assertNotNull(second)
        assertEquals(listOf(rawTitle, cleanTitle), api.searchedNames)
    }

    @Test
    fun cleanTitleHitsOnFirstVariantWithoutFallback() {
        val store = MapCache()
        val api = FakeApi(
            searchAnswers = { musicName ->
                if (musicName == cleanTitle) listOf(candidate()) else emptyList()
            },
            ttml = wordLevelTtml,
        )
        val processor = AmllTtmlProcessor(FakeContext(RecordingLogger()), AmllTtmlCache(store) { 1_000L }) { api }

        val result = processor.processResult(song(cleanTitle), processing(cleanTitle))

        assertNotNull(result)
        assertEquals(listOf(cleanTitle), api.searchedNames)
    }

    @Test
    fun searchTransportFailureAbortsVariantsWithoutNegativeCache() {
        val store = MapCache()
        val logger = RecordingLogger()
        var calls = 0
        val api = FakeApi(searchAnswers = { calls++; null }, ttml = null)
        val processor = AmllTtmlProcessor(FakeContext(logger), AmllTtmlCache(store) { 1_000L }) { api }

        val result = processor.processResult(song(rawTitle), processing(rawTitle))

        assertNull(result)
        assertEquals(1, calls, "传输失败必须立即中止变体循环")
        assertFalse(store.contains(cacheKey(rawTitle)), "传输失败不得写负缓存")
        assertTrue(
            logger.infos.any { it.contains("AMLL search failed (network)") && it.contains(rawTitle) }
        )
    }

    @Test
    fun allVariantsMissWritesNegativeCacheWithTriedList() {
        val store = MapCache()
        val logger = RecordingLogger()
        val api = FakeApi(searchAnswers = { emptyList() }, ttml = null)
        val cache = AmllTtmlCache(store) { 1_000L }
        val processor = AmllTtmlProcessor(FakeContext(logger), cache) { api }

        val result = processor.processResult(song(rawTitle), processing(rawTitle))

        assertNull(result)
        assertEquals(AmllMatch.searchTitleVariants(rawTitle), api.searchedNames)
        val key = cacheKey(rawTitle)
        assertTrue(store.contains(key))
        assertTrue(cache.get(key) is CachedOutcome.Miss)
        assertTrue(
            logger.infos.any {
                it.contains("no AMLL match") && it.contains("tried:") && it.contains(cleanTitle)
            }
        )
    }

    @Test
    fun fetchTransportFailureDoesNotWriteNegativeCache() {
        val store = MapCache()
        val logger = RecordingLogger()
        val api = FakeApi(searchAnswers = { listOf(candidate()) }, ttml = null)
        val cache = AmllTtmlCache(store) { 1_000L }
        val processor = AmllTtmlProcessor(FakeContext(logger), cache) { api }

        val result = processor.processResult(song(cleanTitle), processing(cleanTitle))

        assertNull(result)
        assertFalse(store.contains(cacheKey(cleanTitle)), "取词传输失败不得写负缓存")
        assertTrue(logger.warns.any { it.contains("AMLL fetch failed (network)") })
    }

    @Test
    fun successfulFetchWithEmptyPayloadStillWritesNegativeCache() {
        // client 返回空串 = 请求成功但库中这条不可用：照旧写 Miss（与传输失败区分）。
        val store = MapCache()
        val api = FakeApi(searchAnswers = { listOf(candidate()) }, ttml = "")
        val cache = AmllTtmlCache(store) { 1_000L }
        val processor = AmllTtmlProcessor(FakeContext(RecordingLogger()), cache) { api }

        val result = processor.processResult(song(cleanTitle), processing(cleanTitle))

        assertNull(result)
        assertTrue(cache.get(cacheKey(cleanTitle)) is CachedOutcome.Miss)
    }

    // ---- 假实现 -----------------------------------------------------------

    private class FakeApi(
        private val searchAnswers: (String) -> List<AmllMatch.Candidate>?,
        private val ttml: String?,
    ) : AmllTtmlApi {
        val searchedNames = mutableListOf<String>()
        val fetchedIds = mutableListOf<Long>()

        override fun search(query: AmllQuery, musicName: String, budgetMs: Int): List<AmllMatch.Candidate>? {
            searchedNames += musicName
            return searchAnswers(musicName)
        }

        override fun fetchTtml(id: Long, budgetMs: Int): String? {
            fetchedIds += id
            return ttml
        }
    }

    private class MapCache : PluginCache {
        val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getBytes(key: String): ByteArray? = map[key]?.toByteArray(Charsets.UTF_8)
        override fun putBytes(key: String, value: ByteArray) { map[key] = value.toString(Charsets.UTF_8) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    private class RecordingLogger : PluginLogger {
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        override fun debug(message: String) {}
        override fun info(message: String) { infos += message }
        override fun warn(message: String, throwable: Throwable?) { warns += message }
        override fun error(message: String, throwable: Throwable?) {}
    }

    private class FakeContext(override val logger: PluginLogger) : PluginContext {
        override val pluginId: String = "com.example.hyperglow.amllttml"
        override val hostApiVersion: Int = 1
        override val config: PluginConfig = object : PluginConfig {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            override fun getString(key: String, defaultValue: String?): String? = defaultValue
            override fun getLong(key: String, defaultValue: Long): Long = defaultValue
            override fun getFloat(key: String, defaultValue: Float): Float = defaultValue
            override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> = defaultValue
        }
        override val cache: PluginCache = MapCache()
        override val storage: PluginStorage = object : PluginStorage {
            override fun getString(key: String, defaultValue: String?): String? = defaultValue
            override fun putString(key: String, value: String) {}
            override fun remove(key: String) {}
            override fun clear() {}
        }
        override fun registerExtension(extension: HyperLyricExtension) {}
    }
}
