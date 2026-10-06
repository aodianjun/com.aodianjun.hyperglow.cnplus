package com.example.hyperglow.amllttml

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 缓存格式升级（v1 → v2）回归：旧记录解码失败视为未命中（自动重新取词），
 * 且不再出现在宿主缓存页（正文一并剔除），用户无需手动清缓存。
 */
class AmllTtmlCacheTest {

    private val now = 1_700_000_000_000L

    @Test
    fun legacyFormatVersionIsTreatedAsMiss() {
        val store = MapCache()
        val key = "蝴蝶|洛天依official|https://apiamldev"
        store.putString(key, """{"v":1,"savedAt":$now,"miss":true}""")

        val cache = AmllTtmlCache(store) { now }

        assertNull(cache.get(key), "v1 记录必须解码失败并视为未命中")
    }

    @Test
    fun v2HitAndMissRoundTripThroughStore() {
        val store = MapCache()
        AmllTtmlCache(store) { now }.apply {
            put("k1", CachedOutcome.Miss, "t", "s")
            put("k2", CachedOutcome.Hit("<tt/>", "蝴蝶", "洛天依Official"), "t", "s")
        }

        val fresh = AmllTtmlCache(store) { now }
        assertTrue(fresh.get("k1") is CachedOutcome.Miss)
        assertEquals(CachedOutcome.Hit("<tt/>", "蝴蝶", "洛天依Official"), fresh.get("k2"))
    }

    @Test
    fun entriesPrunesLegacyRecordsAndRemovesPayload() {
        val store = MapCache()
        val cache = AmllTtmlCache(store) { now }
        cache.put("k1", CachedOutcome.Miss, "蝴蝶 — 洛天依Official（未命中）", "summary")
        cache.put("k2", CachedOutcome.Hit("<tt/>", "蝴蝶", "洛天依Official"), "蝴蝶 — 洛天依Official", "summary")
        // 模拟设备上的旧 v1 记录：格式升级后同键 payload 仍是 v1（get() 不命中、也不再展示）。
        store.putString("k1", """{"v":1,"savedAt":$now,"miss":true}""")

        val entries = cache.entries()

        assertEquals(listOf("k2"), entries.map { it.id })
        assertFalse(store.contains("k1"), "旧 v1 正文应从宿主缓存剔除")
        assertTrue(store.contains("k2"))
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
}
