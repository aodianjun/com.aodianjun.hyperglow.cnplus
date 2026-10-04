package com.eza.hyperglow.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.plugin.PluginRuntime
import com.eza.hyperglow.plugin.PluginSettingData
import com.eza.hyperglow.plugin.PluginSettingGroupData
import com.eza.hyperglow.plugin.PluginSettingsStore
import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginSettingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「歌词增强」板块:列出已安装的歌词处理插件(提供 LyricProcessorExtension 的插件),
 * 每个插件进入独立设置页(页面形态)并可管理其缓存。
 *
 * 与插件管理页的分工:插件管理页负责安装/卸载/处理总开关;本板块按"功能"视角呈现
 * 歌词增强能力(AI 翻译、AMLL TTML 等),对应上游 HyperLyric 的「歌词增强」入口。
 */

/** 设置项按 manifest 分组拆分:先无组项,再按声明顺序的各分组。 */
private fun splitSettingsByGroup(
    settings: List<PluginSettingData>,
    groups: List<PluginSettingGroupData>
): Pair<List<PluginSettingData>, List<Pair<PluginSettingGroupData, List<PluginSettingData>>>> {
    val ungrouped = settings.filter { it.group == null }
    val grouped = groups.mapNotNull { group ->
        val members = settings.filter { it.group == group.id }
        if (members.isEmpty()) null else group to members
    }
    return ungrouped to grouped
}

