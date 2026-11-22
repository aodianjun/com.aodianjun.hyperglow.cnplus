package com.eza.hyperglow.ui

import android.content.Context
import com.eza.hyperglow.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 云端配置预设（GitHub Release 通道）。
 *
 * 参考 HyperIsland 的 Hub 思路：列表/详情匿名可读，上传才需要凭证。这里不另建服务，
 * 直接把一份 `presets.json` 作为 GitHub Release 资产：
 *
 * - 仓库默认指向本项目（public），Release 标签默认 `presets`，读取不需要 Token；
 * - 上传/更新需要用户自己的 GitHub Token（contents: write），由 App 直接改 Release 资产；
 * - Release 以 **prerelease** 创建，避免被 `/releases/latest` 当成应用最新版（更新检查走
 *   `releases/latest`，不能把配置预设当成版本发布）；按标签读取不受影响；
 * - 整份清单一个资产，一次请求拿到所有预设与正文，列表卡片可以直接渲染预览。
 *
 * 清单是「最后写入者获胜」的覆盖语义：上传前先拉当前清单、按 id 合并、再整体替换资产。
 * 并发上传可能互相覆盖，这与 HyperIsland Hub 的匿名上传模型同级的取舍。
 */
internal const val PRESET_HUB_DEFAULT_REPO = "aodianjun/com.aodianjun.hyperglow.cnplus"
internal const val PRESET_HUB_DEFAULT_TAG = "presets"
internal const val PRESET_HUB_ASSET_NAME = "presets.json"
internal const val PRESET_HUB_FORMAT = "hyperglow-preset-hub"
internal const val PRESET_HUB_VERSION = 1
internal const val PRESET_HUB_MAX_MANIFEST_BYTES = 8 * 1024 * 1024

/** 云端预设：清单里的一条，id 由上传方生成（本地预设沿用其 UUID）。 */
@Serializable
internal data class CloudPreset(
    val id: String,
    val name: String,
    val note: String = "",
    val author: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val payload: JsonObject,
    /** 匿名 Hub 的设备归属哈希；App 不解释，只负责原样保留，避免更新时把归属洗掉。 */
    val owner: String = ""
) {
    /** 转成界面/应用层使用的 [ConfigPreset]；[displayId] 带 `cloud:` 前缀用于路由。 */
    fun toConfigPreset(displayId: String = CLOUD_PRESET_PREFIX + id): ConfigPreset =
        ConfigPreset(
            id = displayId,
            name = name,
            note = note,
            createdAt = createdAt,
            payload = payload,
            author = author
        )
}

/** 云端清单；format/version 只做形状校验，不兼容未知版本。 */
@Serializable
internal data class CloudPresetManifest(
    val format: String = PRESET_HUB_FORMAT,
    val version: Int = PRESET_HUB_VERSION,
    val updatedAt: Long = 0L,
    val presets: List<CloudPreset> = emptyList()
)

