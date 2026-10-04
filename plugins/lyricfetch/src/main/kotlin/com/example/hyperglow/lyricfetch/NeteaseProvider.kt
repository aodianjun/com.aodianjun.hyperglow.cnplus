package com.example.hyperglow.lyricfetch

import org.json.JSONArray
import org.json.JSONObject

/**
 * 网易云音乐——免鉴权端点即可拿到 **YRC 逐字**歌词（词级）与 LRC 翻译。
 *
 * 端点选择（2026-10 实测）：
 * - 搜索用 `POST /api/cloudsearch/pc`（未加密的表单接口）。旧的
 *   `/api/search/get/web` 对中文查询已开始返回无关结果（英文仍正常），不再使用；
 * - 歌词用 `/api/song/lyric/v1` 直连（不加密）就能返回 yrc/tlyric，
 *   不必移植 Lyricify 的 eapi（AES+RSA）加密通道——少一层易碎依赖。
 *
 * 响应字段兼容两代命名（`ar`/`al`/`dt` 与 `artists`/`album`/`duration`）。
 * 解析拆成纯函数（[parseSearch] / [parseLyric]），便于用真实响应夹具离线回归。
 */
internal object NeteaseProvider : LyricProvider {

    override val id: String = "netease"
    override val displayName: String = "网易云音乐"

    private const val SEARCH = "https://music.163.com/api/cloudsearch/pc"
    private const val LYRIC = "https://music.163.com/api/song/lyric/v1"

    private val headers = mapOf("Referer" to "https://music.163.com/")

    override fun fetch(query: TrackQuery, budgetMs: Int): RawLyrics? {
        val startedAt = System.currentTimeMillis()
        val keyword = buildString {
            append(query.title)
            query.artists.firstOrNull()?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        }
        val searchBody = Http.postForm(
            SEARCH,
            mapOf("s" to keyword, "type" to "1", "limit" to "10", "offset" to "0"),
            headers,
            budgetMs = budgetMs * 6 / 10,
        ) ?: return null
        val candidate = pickBest(query, parseSearch(searchBody)) ?: return null

        val remaining = budgetMs - (System.currentTimeMillis() - startedAt).toInt()
        if (remaining < 1_000) return null
        val lyricBody = Http.get(
            "$LYRIC?id=${Http.encode(candidate.id)}&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1&ytv=-1&yrv=-1",
            headers,
            budgetMs = remaining,
        ) ?: return null
        val parsed = parseLyric(lyricBody) ?: return null

        return RawLyrics(
            providerId = id,
            content = parsed.content,
            translation = parsed.translation,
            matchedTitle = candidate.title,
            matchedArtists = candidate.artists.joinToString("/"),
            matchedDurationMs = candidate.durationMs,
        )
    }

    /** 搜索结果 → 候选列表（兼容 `ar/al/dt` 与 `artists/album/duration` 两代字段名）。 */
    internal fun parseSearch(body: String): List<TrackMatch.Candidate> {
        val songs = runCatching {
            JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
        }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val duration = song.optLong("dt").takeIf { it > 0L }
                    ?: song.optLong("duration").takeIf { it > 0L }
                add(
                    TrackMatch.Candidate(
                        id = song.optLong("id").toString(),
                        title = song.optString("name"),
                        artists = song.optJSONArray("ar").artistNames()
                            .ifEmpty { song.optJSONArray("artists").artistNames() },
                        album = (song.optJSONObject("al") ?: song.optJSONObject("album"))
                            ?.optString("name")?.takeIf { it.isNotBlank() },
                        durationMs = duration,
                    )
                )
            }
        }
    }

    /** 歌词响应 → 原文（优先 yrc，回落 lrc）与翻译。 */
    internal fun parseLyric(body: String): RawLyricText? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val yrc = json.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val content = yrc.takeIf { it.isNotBlank() } ?: lrc.takeIf { it.isNotBlank() } ?: return null
        val translation = json.optJSONObject("tlyric")?.optString("lyric")?.takeIf { it.isNotBlank() }
        return RawLyricText(content, translation)
    }

    private fun JSONArray?.artistNames(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}

/** 取词结果的最小载体：原文 + 可选翻译。 */
internal data class RawLyricText(val content: String, val translation: String?)
