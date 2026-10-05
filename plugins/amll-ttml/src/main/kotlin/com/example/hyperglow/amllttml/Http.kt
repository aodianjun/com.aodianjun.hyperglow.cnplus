package com.example.hyperglow.amllttml

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 极小的阻塞式 HTTP 客户端（插件不引第三方网络库；宿主链每处理器 40s 上限，
 * 因此超时必须短且固定）。
 *
 * 任何异常都收敛为 null——取词失败绝不能让宿主链抛异常。
 */
internal object Http {

    /** 单请求默认预算：连接 + 读取都受调用方给的总预算约束。 */
    const val DEFAULT_BUDGET_MS = 10_000
    private const val MAX_CONNECT_TIMEOUT_MS = 3_000

    /** TTML 正文（几十 KB）比普通歌词大，读取上限比 lyricfetch 宽 3 秒。 */
    private const val MAX_READ_TIMEOUT_MS = 8_000

    private const val DEFAULT_UA =
        "HyperGlow-AmllTtml/1.0 (HyperLyric plugin API v1)"

    fun get(url: String, headers: Map<String, String> = emptyMap(), budgetMs: Int = DEFAULT_BUDGET_MS): String? =
        request(url, headers, budgetMs)

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(
        url: String,
        headers: Map<String, String>,
        budgetMs: Int,
    ): String? = try {
        val connectBudgetMs = budgetMs.coerceIn(500, MAX_CONNECT_TIMEOUT_MS)
        val readBudgetMs = budgetMs.coerceIn(500, MAX_READ_TIMEOUT_MS)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectBudgetMs
            readTimeout = readBudgetMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", DEFAULT_UA)
            // 显式关闭 gzip：少一条解压路径，响应都很小
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.closeQuietly()
                null
            } else {
                connection.inputStream.use { readAll(it) }
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Throwable) {
        null
    }

    private fun readAll(stream: InputStream): String {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = stream.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun InputStream.closeQuietly() {
        runCatching { close() }
    }
}
