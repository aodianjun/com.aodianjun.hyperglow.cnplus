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
 * 分节直接对应配置备份信封的顶层键：预设本身存的就是一份**裁剪过的备份信封**，
 * 因此快照/应用都复用 [ConfigBackupCodec] 的类型化编解码与 [applyImportedConfig] 的落盘路径。
 *
 * 已知分节带本地化标题/说明，并允许把多个顶层键归成一组（例如界面语言 + 界面导航）。
 * 信封里出现、但当前版本还不认识的顶层键会作为**自定义分节**原样保留，标题回落到键名：
 * 备份信封以后新增任何设置面，预设都会自动纳入、上传并随信封一起往返，不需要再回来
 * 维护一张白名单（旧的固定 `entries` 正是会静默丢掉新键的地方）。
 */
internal data class ConfigPresetSection(
    val id: String,
    val envelopeKeys: List<String>,
    @param:StringRes val titleRes: Int?,
    @param:StringRes val summaryRes: Int?
) {
    val isKnown: Boolean get() = titleRes != null

    companion object {
        val APPEARANCE = ConfigPresetSection(
            "appearance",
            listOf(ConfigBackupCodec.CUSTOMIZATION_KEY),
            R.string.config_preset_section_appearance,
            R.string.config_preset_section_appearance_summary
        )
        val BEHAVIOR = ConfigPresetSection(
            "behavior",
            listOf(ConfigBackupCodec.PREFERENCES_KEY),
            R.string.config_preset_section_behavior,
            R.string.config_preset_section_behavior_summary
        )
        val APP_UI = ConfigPresetSection(
            "app_ui",
            listOf(ConfigBackupCodec.APP_UI_KEY),
            R.string.config_preset_section_app_ui,
            R.string.config_preset_section_app_ui_summary
        )
        val LYRIC_SOURCE = ConfigPresetSection(
            "lyric_source",
            listOf(ConfigBackupCodec.LYRIC_SOURCE_KEY),
            R.string.config_preset_section_lyric_source,
            R.string.config_preset_section_lyric_source_summary
        )
        val INTERFACE = ConfigPresetSection(
            "interface",
            listOf(ConfigBackupCodec.UI_LANGUAGE_KEY, ConfigBackupCodec.APP_NAVIGATION_KEY),
            R.string.config_preset_section_interface,
            R.string.config_preset_section_interface_summary
        )
        val DIAGNOSTICS = ConfigPresetSection(
            "diagnostics",
            listOf(ConfigBackupCodec.DIAGNOSTICS_KEY),
            R.string.config_preset_section_diagnostics,
            R.string.config_preset_section_diagnostics_summary
        )
        val PLUGINS = ConfigPresetSection(
            "plugins",
            listOf(ConfigBackupCodec.PLUGIN_SETTINGS_KEY),
            R.string.config_preset_section_plugins,
            R.string.config_preset_section_plugins_summary
        )

        /** 已知分节的固定展示顺序。 */
        val known: List<ConfigPresetSection> = listOf(
            APPEARANCE,
            BEHAVIOR,
            APP_UI,
            LYRIC_SOURCE,
            INTERFACE,
            DIAGNOSTICS,
            PLUGINS
        )

        /**
         * 从备份信封推导分节：已知分节按固定顺序在前，未归组的顶层键按字母序追加。
         * 空对象分节（例如一个插件都没装时的 pluginSettings）视为不存在。
         */
        fun of(envelope: JsonObject): List<ConfigPresetSection> {
            val available = envelope.keys.filterTo(LinkedHashSet()) { key ->
                if (key == ConfigBackupCodec.FORMAT_KEY ||
                    key == ConfigBackupCodec.VERSION_KEY
                ) {
                    return@filterTo false
                }
                val element = envelope[key]
                !(element is JsonObject && element.isEmpty())
            }
            val presentKnown = known.filter { section ->
                section.envelopeKeys.any { it in available }
            }
            val claimed = presentKnown.flatMapTo(HashSet()) { it.envelopeKeys }
            val custom = available
                .filterNot { it in claimed }
                .sorted()
                .map { key ->
                    ConfigPresetSection(
                        // 加前缀避免未来信封键与已知分节 id 重名（例如真出现 "appearance"）。
                        id = "key:$key",
                        envelopeKeys = listOf(key),
                        titleRes = null,
                        summaryRes = null
                    )
                }
            return presentKnown + custom
        }
    }
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
    /** 本预设实际包含的分节：已知分节在前，信封里未归组的顶层键自动追加。 */
    val sections: Set<ConfigPresetSection>
        get() = LinkedHashSet(ConfigPresetSection.of(payload))
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
     * 与配置备份导出同源（[exportAllConfig]），所以快照里每个值都是当前生效值；
     * 分节集合由 [currentSections] 从当前信封动态生成，因此以后新增的设置键也能被捕获。
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
            sections.forEach { section ->
                section.envelopeKeys.forEach { key ->
                    val value = envelope[key]
                    // 空对象分节（如没装插件时的 pluginSettings）没有任何可应用的内容。
                    if (value != null && !(value is JsonObject && value.isEmpty())) {
                        put(key, value)
                    }
                }
            }
        }
        // 一个分节都没抓到（例如对应键缺席）时不落空预设。
        val hasSection = payload.keys.any {
            it != ConfigBackupCodec.FORMAT_KEY && it != ConfigBackupCodec.VERSION_KEY
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
     * 当前导出信封里实际存在的全部分节（含本版本尚未认识的键）。
     * 新建预设对话框用它生成勾选项，默认全选，新增设置面无需改界面代码就会自动出现。
     * 导出整份配置要序列化设置与插件设置，调用方应放在 IO 线程。
     */
    fun currentSections(context: Context): List<ConfigPresetSection> =
        currentEnvelope(context)?.let(ConfigPresetSection::of).orEmpty()

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
        val sections = preset.sections.map { section ->
            val included = section in selected
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
        preset.sections.forEach { section ->
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
    ): List<ConfigPresetChange> = when (section.id) {
        ConfigPresetSection.APPEARANCE.id -> {
            val before = current[ConfigBackupCodec.CUSTOMIZATION_KEY]
            val after = preset[ConfigBackupCodec.CUSTOMIZATION_KEY]
            // 外观文档整份替换，只报一条差异；界面据此展示「整份替换」文案。
            if (before == null || after == null || before == after) {
                emptyList()
            } else {
                listOf(ConfigPresetChange(ConfigBackupCodec.CUSTOMIZATION_KEY, "", ""))
            }
        }
        ConfigPresetSection.BEHAVIOR.id -> diffObject(
            current[ConfigBackupCodec.PREFERENCES_KEY],
            preset[ConfigBackupCodec.PREFERENCES_KEY]
        )
        ConfigPresetSection.APP_UI.id -> diffObject(
            current[ConfigBackupCodec.APP_UI_KEY],
            preset[ConfigBackupCodec.APP_UI_KEY]
        )
        ConfigPresetSection.LYRIC_SOURCE.id -> diffPrimitive(
            ConfigBackupCodec.LYRIC_SOURCE_KEY,
            current[ConfigBackupCodec.LYRIC_SOURCE_KEY],
            preset[ConfigBackupCodec.LYRIC_SOURCE_KEY]
        )
        ConfigPresetSection.INTERFACE.id ->
            diffPrimitive(
                ConfigBackupCodec.UI_LANGUAGE_KEY,
                current[ConfigBackupCodec.UI_LANGUAGE_KEY],
                preset[ConfigBackupCodec.UI_LANGUAGE_KEY]
            ) + diffObject(
                current[ConfigBackupCodec.APP_NAVIGATION_KEY],
                preset[ConfigBackupCodec.APP_NAVIGATION_KEY]
            )
        ConfigPresetSection.DIAGNOSTICS.id -> diffObject(
            current[ConfigBackupCodec.DIAGNOSTICS_KEY],
            preset[ConfigBackupCodec.DIAGNOSTICS_KEY]
        )
        ConfigPresetSection.PLUGINS.id -> diffPluginSettings(
            current[ConfigBackupCodec.PLUGIN_SETTINGS_KEY],
            preset[ConfigBackupCodec.PLUGIN_SETTINGS_KEY]
        )
        // 当前版本还不认识的分节：按路径展开对象差异，至少让用户看到会改动哪些键。
        else -> section.envelopeKeys.flatMap { key ->
            diffGeneric(key, current[key], preset[key])
        }
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

    /**
     * 未知分节的通用差异：对象递归展开成 `key.subkey` 路径，数组/标量各报一条。
     * 这样后续版本新增的设置面也能在旧版本的预览里显示改动点。
     */
    private fun diffGeneric(
        key: String,
        before: JsonElement?,
        after: JsonElement?
    ): List<ConfigPresetChange> {
        if (before == null || after == null || before == after) return emptyList()
        val beforeObject = before as? JsonObject
        val afterObject = after as? JsonObject
        if (beforeObject == null || afterObject == null) {
            return listOf(ConfigPresetChange(key, before.displayValue(), after.displayValue()))
        }
        val changes = mutableListOf<ConfigPresetChange>()
        fun walk(prefix: String, left: JsonObject, right: JsonObject) {
            right.forEach { (childKey, value) ->
                val path = if (prefix.isEmpty()) childKey else "$prefix.$childKey"
                val old = left[childKey]
                if (old == value) return@forEach
                if (old is JsonObject && value is JsonObject) {
                    walk(path, old, value)
                } else {
                    changes += ConfigPresetChange(path, old.displayValue(), value.displayValue())
                }
            }
        }
        walk(key, beforeObject, afterObject)
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