@Composable
internal fun LyricEnhancementScreen(
    onBack: () -> Unit,
    onOpenPluginSettings: (String) -> Unit
) {
    val context = LocalContext.current
    val languageTag = currentLanguageTag(context)
    // 已加载且提供歌词处理器的插件即"歌词增强"插件;加载失败/纯缓存类插件不在此列。
    val plugins = remember { PluginRuntime.installed().filter { it.processors.isNotEmpty() } }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_lyric_enhancement),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 20.dp
            )
        ) {
            item {
                Text(
                    stringResource(R.string.summary_lyric_enhancement),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            if (plugins.isEmpty()) {
                item {
                    SettingsCard {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                stringResource(R.string.lyric_enhancement_empty),
                                fontSize = 15.sp
                            )
                            Text(
                                stringResource(R.string.lyric_enhancement_empty_summary),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(plugins, key = { it.manifest.id }) { plugin ->
                    val manifest = plugin.manifest
                    val versionLine = buildString {
                        if (manifest.version.isNotEmpty()) append("v${manifest.version}")
                        if (manifest.author.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(manifest.author)
                        }
                        if (isNotEmpty()) append(" · ")
                        append(
                            context.getString(
                                R.string.plugin_status_processors,
                                plugin.processors.size
                            )
                        )
                    }
                    SettingsCard {
                        ArrowPreference(
                            title = manifest.localizedName(languageTag),
                            summary = versionLine,
                            onClick = { onOpenPluginSettings(manifest.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PluginSettingsScreen(
    pluginId: String,
    onBack: () -> Unit,
    onOpenCache: (String) -> Unit
) {
    val context = LocalContext.current
    val languageTag = currentLanguageTag(context)
    val plugin = remember(pluginId) { PluginRuntime.installed(pluginId) }
    val manifest = plugin?.manifest
    var revision by remember(pluginId) { mutableStateOf(0) }
    var editSetting by remember(pluginId) { mutableStateOf<PluginSettingData?>(null) }

    fun writeSetting(setting: PluginSettingData, put: () -> Unit) {
        put()
        if (setting.type == PluginSettingType.SWITCH &&
            manifest != null &&
            PluginSettingsStore.getBoolean(context, manifest.id, setting)
        ) {
            // conflictsWith 语义(HyperLyric):开启一个开关时关闭所有与其冲突的开关。
            setting.conflictsWith.forEach { conflictKey ->
                manifest.settings.firstOrNull {
                    it.key == conflictKey && it.type == PluginSettingType.SWITCH
                }?.let { conflict ->
                    PluginSettingsStore.putBoolean(context, manifest.id, conflict.key, false)
                }
            }
        }
        if (manifest != null) PluginRuntime.notifyConfigChanged(manifest.id)
        revision++
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = manifest?.localizedName(languageTag) ?: pluginId,
                onBack = onBack
            )
        }
    ) { innerPadding ->
        if (manifest == null || plugin?.plugin == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    bottom = innerPadding.calculateBottomPadding() + 20.dp
                )
            ) {
                item {
                    SettingsCard {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                stringResource(
                                    R.string.plugin_load_failed,
                                    plugin?.loadError ?: ""
                                ),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            return@Scaffold
        }

        val (ungrouped, grouped) = splitSettingsByGroup(manifest.settings, manifest.settingGroups)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 20.dp
            )
        ) {
            if (manifest.activationSettingKey != null) {
                var activated by remember(manifest.id) {
                    mutableStateOf(PluginSettingsStore.isActivated(context, manifest))
                }
                item {
                    SettingsCard {
                        SwitchPreference(
                            activated,
                            { enabled ->
                                PluginSettingsStore.putBoolean(
                                    context,
                                    manifest.id,
                                    manifest.activationSettingKey,
                                    enabled
                                )
                                if (enabled) PluginRuntime.notifyEnabled(manifest.id, enabled)
                                PluginRuntime.notifyConfigChanged(manifest.id)
                                activated = enabled
                            },
                            stringResource(R.string.plugin_setting_enabled)
                        )
                    }
                }
            }

            if (ungrouped.isNotEmpty()) {
                item {
                    SettingsCard {
                        ungrouped.forEach { setting ->
                            key(setting.key, revision) {
                                PluginSettingRow(
                                    pluginId = manifest.id,
                                    setting = setting,
                                    languageTag = languageTag,
                                    onEdit = { editSetting = setting },
                                    onWrite = { put -> writeSetting(setting, put) }
                                )
                            }
                        }
                    }
                }
            }

            grouped.forEach { (group, members) ->
                item {
                    SmallTitle(
                        text = manifest.settingGroupTitle(group.id, languageTag) ?: group.title
                    )
                }
                item {
                    SettingsCard {
                        members.forEach { setting ->
                            key(setting.key, revision) {
                                PluginSettingRow(
                                    pluginId = manifest.id,
                                    setting = setting,
                                    languageTag = languageTag,
                                    onEdit = { editSetting = setting },
                                    onWrite = { put -> writeSetting(setting, put) }
                                )
                            }
                        }
                    }
                }
            }

            if (manifest.cacheScopes.isNotEmpty()) {
                item { SmallTitle(text = stringResource(R.string.plugin_section_cache)) }
                item {
                    SettingsCard {
                        ArrowPreference(
                            title = stringResource(R.string.plugin_action_manage_cache),
                            summary = formatBytes(PluginRuntime.cacheSizeBytes(manifest.id)),
                            onClick = { onOpenCache(manifest.id) }
                        )
                    }
                }
            }
        }
    }

    editSetting?.let { setting ->
        PluginSettingEditDialog(
            pluginId = pluginId,
            setting = setting,
            languageTag = languageTag,
            onDismiss = { editSetting = null },
            onWrite = { put -> writeSetting(setting, put) }
        )
    }
}

@Composable
internal fun PluginCacheScreen(
    pluginId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val languageTag = currentLanguageTag(context)
    val plugin = remember(pluginId) { PluginRuntime.installed(pluginId) }
    var revision by remember(pluginId) { mutableStateOf(0) }
    var entries by remember(pluginId) { mutableStateOf<List<PluginCacheEntry>?>(null) }
    var unavailable by remember(pluginId) { mutableStateOf(false) }
    var detailEntry by remember(pluginId) { mutableStateOf<PluginCacheEntry?>(null) }
    var showClearAll by remember(pluginId) { mutableStateOf(false) }

    LaunchedEffect(pluginId, revision) {
        entries = null
        unavailable = false
        val result = withContext(Dispatchers.IO) { PluginRuntime.listCacheEntries(pluginId) }
        if (result == null) unavailable = true else entries = result
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.plugin_cache_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 20.dp
            )
        ) {
            item {
                Text(
                    plugin?.manifest?.localizedName(languageTag) ?: pluginId,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            val current = entries
            when {
                unavailable -> {
                    item {
                        SettingsCard {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text(
                                    stringResource(R.string.plugin_cache_unavailable),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                    // 未提供缓存管理的插件仍允许清空宿主缓存(与插件管理页原行为一致)。
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.plugin_action_clear_cache),
                                summary = formatBytes(PluginRuntime.cacheSizeBytes(pluginId)),
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            PluginRuntime.clearCache(pluginId)
                                        }
                                        revision++
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.plugin_toast_cache_cleared),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                }
                current == null -> {
                    item {
                        Text(
                            stringResource(R.string.about_content_loading),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                        )
                    }
                }
                current.isEmpty() -> {
                    item {
                        SettingsCard {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text(
                                    stringResource(R.string.plugin_cache_empty),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
                else -> {
                    item { SmallTitle(text = stringResource(R.string.plugin_cache_section_entries)) }
                    items(current, key = { it.id }) { entry ->
                        SettingsCard {
                            ArrowPreference(
                                title = entry.title,
                                summary = cacheEntrySummary(entry),
                                onClick = { detailEntry = entry }
                            )
                        }
                    }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.plugin_cache_clear_all),
                                summary = formatBytes(PluginRuntime.cacheSizeBytes(pluginId)),
                                onClick = { showClearAll = true }
                            )
                        }
                    }
                }
            }
        }
    }

    detailEntry?.let { entry ->
        WindowDialog(
            title = entry.title,
            summary = entry.summary,
            show = true,
            onDismissRequest = { detailEntry = null }
        ) {
            Column(Modifier.dialogScrollable()) {
                CacheDetailRow(
                    stringResource(R.string.plugin_cache_field_size),
                    formatBytes(entry.sizeBytes ?: 0L)
                )
                CacheDetailRow(
                    stringResource(R.string.plugin_cache_field_updated),
                    formatCacheTimestamp(entry.updatedAtEpochMs)
                        ?: stringResource(R.string.plugin_cache_unknown_time)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { detailEntry = null }
                    )
                    Spacer(Modifier.width(16.dp))
                    TextButton(
                        text = stringResource(R.string.plugin_cache_delete_entry),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            detailEntry = null
                            scope.launch {
                                val removed = withContext(Dispatchers.IO) {
                                    PluginRuntime.clearCacheEntry(pluginId, entry.id)
                                }
                                revision++
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (removed) R.string.plugin_cache_toast_entry_deleted
                                        else R.string.plugin_cache_toast_entry_missing
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showClearAll) {
        WindowDialog(
            title = stringResource(R.string.plugin_cache_clear_confirm_title),
            summary = stringResource(R.string.plugin_cache_clear_confirm_summary),
            show = true,
            onDismissRequest = { showClearAll = false }
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showClearAll = false }
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.plugin_cache_clear_all),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showClearAll = false
                        scope.launch {
                            withContext(Dispatchers.IO) { PluginRuntime.clearCache(pluginId) }
                            revision++
                            Toast.makeText(
                                context,
                                context.getString(R.string.plugin_toast_cache_cleared),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }
}

/** 缓存条目副行:插件摘要 + 大小 · 更新时间(插件未提供摘要时只显示后者)。 */
private fun cacheEntrySummary(entry: PluginCacheEntry): String {
    val meta = buildList {
        entry.sizeBytes?.let { add(formatBytes(it)) }
        formatCacheTimestamp(entry.updatedAtEpochMs)?.let { add(it) }
    }.joinToString(" · ")
    return listOfNotNull(entry.summary?.takeIf { it.isNotBlank() }, meta.takeIf { it.isNotEmpty() })
        .joinToString("\n")
}

@Composable
private fun CacheDetailRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            title,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(value, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 缓存时间戳格式化;空/非法值返回 null 由调用方回落占位文案。 */
private fun formatCacheTimestamp(epochMs: Long?): String? {
    val value = epochMs ?: return null
    if (value <= 0L) return null
    return runCatching {
        SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date(value))
    }.getOrNull()
}
