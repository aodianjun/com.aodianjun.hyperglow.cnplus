package com.eza.hyperglow.ui

import com.eza.hyperglow.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * GitHub Release 云端预设客户端。
 *
 * 读取（列表 + 正文）匿名可用；上传/更新需要 Token。所有网络请求都收在本文件里，
 * 供 ArchitectureGuardTest 的白名单核对（app 侧只允许少数网络客户端）。
 */
internal object PresetHubClient {
    private const val API = "https://api.github.com"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val JSON_ACCEPT = "application/vnd.github+json"
    private const val MAX_JSON_BYTES = 256 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 拉取云端清单；仓库/Release/资产不存在时返回空清单（不是错误）。 */
    suspend fun fetch(config: PresetHubConfig): Result<CloudPresetManifest> =
        withContext(Dispatchers.IO) {
            val primary = guarded {
                if (!config.hasRepo) throw PresetHubException(PresetHubFailure.INVALID_CONFIG)
                val release = getRelease(config) ?: return@guarded CloudPresetManifest()
                val asset = release.assets.firstOrNull { it.name == PRESET_HUB_ASSET_NAME }
                    ?: return@guarded CloudPresetManifest()
                val raw = downloadAsset(config, asset).toString(Charsets.UTF_8)
                parseCloudManifest(raw) ?: throw PresetHubException(PresetHubFailure.MALFORMED)
            }
            if (primary.isSuccess) return@withContext primary
            val failure = (primary.exceptionOrNull() as? PresetHubException)?.failure
            if (failure != null && failure in setOf(
                    PresetHubFailure.NETWORK,
                    PresetHubFailure.SERVER,
                    PresetHubFailure.RATE_LIMITED
                ) && config.anonymousEndpoints.isNotEmpty()
            ) {
                // 返回备用路径的结果（即使失败），错误信息才指向真正尝试过的通道。
                return@withContext guarded { fetchAnonymousManifest(config) }
            }
            primary
        }

