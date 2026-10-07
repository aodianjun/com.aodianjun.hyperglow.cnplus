package com.example.hyperglow.aitranslationwords

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import org.json.JSONArray
import org.json.JSONObject

/** 缓存里的一行：原文 → 译文，以及可选的逐字片段（校验通过的原文）。 */
internal data class CachedLine(val text: String, val translation: String, val fragments: List<String>?)

/** 缓存里存的东西：命中（整首的行级结果，按文本匹配回填，比 index 稳）。 */
internal sealed interface CachedOutcome {
    data class Hit(val lines: List<CachedLine>) : CachedOutcome
}

/**
 * 逐字翻译缓存：**宿主持久缓存**（`PluginCache`，每插件 16 MiB 配额、进程重启后仍在）
 * + 内存一级缓存，并维护一份索引供宿主的缓存管理页展示/删除。
 *
 * 与兄弟插件 `plugins/ai-translation` 的 TranslationCache 同构，
 * 载荷多存逐字片段：`{"v":1,"savedAt":ms,"pairs":[{"t":原文,"r":译文,"s":[片段...]}]}`，
 * 无片段的条目省略 `s`。**只缓存成功结果**（翻译失败多为暂时性——限流/超时，由处理器的
 * 失败节流负责退避，不写入缓存）。键由处理器按「曲目身份 + 目标语言 + 模型 + 地址 +
 * 提示词 + 采样参数 + 逐字开关」哈希得出；回填时按**行文本精确匹配**（跨源版本/行序
 * 变化也稳）。
 *
 * 过期策略：条目超过 [TTL_MS]（30 天）视为过期并顺手删除；条目数超过 [MAX_ENTRIES]
 * 时按时间淘汰最旧的（内存副本同步移除，否则被淘汰的条目仍会从内存命中）。单条删除
 * 同时清内存副本。
 */
internal class TranslationWordsCache(
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
    fun put(key: String, lines: List<CachedLine>, title: String, summary: String) {
        if (lines.isEmpty()) return
        val savedAt = nowMs()
        val payload = encode(lines, savedAt) ?: return
        runCatching { cache.putString(key, payload) }
            .onFailure { return }
        memory[key] = Record(CachedOutcome.Hit(lines), savedAt)
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

    private fun encode(lines: List<CachedLine>, savedAt: Long): String? = runCatching {
        val pairs = JSONArray()
        lines.forEach { line ->
            val item = JSONObject().put("t", line.text).put("r", line.translation)
            line.fragments?.let { fragments -> item.put("s", JSONArray(fragments)) }
            pairs.put(item)
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
        val lines = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val text = item.optString("t")
                val translation = item.optString("r")
                if (text.isBlank() || translation.isBlank()) continue
                add(CachedLine(text, translation, readFragments(item)))
            }
        }
        if (lines.isEmpty()) return@runCatching null
        Record(CachedOutcome.Hit(lines), savedAt)
    }.getOrNull()

    /** `s` 缺失/为 null → 无片段；元素含非字符串（载荷损坏）时按无片段处理。 */
    private fun readFragments(item: JSONObject): List<String>? {
        val arr = item.optJSONArray("s") ?: return null
        val fragments = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val element = arr.opt(i)
            if (element !is String) return null
            fragments.add(element)
        }
        return fragments
    }

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
            memory.remove(oldest.key)
            runCatching { cache.remove(oldest.key) }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val INDEX_KEY = "__ai_translation_words_index__"

        /** 歌词翻译的复用价值较高（曲目集合有限），TTL 比取词缓存宽。 */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 200
    }
}
