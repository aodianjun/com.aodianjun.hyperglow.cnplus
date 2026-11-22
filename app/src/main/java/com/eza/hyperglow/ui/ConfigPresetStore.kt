package com.eza.hyperglow.ui

import android.content.Context
import androidx.annotation.StringRes
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** 预设名称/备注长度上限（与 HyperIsland 预设同一量级，卡片摘要不折行）。 */
internal const val CONFIG_PRESET_NAME_MAX = 20
internal const val CONFIG_PRESET_NOTE_MAX = 60

/** 本地最多保留的预设数量；超出后丢弃最旧的一条。 */
private const val CONFIG_PRESET_LIMIT = 50

/** 云端预设在路由/界面里的 id 前缀；去掉前缀即云端原始 id。 */
internal const val CLOUD_PRESET_PREFIX = "cloud:"

internal fun isCloudPresetId(id: String): Boolean = id.startsWith(CLOUD_PRESET_PREFIX)

/**
 * 单条预设的载荷上限。配置备份本身允许 2 MiB，但预设最多存 50 条且整包落在
 * SharedPreferences 里，必须比备份上限收得更紧，防止插件长文本把 prefs 撑爆。
 */
internal const val CONFIG_PRESET_MAX_BYTES = 256 * 1024

/**
 * 配置预设的一个分节。
 *
 * 每个分节直接对应配置备份信封里的顶层键：预设本身存的就是一份**裁剪过的备份信封**，
 * 因此快照/应用都复用 [ConfigBackupCodec] 的类型化编解码与 [applyImportedConfig] 的落盘路径，
 * 不需要再维护第二套设置键表（参考 HyperIsland 的 PresetConfigs，但分节粒度按本应用设置面收敛）。
 */
internal enum class ConfigPresetSection(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val envelopeKeys: List<String>
) {
    APPEARANCE(
        "appearance",
        R.string.config_preset_section_appearance,
        R.string.config_preset_section_appearance_summary,
        listOf(ConfigBackupCodec.CUSTOMIZATION_KEY)
    ),
    BEHAVIOR(
        "behavior",
        R.string.config_preset_section_behavior,
        R.string.config_preset_section_behavior_summary,
        listOf(ConfigBackupCodec.PREFERENCES_KEY)
    ),
    APP_UI(
        "app_ui",
        R.string.config_preset_section_app_ui,
        R.string.config_preset_section_app_ui_summary,
        listOf(ConfigBackupCodec.APP_UI_KEY)
    ),
    LYRIC_SOURCE(
        "lyric_source",
        R.string.config_preset_section_lyric_source,
        R.string.config_preset_section_lyric_source_summary,
        listOf(ConfigBackupCodec.LYRIC_SOURCE_KEY)
    ),
    INTERFACE(
        "interface",
        R.string.config_preset_section_interface,
        R.string.config_preset_section_interface_summary,
        listOf(ConfigBackupCodec.UI_LANGUAGE_KEY, ConfigBackupCodec.APP_NAVIGATION_KEY)
    ),
    DIAGNOSTICS(
        "diagnostics",
        R.string.config_preset_section_diagnostics,
        R.string.config_preset_section_diagnostics_summary,
        listOf(ConfigBackupCodec.DIAGNOSTICS_KEY)
    ),
    PLUGINS(
        "plugins",
        R.string.config_preset_section_plugins,
        R.string.config_preset_section_plugins_summary,
        listOf(ConfigBackupCodec.PLUGIN_SETTINGS_KEY)
    )
}

/**
 * 一条配置预设：名称/备注 + 创建时间 + 裁剪过的配置备份信封。
 *
 * [payload] 保持备份信封形状（format/version + 若干顶层键），只含创建时勾选的分节；
 * 应用时再按用户选择二次裁剪，交给 [decodeImportedConfig]/[applyImportedConfig]。
 */
@Serializable
internal data class ConfigPreset(
    val id: String,
    val name: String,
    val note: String = "",
    val createdAt: Long,
    val payload: JsonObject,
    /** 云端预设的上传者；本地预设为空串。 */
    val author: String = ""
) {
    /** 本预设实际包含的分节（按固定展示顺序）。 */
    val sections: Set<ConfigPresetSection>
        get() = ConfigPresetSection.entries.filterTo(LinkedHashSet()) { section ->
            section.envelopeKeys.any { payload.containsKey(it) }
        }
}

