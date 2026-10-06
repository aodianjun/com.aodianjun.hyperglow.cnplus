package com.example.hyperglow.lyricfetch

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import org.json.JSONArray
import org.json.JSONObject

/** 缓存里存的东西：命中（原始歌词）或未命中。 */
internal sealed interface CachedOutcome {
    data class Hit(val raw: RawLyrics) : CachedOutcome
    data object Miss : CachedOutcome
}

/**
 * 取词缓存：**宿主持久缓存**（`PluginCache`，每插件 16 MiB 配额、进程重启后仍在）
 * + 内存一级缓存，并维护一份索引供宿主的缓存管理页展示/删除。
 *
 * 为什么需要索引：`PluginCache` 只有 get/put/remove/clear，没有键枚举；
 * 而 `PluginCacheExtension.listEntries()` 要求插件自己给出条目元数据。
 * 因此每次写入同时更新一条索引记录（`INDEX_KEY`），列出 id/标题/摘要/大小/时间。
 *
 * 两条约束：
 * - 索引与条目都在**同一个 PluginCache** 里，`clearAll` 直接整目录清空即可；
 * - 单条删除必须同时清掉内存副本，否则界面上删了、插件仍在用旧结果。
 *
 * 过期策略：条目超过 [TTL_MS]（7 天）视为未命中并顺手删除——在线歌词会更新，
 * 留一个可自愈的上限比"永久缓存 + 手动清理"更稳妥。条目数超过 [MAX_ENTRIES]
 * 时按时间淘汰最旧的。
 *
 * 格式版本见 [FORMAT_VERSION]：升级后旧记录一律解码失败，[entries] 在读列表时
 * 顺手清掉它们的正文——键规则变更（如去掉时长）后设备上残留的旧条目因此自动失效，
 * 用户不必手动清缓存。
 */
internal class LyricCache(
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
            index.put(key, JSONObject()
                .put("title", title)
                .put("summary", summary)
                .put("size", payload.toByteArray(Charsets.UTF_8).size.toLong())
                .put("updatedAt", savedAt))
            evictOldest(index)
        }
    }

    private fun isExpired(savedAt: Long): Boolean = nowMs() - savedAt > TTL_MS

    /**
     * 宿主缓存页用：当前条目元数据。已消失或**按当前格式解不出**（旧版本 / 损坏）的条目
     * 不再展示，并把其正文从宿主缓存删除。
     *
     * 读路径不回写索引：残留索引行因正文已删而不再可见，下次 put/remove/clearEntry
     * 重写索引时自然收敛（也避免读缓存页与并发写入互相覆盖）。
     */
    fun entries(): List<PluginCacheEntry> = readIndex().mapNotNull { (id, meta) ->
        val stored = runCatching { cache.getString(id) }.getOrNull()
        val record = stored?.let { runCatching { decode(it) }.getOrNull() }
        if (record == null) {
            runCatching { cache.remove(id) }
            memory.remove(id)
            return@mapNotNull null
        }
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
                    put("provider", outcome.raw.providerId)
                    put("title", outcome.raw.matchedTitle)
                    put("artists", outcome.raw.matchedArtists)
                    outcome.raw.matchedDurationMs?.let { put("durationMs", it) }
                    put("content", outcome.raw.content)
                    outcome.raw.translation?.let { put("translation", it) }
                }
            }
        }.toString()
    }.getOrNull()

    private fun decode(stored: String): Record? = runCatching {
        val json = JSONObject(stored)
        val savedAt = json.optLong("savedAt")
        if (json.optInt("v") != FORMAT_VERSION) return null
        val outcome = if (json.optBoolean("miss")) {
            CachedOutcome.Miss
        } else {
            val content = json.optString("content").takeIf { it.isNotBlank() } ?: return null
            CachedOutcome.Hit(
                RawLyrics(
                    providerId = json.optString("provider"),
                    content = content,
                    translation = json.optString("translation").takeIf { it.isNotBlank() },
                    matchedTitle = json.optString("title"),
                    matchedArtists = json.optString("artists"),
                    matchedDurationMs = json.optLong("durationMs").takeIf { it > 0L },
                )
            )
        }
        Record(outcome, savedAt)
    }.getOrNull()

    // ---- 索引 ------------------------------------------------------------

    private fun readIndex(): Map<String, JSONObject> = runCatching {
        val raw = cache.getString(INDEX_KEY) ?: return emptyMap()
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
        /** 1 → 2：缓存键不再含时长（1.0.1）。旧 v1 记录一律解码失败 → 视为未命中重新取词。 */
        const val FORMAT_VERSION = 2
        const val INDEX_KEY = "__lyricfetch_index__"
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 100
    }
}
