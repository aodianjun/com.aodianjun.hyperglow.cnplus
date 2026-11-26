package com.eza.hyperglow.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.SceneCompiler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 配置预设列表（参考 HyperIsland 的 PresetConfigPage，按本应用设置面收敛）：
 * 顶部「新建」把当前整套配置快照成分节预设；点卡片进入详情，可勾选分节、
 * 预览外观与逐项差异后再应用。
 *
 * 与配置备份的关系：预设是一份裁剪过的备份信封，存/取都走 [ConfigPresetStore]，
 * 不写进备份文件本身（避免备份套备份）。
 */
@Composable
internal fun ConfigPresetScreen(
    refreshKey: Int,
    onBack: () -> Unit,
    onOpenPreset: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 直接读（不做 remember）：上传成功后登录名会被写回，列表里的「我的」徽标要跟着变。
    val hub = PresetHubPreferences.read(context)
    var presets by remember { mutableStateOf(ConfigPresetStore.load(context)) }
    var cloud by remember(hub.repo, hub.tag) { mutableStateOf<CloudPresetManifest?>(null) }
    var cloudLoading by remember(hub.repo, hub.tag) { mutableStateOf(false) }
    var cloudError by remember(hub.repo, hub.tag) { mutableStateOf<String?>(null) }
    var cloudFetchedAt by remember(hub.repo, hub.tag) {
        mutableStateOf(PresetHubStore.fetchedAt(context, hub))
    }
    // 本机匿名上传过的云端 id；决定卡片「我的」徽标。
    var ownedCloudIds by remember {
        mutableStateOf(PresetHubPreferences.ownedCloudIds(context))
    }
    var showCreate by remember { mutableStateOf(false) }
    var showHubSettings by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val query = searchQuery.trim()
    val loadedCloud = cloud
    val cloudPresets = loadedCloud?.presets.orEmpty()
    val filteredLocal = remember(presets, query) {
        if (query.isBlank()) {
            presets
        } else {
            presets.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.note.contains(query, ignoreCase = true)
            }
        }
    }
    val filteredCloud = remember(cloudPresets, query) {
        if (query.isBlank()) {
            cloudPresets
        } else {
            cloudPresets.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.note.contains(query, ignoreCase = true) ||
                    it.author.contains(query, ignoreCase = true)
            }
        }
    }

    suspend fun refreshCloud() {
        if (cloudLoading) return
        cloudLoading = true
        cloudError = null
        val config = PresetHubPreferences.read(context)
        PresetHubClient.fetch(config)
            .onSuccess { manifest ->
                if (PresetHubStore.save(context, config, manifest)) {
                    cloud = manifest
                    cloudFetchedAt = System.currentTimeMillis()
                } else {
                    cloudError = context.getString(R.string.preset_hub_error_too_large)
                }
            }
            .onFailure { cloudError = hubFailureText(context, it) }
        cloudLoading = false
    }

    // 详情页删除/上传后返回时，列表可能仍以 remembered 旧值组合着；由宿主自增 refreshKey
    // 触发重读本地预设与云端缓存。
    LaunchedEffect(refreshKey, hub.repo, hub.tag) {
        presets = ConfigPresetStore.load(context)
        cloud = withContext(Dispatchers.IO) { PresetHubStore.load(context, hub) }
        cloudFetchedAt = PresetHubStore.fetchedAt(context, hub)
        ownedCloudIds = PresetHubPreferences.ownedCloudIds(context)
    }
    LaunchedEffect(hub.repo, hub.tag) {
        cloud = withContext(Dispatchers.IO) { PresetHubStore.load(context, hub) }
        cloudFetchedAt = PresetHubStore.fetchedAt(context, hub)
        // 缓存超过 10 分钟才自动拉；进页面就 refresh 会白白吃掉匿名 60 次/小时的额度。
        if (System.currentTimeMillis() - PresetHubStore.fetchedAt(context, hub) >
            CLOUD_REFRESH_INTERVAL_MS
        ) {
            refreshCloud()
        }
    }

    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.config_preset_title),
                onBack = onBack,
                actions = {
                    TextButton(
                        text = stringResource(R.string.config_preset_new),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { showCreate = true }
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PresetGridSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(40) }
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                PresetGridSectionTitle(
                    title = stringResource(R.string.config_preset_list_title),
                    count = filteredLocal.size
                )
            }
            if (filteredLocal.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsCard {
                        BasicComponent(
                            title = stringResource(
                                if (presets.isEmpty()) {
                                    R.string.config_preset_empty_title
                                } else {
                                    R.string.preset_search_no_result
                                }
                            ),
                            summary = stringResource(
                                if (presets.isEmpty()) {
                                    R.string.config_preset_empty_summary
                                } else {
                                    R.string.preset_search_no_result_summary
                                }
                            )
                        )
                    }
                }
            } else {
                gridItems(filteredLocal, key = { it.id }) { preset ->
                    PresetPreviewCard(
                        preset = preset,
                        subtitle = presetListSummary(preset),
                        // 本地预设 id 与云端 id 相同：本机上传过就标「已发布」。
                        badge = if (preset.id in ownedCloudIds) {
                            stringResource(R.string.preset_hub_published_badge)
                        } else {
                            null
                        },
                        onClick = { onOpenPreset(preset.id) }
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                PresetHubSectionHeader(
                    subtitle = cloudSectionSubtitle(cloudLoading, cloudFetchedAt),
                    refreshing = cloudLoading,
                    onRefresh = { scope.launch { refreshCloud() } },
                    onSettings = { showHubSettings = true }
                )
            }
            if (cloudError != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PresetHubStatusCard(
                        title = stringResource(R.string.preset_hub_error),
                        summary = cloudError.orEmpty() + " " +
                            stringResource(R.string.preset_hub_retry_hint),
                        onClick = { scope.launch { refreshCloud() } }
                    )
                }
            }
            when {
                cloudLoading && loadedCloud == null -> item(
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    PresetHubStatusCard(
                        title = stringResource(R.string.preset_hub_loading),
                        summary = stringResource(R.string.preset_hub_loading_summary)
                    )
                }
                cloudPresets.isEmpty() -> if (cloudError == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PresetHubStatusCard(
                            title = stringResource(R.string.preset_hub_empty),
                            summary = stringResource(R.string.preset_hub_empty_summary),
                            onClick = {
                                // 空态点一下就是重试，省得再去找刷新入口。
                                scope.launch { refreshCloud() }
                            }
                        )
                    }
                }
                filteredCloud.isEmpty() -> if (!(presets.isNotEmpty() && filteredLocal.isEmpty())) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SettingsCard {
                            BasicComponent(
                                title = stringResource(R.string.preset_search_no_result),
                                summary = stringResource(R.string.preset_search_no_result_summary)
                            )
                        }
                    }
                }
                else -> gridItems(
                    filteredCloud,
                    key = { CLOUD_PRESET_PREFIX + it.id }
                ) { cloudPreset ->
                    val display = cloudPreset.toConfigPreset()
                    // 匿名上传按本机记账判断归属；Token 上传仍按 GitHub 登录名匹配作者。
                    val isMine = cloudPreset.id in ownedCloudIds ||
                        (hub.login.isNotBlank() && cloudPreset.author == hub.login)
                    PresetPreviewCard(
                        preset = display,
                        subtitle = cloudPresetSubtitle(cloudPreset),
                        badge = stringResource(
                            if (isMine) R.string.preset_hub_mine_badge else R.string.preset_hub_cloud_badge
                        ),
                        onClick = { onOpenPreset(display.id) }
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.config_preset_list_footer),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            }
        }
    }

    if (showCreate) {
        CreateConfigPresetDialog(
            onDismiss = { showCreate = false },
            onCreated = { preset ->
                presets = ConfigPresetStore.load(context)
                showCreate = false
                Toast.makeText(
                    context,
                    context.getString(R.string.config_preset_saved, preset.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    if (showHubSettings) {
        PresetHubSettingsDialog(
            onDismiss = { showHubSettings = false },
            onSaved = {
                showHubSettings = false
                Toast.makeText(
                    context,
                    context.getString(R.string.preset_hub_saved),
                    Toast.LENGTH_SHORT
                ).show()
                scope.launch { refreshCloud() }
            }
        )
    }
}

@Composable
private fun cloudSectionSubtitle(loading: Boolean, fetchedAt: Long): String = when {
    loading -> stringResource(R.string.preset_hub_loading)
    fetchedAt <= 0L -> stringResource(R.string.preset_hub_never_synced)
    else -> stringResource(R.string.preset_hub_last_sync, formatPresetTime(fetchedAt))
}

@Composable
private fun cloudPresetSubtitle(preset: CloudPreset): String {
    val author = preset.author.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.preset_hub_anonymous)
    val sectionCount = stringResource(
        R.string.config_preset_section_count,
        ConfigPresetSection.of(preset.payload).size
    )
    return listOfNotNull(
        author,
        preset.note.takeIf { it.isNotBlank() },
        sectionCount,
        formatPresetTime(preset.createdAt).takeIf { preset.createdAt > 0L }
    ).joinToString(" · ")
}

private const val CLOUD_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

/** 列表摘要：备注（有则优先）/ 分节清单 + 保存时间。 */
@Composable
private fun presetListSummary(preset: ConfigPreset): String {
    val sectionCount = stringResource(
        R.string.config_preset_section_count,
        preset.sections.size
    )
    return listOfNotNull(
        preset.note.takeIf { it.isNotBlank() },
        sectionCount,
        formatPresetTime(preset.createdAt)
    ).joinToString(" · ")
}

/**
 * 新建预设：名称/备注 + 分节勾选。默认全选；一个分节都没勾选时不允许保存。
 * 快照与落盘走 IO（导出整份配置要序列化设置与插件设置）。
 */
@Composable
private fun CreateConfigPresetDialog(
    onDismiss: () -> Unit,
    onCreated: (ConfigPreset) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // null = 还在导出当前配置；分节清单完全由当前信封决定，后续版本新增的键会自动出现。
    var available by remember { mutableStateOf<List<ConfigPresetSection>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val sections = withContext(Dispatchers.IO) {
            ConfigPresetStore.currentSections(context)
        }
        available = sections
        selected = sections.map { it.id }.toSet()
    }

    fun create() {
        if (saving) return
        val sections = available.orEmpty().filter { it.id in selected }.toSet()
        if (name.isBlank() || sections.isEmpty()) {
            formError = true
            return
        }
        formError = false
        saving = true
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                ConfigPresetStore.capture(context, name, note, sections)
            }
            val saved = created != null && ConfigPresetStore.save(context, created)
            saving = false
            if (created != null && saved) {
                onCreated(created)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.config_preset_save_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    WindowDialog(
        motion = appDialogMotion,
        title = stringResource(R.string.config_preset_new),
        summary = stringResource(R.string.config_preset_new_summary),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.dialogScrollable()) {
            TextField(
                value = name,
                onValueChange = {
                    name = it.take(CONFIG_PRESET_NAME_MAX)
                    formError = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.config_preset_name_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = note,
                onValueChange = { note = it.take(CONFIG_PRESET_NOTE_MAX) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.config_preset_note_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
            Spacer(Modifier.height(12.dp))
            SmallTitle(text = stringResource(R.string.config_preset_sections))
            val loadedSections = available
            if (loadedSections == null) {
                Text(
                    text = stringResource(R.string.config_preset_loading),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            } else {
                loadedSections.forEach { section ->
                    SwitchPreference(
                        checked = section.id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) {
                                selected + section.id
                            } else {
                                selected - section.id
                            }
                            formError = false
                        },
                        title = section.titleText(),
                        summary = section.summaryText()
                    )
                }
            }
            Text(
                text = stringResource(R.string.config_preset_sections_summary),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            if (formError) {
                Text(
                    text = stringResource(R.string.config_preset_form_error),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
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
                    onClick = { create() }
                )
            }
        }
    }
}

/**
 * 预设详情：应用范围勾选 + 预览（外观画布与分节差异）+ 应用/删除。
 *
 * 预览在 IO 线程构建（导出当前配置 + 逐键对比）；外观画布走 SceneCompiler 的同一
 * 编译管线，与实机渲染同源。
 */
@Composable
internal fun ConfigPresetDetailScreen(
    presetId: String,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    onPresetChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = PresetHubPreferences.read(context)
    val isCloud = isCloudPresetId(presetId)
    val cloudId = if (isCloud) presetId.removePrefix(CLOUD_PRESET_PREFIX) else null
    var preset by remember(presetId) { mutableStateOf<ConfigPreset?>(null) }
    var selected by remember(presetId) { mutableStateOf<Set<ConfigPresetSection>>(emptySet()) }
    var loading by remember(presetId) { mutableStateOf(true) }
    var preview by remember(presetId) { mutableStateOf<ConfigPresetPreview?>(null) }
    var previewLoading by remember(presetId) { mutableStateOf(false) }
    var previewSurface by remember {
        mutableStateOf(SceneCompiler.SURFACE_LOCKSCREEN)
    }
    var showDetails by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDeleteCloud by remember { mutableStateOf(false) }
    var showHubSettings by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var uploadCandidate by remember { mutableStateOf<ConfigPreset?>(null) }
    // 没配上传通道时先把这次上传的载荷留住，设置保存后自动续上。
    var pendingUploadSource by remember { mutableStateOf<ConfigPreset?>(null) }
    // 本机匿名上传过这条云端预设：可覆盖/删除。
    var ownedCloud by remember(presetId) { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var deletingCloud by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    // 本地预设在独立 prefs 里，云端预设在清单缓存里；两者都可能在主线程解析大 JSON，统一搬 IO。
    LaunchedEffect(presetId) {
        val loaded = withContext(Dispatchers.IO) {
            if (cloudId != null) {
                val config = PresetHubPreferences.read(context)
                PresetHubStore.find(context, config, cloudId)?.toConfigPreset(presetId)
            } else {
                ConfigPresetStore.find(context, presetId)
            }
        }
        preset = loaded
        // 云端预设默认不勾选「插件设置」：里面可能有上传者的 API Key，误应用会覆盖本机凭证。
        selected = loaded?.sections.orEmpty().let { sections ->
            if (isCloud) sections - ConfigPresetSection.PLUGINS else sections
        }
        ownedCloud = cloudId != null &&
            PresetHubPreferences.ownedCloudIds(context).contains(cloudId)
        loading = false
    }

    LaunchedEffect(preset?.id, selected) {
        val target = preset ?: return@LaunchedEffect
        previewLoading = true
        preview = withContext(Dispatchers.IO) {
            ConfigPresetStore.preview(context, target, selected)
        }
        previewLoading = false
    }

    val appearanceProfile = remember(preview?.appearance, previewSurface) {
        preview?.appearance?.let { document ->
            runCatching {
                SceneCompiler.compile(document).profiles.getValue(previewSurface)
            }.getOrNull()
        }
    }

    if (loading) {
        ConfigPresetLoadingScreen(onBack = onBack)
        return
    }
    val active = preset
    if (active == null) {
        Scaffold(
            containerColor = appSurfaceColor(),
            topBar = {
                AppTopBar(title = stringResource(R.string.config_preset_title), onBack = onBack)
            }
        ) { innerPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(
                        if (isCloud) R.string.preset_hub_missing_cloud else R.string.config_preset_missing
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        return
    }

    fun apply() {
        if (applying || selected.isEmpty()) return
        applying = true
        scope.launch {
            // 解码(纯计算)在 IO；落盘与应用留在主线程,与配置导入的线程约束一致。
            val decoded = withContext(Dispatchers.IO) {
                ConfigPresetStore.decode(context, active, selected)
            }
            val applied = decoded != null && ConfigPresetStore.apply(context, decoded)
            applying = false
            Toast.makeText(
                context,
                context.getString(
                    if (applied) R.string.config_preset_applied else R.string.config_preset_apply_failed
                ),
                Toast.LENGTH_LONG
            ).show()
            if (applied) {
                onApplied()
                onBack()
            }
        }
    }

    fun requestUpload(source: ConfigPreset) {
        if (uploading) return
        val currentHub = PresetHubPreferences.read(context)
        if (!currentHub.canUpload) {
            pendingUploadSource = source
            showHubSettings = true
            Toast.makeText(
                context,
                context.getString(R.string.preset_hub_token_required),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // 先让用户确认要公开的分节（插件设置默认不上传）。
        uploadCandidate = source
        showUploadDialog = true
    }

    fun upload() = requestUpload(active)

    /**
     * 用当前设置覆盖这条云端预设：抓一份当前全部分节的快照，沿用原云端 id（匿名 Hub 按
     * 设备 owner 允许同设备覆盖），再走正常的上传确认流程。
     */
    fun overwriteWithCurrent() {
        if (uploading || capturing) return
        capturing = true
        scope.launch {
            val captured = withContext(Dispatchers.IO) {
                ConfigPresetStore.capture(
                    context = context,
                    name = active.name,
                    note = active.note,
                    sections = ConfigPresetStore.currentSections(context).toSet()
                )
            }
            capturing = false
            if (captured == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.preset_hub_overwrite_capture_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // 保留 cloud: 前缀；上传客户端会在发请求前去掉。署名沿用原条目。
            requestUpload(captured.copy(id = active.id, author = active.author))
        }
    }

    fun doUpload(payloadPreset: ConfigPreset, authorInput: String) {
        val hub = PresetHubPreferences.read(context)
        uploading = true
        scope.launch {
            val fallback = withContext(Dispatchers.IO) { PresetHubStore.load(context, hub) }
            // 匿名 Hub：完全不需要 GitHub 凭证，署名取上传弹窗里的输入。
            if (hub.hasAnonymousUpload) {
                val author = authorInput.ifBlank { "anonymous" }
                val result = PresetHubClient.uploadAnonymous(
                    config = hub,
                    preset = payloadPreset,
                    author = author,
                    deviceId = PresetHubPreferences.deviceId(context)
                )
                if (result.isSuccess) {
                    // 记下本机上传成功的云端 id：列表标「我的」，详情页可覆盖/删除。
                    result.getOrNull()?.let { uploadedId ->
                        PresetHubPreferences.rememberOwnedCloudId(context, uploadedId)
                        if (uploadedId == cloudId) ownedCloud = true
                    }
                    // Worker 已改完 Release；拉一次清单把本地缓存刷新成最新。
                    PresetHubClient.fetch(hub).onSuccess { manifest ->
                        PresetHubStore.save(context, hub, manifest)
                    }
                }
                uploading = false
                result.onSuccess {
                    Toast.makeText(
                        context,
                        context.getString(R.string.preset_hub_uploaded),
                        Toast.LENGTH_LONG
                    ).show()
                    onPresetChanged()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.preset_hub_upload_failed) + "：" +
                            hubFailureText(context, error),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            val author = PresetHubClient.whoAmI(hub)
                ?: hub.login.takeIf { it.isNotBlank() }
                ?: authorInput.takeIf { it.isNotBlank() }
                ?: "anonymous"
            PresetHubPreferences.updateLogin(context, author)
            val result = PresetHubClient.upload(
                config = hub,
                preset = payloadPreset,
                author = author,
                fallback = fallback,
                onMerged = { merged -> PresetHubStore.save(context, hub, merged) }
            )
            uploading = false
            result.onSuccess {
                Toast.makeText(
                    context,
                    context.getString(R.string.preset_hub_uploaded),
                    Toast.LENGTH_LONG
                ).show()
                onPresetChanged()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(R.string.preset_hub_upload_failed) + "：" +
                        hubFailureText(context, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun saveAsLocal() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                ConfigPresetStore.saveAsLocal(context, active)
            }
            Toast.makeText(
                context,
                context.getString(
                    if (saved != null) {
                        R.string.preset_hub_saved_local
                    } else {
                        R.string.preset_hub_save_local_failed
                    }
                ),
                Toast.LENGTH_SHORT
            ).show()
            if (saved != null) onPresetChanged()
        }
    }

    fun deleteCloud() {
        val id = cloudId ?: return
        if (deletingCloud) return
        deletingCloud = true
        scope.launch {
            val currentHub = PresetHubPreferences.read(context)
            val result = PresetHubClient.deleteAnonymous(
                config = currentHub,
                cloudId = id,
                deviceId = PresetHubPreferences.deviceId(context)
            )
            deletingCloud = false
            result.onSuccess {
                PresetHubPreferences.forgetOwnedCloudId(context, id)
                ownedCloud = false
                // 删除接口只返回 ok；把本地缓存的清单同步剔除，返回列表时不会闪回旧条目。
                withContext(Dispatchers.IO) {
                    val cached = PresetHubStore.load(context, currentHub)
                    if (cached != null) {
                        PresetHubStore.save(
                            context,
                            currentHub,
                            cached.copy(presets = cached.presets.filterNot { it.id == id })
                        )
                    }
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.preset_hub_deleted_cloud),
                    Toast.LENGTH_LONG
                ).show()
                onPresetChanged()
                onBack()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(R.string.preset_hub_delete_cloud_failed) + "：" +
                        hubFailureText(context, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = active.name,
                onBack = onBack,
                actions = {
                    if (!isCloud) {
                        TextButton(
                            text = stringResource(R.string.config_preset_delete),
                            onClick = { showDelete = true }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            )
        ) {
            if (isCloud) {
                item {
                    Text(
                        text = stringResource(
                            R.string.preset_hub_author,
                            active.author.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.preset_hub_anonymous)
                        ),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }
            if (isCloud && ConfigPresetSection.PLUGINS in active.sections) {
                item {
                    Text(
                        text = stringResource(R.string.preset_hub_plugin_apply_warning),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.config_preset_apply_scope)) }
            item {
                SettingsCard {
                    active.sections.forEach { section ->
                        val included = section in selected
                        val changes = preview?.changesFor(section) ?: 0
                        SwitchPreference(
                            checked = included,
                            onCheckedChange = { checked ->
                                selected = if (checked) {
                                    selected + section
                                } else {
                                    selected - section
                                }
                            },
                            title = section.titleText(),
                            summary = sectionSummary(section, included, changes, previewLoading)
                        )
                    }
                }
            }

            item { SmallTitle(text = stringResource(R.string.config_preset_preview)) }
            item {
                SettingsCard {
                    if (preview == null) {
                        PreviewHint(stringResource(R.string.config_preset_preview_loading))
                    } else if (preview?.appearance != null &&
                        ConfigPresetSection.APPEARANCE in selected
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            PreviewSurfaceButton(
                                label = stringResource(R.string.label_lockscreen_lyrics),
                                selected = previewSurface == SceneCompiler.SURFACE_LOCKSCREEN,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    previewSurface = SceneCompiler.SURFACE_LOCKSCREEN
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            PreviewSurfaceButton(
                                label = stringResource(R.string.label_aod_lyrics),
                                selected = previewSurface == SceneCompiler.SURFACE_AOD,
                                modifier = Modifier.weight(1f),
                                onClick = { previewSurface = SceneCompiler.SURFACE_AOD }
                            )
                        }
                        if (appearanceProfile != null) {
                            AppearanceLivePreview(
                                profile = appearanceProfile,
                                scenario = previewSurface,
                                metadataParts = appearanceProfile.metadataParts,
                                metadataSeparators = appearanceProfile.metadataSeparators,
                                duetMarkers = appearanceProfile.duetMarkers
                            )
                        } else {
                            PreviewHint(stringResource(R.string.config_preset_preview_failed))
                        }
                    } else {
                        PreviewHint(
                            stringResource(
                                if (preview?.appearance != null) {
                                    R.string.config_preset_preview_deselected
                                } else {
                                    R.string.config_preset_preview_missing
                                }
                            )
                        )
                    }
                }
            }

            item {
                SettingsCard {
                    ArrowPreference(
                        title = stringResource(
                            R.string.config_preset_change_details,
                            preview?.totalChanges ?: 0
                        ),
                        summary = if (preview == null) {
                            stringResource(R.string.config_preset_preview_loading)
                        } else {
                            stringResource(
                                if ((preview?.totalChanges ?: 0) > 0) {
                                    R.string.config_preset_changes
                                } else {
                                    R.string.config_preset_no_changes
                                },
                                preview?.totalChanges ?: 0
                            )
                        },
                        onClick = { showDetails = !showDetails }
                    )
                }
            }

            if (showDetails) {
                item {
                    SettingsCard {
                        val changes = preview?.sections.orEmpty()
                            .filter { it.included }
                            .flatMap { sectionPreview ->
                                sectionPreview.changes.map { sectionPreview.section to it }
                            }
                        if (changes.isEmpty()) {
                            BasicComponent(
                                title = stringResource(R.string.config_preset_no_changes),
                                summary = stringResource(R.string.config_preset_no_changes_summary)
                            )
                        } else {
                            changes.forEach { (section, change) ->
                                ChangeRow(section = section, change = change)
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    if (selected.isEmpty()) {
                        Text(
                            text = stringResource(R.string.config_preset_select_section),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else if (preview != null && preview?.totalChanges == 0) {
                        Text(
                            text = stringResource(R.string.config_preset_identical),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.config_preset_apply),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { apply() }
                    )
                    if (isCloud && ownedCloud) {
                        TextButton(
                            text = stringResource(
                                if (capturing) {
                                    R.string.preset_hub_overwrite_busy
                                } else {
                                    R.string.preset_hub_overwrite
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { overwriteWithCurrent() }
                        )
                    }
                    if (isCloud) {
                        TextButton(
                            text = stringResource(R.string.preset_hub_save_local),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { saveAsLocal() }
                        )
                    } else {
                        TextButton(
                            text = stringResource(
                                if (uploading) {
                                    R.string.preset_hub_uploading
                                } else {
                                    R.string.preset_hub_upload
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { upload() }
                        )
                    }
                    if (isCloud && ownedCloud && hub.hasAnonymousUpload) {
                        TextButton(
                            text = stringResource(
                                if (deletingCloud) {
                                    R.string.preset_hub_deleting_cloud
                                } else {
                                    R.string.preset_hub_delete_cloud
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showDeleteCloud = true }
                        )
                    }
                }
            }
        }
    }

    if (showDelete) {
        WindowDialog(
            motion = appDialogMotion,
            title = stringResource(R.string.config_preset_delete_title),
            summary = stringResource(R.string.config_preset_delete_summary, active.name),
            show = true,
            onDismissRequest = { showDelete = false }
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showDelete = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.config_preset_delete),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showDelete = false
                        val deleted = ConfigPresetStore.delete(context, active.id)
                        Toast.makeText(
                            context,
                            context.getString(
                                if (deleted) {
                                    R.string.config_preset_deleted
                                } else {
                                    R.string.config_preset_delete_failed
                                }
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        if (deleted) {
                            onPresetChanged()
                            onBack()
                        }
                    }
                )
            }
        }
    }

    if (showDeleteCloud) {
        WindowDialog(
            motion = appDialogMotion,
            title = stringResource(R.string.preset_hub_delete_cloud_title),
            summary = stringResource(R.string.preset_hub_delete_cloud_summary, active.name),
            show = true,
            onDismissRequest = { showDeleteCloud = false }
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showDeleteCloud = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.preset_hub_delete_cloud),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showDeleteCloud = false
                        deleteCloud()
                    }
                )
            }
        }
    }

    val candidate = uploadCandidate
    if (showUploadDialog && candidate != null) {
        PresetUploadDialog(
            preset = candidate,
            onDismiss = {
                showUploadDialog = false
                uploadCandidate = null
            },
            onUpload = { filtered, author ->
                showUploadDialog = false
                uploadCandidate = null
                doUpload(filtered, author)
            }
        )
    }

    if (showHubSettings) {
        PresetHubSettingsDialog(
            onDismiss = { showHubSettings = false },
            onSaved = {
                showHubSettings = false
                Toast.makeText(
                    context,
                    context.getString(R.string.preset_hub_saved),
                    Toast.LENGTH_SHORT
                ).show()
                // 从「没 Token」点上传/覆盖进来的：设置保存后直接续上这次上传（进入分节确认）。
                val pending = pendingUploadSource
                pendingUploadSource = null
                if (pending != null) requestUpload(pending)
            }
        )
    }
}

@Composable
private fun sectionSummary(
    section: ConfigPresetSection,
    included: Boolean,
    changes: Int,
    loading: Boolean
): String = when {
    !included -> section.summaryText()
    loading -> stringResource(R.string.config_preset_preview_loading)
    section.id == ConfigPresetSection.APPEARANCE.id && changes > 0 ->
        stringResource(R.string.config_preset_appearance_replaced)
    changes == 0 -> stringResource(R.string.config_preset_no_changes)
    else -> stringResource(R.string.config_preset_changes, changes)
}

@Composable
private fun PreviewSurfaceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        TextButton(
            text = label,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = onClick
        )
    } else {
        TextButton(text = label, modifier = modifier, onClick = onClick)
    }
}

@Composable
private fun PreviewHint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
    )
}

@Composable
private fun ChangeRow(section: ConfigPresetSection, change: ConfigPresetChange) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "${section.titleText()} · ${change.key}",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceContainerHighest
        )
        Text(
            text = if (change.before.isBlank() && change.after.isBlank()) {
                stringResource(R.string.config_preset_appearance_replaced)
            } else {
                "${change.before.take(CHANGE_VALUE_PREVIEW_MAX)} → " +
                    change.after.take(CHANGE_VALUE_PREVIEW_MAX)
            },
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

private const val CHANGE_VALUE_PREVIEW_MAX = 60

/** 已知分节用本地化标题；信封里新增、当前版本尚未归组的分节回落到原始键名。 */
@Composable
internal fun ConfigPresetSection.titleText(): String =
    titleRes?.let { stringResource(it) }
        ?: stringResource(
            R.string.config_preset_section_custom_title,
            envelopeKeys.firstOrNull() ?: id
        )

@Composable
internal fun ConfigPresetSection.summaryText(): String =
    summaryRes?.let { stringResource(it) }
        ?: stringResource(R.string.config_preset_section_custom_summary)

private fun formatPresetTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