    /**
     * 主路径（GitHub API）网络失败时，从主/备匿名 Hub 的 `/api/presets` 拉同一份清单。
     * EdgeOne 备用入口是在服务端读 GitHub 的，所以手机到 GitHub 被挡时仍可能拿到列表。
     */
    private fun fetchAnonymousManifest(config: PresetHubConfig): CloudPresetManifest {
        var lastError: PresetHubException? = null
        config.anonymousEndpoints.forEachIndexed { index, endpoint ->
            try {
                val response = request(
                    method = "GET",
                    url = endpoint.trimEnd('/') + "/api/presets",
                    accept = JSON_ACCEPT
                )
                if (response.code in 200..299) {
                    return parseCloudManifest(response.body.toString(Charsets.UTF_8))
                        ?: throw PresetHubException(PresetHubFailure.MALFORMED)
                }
                lastError = anonymousError(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PresetHubException) {
                lastError = error
            } catch (error: Exception) {
                lastError = PresetHubException(PresetHubFailure.NETWORK, cause = error)
            }
            if (index == config.anonymousEndpoints.lastIndex) {
                throw lastError
            }
        }
        throw lastError ?: PresetHubException(PresetHubFailure.NETWORK)
    }

    /**
     * 上传一条本地预设（同 id 覆盖，否则新增）。
     *
     * [onMerged] 在删除旧资产之前调用：调用方先把合并后的清单落本地缓存，万一下载/上传
     * 中途失败，下一次上传可用 [fallback] 作为基底，不会丢掉云端已有条目。
     */
    suspend fun upload(
        config: PresetHubConfig,
        preset: ConfigPreset,
        author: String,
        fallback: CloudPresetManifest?,
        onMerged: (CloudPresetManifest) -> Unit
    ): Result<CloudPresetManifest> = withContext(Dispatchers.IO) {
        guarded {
            if (!config.canUpload) throw PresetHubException(PresetHubFailure.INVALID_CONFIG)
            var release = getRelease(config) ?: createRelease(config)
            val asset = release.assets.firstOrNull { it.name == PRESET_HUB_ASSET_NAME }
            val current = if (asset != null) {
                val raw = downloadAsset(config, asset).toString(Charsets.UTF_8)
                parseCloudManifest(raw) ?: throw PresetHubException(PresetHubFailure.MALFORMED)
            } else {
                fallback ?: CloudPresetManifest()
            }
            val merged = mergeCloudPreset(
                manifest = current,
                preset = preset,
                author = author,
                now = System.currentTimeMillis()
            )
            val bytes = json.encodeToString(merged).toByteArray(Charsets.UTF_8)
            if (bytes.size > PRESET_HUB_MAX_MANIFEST_BYTES) {
                throw PresetHubException(PresetHubFailure.TOO_LARGE)
            }
            onMerged(merged)
            // GitHub 资产不能原地覆盖：先删同名旧资产，再上传整份新清单。
            if (asset != null) deleteAsset(config, asset.id)
            try {
                uploadAsset(config, release.uploadUrl, bytes)
            } catch (error: PresetHubException) {
                // 请求可能已成功但响应丢失：回查一次，资产在就算成功。
                release = runCatching { getRelease(config) }.getOrNull() ?: release
                if (release.assets.none { it.name == PRESET_HUB_ASSET_NAME }) throw error
            }
            merged
        }
    }

    /**
     * 匿名 Hub 上传：POST 到用户部署的 Worker，Worker 用自己的 GitHub Token 更新 Release。
     * 请求只带设备标识（Worker 存哈希做归属），不需要任何 GitHub 凭证。
     */
    suspend fun uploadAnonymous(
        config: PresetHubConfig,
        preset: ConfigPreset,
        author: String,
        deviceId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        guarded {
            val endpoints = config.anonymousEndpoints
            if (endpoints.isEmpty()) throw PresetHubException(PresetHubFailure.INVALID_CONFIG)
            val cloudId = preset.id.removePrefix(CLOUD_PRESET_PREFIX)
            if (cloudId.isBlank()) throw PresetHubException(PresetHubFailure.INVALID_CONFIG)
            val body = buildJsonObject {
                put("id", cloudId)
                put("name", preset.name)
                put("note", preset.note)
                put("author", author.take(20))
                put("payload", preset.payload)
            }.toString().toByteArray(Charsets.UTF_8)
            if (body.size > CONFIG_PRESET_MAX_BYTES + 8 * 1024) {
                throw PresetHubException(PresetHubFailure.TOO_LARGE)
            }
            var lastError: PresetHubException? = null
            endpoints.forEachIndexed { index, endpoint ->
                val response = try {
                    request(
                        method = "POST",
                        url = endpoint.trimEnd('/') + "/api/presets",
                        body = body,
                        headers = mapOf("X-HyperGlow-Device" to deviceId)
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: PresetHubException) {
                    throw if (error.failure != PresetHubFailure.TOO_LARGE) {
                        PresetHubException(PresetHubFailure.NETWORK, cause = error)
                    } else {
                        error
                    }
                } catch (error: Exception) {
                    throw PresetHubException(PresetHubFailure.NETWORK, cause = error)
                }
                if (response.code in 200..299) return@guarded cloudId
                val failure = anonymousError(response)
                lastError = failure
                // 主 Hub 网络/5xx/限流/路由异常时自动尝试备用 Hub；4xx 语义错误不重试。
                val retryable = failure.failure in setOf(
                    PresetHubFailure.NETWORK,
                    PresetHubFailure.SERVER,
                    PresetHubFailure.RATE_LIMITED,
                    PresetHubFailure.NOT_FOUND
                )
                if (!retryable || index == endpoints.lastIndex) throw failure
            }
            throw lastError ?: PresetHubException(PresetHubFailure.NETWORK)
        }
    }

    /**
     * 匿名删除自己上传的云端预设。Worker 用 `X-HyperGlow-Device` 的哈希校验 owner：
     * 只有当初上传的那台设备能删，换设备返回 403 `not_owner`。
     */
    suspend fun deleteAnonymous(
        config: PresetHubConfig,
        cloudId: String,
        deviceId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        guarded {
            val endpoints = config.anonymousEndpoints
            if (endpoints.isEmpty()) throw PresetHubException(PresetHubFailure.INVALID_CONFIG)
            val id = cloudId.removePrefix(CLOUD_PRESET_PREFIX).trim()
            if (id.isBlank()) throw PresetHubException(PresetHubFailure.INVALID_CONFIG)
            val body = buildJsonObject { put("id", id) }
                .toString().toByteArray(Charsets.UTF_8)
            var lastError: PresetHubException? = null
            endpoints.forEachIndexed { index, endpoint ->
                val response = try {
                    request(
                        method = "POST",
                        url = endpoint.trimEnd('/') + "/api/presets/delete",
                        body = body,
                        headers = mapOf("X-HyperGlow-Device" to deviceId)
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: PresetHubException) {
                    throw PresetHubException(PresetHubFailure.NETWORK, cause = error)
                } catch (error: Exception) {
                    throw PresetHubException(PresetHubFailure.NETWORK, cause = error)
                }
                if (response.code in 200..299) return@guarded Unit
                val failure = anonymousError(response)
                lastError = failure
                val retryable = failure.failure in setOf(
                    PresetHubFailure.NETWORK,
                    PresetHubFailure.SERVER,
                    PresetHubFailure.RATE_LIMITED,
                    PresetHubFailure.NOT_FOUND
                )
                if (!retryable || index == endpoints.lastIndex) throw failure
            }
            throw lastError ?: PresetHubException(PresetHubFailure.NETWORK)
        }
    }

    /** 用 Token 问 GitHub 当前登录名；失败返回 null（上传作者回退 anonymous）。 */
    suspend fun whoAmI(config: PresetHubConfig): String? = withContext(Dispatchers.IO) {
        if (config.token.isBlank()) return@withContext null
        try {
            val response = request("GET", "$API/user", config.token, accept = JSON_ACCEPT)
            if (response.code !in 200..299) return@withContext null
            Json.parseToJsonElement(response.body.toString(Charsets.UTF_8))
                .jsonObject["login"]?.jsonPrimitive?.contentOrNull
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun getRelease(config: PresetHubConfig): GitHubRelease? {
        val response = request(
            method = "GET",
            url = "$API/repos/${config.repo}/releases/tags/${encodePathSegment(config.tag)}",
            token = config.token
        )
        return when {
            response.code == HttpURLConnection.HTTP_NOT_FOUND -> null
            response.code in 200..299 -> parseRelease(response)
            else -> throw errorFor(response)
        }
    }

    private fun createRelease(config: PresetHubConfig): GitHubRelease {
        val body = buildJsonObject {
            put("tag_name", config.tag.trim())
            put("name", "HyperGlow preset hub")
            put(
                "body",
                "HyperGlow 配置预设云端通道（$PRESET_HUB_ASSET_NAME）。" +
                    "该 Release 刻意标记为预发布，避免被应用更新检查误判为最新版本。"
            )
            put("draft", false)
            put("prerelease", true)
        }.toString().toByteArray(Charsets.UTF_8)
        val response = request(
            method = "POST",
            url = "$API/repos/${config.repo}/releases",
            token = config.token,
            body = body
        )
        if (response.code == 201) return parseRelease(response)
        // 并发创建：另一个客户端已经建好了同名 Release。
        if (response.code == 422) {
            return getRelease(config) ?: throw errorFor(response)
        }
        throw errorFor(response)
    }

    private fun downloadAsset(config: PresetHubConfig, asset: GitHubAsset): ByteArray {
        val apiUrl = "$API/repos/${config.repo}/releases/assets/${asset.id}"
        // GitHub 对资产下载返回 302 到签名 CDN 地址。手动跟一次且**不带 Token**：
        // 避免 HttpURLConnection 把 Authorization 一起转发给第三方主机。
        val first = request(
            method = "GET",
            url = apiUrl,
            token = config.token,
            accept = "application/octet-stream",
            maxBytes = PRESET_HUB_MAX_MANIFEST_BYTES,
            followRedirects = false
        )
        val response = if (first.code in 300..399 && !first.location.isNullOrBlank()) {
            request(
                method = "GET",
                url = first.location.orEmpty(),
                token = null,
                accept = "application/octet-stream",
                maxBytes = PRESET_HUB_MAX_MANIFEST_BYTES
            )
        } else {
            first
        }
        if (response.code !in 200..299) throw errorFor(response)
        return response.body
    }

    private fun uploadAsset(config: PresetHubConfig, uploadUrl: String, bytes: ByteArray) {
        val base = uploadUrl.substringBefore("{")
        val name = URLEncoder.encode(PRESET_HUB_ASSET_NAME, "UTF-8")
        val response = request(
            method = "POST",
            url = "$base?name=$name",
            token = config.token,
            body = bytes
        )
        if (response.code != 201) throw errorFor(response)
    }

    private fun deleteAsset(config: PresetHubConfig, assetId: Long) {
        val response = request(
            method = "DELETE",
            url = "$API/repos/${config.repo}/releases/assets/$assetId",
            token = config.token
        )
        // 204 = 删除成功；404 = 别人已删，目标状态一致。
        if (response.code !in 200..299 && response.code != HttpURLConnection.HTTP_NOT_FOUND) {
            throw errorFor(response)
        }
    }

    private fun parseRelease(response: HttpResponse): GitHubRelease {
        val root = runCatching {
            Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: throw PresetHubException(PresetHubFailure.MALFORMED)
        val id = (root["id"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
            ?: throw PresetHubException(PresetHubFailure.MALFORMED)
        val uploadUrl = (root["upload_url"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?: throw PresetHubException(PresetHubFailure.MALFORMED)
        val assets = (root["assets"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            val assetId = (asset["id"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
                ?: return@mapNotNull null
            val name = (asset["name"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            GitHubAsset(assetId, name)
        }.orEmpty()
        return GitHubRelease(id = id, uploadUrl = uploadUrl, assets = assets)
    }

    private fun request(
        method: String,
        url: String,
        token: String? = null,
        body: ByteArray? = null,
        accept: String = JSON_ACCEPT,
        maxBytes: Int = MAX_JSON_BYTES,
        followRedirects: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = followRedirects
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "HyperGlow/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            token?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer ${sanitizeHeader(it)}")
            }
            headers.forEach { (name, value) ->
                if (name.isNotBlank()) setRequestProperty(name, sanitizeHeader(value))
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setFixedLengthStreamingMode(body.size)
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val remaining = connection.getHeaderField("X-RateLimit-Remaining")
            val location = connection.getHeaderField("Location")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.readCapped(maxBytes) ?: ByteArray(0)
            HttpResponse(code, bytes, remaining, location)
        } finally {
            connection.disconnect()
        }
    }

    private fun anonymousError(response: HttpResponse): PresetHubException {
        val failure = when (response.code) {
            400, 422 -> PresetHubFailure.MALFORMED
            HttpURLConnection.HTTP_UNAUTHORIZED -> PresetHubFailure.UNAUTHORIZED
            // 匿名 Hub 的 403 只用于 owner 校验失败（上传冲突走 409）。
            HttpURLConnection.HTTP_FORBIDDEN -> PresetHubFailure.NOT_OWNER
            HttpURLConnection.HTTP_NOT_FOUND -> PresetHubFailure.NOT_FOUND
            409 -> PresetHubFailure.CONFLICT
            413 -> PresetHubFailure.TOO_LARGE
            429 -> PresetHubFailure.RATE_LIMITED
            else -> PresetHubFailure.SERVER
        }
        return PresetHubException(failure, response.code)
    }

    private fun errorFor(response: HttpResponse): PresetHubException {        val failure = when (response.code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> PresetHubFailure.UNAUTHORIZED
            HttpURLConnection.HTTP_FORBIDDEN -> {
                val text = response.body.toString(Charsets.UTF_8)
                if (response.rateRemaining == "0" || text.contains("rate limit", ignoreCase = true)) {
                    PresetHubFailure.RATE_LIMITED
                } else {
                    PresetHubFailure.UNAUTHORIZED
                }
            }
            HttpURLConnection.HTTP_NOT_FOUND -> PresetHubFailure.NOT_FOUND
            413 -> PresetHubFailure.TOO_LARGE
            else -> PresetHubFailure.SERVER
        }
        return PresetHubException(failure, response.code)
    }

    private suspend fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (hub: PresetHubException) {
        Result.failure(hub)
    } catch (error: Exception) {
        Result.failure(PresetHubException(PresetHubFailure.NETWORK, cause = error))
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")

    private fun sanitizeHeader(value: String): String =
        value.replace("\r", "").replace("\n", "").trim()

    private fun InputStream.readCapped(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) {
                throw PresetHubException(PresetHubFailure.TOO_LARGE)
            }
        }
        return out.toByteArray()
    }

    private data class GitHubAsset(val id: Long, val name: String)

    private data class GitHubRelease(
        val id: Long,
        val uploadUrl: String,
        val assets: List<GitHubAsset>
    )

    private data class HttpResponse(
        val code: Int,
        val body: ByteArray,
        val rateRemaining: String?,
        val location: String?
    )
}
