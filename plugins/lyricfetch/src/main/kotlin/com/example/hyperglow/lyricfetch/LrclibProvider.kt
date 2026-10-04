package com.example.hyperglow.lyricfetch

import org.json.JSONArray
import org.json.JSONObject

/**
 * LRCLIB（https://lrclib.net）——开放歌词库，无需鉴权，返回同步 LRC（行级）。
 * 接口语义对齐 Lyricify-Lyrics-Helper 的 LRCLIB Provider：/api/get 精确取词，
 * 失败回落到 /api/search 再按标题/艺人/时长挑最佳候选。
 *
 * 解析拆成纯函数（[parseGet] / [parseSearch]），便于用真实响应夹具离线回归。
 */
internal object LrclibProvider : LyricProvider {

    override val id: String = "lrclib"
    override val displayName: String = "LRCLIB"

    private const val BASE = "https://lrclib.net/api"

    /** 搜索命中：候选信息与歌词一体返回（LRCLIB 的 search 就带 syncedLyrics）。 */
    internal data class Hit(val candidate: TrackMatch.Candidate, val syncedLyrics: String)

    override fun fetch(query: TrackQuery, budgetMs: Int): RawLyrics? {
        val startedAt = System.currentTimeMillis()
        directGet(query, budgetMs)?.let { return it }
        val remaining = budgetMs - (System.currentTimeMillis() - startedAt).toInt()
        if (remaining < 1_000) return null
        return searchThenPick(query, remaining)
    }

    private fun directGet(query: TrackQuery, budgetMs: Int): RawLyrics? {
        val artist = query.artists.firstOrNull().orEmpty()
        val url = buildString {
            append(BASE).append("/get?track_name=").append(Http.encode(query.title))
            if (artist.isNotBlank()) append("&artist_name=").append(Http.encode(artist))
            query.album?.takeIf { it.isNotBlank() }?.let { append("&album_name=").append(Http.encode(it)) }
            query.durationMs?.takeIf { it > 0 }?.let { append("&duration=").append(it / 1000.0) }
        }
        val body = Http.get(url, budgetMs = budgetMs) ?: return null
        val parsed = parseGet(body) ?: return null
        return RawLyrics(
            providerId = id,
            content = parsed.content,
            matchedTitle = parsed.matchedTitle,
            matchedArtists = parsed.matchedArtists,
            matchedDurationMs = parsed.matchedDurationMs,
        )
    }

    private fun searchThenPick(query: TrackQuery, budgetMs: Int): RawLyrics? {
        val url = buildString {
            append(BASE).append("/search?track_name=").append(Http.encode(query.title))
            query.artists.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { append("&artist_name=").append(Http.encode(it)) }
        }
        val body = Http.get(url, budgetMs = budgetMs) ?: return null
        val hits = parseSearch(body)
        if (hits.isEmpty()) return null
        val best = pickBest(query, hits.map { it.candidate }) ?: return null
        val hit = hits.first { it.candidate.id == best.id }
        return RawLyrics(
            providerId = id,
            content = hit.syncedLyrics,
            matchedTitle = hit.candidate.title,
            matchedArtists = hit.candidate.artists.joinToString("/"),
            matchedDurationMs = hit.candidate.durationMs,
        )
    }

    internal data class GetResult(
        val content: String,
        val matchedTitle: String,
        val matchedArtists: String,
        val matchedDurationMs: Long?,
    )

    /** /api/get 响应 → 歌词（无 syncedLyrics 视为未命中）。 */
    internal fun parseGet(body: String): GetResult? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: return null
        return GetResult(
            content = synced,
            matchedTitle = json.optString("trackName"),
            matchedArtists = json.optString("artistName"),
            matchedDurationMs = (json.optDouble("duration", 0.0) * 1000.0).toLong().takeIf { it > 0 },
        )
    }

    /** /api/search 响应 → 命中列表（只保留带同步歌词的项）。 */
    internal fun parseSearch(body: String): List<Hit> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val synced = item.optString("syncedLyrics")
                if (synced.isBlank()) continue
                add(
                    Hit(
                        candidate = TrackMatch.Candidate(
                            id = item.optInt("id").toString(),
                            title = item.optString("trackName").ifBlank { item.optString("name") },
                            artists = listOfNotNull(
                                item.optString("artistName").takeIf { it.isNotBlank() }
                            ),
                            album = item.optString("albumName").takeIf { it.isNotBlank() },
                            durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong()
                                .takeIf { it > 0 },
                        ),
                        syncedLyrics = synced,
                    )
                )
            }
        }
    }
}
