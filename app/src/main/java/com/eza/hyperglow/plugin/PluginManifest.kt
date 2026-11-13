package com.eza.hyperglow.plugin

import com.eza.hyperglow.AppLog
import com.lidesheng.hyperlyric.plugin.api.HYPERLYRIC_PLUGIN_API_VERSION
import com.lidesheng.hyperlyric.plugin.api.PluginSettingInputType
import com.lidesheng.hyperlyric.plugin.api.PluginSettingType
import com.lidesheng.hyperlyric.plugin.api.PluginSettingValuePresentation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 插件 manifest.json 的宿主侧解析模型（HyperLyric 格式，FQCN 兼容体系的组成部分）。
 *
 * 与 `Plugins/api` 里插件运行时可见的 [PluginSettingsSchema] 分工：本模型只负责
 * 把 ZIP 内的 JSON 解析成宿主可校验、可渲染的数据；序列化默认值与 JSON 容错
 * 全部收在 [PluginManifestCodec] 里，不外泄到 UI/运行时层。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val nameLocales: Map<String, String> = emptyMap(),
    val author: String = "",
    val version: String = "",
    val apiVersion: Int = 1,
    val entry: String,
    /** 插件激活开关对应的设置键（插件代码从 PluginConfig 读它判断自己是否启用）。 */
    val activationSettingKey: String? = null,
    val settingGroups: List<PluginSettingGroupData> = emptyList(),
    val cacheScopes: List<PluginCacheScopeData> = emptyList(),
    val settings: List<PluginSettingData> = emptyList()
) {
    /** 按当前系统语言挑选本地化名称，回退到默认 name。 */
    fun localizedName(languageTag: String): String =
        localizedValue(languageTag, nameLocales, name)

    fun settingGroupTitle(groupId: String, languageTag: String): String? =
        settingGroups.firstOrNull { it.id == groupId }
            ?.let { localizedValue(languageTag, it.titleLocales, it.title) }

    /**
     * 校验 manifest 结构。返回 null 表示合法；否则返回人类可读的拒绝原因
     * （直接用于安装失败的 toast/日志）。
     */
    fun validate(): String? {
        if (!isValidPluginId(id)) {
            return "bad id: $id"
        }
        if (apiVersion > HYPERLYRIC_PLUGIN_API_VERSION) {
            return "apiVersion $apiVersion > host $HYPERLYRIC_PLUGIN_API_VERSION"
        }
        if (entry.isBlank()) return "missing entry"
        val settingKeys = settings.map { it.key }
        if (settingKeys.size != settingKeys.toSet().size) return "duplicate setting keys"
        settings.forEach { setting ->
            if (setting.key.isBlank()) return "blank setting key"
            val type = setting.type ?: return "unknown setting type: ${setting.typeWire}"
            if (setting.valuePresentationWire != null && setting.valuePresentation == null) {
                return "unknown valuePresentation: ${setting.valuePresentationWire}"
            }
            if (setting.inputTypeWire != null && setting.inputType == null) {
                return "unknown inputType: ${setting.inputTypeWire}"
            }
            when (type) {
                PluginSettingType.SELECT, PluginSettingType.MULTI_SELECT -> {
                    if (setting.options.isEmpty()) {
                        return "setting ${setting.key} (${type.wireName}) has no options"
                    }
                    if (setting.options.any { it.value.isBlank() }) {
                        return "setting ${setting.key} has blank option value"
                    }
                }
                PluginSettingType.SLIDER -> {
                    val min = setting.min
                    val max = setting.max
                    if (min == null || max == null || min > max) {
                        return "setting ${setting.key} (slider) needs min<=max"
                    }
                }
                else -> Unit
            }
            setting.conflictsWith.forEach { conflict ->
                if (conflict !in settingKeys) {
                    return "setting ${setting.key} conflictsWith unknown key $conflict"
                }
            }
            if (setting.group != null && settingGroups.none { it.id == setting.group }) {
                return "setting ${setting.key} references unknown group ${setting.group}"
            }
        }
        return null
    }
}

