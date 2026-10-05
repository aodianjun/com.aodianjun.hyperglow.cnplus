package com.example.hyperglow.amllttml

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import org.json.JSONArray
import org.json.JSONObject

/** 缓存里存的东西：命中（TTML 原文 + 匹配到的曲目信息）或未命中。 */
internal sealed interface CachedOutcome {
    data class Hit(
        val ttml: String,
        val matchedTitle: String,
        val matchedArtists: String,
    ) : CachedOutcome

    data object Miss : CachedOutcome
}

/**
 * 取词缓存：**宿主持久缓存**（`PluginCache`，每插件 16 MiB 配额、进程重启后仍在）
 * + 内存一级缓存，并维护一份索引供宿主的缓存管理页展示/删除。
 *
 * 与 plugins/lyricfetch 的 LyricCache 同构：`PluginCache` 只有 get/put/remove/clear、
 * 没有键枚举，而 `PluginCacheExtension.listEntries()` 要求插件自己给出条目元数据——
 * 因此每次写入同时更新一条索引记录（[INDEX_KEY]）。存 TTML **原文**而不是映射结果：
 * 解析便宜、能容忍后续解析改进，也便于用户切换对唱/和声开关时即时重映射。
 *
 * 过期策略：条目超过 [TTL_MS]（7 天）视为未命中并顺手删除；条目数超过
 * [MAX_ENTRIES] 时按时间淘汰最旧的。单条删除同时清内存副本。
 */
internal class AmllTtmlCache(
    private val cache: PluginCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** 内存一级缓存也带 savedAt：TTL 对内存命中同样生效（否则进程内条目永不过期）。 */
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
        JSONObject().apply {
            put("v", FORMAT_VERSION)
            put("savedAt", savedAt)
            when (outcome) {
                is CachedOutcome.Miss -> put("miss", true)
                is CachedOutcome.Hit -> {
                    put("miss", false)
                    put("ttml", outcome.ttml)
                    put("title", outcome.matchedTitle)
                    put("artists", outcome.matchedArtists)
                }
            }
        }.toString()
    }.getOrNull()

    private fun decode(stored: String): Record? = runCatching {
        val json = JSONObject(stored)
        if (json.optInt("v") != FORMAT_VERSION) return@runCatching null
        val savedAt = json.optLong("savedAt")
        val outcome = if (json.optBoolean("miss")) {
            CachedOutcome.Miss
        } else {
            val ttml = json.optString("ttml").takeIf { it.isNotBlank() } ?: return@runCatching null
            CachedOutcome.Hit(
                ttml = ttml,
                matchedTitle = json.optString("title"),
                matchedArtists = json.optString("artists"),
            )
        }
        Record(outcome, savedAt)
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

    /** 条目数上限：按 updatedAt 淘汰最旧的（同时删掉宿主缓存里的正文）。 */
    private fun evictOldest(index: MutableMap<String, JSONObject>) {
        while (index.size > MAX_ENTRIES) {
            val oldest = index.entries.minByOrNull { it.value.optLong("updatedAt") } ?: return
            index.remove(oldest.key)
            runCatching { cache.remove(oldest.key) }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val INDEX_KEY = "__amll_ttml_index__"
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 100
    }
}
