package com.eza.hyperglow.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.bridge.SpicyBridgeDocumentStore
import com.eza.hyperglow.plugin.PluginInstaller
import com.eza.hyperglow.plugin.PluginPipeline
import com.eza.hyperglow.plugin.PluginRuntime
import com.eza.hyperglow.plugin.PluginSettingsStore
import com.eza.hyperglow.plugin.PluginSettingData
import com.lidesheng.hyperlyric.plugin.api.PluginSettingInputType
import com.lidesheng.hyperlyric.plugin.api.PluginSettingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale

/**
 * 插件管理页（HyperLyric 兼容插件的宿主 UI）。
 *
 * 职责：
 * - 总开关（plugin_processing_enabled）+ ZIP 安装入口 + 已装插件列表；
 * - 每个插件：激活开关（activationSettingKey）、manifest 声明式设置渲染、
 *   缓存清理、卸载确认；
 * - 配置写入后实时同步 onConfigChanged/onEnable（下一首歌生效）；
 *   安装/卸载等代码变更即时 reloadAll（旧 ClassLoader 无法卸载，
 *   彻底清理需重启 App——对应 HyperLyric 在 SystemUI 里需重启 SystemUI）。
 *
 * 对话框状态全部提升到本函数：对话框不能挂在 LazyColumn 的 item 里，
 * 否则条目滚出可视区被回收时对话框会被意外关闭。
 */