/** 单条字段差异；[key] 是原始设置键或 `pluginId/key`。 */
internal data class ConfigPresetChange(
    val key: String,
    val before: String,
    val after: String
)

/** 分节预览：included = 预设含该分节且本次勾选。 */
internal data class ConfigPresetSectionPreview(
    val section: ConfigPresetSection,
    val included: Boolean,
    val changes: List<ConfigPresetChange>
)

/**
 * 应用前预览：逐分节差异 + 预设里的歌词外观文档（供实时预览）。
 *
 * 外观文档的差异不逐字段展开（文档结构深、键名对用户无意义），统一由界面展示
 * 「整份外观文档将被替换」，真正的「预览」由 [appearance] 编译后的实机同源画布承担。
 */
internal data class ConfigPresetPreview(
    val sections: List<ConfigPresetSectionPreview>,
    val appearance: CustomizationDocument?
) {
    val totalChanges: Int get() = sections.sumOf { it.changes.size }

    fun changesFor(section: ConfigPresetSection): Int =
        sections.firstOrNull { it.section == section }?.changes?.size ?: 0
}

/**
 * 配置预设的本地存储与快照 / 预览 / 应用。
 *
 * 存储独立于配置备份：预设不参与配置备份导入导出，避免「备份里带备份」的递归；
 * 每条预设是一份完整可解码的备份信封，跨版本演进由 [ConfigBackupCodec] 的
 * 忽略未知键 / 缺省保持现状语义兜底。
 */