internal fun isValidPresetHubRepo(value: String): Boolean =
    Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""").matches(value.trim())

/** GitHub 通道设置：仓库、Release 标签、上传 Token、主/备匿名 Hub 地址、缓存的登录名。 */
internal data class PresetHubConfig(
    val repo: String = PRESET_HUB_DEFAULT_REPO,
    val tag: String = PRESET_HUB_DEFAULT_TAG,
    val token: String = "",
    val login: String = "",
    val anonymousUrl: String = "",
    /** 备用匿名 Hub（例如 EdgeOne）；主地址网络不可用时自动回退。 */
    val anonymousBackupUrl: String = ""
) {
    val hasRepo: Boolean get() = isValidPresetHubRepo(repo) &&
        repo.length <= 120 &&
        tag.isNotBlank() &&
        tag.length <= 100 &&
        tag.none { it == '\n' || it == '\r' }

    /** 主地址是否已填（不校验 HTTPS）。 */
    val hasAnonymousUrl: Boolean get() = anonymousUrl.isNotBlank()

    /** 匿名 Hub 必须走 HTTPS（预设内容会发给该地址）；留空表示不用该地址。 */
    val anonymousUrlValid: Boolean get() = isValidAnonymousUrl(anonymousUrl)

    val anonymousBackupUrlValid: Boolean get() = isValidAnonymousUrl(anonymousBackupUrl)

    /** 主地址或备用地址任一可用，就能走免 Token 上传。 */
    val hasAnonymousUpload: Boolean
        get() = (anonymousUrl.isNotBlank() && anonymousUrlValid) ||
            (anonymousBackupUrl.isNotBlank() && anonymousBackupUrlValid)

    /** 实际要尝试的匿名端点：主地址在前，备用地址在后，去重。 */
    val anonymousEndpoints: List<String>
        get() = buildList {
            anonymousUrl.trim().takeIf {
                it.isNotBlank() && anonymousUrlValid
            }?.let(::add)
            anonymousBackupUrl.trim().takeIf {
                it.isNotBlank() && anonymousBackupUrlValid
            }?.let(::add)
        }.distinct()

    val canUpload: Boolean
        get() = hasRepo && (token.isNotBlank() || hasAnonymousUpload)
}

private fun isValidAnonymousUrl(value: String): Boolean =
    value.isBlank() ||
        (value.startsWith("https://") &&
            value.length in 9..300 &&
            value.none { it.isWhitespace() })

internal object PresetHubPreferences {
    private const val PREFS = "hyperglow_preset_hub"
    private const val KEY_REPO = "repo"
    private const val KEY_TAG = "tag"
    private const val KEY_TOKEN = "token"
    private const val KEY_LOGIN = "login"
    private const val KEY_ANONYMOUS_URL = "anonymous_url"
    private const val KEY_ANONYMOUS_BACKUP_URL = "anonymous_backup_url"
    private const val KEY_DEVICE_ID = "device_id"

    fun read(context: Context): PresetHubConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PresetHubConfig(
            repo = prefs.getString(KEY_REPO, null)?.takeIf { it.isNotBlank() }
                ?: PRESET_HUB_DEFAULT_REPO,
            tag = prefs.getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }
                ?: PRESET_HUB_DEFAULT_TAG,
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            login = prefs.getString(KEY_LOGIN, "").orEmpty(),
            anonymousUrl = prefs.getString(KEY_ANONYMOUS_URL, "").orEmpty(),
            anonymousBackupUrl = prefs.getString(KEY_ANONYMOUS_BACKUP_URL, "").orEmpty()
        )
    }

    fun write(context: Context, config: PresetHubConfig): Boolean {
        val old = read(context)
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_REPO, config.repo.trim())
                .putString(KEY_TAG, config.tag.trim())
                .putString(KEY_TOKEN, config.token.trim())
                .putString(KEY_ANONYMOUS_URL, config.anonymousUrl.trim())
                .putString(KEY_ANONYMOUS_BACKUP_URL, config.anonymousBackupUrl.trim())
                // Token 换人后旧的登录名不再可信，让下一次上传重新问 GitHub。
                .putString(KEY_LOGIN, if (old.token == config.token) old.login else "")
                .commit()
        }.getOrDefault(false)
    }

    /**
     * 匿名上传的设备标识：首次使用生成 UUID 并长期保存，随 `X-HyperGlow-Device` 头发给 Hub。
     * Worker 只存它的 SHA-256，用来区分「谁能更新哪条」，不暴露原始标识。
     */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun updateLogin(context: Context, login: String) {
        if (login.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOGIN, login)
            .apply()
    }
}

/** 云端清单的本地缓存；断网时列表仍能显示上次拉到的预设。 */
internal object PresetHubStore {
    private const val PREFS = "hyperglow_preset_hub_cache"
    private const val KEY_MANIFEST = "manifest"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_SOURCE_REPO = "source_repo"
    private const val KEY_SOURCE_TAG = "source_tag"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 读取缓存。传 [config] 时只返回同一仓库/标签的缓存：用户在设置里切了仓库以后，
     * 旧仓库的清单既不能显示成新仓库的内容，也不能被当成上传 fallback 复制过去。
     */
    fun load(context: Context, config: PresetHubConfig? = null): CloudPresetManifest? {
        if (config != null && !matchesSource(context, config)) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST, null) ?: return null
        return parseCloudManifest(raw)
    }

    fun fetchedAt(context: Context, config: PresetHubConfig? = null): Long {
        if (config != null && !matchesSource(context, config)) return 0L
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_FETCHED_AT, 0L)
    }

    fun save(
        context: Context,
        config: PresetHubConfig,
        manifest: CloudPresetManifest
    ): Boolean {
        val raw = runCatching { json.encodeToString(manifest) }.getOrNull() ?: return false
        if (raw.toByteArray(Charsets.UTF_8).size > PRESET_HUB_MAX_MANIFEST_BYTES) return false
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MANIFEST, raw)
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .putString(KEY_SOURCE_REPO, config.repo.trim())
                .putString(KEY_SOURCE_TAG, config.tag.trim())
                .commit()
        }.getOrDefault(false)
    }

    fun find(
        context: Context,
        config: PresetHubConfig,
        cloudId: String
    ): CloudPreset? =
        load(context, config)?.presets?.firstOrNull { it.id == cloudId }

    private fun matchesSource(context: Context, config: PresetHubConfig): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val repo = prefs.getString(KEY_SOURCE_REPO, null) ?: return false
        val tag = prefs.getString(KEY_SOURCE_TAG, null) ?: return false
        return repo == config.repo.trim() && tag == config.tag.trim()
    }
}

/** 单个云端预设的载荷上限与清单条数上限：防止一份超大 Release 把列表拖死。 */
internal const val PRESET_HUB_MAX_PRESETS = 100

/**
 * 反序列化并校验清单；坏条目不阻断整份列表。
 *
 * 逐条解码而不是整份 `decodeFromString<CloudPresetManifest>`：一条 payload 形状不对不应该
 * 让整个云端列表变成「格式错误」。format/version 不匹配仍整份拒绝。
 */
internal fun parseCloudManifest(raw: String): CloudPresetManifest? {
    if (raw.toByteArray(Charsets.UTF_8).size > PRESET_HUB_MAX_MANIFEST_BYTES) return null
    val json = Json { ignoreUnknownKeys = true }
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val format = (root["format"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (format != PRESET_HUB_FORMAT) return null
    val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return null
    if (version != PRESET_HUB_VERSION) return null
    val updatedAt = (root["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
    val presets = (root["presets"] as? JsonArray).orEmpty()
        .mapNotNull { element ->
            val preset = runCatching {
                json.decodeFromJsonElement(CloudPreset.serializer(), element)
            }.getOrNull() ?: return@mapNotNull null
            if (preset.id.isBlank() || preset.name.isBlank() || preset.payload.isEmpty()) {
                return@mapNotNull null
            }
            if (preset.payload.toString().toByteArray(Charsets.UTF_8).size >
                CONFIG_PRESET_MAX_BYTES
            ) {
                return@mapNotNull null
            }
            preset
        }
        .sortedByDescending { it.updatedAt }
        .distinctBy { it.id }
        .take(PRESET_HUB_MAX_PRESETS)
    return CloudPresetManifest(
        format = format,
        version = version,
        updatedAt = updatedAt,
        presets = presets
    )
}

/**
 * 把一条本地预设合并进云端清单：同 id 覆盖（保留原作者与创建时间），否则插入；
 * 排序按更新时间新→旧。纯函数，JVM 单测直接覆盖。
 */
internal fun mergeCloudPreset(
    manifest: CloudPresetManifest,
    preset: ConfigPreset,
    author: String,
    now: Long
): CloudPresetManifest {
    val cloudId = preset.id.removePrefix(CLOUD_PRESET_PREFIX).ifBlank {
        java.util.UUID.randomUUID().toString()
    }
    val existing = manifest.presets.firstOrNull { it.id == cloudId }
    val merged = CloudPreset(
        id = cloudId,
        name = preset.name,
        note = preset.note,
        author = existing?.author?.takeIf { it.isNotBlank() }
            ?: author.takeIf { it.isNotBlank() }
            ?: "anonymous",
        createdAt = existing?.createdAt?.takeIf { it > 0 }
            ?: preset.createdAt.takeIf { it > 0 }
            ?: now,
        updatedAt = now,
        payload = preset.payload,
        // 保留 Worker 写入的设备归属，避免一次 Token 上传把匿名条目的 owner 洗掉。
        owner = existing?.owner.orEmpty()
    )
    return manifest.copy(
        updatedAt = now,
        presets = (manifest.presets.filterNot { it.id == cloudId } + merged)
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.id }
            .take(PRESET_HUB_MAX_PRESETS)
    )
}

/**
 * 把云端失败归一到界面能翻译的几类；网络客户端不直接产生用户文案。
 */
internal enum class PresetHubFailure {
    INVALID_CONFIG,
    NETWORK,
    UNAUTHORIZED,
    RATE_LIMITED,
    NOT_FOUND,
    CONFLICT,
    MALFORMED,
    TOO_LARGE,
    SERVER
}

internal class PresetHubException(
    val failure: PresetHubFailure,
    val status: Int = 0,
    cause: Throwable? = null
) : Exception("preset hub ${failure.name} (http=$status)", cause) {
    init {
        // 这里只记失败类型，不记 URL 查询串或 Token。
        AppLog.w("PresetHub", "request failed: ${failure.name} http=$status")
    }
}
