package com.example.hyperglow.lyricfetch

import org.json.JSONObject
import java.util.Base64

/**
 * QQ 音乐——免鉴权拿到行级 LRC（base64 包装），并附带翻译通道。
 *
 * 现状核实（2026-10 实测）：匿名请求下 PlayLyricInfo 的 `qrc` 字段为空，`lyric` 字段是
 * base64 的**行级 LRC**；社区流传的 QRC 3DES 密钥（"!@#)(*$%123ZXC!@!@#)(NHL"）对
 * lyric_download.fcg 返回的 hex 已解不开（DES/3DES 多密钥变体实测均失败，密钥或通道已变更），
 * 因此本 provider 只承诺行级歌词；逐字来源交给网易云 YRC。
 *
 * 解析拆成纯函数（[parseSearch] / [parseLyric]），便于用真实响应夹具离线回归。
 */
internal object QqMusicProvider : LyricProvider {

    override val id: String = "qq"
    override val displayName: String = "QQ 音乐"

    private const val API = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    private val headers = mapOf("Referer" to "https://y.qq.com/portal/player.html")

    private fun comm(): JSONObject = JSONObject()
        .put("ct", 11)
        .put("cv", "1003006")
        .put("v", "1003006")
        .put("os_ver", "10")
        .put("device", "")
        .put("uin", "0")
        .put("format", "json")
        .put("platform", "Android")

    /** 搜索结果：`mid` 用于取词，`id` 是数字歌曲 ID，`interval` 是秒。 */
    internal data class QqCandidate(
        val mid: String,
        val numericId: Long,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    ) {
        fun toMatchCandidate(): TrackMatch.Candidate =
            TrackMatch.Candidate(mid, title, artists, album, durationMs)
    }

    override fun fetch(query: TrackQuery, budgetMs: Int): RawLyrics? {
        val startedAt = System.currentTimeMillis()
        val keyword = buildString {
            append(query.title)
            query.artists.firstOrNull()?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        }
        val searchBody = JSONObject().put(
            "music.search.SearchCgiService",
            JSONObject()
                .put("method", "DoSearchForQQMusicDesktop")
                .put("module", "music.search.SearchCgiService")
                .put(
                    "param",
                    JSONObject()
                        .put("num_per_page", 10)
                        .put("page_num", 1)
                        .put("query", keyword)
                        .put("search_type", 0),
                ),
        )
        val searchResponse = Http.postJson(
            API,
            searchBody.toString(),
            headers,
            budgetMs = budgetMs * 6 / 10,
        ) ?: return null
        val candidates = parseSearch(searchResponse)
        val best = pickBest(query, candidates.map { it.toMatchCandidate() }) ?: return null
        val candidate = candidates.first { it.mid == best.id }

        val remaining = budgetMs - (System.currentTimeMillis() - startedAt).toInt()
        if (remaining < 1_000) return null
        val lyricBody = JSONObject()
            .put("comm", comm())
            .put(
                "req_1",
                JSONObject()
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put("method", "GetPlayLyricInfo")
                    .put(
                        "param",
                        JSONObject()
                            .put("format", "json")
                            .put("songMID", candidate.mid)
                            .put("songID", candidate.numericId)
                            .put("type", 0),
                    ),
            )
        val lyricResponse = Http.postJson(API, lyricBody.toString(), headers, budgetMs = remaining)
            ?: return null
        val parsed = parseLyric(lyricResponse) ?: return null

        return RawLyrics(
            providerId = id,
            content = parsed.content,
            translation = parsed.translation,
            matchedTitle = candidate.title,
            matchedArtists = candidate.artists.joinToString("/"),
            matchedDurationMs = candidate.durationMs,
        )
    }

    internal fun parseSearch(body: String): List<QqCandidate> {
        val list = runCatching {
            JSONObject(body)
                .optJSONObject("music.search.SearchCgiService")
                ?.optJSONObject("data")
                ?.optJSONObject("body")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
        }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val song = list.optJSONObject(i) ?: continue
                val mid = song.optString("mid").takeIf { it.isNotBlank() } ?: continue
                add(
                    QqCandidate(
                        mid = mid,
                        numericId = song.optLong("id"),
                        title = song.optString("name"),
                        artists = song.optJSONArray("singer")?.let { singers ->
                            buildList {
                                for (j in 0 until singers.length()) {
                                    singers.optJSONObject(j)?.optString("name")
                                        ?.takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }
                        }.orEmpty(),
                        album = song.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() },
                        durationMs = song.optLong("interval").takeIf { it > 0L }?.times(1000L),
                    )
                )
            }
        }
    }

    /** 歌词响应 → 原文与翻译（字段可能是 base64，也可能是明文）。 */
    internal fun parseLyric(body: String): RawLyricText? {
        val data = runCatching {
            JSONObject(body).optJSONObject("req_1")?.optJSONObject("data")
        }.getOrNull() ?: return null
        val content = decodeLyricField(data.optString("lyric")) ?: return null
        return RawLyricText(content, decodeLyricField(data.optString("trans")))
    }

    internal fun decodeLyricField(raw: String): String? {
        if (raw.isBlank()) return null
        val decoded = runCatching {
            String(Base64.getDecoder().decode(raw.trim()), Charsets.UTF_8)
        }.getOrNull()
        val text = decoded?.takeIf { it.contains('[') || it.contains('<') } ?: raw
        return text.takeIf { it.isNotBlank() }
    }
}
