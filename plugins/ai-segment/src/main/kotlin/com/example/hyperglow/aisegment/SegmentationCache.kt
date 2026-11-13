package com.example.hyperglow.aisegment

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 切词缓存:键 = 源行身份(起始时间 + 行文本 + 影响结果的配置)哈希,
 * 值 = 该行切分后的子行文本列表。
 *
 * 与 ai-translation 的缓存同策略:**只缓存成功结果**(失败由处理器的失败节流退避,
 * 不写缓存);流式源(逐行推送)每 15s 重跑一次链,行级缓存保证只有新出现的长行
 * 才会触发 AI 请求,已切过的行直接复用。
 */
internal class SegmentationCache(
    private val cache: PluginCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val memory = java.util.concurrent.ConcurrentHashMap<String, Record>()

    /** 取一行切词结果;过期/损坏/缺失一律 null(调用方按未命中处理)。 */
    fun get(key: String): List<String>? {
        memory[key]?.let { cached ->
            if (!isExpired(cached.savedAt)) return cached.children
            memory.remove(key)
            runCatching { cache.remove(key) }
            updateIndex { it.remove(key) }
            return null
        }
        val stored = runCatching { cache.getString(key) }.getOrNull() ?: return null
        val record = runCatching { decode(stored) }.getOrNull() ?: return null
        if (isExpired(record.savedAt)) {
            runCatching { cache.remove(key) }
            updateIndex { it.remove(key) }
            return null
        }
        memory[key] = record
        return record.children
    }

    /** 写入一行切词结果并更新索引(title/summary 供宿主缓存管理页展示)。 */
    fun put(key: String, children: List<String>, title: String, summary: String) {
        val savedAt = nowMs()
        val payload = encode(children, savedAt) ?: return
        runCatching { cache.putString(key, payload) }
            .onFailure { return }
        memory[key] = Record(children, savedAt)
        updateIndex { index ->
            index.put(
                key,
                JSONObject()
                    .put("title", title)
                    .put("summary", summary)
                    .put("size", payload.toByteArray(Charsets.UTF_8).size.toLong())
                    .put("updatedAt", savedAt)
            )
            evictOldest(index)
        }
    }

    private fun isExpired(savedAt: Long): Boolean = nowMs() - savedAt > TTL_MS

    /** 宿主缓存页用:当前条目元数据(已消失的条目自动从索引剔除)。 */
    fun entries(): List<PluginCacheEntry> = readIndex().mapNotNull { (id, meta) ->
        val exists = runCatching { cache.contains(id) }.getOrDefault(false)
        if (!exists) return@mapNotNull null
        PluginCacheEntry(
            id = id,
            title = meta.optString("title").ifBlank { id },
            summary = meta.optString("summary").takeIf { it.isNotBlank() },
            sizeBytes = meta.optLong("size").takeIf { it > 0L },
            updatedAtEpochMs = meta.optLong("updatedAt").takeIf { it > 0L },
        )
    }.sortedByDescending { it.updatedAtEpochMs ?: 0L }

    fun clear() {
        runCatching { cache.clear() }
        memory.clear()
    }

    fun clearEntry(entryId: String): Boolean {
        val existed = runCatching { cache.contains(entryId) }.getOrDefault(false)
        runCatching { cache.remove(entryId) }
        memory.remove(entryId)
        updateIndex { it.remove(entryId) }
        return existed
    }

    // ---- 序列化 ----------------------------------------------------------

    private data class Record(val children: List<String>, val savedAt: Long)

    private fun encode(children: List<String>, savedAt: Long): String? = runCatching {
        val array = JSONArray()
        children.forEach { array.put(it) }
        JSONObject()
            .put("v", FORMAT_VERSION)
            .put("savedAt", savedAt)
            .put("children", array)
            .toString()
    }.getOrNull()

    private fun decode(stored: String): Record? = runCatching {
        val json = JSONObject(stored)
        if (json.optInt("v") != FORMAT_VERSION) return@runCatching null
        val savedAt = json.optLong("savedAt")
        val array = json.optJSONArray("children") ?: return@runCatching null
        val children = buildList {
            for (i in 0 until array.length()) {
                val child = array.optString(i)
                if (child.isNotBlank()) add(child)
            }
        }
        if (children.isEmpty()) return@runCatching null
        Record(children, savedAt)
    }.getOrNull()

    // ---- 索引 ------------------------------------------------------------

    private fun readIndex(): Map<String, JSONObject> = runCatching {
        val raw = cache.getString(INDEX_KEY) ?: return@runCatching emptyMap()
        val array = JSONArray(raw)
        buildMap {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                put(id, item)
            }
        }
    }.getOrDefault(emptyMap())

    private fun updateIndex(mutate: (MutableMap<String, JSONObject>) -> Unit) {
        val index = readIndex().toMutableMap()
        mutate(index)
        val array = JSONArray()
        index.forEach { (id, meta) -> array.put(JSONObject(meta.toString()).put("id", id)) }
        runCatching { cache.putString(INDEX_KEY, array.toString()) }
    }

    private fun evictOldest(index: MutableMap<String, JSONObject>) {
        while (index.size > MAX_ENTRIES) {
            val oldest = index.entries.minByOrNull { it.value.optLong("updatedAt") } ?: return
            index.remove(oldest.key)
            runCatching { cache.remove(oldest.key) }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val INDEX_KEY = "__ai_segment_index__"

        /** 切词结果与歌词文本一一对应,曲目集合有限,复用价值高。 */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 300
    }
}