@Composable
internal fun PluginManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf(PluginRuntime.installed()) }
    var processingEnabled by remember {
        mutableStateOf(AodRenderPreferences.read(context).pluginProcessingEnabled)
    }
    var openSettingsPluginId by remember { mutableStateOf<String?>(null) }
    var uninstallPluginId by remember { mutableStateOf<String?>(null) }
    // 管线输入状态(与 PluginPipeline.maybeProcess 同源):总开关开启时向用户解释
    // 「为什么配好了插件却没有动静」——逐行源没有整首文档,v1 不进插件链。
    val activeProducerState by collectActiveState()
    val spicyDocument by SpicyBridgeDocumentStore.state.collectAsState()

    fun refresh() {
        plugins = PluginRuntime.installed()
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (manifest, error) = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readNBytes(PluginInstaller.MAX_ZIP_BYTES.toInt() + 1)
                    } ?: error("plugin file unavailable")
                }.getOrElse { return@withContext null to "read failed: ${it.message}" }
                if (bytes.size > PluginInstaller.MAX_ZIP_BYTES) {
                    return@withContext null to "archive too large"
                }
                PluginInstaller.install(context, bytes)
            }
            if (manifest != null) {
                PluginRuntime.reloadAll()
                refresh()
                Toast.makeText(
                    context,
                    context.getString(R.string.plugin_toast_installed, manifest.name),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.plugin_toast_install_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun pickPluginZip() {
        installLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.plugin_management_title),
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←") }
                }
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
            item { SmallTitle(text = stringResource(R.string.section_plugin_processing)) }
            item {
                SettingsCard {
                    SwitchPreference(
                        processingEnabled,
                        { enabled ->
                            val saved = context
                                .getSharedPreferences(AodRenderPreferences.PREFS, 0)
                                .edit()
                                .putBoolean(
                                    AodRenderPreferences.PLUGIN_PROCESSING_ENABLED,
                                    enabled
                                )
                                .commit()
                            if (saved) {
                                processingEnabled = enabled
                                if (enabled) {
                                    PluginPipeline.requestProcess()
                                } else {
                                    PluginPipeline.invalidate()
                                }
                            }
                        },
                        stringResource(R.string.setting_plugin_processing_enabled),
                        summary = stringResource(R.string.summary_plugin_processing_enabled)
                    )
                    ArrowPreference(
                        title = stringResource(R.string.plugin_action_install),
                        summary = stringResource(R.string.summary_plugin_action_install),
                        onClick = { pickPluginZip() }
                    )
                    val inputState = PluginPipeline.pipelineInputState(
                        activeProducerState, spicyDocument
                    )
                    if (processingEnabled && plugins.isNotEmpty() &&
                        inputState != PluginPipeline.PipelineInputState.IDLE_NO_SOURCE
                    ) {
                        val statusText: String? = when (inputState) {
                            PluginPipeline.PipelineInputState.IDLE_NO_DOCUMENT ->
                                stringResource(
                                    R.string.plugin_pipeline_status_no_document,
                                    activeProducerState?.producerId ?: ""
                                )
                            PluginPipeline.PipelineInputState.SOURCE_MISMATCH ->
                                stringResource(R.string.plugin_pipeline_status_mismatch)
                            PluginPipeline.PipelineInputState.READY ->
                                stringResource(
                                    R.string.plugin_pipeline_status_ready,
                                    spicyDocument?.rows?.size ?: 0
                                )
                            PluginPipeline.PipelineInputState.IDLE_NO_SOURCE -> null
                        }
                        if (statusText != null) {
                            Text(
                                text = statusText,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                            )
                        }
                    }
                }
            }
            if (plugins.isEmpty()) {
                item {
                    SettingsCard {
                        Text(
                            text = stringResource(R.string.plugin_empty_hint),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }
                }
            } else {
                item { SmallTitle(text = stringResource(R.string.section_installed_plugins)) }
                items(plugins, key = { it.manifest.id }) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        onOpenSettings = { openSettingsPluginId = plugin.manifest.id },
                        onRequestUninstall = { uninstallPluginId = plugin.manifest.id },
                        onChanged = { refresh() }
                    )
                }
            }
        }
    }

    // 设置对话框：宿主状态提升，插件列表刷新后按 id 重新取最新实例。
    openSettingsPluginId?.let { pluginId ->
        val settingsPlugin = PluginRuntime.installed(pluginId)
        if (settingsPlugin != null) {
            PluginSettingsDialog(
                plugin = settingsPlugin,
                onDismiss = { openSettingsPluginId = null }
            )
        } else {
            // 插件在对话框打开期间被卸载：清掉悬空的 id，避免重装后对话框意外复现。
            LaunchedEffect(pluginId) { openSettingsPluginId = null }
        }
    }

    // 卸载确认对话框。
    uninstallPluginId?.let { pluginId ->
        val plugin = plugins.firstOrNull { it.manifest.id == pluginId }
        if (plugin == null) {
            uninstallPluginId = null
        } else {
            WindowDialog(
                title = stringResource(R.string.plugin_uninstall_confirm_title),
                summary = stringResource(
                    R.string.plugin_uninstall_confirm_summary,
                    plugin.manifest.localizedName(currentLanguageTag(context))
                ),
                show = true,
                onDismissRequest = { uninstallPluginId = null }
            ) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { uninstallPluginId = null }
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.action_uninstall),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = {
                                val removed = PluginInstaller.uninstall(context, pluginId)
                                PluginRuntime.reloadAll()
                                PluginSettingsStore.clear(context, pluginId)
                                refresh()
                                uninstallPluginId = null
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (removed) R.string.plugin_toast_uninstalled
                                        else R.string.plugin_toast_uninstall_failed
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 单个插件的卡片：名称/作者/版本、加载状态、激活开关、设置/缓存/卸载入口。 */
@Composable
private fun PluginCard(
    plugin: PluginRuntime.LoadedPlugin,
    onOpenSettings: () -> Unit,
    onRequestUninstall: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val languageTag = currentLanguageTag(context)
    val manifest = plugin.manifest
    var cacheRevision by remember(manifest.id) { mutableStateOf(0) }

    SettingsCard {
        Column(Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp, bottom = 2.dp)) {
            Text(
                text = manifest.localizedName(languageTag),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            val versionLine = buildString {
                if (manifest.version.isNotEmpty()) append("v${manifest.version}")
                if (manifest.author.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(manifest.author)
                }
            }
            if (versionLine.isNotEmpty()) {
                Text(
                    text = versionLine,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                )
            }
            Text(
                text = when {
                    plugin.plugin == null ->
                        stringResource(R.string.plugin_load_failed, plugin.loadError ?: "")
                    !plugin.isActivated(context) ->
                        stringResource(R.string.plugin_status_inactive)
                    plugin.processors.isEmpty() ->
                        stringResource(R.string.plugin_status_no_processors)
                    else -> stringResource(R.string.plugin_status_processors, plugin.processors.size)
                },
                fontSize = 13.sp,
                color = if (plugin.plugin == null) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                },
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
        }
        if (manifest.activationSettingKey != null && plugin.plugin != null) {
            var activated by remember(manifest.id) {
                mutableStateOf(PluginSettingsStore.isActivated(context, manifest))
            }
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
        if (manifest.settings.isNotEmpty()) {
            ArrowPreference(
                title = stringResource(R.string.plugin_action_settings),
                onClick = onOpenSettings,
                enabled = plugin.plugin != null
            )
        }
        if (manifest.cacheScopes.isNotEmpty()) {
            val cacheBytes = remember(manifest.id, cacheRevision) {
                PluginRuntime.cacheSizeBytes(manifest.id)
            }
            ArrowPreference(
                title = stringResource(R.string.plugin_action_clear_cache, formatBytes(cacheBytes)),
                onClick = {
                    PluginRuntime.clearCache(manifest.id)
                    cacheRevision++
                    Toast.makeText(
                        context,
                        context.getString(R.string.plugin_toast_cache_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                    onChanged()
                },
                enabled = plugin.plugin != null
            )
        }
        ArrowPreference(
            title = stringResource(R.string.plugin_action_uninstall),
            onClick = onRequestUninstall
        )
    }
}

/**
 * 插件设置对话框：按 manifest 声明渲染全部设置项。
 * [revision] 在每次写入后自增，整棵树重读 SharedPreferences。
 */
@Composable
private fun PluginSettingsDialog(
    plugin: PluginRuntime.LoadedPlugin,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val languageTag = currentLanguageTag(context)
    val manifest = plugin.manifest
    var revision by remember(manifest.id) { mutableStateOf(0) }
    var editSetting by remember(manifest.id) { mutableStateOf<PluginSettingData?>(null) }

    fun writeSetting(setting: PluginSettingData, put: () -> Unit) {
        put()
        if (setting.type == PluginSettingType.SWITCH &&
            PluginSettingsStore.getBoolean(context, manifest.id, setting)
        ) {
            // conflictsWith 语义（HyperLyric）：开启一个开关时关闭所有与其冲突的开关。
            setting.conflictsWith.forEach { conflictKey ->
                manifest.settings.firstOrNull {
                    it.key == conflictKey && it.type == PluginSettingType.SWITCH
                }?.let { conflict ->
                    PluginSettingsStore.putBoolean(context, manifest.id, conflict.key, false)
                }
            }
        }
        PluginRuntime.notifyConfigChanged(manifest.id)
        revision++
    }

    WindowDialog(
        title = manifest.localizedName(languageTag),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
        ) {
            manifest.settings.forEach { setting ->
                // revision 参与组合键：写入后强制本行重读存储。
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

    editSetting?.let { setting ->
        PluginSettingEditDialog(
            pluginId = manifest.id,
            setting = setting,
            languageTag = languageTag,
            onDismiss = { editSetting = null },
            onWrite = { put -> writeSetting(setting, put) }
        )
    }
}

/** 单条设置的宿主渲染，按 [PluginSettingType] 分派到 miuix 偏好组件。 */
@Composable
private fun PluginSettingRow(
    pluginId: String,
    setting: PluginSettingData,
    languageTag: String,
    onEdit: () -> Unit,
    onWrite: (put: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val title = setting.localizedTitle(languageTag)
    val summary = setting.localizedSummary(languageTag)

    when (setting.type) {
        PluginSettingType.SWITCH -> {
            val checked = PluginSettingsStore.getBoolean(context, pluginId, setting)
            SwitchPreference(
                checked,
                { enabled ->
                    onWrite {
                        PluginSettingsStore.putBoolean(context, pluginId, setting.key, enabled)
                    }
                },
                title,
                summary = summary
            )
        }
        PluginSettingType.SLIDER -> {
            val min = (setting.min ?: 0.0).toFloat()
            val max = (setting.max ?: 100.0).toFloat()
            if (max > min) {
                val value = PluginSettingsStore.getFloat(context, pluginId, setting)
                    .coerceIn(min, max)
                val step = setting.step?.toFloat()?.takeIf { it > 0f }
                val steps = if (step != null) {
                    (((max - min) / step).toInt() - 1).coerceAtLeast(0)
                } else {
                    0
                }
                SliderPreference(
                    value = value,
                    onValueChange = { next ->
                        onWrite {
                            PluginSettingsStore.putFloat(context, pluginId, setting.key, next)
                        }
                    },
                    title = title,
                    summary = summary,
                    valueText = formatSettingNumber(value),
                    valueRange = min..max,
                    steps = steps
                )
            } else {
                ArrowPreference(title = title, summary = summary, onClick = {})
            }
        }
        PluginSettingType.SELECT -> {
            val selected = PluginSettingsStore.getString(context, pluginId, setting)
            val selectedLabel = setting.options
                .firstOrNull { it.value == selected }
                ?.localizedLabel(languageTag)
                ?: selected.ifEmpty { null }
                ?: setting.localizedEmptyValueSummary(languageTag)
                ?: stringResource(R.string.plugin_setting_empty_value)
            ArrowPreference(
                title = title,
                summary = listOfNotNull(summary, selectedLabel).joinToString("\n"),
                onClick = onEdit
            )
        }
        PluginSettingType.MULTI_SELECT -> {
            val selected = PluginSettingsStore.getStringSet(context, pluginId, setting)
            val selectedLabel = setting.options
                .filter { it.value in selected }
                .joinToString(", ") { it.localizedLabel(languageTag) }
                .ifEmpty {
                    setting.localizedEmptyValueSummary(languageTag)
                        ?: stringResource(R.string.plugin_setting_empty_value)
                }
            ArrowPreference(
                title = title,
                summary = listOfNotNull(summary, selectedLabel).joinToString("\n"),
                onClick = onEdit
            )
        }
        PluginSettingType.TEXT, PluginSettingType.PASSWORD -> {
            val value = PluginSettingsStore.getString(context, pluginId, setting)
            val valueLabel = when {
                setting.type == PluginSettingType.PASSWORD && value.isNotEmpty() -> "•••"
                value.isNotEmpty() -> value
                else -> setting.localizedEmptyValueSummary(languageTag)
                    ?: stringResource(R.string.plugin_setting_empty_value)
            }
            ArrowPreference(
                title = title,
                summary = listOfNotNull(summary, valueLabel).joinToString("\n"),
                onClick = onEdit
            )
        }
        PluginSettingType.NUMBER -> {
            val value = PluginSettingsStore.getFloat(context, pluginId, setting)
            ArrowPreference(
                title = title,
                summary = listOfNotNull(summary, formatSettingNumber(value))
                    .joinToString("\n"),
                onClick = onEdit
            )
        }
        // ACTION 型设置在 HyperLyric 上游依赖其自有 UI 框架的点击回调，本 API 副本
        // 未暴露回调接口；宿主以只读行展示，保持 manifest 语义可见。
        PluginSettingType.ACTION -> {
            ArrowPreference(
                title = title,
                summary = summary ?: stringResource(R.string.plugin_setting_action_unsupported),
                onClick = {},
                enabled = false
            )
        }
        null -> Unit
    }
}

/** 标量/单选/多选设置的编辑子对话框。 */
@Composable
private fun PluginSettingEditDialog(
    pluginId: String,
    setting: PluginSettingData,
    languageTag: String,
    onDismiss: () -> Unit,
    onWrite: (put: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val title = setting.localizedTitle(languageTag)

    when (setting.type) {
        PluginSettingType.SELECT -> {
            val selected = PluginSettingsStore.getString(context, pluginId, setting)
            WindowDialog(
                title = title,
                summary = setting.localizedDialogSummary(languageTag),
                show = true,
                onDismissRequest = onDismiss
            ) {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    setting.options.forEach { option ->
                        RadioButtonPreference(
                            option.localizedLabel(languageTag),
                            option.value == selected,
                            {
                                onWrite {
                                    PluginSettingsStore.putString(
                                        context, pluginId, setting.key, option.value
                                    )
                                }
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
        PluginSettingType.MULTI_SELECT -> {
            // 本地可变快照：多选不关对话框，切换即时反馈；写入仍走 onWrite 落盘。
            var selected by remember(setting.key) {
                mutableStateOf(PluginSettingsStore.getStringSet(context, pluginId, setting))
            }
            WindowDialog(
                title = title,
                summary = setting.localizedDialogSummary(languageTag),
                show = true,
                onDismissRequest = onDismiss
            ) {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    setting.options.forEach { option ->
                        SwitchPreference(
                            option.value in selected,
                            { enabled ->
                                val next = if (enabled) {
                                    selected + option.value
                                } else {
                                    selected - option.value
                                }
                                selected = next
                                onWrite {
                                    PluginSettingsStore.putStringSet(
                                        context, pluginId, setting.key, next
                                    )
                                }
                            },
                            option.localizedLabel(languageTag)
                        )
                    }
                }
            }
        }
        PluginSettingType.TEXT, PluginSettingType.PASSWORD, PluginSettingType.NUMBER -> {
            val isNumber = setting.type == PluginSettingType.NUMBER
            val storedText = if (isNumber) {
                formatSettingNumber(PluginSettingsStore.getFloat(context, pluginId, setting))
            } else {
                PluginSettingsStore.getString(context, pluginId, setting)
            }
            var text by remember(setting.key) { mutableStateOf(storedText) }
            val numericInput = isNumber ||
                setting.inputType == PluginSettingInputType.NUMBER
            WindowDialog(
                title = title,
                summary = setting.localizedDialogSummary(languageTag),
                show = true,
                onDismissRequest = onDismiss
            ) {
                Column {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        keyboardOptions = if (numericInput) {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else {
                            KeyboardOptions.Default
                        },
                        decorationBox = { input ->
                            androidx.compose.foundation.layout.Box {
                                if (text.isEmpty()) {
                                    Text(
                                        text = setting.localizedEmptyValueSummary(languageTag)
                                            ?: stringResource(R.string.plugin_setting_empty_value),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        fontSize = 16.sp
                                    )
                                }
                                input()
                            }
                        }
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            modifier = Modifier.weight(1f),
                            onClick = onDismiss
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.action_save),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = {
                                if (isNumber) {
                                    val parsed = text.trim().toFloatOrNull()
                                    if (parsed == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.plugin_toast_invalid_number
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@TextButton
                                    }
                                    onWrite {
                                        PluginSettingsStore.putFloat(
                                            context, pluginId, setting.key, parsed
                                        )
                                    }
                                } else {
                                    onWrite {
                                        PluginSettingsStore.putString(
                                            context, pluginId, setting.key, text
                                        )
                                    }
                                }
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
        else -> onDismiss()
    }
}

/** App 当前语言标签（per-app locale 优先），匹配 manifest 的 *Locales 键。 */
internal fun currentLanguageTag(context: Context): String =
    context.resources.configuration.locales[0].toLanguageTag()

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024L ->
        String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

private fun formatSettingNumber(value: Float): String =
    if (value == value.toLong().toFloat()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