@Serializable
data class PluginSettingGroupData(
    val id: String,
    val title: String,
    val titleLocales: Map<String, String> = emptyMap()
)

@Serializable
data class PluginCacheScopeData(
    val id: String,
    val title: String,
    val summary: String? = null,
    val titleLocales: Map<String, String> = emptyMap(),
    val summaryLocales: Map<String, String> = emptyMap()
)

/** manifest 中的单条设置声明；type/valuePresentation/inputType 存 wire 名，惰性映射为 API 枚举。 */
@Serializable
data class PluginSettingData(
    /**
     * JSON 字段名为 wire 名 `type`（HyperLyric manifest 标准）。
     * 可空 + 默认 null：type 是必填，但个别损坏的插件包可能缺失；为保证整包仍可解析、
     * 进而让 [PluginManifest.validate] 给出明确的 "unknown setting type" 拒绝信息，
     * 这里用默认 null 兜底（而非让 decode 抛 MissingFieldException）。非缺失时按 wire 名填充。
     */
    @SerialName("type")
    val typeWire: String? = null,
    val key: String,
    val title: String,
    val titleLocales: Map<String, String> = emptyMap(),
    val summary: String? = null,
    val summaryLocales: Map<String, String> = emptyMap(),
    val dialogSummary: String? = null,
    val dialogSummaryLocales: Map<String, String> = emptyMap(),
    val emptyValueSummary: String? = null,
    val emptyValueSummaryLocales: Map<String, String> = emptyMap(),
    /** JSON 原始默认值(bool/number/string 均按原文保留)，由 [PluginSettingsStore] 按类型读取。 */
    val default: JsonElement? = null,
    val options: List<PluginSettingOptionData> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** JSON 字段名为 wire 名 `valuePresentation`。 */
    @SerialName("valuePresentation")
    val valuePresentationWire: String? = null,
    val previewLineCount: Int = 2,
    /** JSON 字段名为 wire 名 `inputType`。 */
    @SerialName("inputType")
    val inputTypeWire: String? = null,
    val conflictsWith: List<String> = emptyList(),
    val backup: Boolean = true,
    val group: String? = null
) {
    val type: PluginSettingType? get() = typeWire?.let(PluginSettingType::fromWire)
    val valuePresentation: PluginSettingValuePresentation?
        get() = valuePresentationWire?.let(PluginSettingValuePresentation::fromWire)
    val inputType: PluginSettingInputType?
        get() = inputTypeWire?.let(PluginSettingInputType::fromWire)

    /** 默认布尔值：JSON 里是 bool 用之；是字符串 "true"/"false" 也接受；否则 false。 */
    fun defaultBoolean(): Boolean = when (default) {
        null -> false
        is JsonPrimitive -> default.booleanOrNull
            ?: (default.content.equals("true", ignoreCase = true))
        else -> false
    }

    /** 默认字符串：JSON 原文（bool/number 转成字符串），null → ""。 */
    fun defaultString(): String = default?.let { element ->
        when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    } ?: ""

    /** 默认浮点：number 或可解析字符串，null → 0。 */
    fun defaultFloat(): Float = (default as? JsonPrimitive)
        ?.doubleOrNull
        ?.toFloat()
        ?: defaultString().toFloatOrNull()
        ?: 0f

    fun localizedTitle(languageTag: String): String =
        localizedValue(languageTag, titleLocales, title)

    fun localizedSummary(languageTag: String): String? =
        summary?.let { localizedValue(languageTag, summaryLocales, it) }

    fun localizedDialogSummary(languageTag: String): String? =
        dialogSummary?.let { localizedValue(languageTag, dialogSummaryLocales, it) }

    fun localizedEmptyValueSummary(languageTag: String): String? =
        emptyValueSummary?.let { localizedValue(languageTag, emptyValueSummaryLocales, it) }
}

