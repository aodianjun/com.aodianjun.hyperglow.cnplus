package com.example.hyperglow.aitranslationwords

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极小的阻塞式 HTTP 客户端（插件不引第三方网络库；宿主链每处理器 40s 上限）。
 *
 * 与 amll-ttml/lyricfetch 的版本差异：LLM 生成慢，读取上限放宽到 [MAX_READ_TIMEOUT_MS]，
 * 且只需要 POST JSON。任何异常都收敛为 null——翻译失败绝不能让宿主链抛异常。
 */
internal object Http {

    /** 单请求默认预算：连接 + 读取都受调用方给的总预算约束。 */
    const val DEFAULT_BUDGET_MS = 32_000
    private const val MAX_CONNECT_TIMEOUT_MS = 3_000

    /** LLM 逐字生成，几十秒属正常；宿主上限 40s，这里封顶 32s 留合并余量。 */
    private const val MAX_READ_TIMEOUT_MS = 32_000

    private const val DEFAULT_UA =
        "HyperGlow-AiTranslationWords/1.0 (HyperLyric plugin API v1)"

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        budgetMs: Int = DEFAULT_BUDGET_MS,
    ): String? = try {
        val connectBudgetMs = budgetMs.coerceIn(500, MAX_CONNECT_TIMEOUT_MS)
        val readBudgetMs = budgetMs.coerceIn(1_000, MAX_READ_TIMEOUT_MS)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectBudgetMs
            readTimeout = readBudgetMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", DEFAULT_UA)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            doOutput = true
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(jsonBody.toByteArray(Charsets.UTF_8))
            }
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