internal object ConfigPresetStore {
    private const val PREFS = "hyperglow_config_presets"
    private const val KEY_PRESETS = "presets"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context): List<ConfigPreset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ConfigPreset>>(raw)
        }.getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
    }

    fun find(context: Context, id: String): ConfigPreset? =
        load(context).firstOrNull { it.id == id }

    fun save(context: Context, preset: ConfigPreset): Boolean {
        val current = load(context).toMutableList()
        current.removeAll { it.id == preset.id }
        current.add(0, preset)
        while (current.size > CONFIG_PRESET_LIMIT) current.removeAt(current.lastIndex)
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRESETS, json.encodeToString(current))
                .commit()
        }.getOrDefault(false)
    }

    fun delete(context: Context, id: String): Boolean {
        val remaining = load(context).filterNot { it.id == id }
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRESETS, json.encodeToString(remaining))
                .commit()
        }.getOrDefault(false)
    }

    /**
     * 把云端预设另存为本地预设：换新 UUID，避免下次上传时把云端条目当成同一条覆盖。
     */
    fun saveAsLocal(context: Context, preset: ConfigPreset): ConfigPreset? {
        val copy = preset.copy(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis()
        )
        return if (save(context, copy)) copy else null
    }

    /**
     * 由预设里的外观文档编译出某个曲面的实机同源 profile；供列表卡片缩略图用。
     * 纯 CPU，调用方应放在 IO 线程（见 ConfigPresetScreen 的 produceState）。
     */
    fun appearanceProfile(
        preset: ConfigPreset,
        surface: String
    ): CompiledSurfaceProfile? {
        val element = preset.payload[ConfigBackupCodec.CUSTOMIZATION_KEY] as? JsonObject
            ?: return null
        val document = SceneCompiler.decodeDocumentElement(element) ?: return null
        return runCatching {
            SceneCompiler.compile(document).profiles[surface]
        }.getOrNull()
    }

    /**
     * 把当前整套配置快照成预设：只保留 [sections] 命中的信封顶层键。
     * 与配置备份导出同源（[exportAllConfig]），所以快照里每个值都是当前生效值。
     */
    fun capture(
        context: Context,
        name: String,
        note: String,
        sections: Set<ConfigPresetSection>
    ): ConfigPreset? {
        val envelope = currentEnvelope(context) ?: return null
        val payload = buildJsonObject {
            envelope[ConfigBackupCodec.FORMAT_KEY]?.let {
                put(ConfigBackupCodec.FORMAT_KEY, it)
            }
            envelope[ConfigBackupCodec.VERSION_KEY]?.let {
                put(ConfigBackupCodec.VERSION_KEY, it)
            }
            ConfigPresetSection.entries.forEach { section ->
                if (section in sections) {
                    section.envelopeKeys.forEach { key ->
                        val value = envelope[key]
                        // 一个插件都没装时 pluginSettings 是空对象，不落这个空分节。
                        val emptyPlugins = key == ConfigBackupCodec.PLUGIN_SETTINGS_KEY &&
                            value is JsonObject && value.isEmpty()
                        if (value != null && !emptyPlugins) put(key, value)
                    }
                }
            }
        }
        // 一个分节都没抓到（例如插件一个都没装、对应键缺席）时不落空预设。
        val hasSection = ConfigPresetSection.entries.any { section ->
            section.envelopeKeys.any { payload.containsKey(it) }
        }
        if (!hasSection) return null
        if (payload.toString().toByteArray(Charsets.UTF_8).size > CONFIG_PRESET_MAX_BYTES) {
            return null
        }
        return ConfigPreset(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(CONFIG_PRESET_NAME_MAX),
            note = note.trim().take(CONFIG_PRESET_NOTE_MAX),
            createdAt = System.currentTimeMillis(),
            payload = payload
        )
    }

    /**
     * 计算应用前预览。必须在 IO 线程调用：导出当前配置要序列化整份设置与插件设置，
     * 与配置备份导入端的线程约束一致（JSON 编解码不占主线程）。
     */
    fun preview(
        context: Context,
        preset: ConfigPreset,
        selected: Set<ConfigPresetSection>
    ): ConfigPresetPreview {
        val current = currentEnvelope(context) ?: JsonObject(emptyMap())
        val sections = ConfigPresetSection.entries.map { section ->
            val included = section in selected &&
                section.envelopeKeys.any { preset.payload.containsKey(it) }
            ConfigPresetSectionPreview(
                section = section,
                included = included,
                changes = if (included) diffSection(section, current, preset.payload) else emptyList()
            )
        }
        val document = if (ConfigPresetSection.APPEARANCE in selected) {
            (preset.payload[ConfigBackupCodec.CUSTOMIZATION_KEY] as? JsonObject)
                ?.let { SceneCompiler.decodeDocumentElement(it) }
        } else {
            null
        }
        return ConfigPresetPreview(sections, document)
    }

    /** 按勾选分节裁剪预设信封；分节缺席时对应顶层键不会写入。 */
    fun filteredEnvelope(
        preset: ConfigPreset,
        selected: Set<ConfigPresetSection>
    ): JsonObject = buildJsonObject {
        preset.payload[ConfigBackupCodec.FORMAT_KEY]?.let {
            put(ConfigBackupCodec.FORMAT_KEY, it)
        }
        preset.payload[ConfigBackupCodec.VERSION_KEY]?.let {
            put(ConfigBackupCodec.VERSION_KEY, it)
        }
        ConfigPresetSection.entries.forEach { section ->
            if (section in selected) {
                section.envelopeKeys.forEach { key ->
                    preset.payload[key]?.let { put(key, it) }
                }
            }
        }
    }

    /**
     * 解码（纯计算、无副作用），必须在 IO 线程调用；返回 null = 裁剪后没有任何分节。
     * 解码以当前生效配置为基底，未勾选的分节保持现状。
     */
    fun decode(
        context: Context,
        preset: ConfigPreset,
        selected: Set<ConfigPresetSection>
    ): ConfigBackupDecodeResult? {
        val envelope = filteredEnvelope(preset, selected)
        if (envelope.size <= 2) return null
        return runCatching {
            decodeImportedConfig(context, envelope.toString())
        }.getOrNull()
    }

    /**
     * 应用已解码的预设。写在主线程（与配置导入一致：插件 onConfigChanged 等回调的线程
     * 与设置页同源），成功后把运行时配置推给 SystemUI。
     */
    fun apply(context: Context, decoded: ConfigBackupDecodeResult): Boolean {
        val applied = applyImportedConfig(context, decoded)
        if (applied) publishRuntimeConfiguration(context)
        return applied
    }

    private fun currentEnvelope(context: Context): JsonObject? = runCatching {
        SceneCompiler.json.parseToJsonElement(exportAllConfig(context)) as? JsonObject
    }.getOrNull()

    private fun diffSection(
        section: ConfigPresetSection,
        current: JsonObject,
        preset: JsonObject
    ): List<ConfigPresetChange> = when (section) {
        ConfigPresetSection.APPEARANCE -> {
            val before = current[ConfigBackupCodec.CUSTOMIZATION_KEY]
            val after = preset[ConfigBackupCodec.CUSTOMIZATION_KEY]
            // 外观文档整份替换，只报一条差异；界面据此展示「整份替换」文案。
            if (before == null || after == null || before == after) {
                emptyList()
            } else {
                listOf(ConfigPresetChange(ConfigBackupCodec.CUSTOMIZATION_KEY, "", ""))
            }
        }
        ConfigPresetSection.BEHAVIOR -> diffObject(
            current[ConfigBackupCodec.PREFERENCES_KEY],
            preset[ConfigBackupCodec.PREFERENCES_KEY]
        )
        ConfigPresetSection.APP_UI -> diffObject(
            current[ConfigBackupCodec.APP_UI_KEY],
            preset[ConfigBackupCodec.APP_UI_KEY]
        )
        ConfigPresetSection.LYRIC_SOURCE -> diffPrimitive(
            ConfigBackupCodec.LYRIC_SOURCE_KEY,
            current[ConfigBackupCodec.LYRIC_SOURCE_KEY],
            preset[ConfigBackupCodec.LYRIC_SOURCE_KEY]
        )
        ConfigPresetSection.INTERFACE ->
            diffPrimitive(
                ConfigBackupCodec.UI_LANGUAGE_KEY,
                current[ConfigBackupCodec.UI_LANGUAGE_KEY],
                preset[ConfigBackupCodec.UI_LANGUAGE_KEY]
            ) + diffObject(
                current[ConfigBackupCodec.APP_NAVIGATION_KEY],
                preset[ConfigBackupCodec.APP_NAVIGATION_KEY]
            )
        ConfigPresetSection.DIAGNOSTICS -> diffObject(
            current[ConfigBackupCodec.DIAGNOSTICS_KEY],
            preset[ConfigBackupCodec.DIAGNOSTICS_KEY]
        )
        ConfigPresetSection.PLUGINS -> diffPluginSettings(
            current[ConfigBackupCodec.PLUGIN_SETTINGS_KEY],
            preset[ConfigBackupCodec.PLUGIN_SETTINGS_KEY]
        )
    }

    private fun diffPrimitive(
        key: String,
        before: JsonElement?,
        after: JsonElement?
    ): List<ConfigPresetChange> {
        if (before == null || after == null || before == after) return emptyList()
        return listOf(ConfigPresetChange(key, before.displayValue(), after.displayValue()))
    }

    private fun diffObject(before: JsonElement?, after: JsonElement?): List<ConfigPresetChange> {
        val beforeObject = before as? JsonObject ?: return emptyList()
        val afterObject = after as? JsonObject ?: return emptyList()
        val changes = mutableListOf<ConfigPresetChange>()
        afterObject.forEach { (key, value) ->
            val current = beforeObject[key]
            if (current != value) {
                changes += ConfigPresetChange(key, current.displayValue(), value.displayValue())
            }
        }
        return changes
    }

    private fun diffPluginSettings(
        before: JsonElement?,
        after: JsonElement?
    ): List<ConfigPresetChange> {
        val beforePlugins = before as? JsonObject ?: return emptyList()
        val afterPlugins = after as? JsonObject ?: return emptyList()
        val changes = mutableListOf<ConfigPresetChange>()
        afterPlugins.forEach { (pluginId, values) ->
            val current = beforePlugins[pluginId] as? JsonObject ?: JsonObject(emptyMap())
            (values as? JsonObject)?.forEach { (key, value) ->
                val previous = current[key]
                if (previous != value) {
                    changes += ConfigPresetChange(
                        "$pluginId/$key",
                        previous.displayValue(),
                        value.displayValue()
                    )
                }
            }
        }
        return changes
    }

    private fun JsonElement?.displayValue(): String = when {
        this == null -> "—"
        this is JsonNull -> "—"
        else -> toString()
    }
}
