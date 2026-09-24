package com.eza.hyperglow.plugin

import android.content.Context
import android.content.SharedPreferences
import com.eza.hyperglow.AppLog
import com.lidesheng.hyperlyric.plugin.api.PluginConfig

/**
 * 每插件一份的设置存储（SharedPreferences `plugin_settings_<id>`）。
 *
 * 与 HyperLyric 的存储语义一致：
 * - 插件经 [PluginConfig] 只读访问（onLoad/onConfigChanged 时取快照）；
 * - 宿主 UI 直接读写（设置页渲染、激活开关）；
 * - `backup=false` 的键不参与配置导出（当前导出仅覆盖 `aod_render`，插件设置
 *   存独立文件天然隔离，后续若做统一备份再按该标记过滤）。
 */
object PluginSettingsStore {
    private const val TAG = "PluginSettings"

    private fun prefs(context: Context, pluginId: String): SharedPreferences =
        context.getSharedPreferences("plugin_settings_$pluginId", Context.MODE_PRIVATE)

    fun getBoolean(context: Context, pluginId: String, setting: PluginSettingData): Boolean =
        prefs(context, pluginId).getBoolean(setting.key, setting.defaultBoolean())

    fun getString(context: Context, pluginId: String, setting: PluginSettingData): String =
        prefs(context, pluginId).getString(setting.key, null) ?: setting.defaultString()

    fun getFloat(context: Context, pluginId: String, setting: PluginSettingData): Float =
        prefs(context, pluginId).getFloat(setting.key, setting.defaultFloat())

    fun getStringSet(context: Context, pluginId: String, setting: PluginSettingData): Set<String> {
        val stored = prefs(context, pluginId).getStringSet(setting.key, null)
        if (stored != null) return stored
        val default = setting.defaultString()
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return default
    }

    fun putBoolean(context: Context, pluginId: String, key: String, value: Boolean) {
        prefs(context, pluginId).edit().putBoolean(key, value).apply()
        AppLog.i(TAG, "put $pluginId $key=$value")
    }

    fun putString(context: Context, pluginId: String, key: String, value: String) {
        prefs(context, pluginId).edit().putString(key, value).apply()
        // 设置值可能很长（含换行/正文），只记长度，避免污染日志。
        AppLog.i(TAG, "put $pluginId $key (chars=${value.length})")
    }

    fun putFloat(context: Context, pluginId: String, key: String, value: Float) {
        prefs(context, pluginId).edit().putFloat(key, value).apply()
        AppLog.i(TAG, "put $pluginId $key=$value")
    }

    fun putStringSet(context: Context, pluginId: String, key: String, value: Set<String>) {
        prefs(context, pluginId).edit().putStringSet(key, value).apply()
        AppLog.i(TAG, "put $pluginId $key (size=${value.size} values=$value)")
    }

    fun clear(context: Context, pluginId: String) {
        prefs(context, pluginId).edit().clear().apply()
        AppLog.i(TAG, "cleared settings for $pluginId")
    }

    /** 插件激活判定：manifest 声明了 activationSettingKey 则读该键，未声明视为始终激活。 */
    fun isActivated(context: Context, manifest: PluginManifest): Boolean {
        val activationKey = manifest.activationSettingKey ?: return true
        val setting = manifest.settings.firstOrNull { it.key == activationKey }
            ?: return prefs(context, manifest.id).getBoolean(activationKey, false)
        return getBoolean(context, manifest.id, setting)
    }

    /** 把 manifest 里的 [PluginConfig] 视图绑定到具体插件存储。 */
    fun asPluginConfig(context: Context, pluginId: String): PluginConfig =
        HostPluginConfig(context.applicationContext, pluginId)
}

/** [PluginConfig] 的宿主实现：读插件专属 SharedPreferences，按 manifest 默认值兜底。 */
internal class HostPluginConfig(
    private val context: Context,
    private val pluginId: String
) : PluginConfig {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        context.getSharedPreferences("plugin_settings_$pluginId", Context.MODE_PRIVATE)
            .getBoolean(key, defaultValue)

    override fun getString(key: String, defaultValue: String?): String? =
        context.getSharedPreferences("plugin_settings_$pluginId", Context.MODE_PRIVATE)
            .getString(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long =
        context.getSharedPreferences("plugin_settings_$pluginId", Context.MODE_PRIVATE)
            .getLong(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Float =
        context.getSharedPreferences("plugin_settings_$pluginId", Context.MODE_PRIVATE)
            .getFloat(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        context.getSharedPreferences("plugin_settings_$pluginId", Context.MODE_PRIVATE)
            .getStringSet(key, defaultValue) ?: defaultValue
}
