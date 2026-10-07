package com.example.hyperglow.aitranslationwords

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内存版 PluginCache（宿主实现走文件；这里只验证缓存逻辑本身）。 */
private class FakeCache : PluginCache {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun getBytes(key: String): ByteArray? = map[key]?.toByteArray(Charsets.UTF_8)
    override fun putBytes(key: String, value: ByteArray) {
        map[key] = value.toString(Charsets.UTF_8)
    }

    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun remove(key: String) {
        map.remove(key)
    }

    override fun clear() {
        map.clear()
    }
}

class TranslationWordsCacheTest {

    private val lines = listOf(
        CachedLine("hello world", "你好世界", listOf("你好", "世界")),
        CachedLine("good night", "晚安", null),
    )

    @Test
    fun putThenGetRoundTripsLinesWithFragments() {
        val cache = TranslationWordsCache(FakeCache())
        cache.put("k1", lines, "蝴蝶 — 洛天依Official", "→ 中文 · mimo-v2.5 · 2 行 · 逐字")
        val hit = cache.get("k1") as? CachedOutcome.Hit
        assertEquals(lines, hit?.lines)
    }

    @Test
    fun payloadStoresFragmentsOnlyWhenPresent() {
        val fake = FakeCache()
        TranslationWordsCache(fake).put("k1", lines, "t", "s")
        val pairs = JSONObject(fake.map.getValue("k1")).getJSONArray("pairs")
        assertEquals(2, pairs.getJSONObject(0).getJSONArray("s").length())
        assertTrue(!pairs.getJSONObject(1).has("s"))
    }

    @Test
    fun entriesExposeMetadataAndFollowDeletion() {
        val fake = FakeCache()
        val cache = TranslationWordsCache(fake)
        cache.put("k1", lines, "蝴蝶 — 洛天依Official", "→ 中文 · mimo-v2.5 · 2 行 · 逐字")
        val entry = cache.entries().single()
        assertEquals("k1", entry.id)
        assertEquals("蝴蝶 — 洛天依Official", entry.title)
        assertEquals("→ 中文 · mimo-v2.5 · 2 行 · 逐字", entry.summary)
        assertTrue((entry.sizeBytes ?: 0) > 0)

        // 删除后：内存与索引同步失效
        assertTrue(cache.clearEntry("k1"))
        assertNull(cache.get("k1"))
        assertTrue(cache.entries().isEmpty())
        assertFalse(cache.clearEntry("k1"))
    }

    @Test
    fun clearDropsEverything() {
        val cache = TranslationWordsCache(FakeCache())
        cache.put("k1", lines, "t", "s")
        cache.clear()
        assertNull(cache.get("k1"))
        assertTrue(cache.entries().isEmpty())
    }

    @Test
    fun expiredEntriesAreDroppedOnRead() {
        var now = 0L
        val cache = TranslationWordsCache(FakeCache(), nowMs = { now })
        cache.put("k1", lines, "t", "s")
        // 30 天 TTL：差 1 毫秒未过期
        now = 30L * 24 * 60 * 60 * 1000
        assertTrue(cache.get("k1") is CachedOutcome.Hit)
        // 超过后：读为未命中，且条目被清
        now += 1
        assertNull(cache.get("k1"))
        assertTrue(cache.entries().isEmpty())
    }

    @Test
    fun corruptedPayloadReadsAsMiss() {
        val fake = FakeCache()
        val cache = TranslationWordsCache(fake)
        fake.map["k1"] = "not json"
        assertNull(cache.get("k1"))
        fake.map["k2"] = """{"v":1,"savedAt":1,"pairs":[]}"""
        assertNull(cache.get("k2"))
    }

    @Test
    fun corruptedFragmentsReadAsNoFragments() {
        // 片段元素不是字符串（载荷损坏）时按无片段处理，译文仍然可用。
        val fake = FakeCache()
        val cache = TranslationWordsCache(fake)
        fake.map["k1"] =
            """{"v":1,"savedAt":${System.currentTimeMillis()},"pairs":[{"t":"a","r":"A","s":["x",5]}]}"""
        val hit = cache.get("k1") as? CachedOutcome.Hit
        assertEquals(listOf(CachedLine("a", "A", null)), hit?.lines)
    }
}
