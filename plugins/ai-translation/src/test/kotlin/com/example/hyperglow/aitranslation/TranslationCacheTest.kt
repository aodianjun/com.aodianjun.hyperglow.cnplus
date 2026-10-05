package com.example.hyperglow.aitranslation

import com.lidesheng.hyperlyric.plugin.api.PluginCache
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

class TranslationCacheTest {

    private val pairs = listOf(
        "你说你来到这世界的那天" to "The day you came to this world",
        "hello world" to "你好世界",
    )

    @Test
    fun putThenGetRoundTripsPairs() {
        val cache = TranslationCache(FakeCache())
        cache.put("k1", CachedOutcome.Hit(pairs), "蝴蝶 — 洛天依Official", "→ 中文 · mimo-v2.5 · 2 行")
        val hit = cache.get("k1") as? CachedOutcome.Hit
        assertEquals(pairs, hit?.pairs)
    }

    @Test
    fun entriesExposeMetadataAndFollowDeletion() {
        val fake = FakeCache()
        val cache = TranslationCache(fake)
        cache.put("k1", CachedOutcome.Hit(pairs), "蝴蝶 — 洛天依Official", "→ 中文 · mimo-v2.5 · 2 行")
        val entry = cache.entries().single()
        assertEquals("k1", entry.id)
        assertEquals("蝴蝶 — 洛天依Official", entry.title)
        assertEquals("→ 中文 · mimo-v2.5 · 2 行", entry.summary)
        assertTrue((entry.sizeBytes ?: 0) > 0)

        // 删除后：内存与索引同步失效
        assertTrue(cache.clearEntry("k1"))
        assertNull(cache.get("k1"))
        assertTrue(cache.entries().isEmpty())
        assertFalse(cache.clearEntry("k1"))
    }

    @Test
    fun clearDropsEverything() {
        val cache = TranslationCache(FakeCache())
        cache.put("k1", CachedOutcome.Hit(pairs), "t", "s")
        cache.clear()
        assertNull(cache.get("k1"))
        assertTrue(cache.entries().isEmpty())
    }

    @Test
    fun expiredEntriesAreDroppedOnRead() {
        var now = 0L
        val cache = TranslationCache(FakeCache(), nowMs = { now })
        cache.put("k1", CachedOutcome.Hit(pairs), "t", "s")
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
        val cache = TranslationCache(fake)
        fake.map["k1"] = "not json"
        assertNull(cache.get("k1"))
        fake.map["k2"] = """{"v":1,"savedAt":1,"pairs":[]}"""
        assertNull(cache.get("k2"))
    }
}