@Serializable
data class PluginSettingOptionData(
    val value: String,
    val label: String,
    val labelLocales: Map<String, String> = emptyMap()
) {
    fun localizedLabel(languageTag: String): String =
        localizedValue(languageTag, labelLocales, label)
}

/**
 * 插件 id 白名单。id 会拼进 `plugin_settings_<id>` 的 SharedPreferences 文件名,
 * 因此配置备份的导入路径也必须过同一校验(见 ConfigBackupCodec),防止载荷里的
 * 越界 id 变成路径片段。
 */
internal val PLUGIN_ID_PATTERN = Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+")

internal fun isValidPluginId(id: String): Boolean = id.matches(PLUGIN_ID_PATTERN)

/**
 * 语言标签匹配规则：完整匹配（zh-CN）→ 同主语言且文种相容的条目 → 默认值。
 * 与宿主 UI 现有三语（en/zh-CN/zh-TW）约定一致。
 *
 * **文种判据**（2026-10-07 修复「插件设置整片显示成繁体」）：插件清单的约定是「默认值 =
 * 插件母语文案，*Locales 只列需要覆盖的语言」——仓库内插件因此只声明 zh-TW/en，简体中文
 * 靠默认值。若主语言匹配直接取第一个 zh 条目，简体设备（zh-CN / zh-Hans*）会命中 zh-TW。
 * 因此：显式声明了**相反文种**的条目不参与匹配（简体设备不认 zh-TW，繁体设备不认 zh-CN），
 * 此时回落默认值；同文种的条目优先于无从判断文种的条目。
 */
internal fun localizedValue(
    languageTag: String,
    locales: Map<String, String>,
    fallback: String
): String {
    if (locales.isEmpty()) return fallback
    locales[languageTag]?.let { return it }
    val mainLanguage = languageTag.substringBefore('-')
    val candidates = locales.entries.filter { it.key.substringBefore('-') == mainLanguage }
    if (candidates.isEmpty()) return fallback
    val deviceScript = chineseScriptOf(languageTag)
    if (deviceScript != null) {
        candidates.firstOrNull { chineseScriptOf(it.key) == deviceScript }?.let { return it.value }
        // 文种中立的条目（如 "zh"）优于相反文种的条目。
        candidates.firstOrNull { chineseScriptOf(it.key) == null }?.let { return it.value }
        // 只剩显式相反文种 → 默认值（插件母语文案）比反向文种更贴近设备。
        return fallback
    }
    return candidates.first().value
}

/**
 * 中文文种（`Hans` / `Hant`）：显式 script 优先，否则按地区推断
 * （CN/SG/MY → Hans，TW/HK/MO → Hant）；非中文或无从判断返回 null。纯函数，可单测。
 */
internal fun chineseScriptOf(languageTag: String): String? {
    val parts = languageTag.split('-').filter { it.isNotEmpty() }
    if (parts.firstOrNull()?.lowercase() != "zh") return null
    for (part in parts.drop(1)) {
        when (part.lowercase()) {
            "hans" -> return "Hans"
            "hant" -> return "Hant"
        }
    }
    val region = parts.drop(1).lastOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
    return when (region) {
        "CN", "SG", "MY" -> "Hans"
        "TW", "HK", "MO" -> "Hant"
        else -> null
    }
}

/** manifest.json 解析与容错（宽松：未知字段忽略，类型不符按缺省处理）。 */
object PluginManifestCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun decode(text: String): PluginManifest? = runCatching {
        json.decodeFromString<PluginManifest>(text)
    }.getOrElse { error ->
        // 解析失败必须留痕，否则只会得到一句 "unparseable manifest.json"，无法定位字段名/类型问题。
        AppLog.e("PluginManifest", "decode failed", error)
        null
    }
}
