package com.example.hyperglow.amllttml

import org.json.JSONArray
import org.json.JSONObject

/**
 * AMLL TTML DataBase API 客户端（`/v1/lyrics/search` + `/v1/lyrics/get`）。
 *
 * 契约来源：https://amll.dev/api/ttml/openapi.yaml（官方 OpenAPI 规范）。
 * 响应统一是 `{"status":200,"data":{...}}` 包装；search 返回条目列表（不含歌词正文），
 * get 返回条目元数据 + `lyrics`（TTML 原文）。
 *
 * ⚠️ 与官方 HyperLyric 插件（hyperlyric.amll.ttml 1.1.0）的差异：官方插件用的是旧版
 * 参数名（`title=`/`artist=`），当前 API 已不接受（实测 400 "Missing valid search
 * parameters"）——本客户端按 OpenAPI 现行契约实现（`musicName`/`artistName`）。
 *
 * 任何失败返回 null，不抛异常。
 */
internal class AmllTtmlClient(private val baseUrl: String) {

    fun search(query: AmllQuery, budgetMs: Int): List<AmllMatch.Candidate>? {
        val params = buildList {
            add("musicName" to query.title)
            query.artists.firstOrNull { it.isNotBlank() }?.let { add("artistName" to it) }
            add("pageSize" to SEARCH_PAGE_SIZE.toString())
        }
        val url = buildUrl(baseUrl, SEARCH_PATH, params)
        val body = Http.get(url, budgetMs = budgetMs) ?: return null
        return parseSearch(body)
    }

    fun fetchTtml(id: Long, budgetMs: Int): String? {
        val url = buildUrl(baseUrl, GET_PATH, listOf("id" to id.toString()))
        val body = Http.get(url, budgetMs = budgetMs) ?: return null
        return parseGetTtml(body)
    }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://api.amll.dev"
        private const val SEARCH_PATH = "/v1/lyrics/search"
        private const val GET_PATH = "/v1/lyrics/get"
        private const val SEARCH_PAGE_SIZE = 10

        /** 规范化用户填写的 API 地址：去尾斜杠；空/非法一律回退官方地址。 */
        fun normalizeBaseUrl(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_BASE_URL
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return DEFAULT_BASE_URL
            return trimmed
        }

        internal fun buildUrl(base: String, path: String, params: List<Pair<String, String>>): String =
            buildString {
                append(base)
                append(path)
                if (params.isNotEmpty()) {
                    append('?')
                    append(params.joinToString("&") { (key, value) ->
                        Http.encode(key) + "=" + Http.encode(value)
                    })
                }
            }

        internal fun parseSearch(body: String): List<AmllMatch.Candidate>? = runCatching {
            val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
            val items = data.optJSONArray("items")
                ?: return@runCatching emptyList<AmllMatch.Candidate>()
            buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optLong("id", -1L)
                    if (id <= 0L) continue
                    add(
                        AmllMatch.Candidate(
                            id = id,
                            musicNames = item.optJSONArray("musicNames").toStringList(),
                            artistNames = item.optJSONArray("artistNames").toStringList(),
                            albumNames = item.optJSONArray("albumNames").toStringList(),
                        )
                    )
                }
            }
        }.getOrNull()

        internal fun parseGetTtml(body: String): String? = runCatching {
            val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
            data.optString("lyrics").takeIf { it.isNotBlank() }
        }.getOrNull()

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
}
