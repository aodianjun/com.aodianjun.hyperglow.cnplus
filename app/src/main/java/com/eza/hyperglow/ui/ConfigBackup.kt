package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderPreferences
import kotlinx.serialization.json.*

/** 配置导出/导入:把 aod_render SharedPreferences 序列化为 JSON 备份与还原。 */

private const val CONFIG_BACKUP_FORMAT = "hyperglow-config"
private const val CONFIG_BACKUP_VERSION = 1

/** 配置备份文件的最大字节数,防止导入异常大文件。 */
internal const val MAX_CONFIG_FILE_BYTES = 512 * 1024

internal fun exportAllConfig(context: android.content.Context): String {
    val prefs = context.getSharedPreferences(AodRenderPreferences.PREFS, 0)
    return buildJsonObject {
        put("format", JsonPrimitive(CONFIG_BACKUP_FORMAT))
        put("version", JsonPrimitive(CONFIG_BACKUP_VERSION))
        putJsonObject("preferences") {
            prefs.all.forEach { (key, value) ->
                when (value) {
                    is Boolean -> put(key, JsonPrimitive(value))
                    is Int -> put(key, JsonPrimitive(value))
                    is Long -> put(key, JsonPrimitive(value))
                    is Float -> put(key, JsonPrimitive(value))
                    is String -> put(key, JsonPrimitive(value))
                    is Set<*> -> putJsonArray(key) {
                        value.forEach { add(JsonPrimitive(it.toString())) }
                    }
                }
            }
        }
    }.toString()
}

internal fun importAllConfig(context: android.content.Context, raw: String): Boolean = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["format"]?.jsonPrimitive?.content != CONFIG_BACKUP_FORMAT) return false
    val preferences = root["preferences"]?.jsonObject ?: return false
    val editor = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
    preferences.forEach { (key, element) ->
        when (element) {
            is JsonPrimitive -> when {
                element.booleanOrNull != null -> editor.putBoolean(key, element.boolean)
                element.intOrNull != null -> editor.putInt(key, element.int)
                element.longOrNull != null -> editor.putLong(key, element.long)
                else -> {
                    val float = element.content.toFloatOrNull()
                    if (float != null) editor.putFloat(key, float) else editor.putString(key, element.content)
                }
            }
            is JsonArray -> editor.putStringSet(
                key,
                element.map { it.jsonPrimitive.content }.toSet()
            )
            else -> Unit
        }
    }
    editor.commit()
}.getOrDefault(false)
