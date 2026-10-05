package com.example.hyperglow.aitranslation

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import org.json.JSONArray
import org.json.JSONObject

/** 缓存里存的东西：命中（行文本 → 译文 的配对表，按文本匹配回填，比 index 稳）。 */
internal sealed interface CachedOutcome {
    data class Hit(val pairs: List<Pair<String, String>>) : CachedOutcome
}

/**
 * 翻译缓存：**宿主持久缓存**（`PluginCache`，每插件 16 MiB 配额、进程重启后仍在）
 * + 内存一级缓存，并维护一份索引供宿主的缓存管理页展示/删除。
 *
 * 与 amll-ttml/lyricfetch 的差异：**只缓存成功结果**（翻译失败多为暂时性——限流/超时，
 * 由处理器的失败节流负责退避，不写入缓存）。键由处理器按
 * 「曲目身份 + 目标语言 + 模型 + 地址 + 提示词 + 采样参数」哈希得出；条目里存
 * `[{text, translation}]` 配对，回填时按**行文本精确匹配**（跨源版本/行序变化也稳）。
 *
 * 过期策略：条目超过 [TTL_MS]（30 天）视为过期并顺手删除；条目数超过 [MAX_ENTRIES]
 * 时按时间淘汰最旧的。单条删除同时清内存副本。
 */
internal class TranslationCache(
    private val cache: PluginCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val memory = java.util.concurrent.ConcurrentHashMap<String, Record>()

    /** 取缓存；过期/损坏/缺失一律返回 null（调用方按未命中处理）。 */
    fun get(key: String): CachedOutcome? {
        memory[key]?.let { cached ->
            if (!isExpired(cached.savedAt)) return cached.outcome
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
        return record.outcome
    }

    /** 写入缓存并更新索引（[title]/[summary] 只用于宿主缓存页展示）。 */
    fun put(key: String, outcome: CachedOutcome, title: String, summary: String) {
        val savedAt = nowMs()
        val payload = encode(outcome, savedAt) ?: return
        runCatching { cache.putString(key, payload) }
            .onFailure { return }
        memory[key] = Record(outcome, savedAt)
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

    /** 宿主缓存页用：当前条目元数据（已消失的条目自动从索引剔除）。 */
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

    private data class Record(val outcome: CachedOutcome, val savedAt: Long)

    private fun encode(outcome: CachedOutcome, savedAt: Long): String? = runCatching {
        val pairs = JSONArray()
        when (outcome) {
            is CachedOutcome.Hit -> outcome.pairs.forEach { (text, translation) ->
                pairs.put(JSONObject().put("t", text).put("r", translation))
            }
        }
        JSONObject()
            .put("v", FORMAT_VERSION)
            .put("savedAt", savedAt)
            .put("pairs", pairs)
            .toString()
    }.getOrNull()

    private fun decode(stored: String): Record? = runCatching {
        val json = JSONObject(stored)
        if (json.optInt("v") != FORMAT_VERSION) return@runCatching null
        val savedAt = json.optLong("savedAt")
        val arr = json.optJSONArray("pairs") ?: return@runCatching null
        val pairs = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val text = item.optString("t")
                val translation = item.optString("r")
                if (text.isNotBlank() && translation.isNotBlank()) add(text to translation)
            }
        }
        if (pairs.isEmpty()) return@runCatching null
        Record(CachedOutcome.Hit(pairs), savedAt)
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
        const val INDEX_KEY = "__ai_translation_index__"

        /** 歌词翻译的复用价值较高（曲目集合有限），TTL 比取词缓存宽。 */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 200
    }
}
