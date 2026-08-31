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
