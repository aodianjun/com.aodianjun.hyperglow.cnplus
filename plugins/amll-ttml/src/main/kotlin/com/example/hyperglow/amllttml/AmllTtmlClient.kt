package com.example.hyperglow.amllttml

import org.json.JSONArray
import org.json.JSONObject

/**
 * 取词传输层接口：`AmllTtmlProcessor` 只依赖它，单测用脚本化假实现替换网络。
 */
internal interface AmllTtmlApi {

    /**
     * 按给定 [musicName] 搜索一次（调用方负责标题变体的逐级回退）。
     *
     * 传输/解析失败返回 null（**调用方据此中止且不写负缓存**）；请求成功但没有
     * 可解析条目返回空列表（可继续尝试下一个标题变体）。
     */
    fun search(query: AmllQuery, musicName: String, budgetMs: Int): List<AmllMatch.Candidate>?

    /**
     * 取 TTML 原文。传输失败返回 null；请求成功但正文为空/不可解析返回空串
     * （调用方对这两种情况的缓存语义不同：前者不写负缓存，后者照旧写）。
     */
    fun fetchTtml(id: Long, budgetMs: Int): String?
}

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
 * 任何失败都不抛异常（见 [AmllTtmlApi] 的返回值语义）。
 */
internal class AmllTtmlClient(private val baseUrl: String) : AmllTtmlApi {

    override fun search(query: AmllQuery, musicName: String, budgetMs: Int): List<AmllMatch.Candidate>? {
        val params = buildList {
            // 搜索标题由调用方逐变体给入（原文 → 剥括号/feat. → 全量规范化）；
            // 打分与缓存键仍统一用 AmllMatch.normalize。
            add("musicName" to musicName)
            query.artists.firstOrNull { it.isNotBlank() }?.let { add("artistName" to it) }
            add("pageSize" to SEARCH_PAGE_SIZE.toString())
        }
        val url = buildUrl(baseUrl, SEARCH_PATH, params)
        val body = Http.get(url, budgetMs = budgetMs) ?: return null
        return parseSearch(body)
    }

    override fun fetchTtml(id: Long, budgetMs: Int): String? {
        val url = buildUrl(baseUrl, GET_PATH, listOf("id" to id.toString()))
        val body = Http.get(url, budgetMs = budgetMs) ?: return null
        // 请求成功（HTTP 2xx）但正文不可用时返回空串而不是 null：调用方需要区分
        // 「传输失败（瞬时，不写负缓存）」与「库中这条不可用（照旧写负缓存）」。
        return parseGetTtml(body) ?: ""
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
