package com.eza.hyperglow.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.eza.hyperglow.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** GitHub release 摘要,用于关于页「更新日志」。 */
internal data class ReleaseEntry(
    val tagName: String,
    val title: String,
    /** 发布日期 yyyy-MM-dd(取 published_at 前 10 位);缺失时为 null。 */
    val publishedDate: String?,
    val body: String
)

/** GitHub contributor 摘要,用于关于页「贡献者」。 */
internal data class ContributorEntry(
    val login: String,
    val avatarUrl: String,
    val profileUrl: String,
    val contributions: Int
)

private const val CNPLUS_RELEASES_API =
    "https://api.github.com/repos/aodianjun/com.aodianjun.hyperglow.cnplus/releases?per_page=20"
private const val CNPLUS_CONTRIBUTORS_API =
    "https://api.github.com/repos/aodianjun/com.aodianjun.hyperglow.cnplus/contributors?per_page=50"
private const val REQUEST_TIMEOUT_MS = 8000

internal fun queryGitHubReleases(): List<ReleaseEntry>? {
    val response = httpGetText(CNPLUS_RELEASES_API) ?: return null
    return runCatching {
        Json.parseToJsonElement(response).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ReleaseEntry(
                tagName = tag,
                title = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                publishedDate = obj["published_at"]?.jsonPrimitive?.content
                    ?.takeIf { it.length >= 10 }
                    ?.substring(0, 10),
                body = obj["body"]?.jsonPrimitive?.content.orEmpty()
            )
        }
    }.onFailure { error ->
        AppLog.w("AboutContent", "parse releases failed: ${error.message}")
    }.getOrNull()
}

internal fun queryGitHubContributors(): List<ContributorEntry>? {
    val response = httpGetText(CNPLUS_CONTRIBUTORS_API) ?: return null
    return runCatching {
        Json.parseToJsonElement(response).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val login = obj["login"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ContributorEntry(
                login = login,
                avatarUrl = obj["avatar_url"]?.jsonPrimitive?.content.orEmpty(),
                profileUrl = obj["html_url"]?.jsonPrimitive?.content.orEmpty(),
                contributions = obj["contributions"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            )
        }
    }.onFailure { error ->
        AppLog.w("AboutContent", "parse contributors failed: ${error.message}")
    }.getOrNull()
}

/** 匿名调用 GitHub API(与 VersionCheck 同一限流档位);非 200 或任何异常返回 null。 */
private fun httpGetText(url: String): String? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HyperGlow-CNPlus")
        }
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else null
    } catch (e: Exception) {
        AppLog.w("AboutContent", "httpGetText failed: ${e.message}")
        null
    } finally {
        connection?.disconnect()
    }
}

internal fun fetchBitmap(url: String): Bitmap? {
    if (url.isEmpty()) return null
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "HyperGlow-CNPlus")
        }
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.use(BitmapFactory::decodeStream)
        } else null
    } catch (e: Exception) {
        AppLog.w("AboutContent", "fetchBitmap failed: ${e.message}")
        null
    } finally {
        connection?.disconnect()
    }
}
