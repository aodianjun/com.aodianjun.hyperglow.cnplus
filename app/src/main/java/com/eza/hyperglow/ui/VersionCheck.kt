package com.eza.hyperglow.ui

import com.eza.hyperglow.AppLog
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GitHub 最新正式版信息,用于主页"检查更新"。 */
internal data class LatestReleaseInfo(
    val tag: String,
    /** GitHub release 资产的 SHA256（不含 "sha256:" 前缀）。空集合表示未取到哈希。 */
    val sha256: Set<String>
)

internal fun queryLatestReleaseInfo(): LatestReleaseInfo? {
    var connection: HttpURLConnection? = null
    return try {
        val url = URL("https://api.github.com/repos/aodianjun/com.aodianjun.hyperglow.cnplus/releases/latest")
        connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val response = connection.inputStream.bufferedReader().readText()
            val json = Json.parseToJsonElement(response)
            val tag = json.jsonObject["tag_name"]?.jsonPrimitive?.content ?: return null
            val digests = json.jsonObject["assets"]?.jsonArray
                ?.mapNotNull { asset ->
                    asset.jsonObject["digest"]?.jsonPrimitive?.content
                        ?.substringAfter("sha256:", "")
                        ?.takeIf { it.isNotBlank() }
                }
                ?.toSet()
                .orEmpty()
            LatestReleaseInfo(tag = tag, sha256 = digests)
        } else null
    } catch (e: Exception) {
        AppLog.e("VersionCheck", "queryLatestReleaseInfo failed", e)
        null
    } finally {
        connection?.disconnect()
    }
}

/**
 * 计算本地已安装 APK 的 SHA256(hex)。用于与 GitHub release 资产指纹比对:
 * 若不一致,说明安装的不是官方最新发布,视为"不是最新版"。
 */
internal fun localApkSha256(context: android.content.Context): String? = runCatching {
    val sourceDir = context.packageManager
        .getApplicationInfo(context.packageName, 0)
        .sourceDir
    val digest = MessageDigest.getInstance("SHA-256")
    File(sourceDir).inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

/**
 * 拉取关于页作者头像(同"检查更新"一样是界面侧的远程读取)。
 * 放在本文件而非使用处:ArchitectureGuardTest 只允许网络客户端出现在白名单文件
 * (diagnostics/DiagnosticUploader.kt、ui/SettingsSupport.kt、ui/VersionCheck.kt),
 * ui/HomeComponents.kt 不在白名单内。
 */
internal fun fetchImageBitmap(url: String, timeoutMs: Int = 5_000): android.graphics.Bitmap? =
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.inputStream.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        }
    }.getOrNull()

internal fun compareVersions(v1: String, v2: String): Int {
    val a = v1.trim().split(".").mapNotNull { it.toIntOrNull() }
    val b = v2.trim().split(".").mapNotNull { it.toIntOrNull() }
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return if (x > y) 1 else -1
    }
    return 0
}

/** 关于页「更新日志」的 release 摘要。 */
internal data class ReleaseEntry(
    val tagName: String,
    val title: String,
    /** 发布日期 yyyy-MM-dd(取 published_at 前 10 位);缺失时为 null。 */
    val publishedDate: String?,
    val body: String
)

/** 关于页「贡献者」的 contributor 摘要。 */
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
private const val ABOUT_QUERY_TIMEOUT_MS = 8_000

/**
 * 拉取 release 列表(含 prerelease,与本项目以 prerelease 发布的约定一致)。
 * 与其余网络读取同放本文件:ArchitectureGuardTest 只允许网络客户端出现在白名单文件。
 */
internal fun queryGitHubReleases(): List<ReleaseEntry>? {
    val response = githubGetText(CNPLUS_RELEASES_API) ?: return null
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
        AppLog.e("VersionCheck", "queryGitHubReleases parse failed", error)
    }.getOrNull()
}

/** 拉取贡献者列表(API 已按贡献数降序);失败返回 null,由调用方呈现失败态。 */
internal fun queryGitHubContributors(): List<ContributorEntry>? {
    val response = githubGetText(CNPLUS_CONTRIBUTORS_API) ?: return null
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
        AppLog.e("VersionCheck", "queryGitHubContributors parse failed", error)
    }.getOrNull()
}

/** 匿名 GET GitHub API(与版本检查同一限流档位);非 200 或异常返回 null。 */
private fun githubGetText(url: String): String? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = ABOUT_QUERY_TIMEOUT_MS
            readTimeout = ABOUT_QUERY_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HyperGlow-CNPlus")
        }
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } else null
    } catch (e: Exception) {
        AppLog.e("VersionCheck", "githubGetText failed: $url", e)
        null
    } finally {
        connection?.disconnect()
    }
}
