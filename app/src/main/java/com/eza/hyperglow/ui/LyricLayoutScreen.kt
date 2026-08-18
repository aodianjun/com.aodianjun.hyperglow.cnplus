package com.eza.hyperglow.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.CustomizationEditorState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
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

/** 外观编辑页:AOD/锁屏歌词外观(位置/文字/特效/卡片)的组合与选项对话框。 */

@Composable
internal fun LyricLayoutScreen(
    initialSurface: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var editorState by remember {
        mutableStateOf(
            CustomizationEditorState(
                CustomizationRepository.loadDocument(context),
                initialSurface
            )
        )
    }
    var activeChoice by remember { mutableStateOf<AodChoice?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(SceneCompiler.MAX_CONFIG_BYTES + 1)
                if (bytes.size > SceneCompiler.MAX_CONFIG_BYTES) {
                    error("Appearance file too large")
                }
                bytes.toString(Charsets.UTF_8)
            } ?: error("Appearance file unavailable")
        }.getOrNull()
        val imported = raw != null && CustomizationRepository.importDocument(context, raw)
        if (imported) {
            val document = CustomizationRepository.loadDocument(context)
            syncCustomizationRuntime(context, document)
            editorState = CustomizationEditorState(document, editorState.selectedSurface)
        }
        Toast.makeText(
            context,
            if (imported) {
                context.getString(R.string.toast_appearance_imported)
            } else {
                context.getString(R.string.toast_appearance_invalid)
            },
            Toast.LENGTH_LONG
        ).show()
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(CustomizationRepository.exportDocument(context))
            } ?: error("Appearance file unavailable")
        }.isSuccess
        Toast.makeText(
            context,
            context.getString(
                if (written) R.string.toast_appearance_exported
                else R.string.toast_appearance_export_failed
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    BackHandler(enabled = activeChoice == null && !showResetDialog, onBack = onBack)

    fun saveEditor(next: CustomizationEditorState): Boolean {
        if (!CustomizationRepository.saveDocument(context, next.document)) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_appearance_save_failed),
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        val document = CustomizationRepository.loadDocument(context)
        syncCustomizationRuntime(context, document)
        editorState = CustomizationEditorState(
            document,
            next.selectedSurface
        )
        return true
    }

    fun updateSelected(updateProfile: (SurfaceProfile) -> SurfaceProfile) {
        saveEditor(editorState.updateSelected(updateProfile))
    }

    LaunchedEffect(Unit) {
        if (editorState.document.linkSurfaces) {
            saveEditor(editorState.setLinkSurfaces(false))
        }
    }

    fun openChoice(
        kind: AodChoiceKind,
        values: List<String>,
        current: String,
        onSelect: (String) -> Unit
    ) {
        activeChoice = AodChoice(kind, values, current, onSelect)
    }

    val selectedProfile = editorState.document.profiles[editorState.selectedSurface] ?: SurfaceProfile()
    // 预览走与实机相同的编译管线(归一化/白名单),编辑后立即反映最终生效效果,所见即所得
    val compiledPreviewProfile = remember(editorState.document) {
        SceneCompiler.compile(editorState.document)
            .profiles.getValue(editorState.selectedSurface)
    }
    var previewCollapsed by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (editorState.selectedSurface == SceneCompiler.SURFACE_AOD) {
                    stringResource(R.string.title_aod_appearance)
                } else {
                    stringResource(R.string.title_lockscreen_appearance)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // 顶部常驻悬浮预览:调节下方选项时效果实时可见;点击标题栏可折叠让位给长列表
            AppearancePreviewHeader(
                expanded = !previewCollapsed,
                onToggle = { previewCollapsed = !previewCollapsed }
            )
            AnimatedVisibility(visible = !previewCollapsed) {
                AppearanceLivePreview(
                    profile = compiledPreviewProfile,
                    scenario = editorState.selectedSurface
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = innerPadding.calculateBottomPadding() + 20.dp
                ),
                modifier = Modifier.weight(1f)
            ) {
            item { SmallTitle(text = stringResource(R.string.section_placement)) }
            item {
                SettingsCard {
                    AodChoiceRow(AodChoiceKind.POSITION, selectedProfile.anchor) {
                        openChoice(
                            AodChoiceKind.POSITION,
                            listOf(
                                "below_stock_clock",
                                "screen_center",
                                "screen_top_safe",
                                "screen_bottom_safe",
                                "custom_vertical_bias"
                            ),
                            selectedProfile.anchor
                        ) { value -> updateSelected { it.copy(anchor = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.WIDTH, selectedProfile.widthFraction.toString()) {
                        openChoice(
                            AodChoiceKind.WIDTH,
                            listOf("0.7", "0.88", "1.0"),
                            selectedProfile.widthFraction.toString()
                        ) { value -> updateSelected { it.copy(widthFraction = value.toFloat()) } }
                    }
                    AodChoiceRow(
                        AodChoiceKind.HEIGHT,
                        selectedProfile.maxHeightFraction.toString()
                    ) {
                        openChoice(
                            AodChoiceKind.HEIGHT,
                            heightChoices(editorState.selectedSurface),
                            selectedProfile.maxHeightFraction.toString()
                        ) { value -> updateSelected { it.copy(maxHeightFraction = value.toFloat()) } }
                    }
                    SliderPreference(
                        value = selectedProfile.verticalBias * 100f,
                        onValueChange = { pct ->
                            updateSelected {
                                it.copy(
                                    anchor = "custom_vertical_bias",
                                    verticalBias = (pct / 100f).coerceIn(0f, 1f)
                                )
                            }
                        },
                        title = stringResource(R.string.setting_custom_position),
                        summary = stringResource(R.string.summary_custom_position),
                        valueText = "${(selectedProfile.verticalBias * 100).roundToInt()}%",
                        valueRange = 0f..100f,
                        steps = 20
                    )
                    AodChoiceRow(AodChoiceKind.OVERLAP, selectedProfile.collisionPolicy) {
                        openChoice(
                            AodChoiceKind.OVERLAP,
                            listOf("avoid", "behind_system", "hide_optional", "hide_scene"),
                            selectedProfile.collisionPolicy
                        ) { value -> updateSelected { it.copy(collisionPolicy = value) } }
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_text_language)) }
            item {
                SettingsCard {
                    AodChoiceRow(AodChoiceKind.ALIGNMENT, selectedProfile.alignment) {
                        openChoice(
                            AodChoiceKind.ALIGNMENT,
                            listOf("auto", "start", "center", "end"),
                            selectedProfile.alignment
                        ) { value -> updateSelected { it.copy(alignment = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.SECONDARY_TEXT, selectedProfile.secondaryMode) {
                        openChoice(
                            AodChoiceKind.SECONDARY_TEXT,
                            listOf("Main only", "Transliteration", "Translation", "Both"),
                            selectedProfile.secondaryMode
                        ) { value -> updateSelected { it.copy(secondaryMode = value) } }
                    }
                    if (selectedProfile.secondaryMode != "Main only") {
                        SwitchPreference(
                            selectedProfile.secondaryTextBright,
                            { bright -> updateSelected { it.copy(secondaryTextBright = bright) } },
                            stringResource(R.string.setting_bright_secondary_text)
                        )
                    }
                    SwitchPreference(
                        selectedProfile.rubyVisible,
                        { visible -> updateSelected { it.copy(rubyVisible = visible) } },
                        stringResource(R.string.setting_show_furigana)
                    )
                    AodChoiceRow(AodChoiceKind.LONG_LINES, selectedProfile.overflow) {
                        openChoice(
                            AodChoiceKind.LONG_LINES,
                            listOf("Wrap", "Clip"),
                            selectedProfile.overflow
                        ) { value -> updateSelected { it.copy(overflow = value) } }
                    }
                    if (selectedProfile.overflow == "Wrap") {
                        AodChoiceRow(AodChoiceKind.LYRIC_LINES, selectedProfile.lyricLineLimit.toString()) {
                            openChoice(
                                AodChoiceKind.LYRIC_LINES,
                                listOf("1", "2", "3", "4", "5", "0"),
                                selectedProfile.lyricLineLimit.toString()
                            ) { value ->
                                updateSelected { it.copy(lyricLineLimit = value.toInt()) }
                            }
                        }
                    }
                    SwitchPreference(
                        selectedProfile.adaptiveSectioning,
                        { enabled -> updateSelected { it.copy(adaptiveSectioning = enabled) } },
                        stringResource(R.string.setting_keep_phrases_together)
                    )
                    SwitchPreference(
                        selectedProfile.showNextLine,
                        { enabled -> updateSelected { it.copy(showNextLine = enabled) } },
                        stringResource(R.string.setting_show_next_line)
                    )
                    SwitchPreference(
                        selectedProfile.metadataVisible,
                        { visible -> updateSelected { withMetadataVisible(it, visible) } },
                        stringResource(R.string.setting_show_song_info)
                    )
                    if (selectedProfile.metadataVisible) {
                        AodChoiceRow(AodChoiceKind.SONG_INFO_POSITION, selectedProfile.metadataAnchor) {
                            openChoice(
                                AodChoiceKind.SONG_INFO_POSITION,
                                listOf("top", "bottom"),
                                selectedProfile.metadataAnchor
                            ) { value -> updateSelected { it.copy(metadataAnchor = value) } }
                        }
                        TextSizePreference(
                            title = stringResource(R.string.setting_song_info_size),
                            percent = selectedProfile.metadataSizePercent.coerceIn(50, 200),
                            onDecrease = {
                                updateSelected {
                                    it.copy(
                                        metadataSizePercent =
                                            (it.metadataSizePercent - 5).coerceIn(50, 200)
                                    )
                                }
                            },
                            onIncrease = {
                                updateSelected {
                                    it.copy(
                                        metadataSizePercent =
                                            (it.metadataSizePercent + 5).coerceIn(50, 200)
                                    )
                                }
                            }
                        )
                    }
                    AodChoiceRow(AodChoiceKind.TEXT_WEIGHT, selectedProfile.weight) {
                        openChoice(
                            AodChoiceKind.TEXT_WEIGHT,
                            listOf("Regular", "Medium", "Bold"),
                            selectedProfile.weight
                        ) { value -> updateSelected { it.copy(weight = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.TEXT_SIZE, selectedProfile.textSize) {
                        openChoice(
                            AodChoiceKind.TEXT_SIZE,
                            listOf("small", "normal", "large", "xlarge", "custom"),
                            selectedProfile.textSize
                        ) { value -> updateSelected { it.copy(textSize = value) } }
                    }
                    TextSizePreference(
                        title = stringResource(R.string.setting_lyric_size),
                        percent = effectiveTextSizePercent(selectedProfile),
                        onDecrease = {
                            updateSelected {
                                it.copy(
                                    textSize = "custom",
                                    textSizeCustom = (effectiveTextSizePercent(it) - 5).coerceIn(50, 200)
                                )
                            }
                        },
                        onIncrease = {
                            updateSelected {
                                it.copy(
                                    textSize = "custom",
                                    textSizeCustom = (effectiveTextSizePercent(it) + 5).coerceIn(50, 200)
                                )
                            }
                        }
                    )
                    AodChoiceRow(AodChoiceKind.FONT, selectedProfile.fontFamily) {
                        openChoice(
                            AodChoiceKind.FONT,
                            listOf("noto", "spotify", "apple"),
                            selectedProfile.fontFamily
                        ) { value -> updateSelected { it.copy(fontFamily = value) } }
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_effects)) }
            item {
                SettingsCard {
                    AodChoiceRow(AodChoiceKind.WORD_ANIMATION, selectedProfile.animation) {
                        openChoice(
                            AodChoiceKind.WORD_ANIMATION,
                            listOf("Minimal", "Gradient"),
                            selectedProfile.animation
                        ) { value -> updateSelected { it.copy(animation = value) } }
                    }
                    SwitchPreference(
                        selectedProfile.glow == "On",
                        { on -> updateSelected { it.copy(glow = if (on) "On" else "Off") } },
                        stringResource(R.string.choice_glow)
                    )
                    AodChoiceRow(AodChoiceKind.LINE_PROGRESS, selectedProfile.lineSyncFillMode) {
                        openChoice(
                            AodChoiceKind.LINE_PROGRESS,
                            listOf(
                                "None",
                                "Top to bottom",
                                "Left to right (main only)",
                                "Left to right (whole block)"
                            ),
                            selectedProfile.lineSyncFillMode
                        ) { value -> updateSelected { it.copy(lineSyncFillMode = value) } }
                    }
                    AodChoiceRow(AodChoiceKind.TEXT_BRIGHTNESS, palettePresetName(selectedProfile.palette)) {
                        openChoice(
                            AodChoiceKind.TEXT_BRIGHTNESS,
                            listOf("default", "dimmed"),
                            palettePresetName(selectedProfile.palette)
                        ) { value -> updateSelected { it.copy(palette = palettePreset(value)) } }
                    }
                    AodChoiceRow(AodChoiceKind.FONT_COLOR, fontColorPresetName(selectedProfile.palette)) {
                        openChoice(
                            AodChoiceKind.FONT_COLOR,
                            FONT_COLOR_CHOICES,
                            fontColorPresetName(selectedProfile.palette)
                        ) { value ->
                            updateSelected { it.copy(palette = applyFontColor(it.palette, value)) }
                        }
                    }
                    if (selectedProfile.metadataVisible) {
                        AodChoiceRow(
                            AodChoiceKind.SONG_INFO_COLOR,
                            metadataColorPresetName(selectedProfile.palette)
                        ) {
                            openChoice(
                                AodChoiceKind.SONG_INFO_COLOR,
                                FONT_COLOR_CHOICES,
                                metadataColorPresetName(selectedProfile.palette)
                            ) { value ->
                                updateSelected {
                                    it.copy(palette = applyMetadataColor(it.palette, value))
                                }
                            }
                        }
                    }
                    if (selectedProfile.showNextLine) {
                        AodChoiceRow(
                            AodChoiceKind.NEXT_LINE_COLOR,
                            nextLineColorPresetName(selectedProfile.palette)
                        ) {
                            openChoice(
                                AodChoiceKind.NEXT_LINE_COLOR,
                                FONT_COLOR_CHOICES,
                                nextLineColorPresetName(selectedProfile.palette)
                            ) { value ->
                                updateSelected {
                                    it.copy(palette = applyNextLineColor(it.palette, value))
                                }
                            }
                        }
                    }
                    AodChoiceRow(
                        AodChoiceKind.TRANSITION_SPEED,
                        selectedProfile.transition.durationMs.toString()
                    ) {
                        openChoice(
                            AodChoiceKind.TRANSITION_SPEED,
                            listOf("200", "320", "500"),
                            selectedProfile.transition.durationMs.toString()
                        ) { value ->
                            updateSelected {
                                it.copy(transition = it.transition.copy(durationMs = value.toInt()))
                            }
                        }
                    }
                }
            }
            if (editorState.selectedSurface == SceneCompiler.SURFACE_LOCKSCREEN) {
                item { SmallTitle(text = stringResource(R.string.section_lockscreen_card)) }
                item {
                    SettingsCard {
                        SwitchPreference(
                            selectedProfile.backgroundStyle != "none",
                            { enabled ->
                                updateSelected {
                                    it.copy(backgroundStyle = if (enabled) "card" else "none")
                                }
                            },
                            stringResource(R.string.setting_show_lyric_card)
                        )
                        if (selectedProfile.backgroundStyle == "card") {
                            AodChoiceRow(AodChoiceKind.CARD_COLOR, selectedProfile.cardColor) {
                                openChoice(
                                    AodChoiceKind.CARD_COLOR,
                                    com.eza.hyperglow.customization.CARD_COLOR_VALUES.toList(),
                                    selectedProfile.cardColor
                                ) { value -> updateSelected { it.copy(cardColor = value) } }
                            }
                            SliderPreference(
                                value = selectedProfile.cardAlpha.toFloat(),
                                onValueChange = { value ->
                                    updateSelected {
                                        it.copy(cardAlpha = value.roundToInt())
                                    }
                                },
                                title = stringResource(R.string.setting_card_transparency),
                                summary = stringResource(R.string.summary_card_transparency),
                                valueText = "${selectedProfile.cardAlpha}%",
                                valueRange = 0f..100f,
                                steps = 19
                            )
                        }
                        val progressEnabled = selectedProfile.widgets.any { it.type == "media_progress" }
                        SwitchPreference(
                            progressEnabled,
                            { enabled ->
                                updateSelected { profile ->
                                    val widgets = profile.widgets.filterNot {
                                        it.type == "media_progress"
                                    }.toMutableList()
                                    if (enabled) {
                                        widgets += com.eza.hyperglow.customization.WidgetSpec(
                                            "media_progress",
                                            optional = true
                                        )
                                    }
                                    profile.copy(widgets = widgets)
                                }
                            },
                            stringResource(R.string.setting_show_playback_progress),
                            summary = stringResource(R.string.summary_show_playback_progress)
                        )
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_both_surfaces)) }
            item {
                Card(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.action_import_appearance),
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.action_export_appearance),
                            onClick = { exportLauncher.launch("hyperglow-profile.json") }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.action_reset_surfaces),
                            onClick = { showResetDialog = true }
                        )
                    }
                }
            }
        }
        }
    }

    activeChoice?.let { selected ->
        WindowDialog(
            title = stringResource(selected.kind.titleRes),
            show = true,
            onDismissRequest = { activeChoice = null }
        ) {
            Column {
                selected.values.forEach { value ->
                    val label = choiceDisplayLabel(context, selected.kind, value)
                    if (selected.kind.swatchColorFor(value) != null) {
                        // 颜色类选项用色块行直观展示颜色效果,其余选项保持单选样式
                        ColorSwatchOptionRow(
                            label = label,
                            swatch = selected.kind.swatchColorFor(value)!!,
                            selected = selected.current == value,
                            onClick = {
                                selected.onSelect(value)
                                activeChoice = null
                            }
                        )
                    } else {
                        RadioButtonPreference(
                            label,
                            selected.current == value,
                            {
                                selected.onSelect(value)
                                activeChoice = null
                            }
                        )
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        WindowDialog(
            title = stringResource(R.string.dialog_reset_title),
            summary =
                stringResource(R.string.dialog_reset_summary),
            show = true,
            onDismissRequest = { showResetDialog = false }
        ) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showResetDialog = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_restore),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showResetDialog = false
                        val reset = CustomizationRepository.reset(context)
                        if (reset) {
                            val document = CustomizationRepository.loadDocument(context)
                            syncCustomizationRuntime(context, document)
                            editorState = CustomizationEditorState(
                                document,
                                editorState.selectedSurface
                            )
                        }
                        Toast.makeText(
                            context,
                            context.getString(
                                if (reset) R.string.toast_settings_restored
                                else R.string.toast_settings_restore_failed
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }
}

@Composable
private fun AodChoiceRow(kind: AodChoiceKind, value: String, onClick: () -> Unit) {
    val context = LocalContext.current
    ArrowPreference(
        title = stringResource(kind.titleRes),
        summary = choiceDisplayLabel(context, kind, value),
        onClick = onClick
    )
}

/**
 * 悬浮预览的标题栏:整行可点击切换展开/折叠。折叠后预览让位给设置列表,
 * 便于长列表快速调整;展开时调节下方选项效果实时可见。
 */
@Composable
private fun AppearancePreviewHeader(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.title_appearance_preview),
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (expanded) "▾" else "▸",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/**
 * 颜色类选项(字体/歌曲信息/下一行歌词/卡片颜色)的色块行:色块 + 名称 + 选中勾,
 * 替代纯文字单选,颜色效果一眼可辨。色块颜色与实际渲染颜色一致。
 */
@Composable
private fun ColorSwatchOptionRow(
    label: String,
    swatch: ComposeColor,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(1.dp, ComposeColor(0x33FFFFFF), CircleShape)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        if (selected) {
            Text("✓", color = MiuixTheme.colorScheme.primary)
        }
    }
}

/**
 * 选项值对应的色块颜色;非颜色类选项返回 null(走普通单选样式)。
 * 字体/歌曲信息/下一行颜色:hex token 解析为色块,default 显示白;
 * 卡片颜色:预设 token 映射为对应底色(与 previewCardColor 的基色一致)。
 */
private fun AodChoiceKind.swatchColorFor(value: String): ComposeColor? = when (this) {
    AodChoiceKind.FONT_COLOR,
    AodChoiceKind.SONG_INFO_COLOR,
    AodChoiceKind.NEXT_LINE_COLOR ->
        if (value.startsWith("#")) {
            runCatching { ComposeColor(android.graphics.Color.parseColor(value)) }
                .getOrDefault(ComposeColor.White)
        } else {
            ComposeColor.White
        }
    AodChoiceKind.CARD_COLOR -> when (value) {
        "white" -> ComposeColor(0xFFFFFFFF)
        "dark_gray" -> ComposeColor(0xFF2A2A2A)
        "accent" -> ComposeColor(0xFF3A6EA5)
        "blur" -> ComposeColor(0xFF1A1A1E)
        else -> ComposeColor(0xFF000000)
    }
    else -> null
}

@Composable
private fun TextSizePreference(
    title: String,
    percent: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    BasicComponent(
        title = title,
        endActions = {
            IconButton(
                onClick = onDecrease,
                enabled = percent > 50,
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                cornerRadius = 24.dp,
                minHeight = 48.dp,
                minWidth = 48.dp
            ) {
                Text("−", fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$percent%",
                modifier = Modifier
                    .width(64.dp)
                    .align(Alignment.CenterVertically),
                color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = onIncrease,
                enabled = percent < 200,
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                cornerRadius = 24.dp,
                minHeight = 48.dp,
                minWidth = 48.dp
            ) {
                Text("+", fontSize = 28.sp)
            }
        }
    )
}

private fun effectiveTextSizePercent(profile: SurfaceProfile): Int = when (profile.textSize) {
    "small" -> 90
    "large" -> 120
    "xlarge" -> 150
    "custom" -> profile.textSizeCustom.coerceIn(50, 200)
    else -> 100
}

/**
 * Discrete lyric-block height choices (as `maxHeightFraction` fractions). AOD clamps to 0.5,
 * lockscreen to 0.8 (see [com.eza.hyperglow.customization.SceneCompiler]), so the offered
 * ranges differ per surface.
 */
private fun heightChoices(surface: String): List<String> =
    if (surface == SceneCompiler.SURFACE_AOD) {
        listOf("0.3", "0.4", "0.5")
    } else {
        listOf("0.4", "0.5", "0.6", "0.7")
    }

private fun choiceDisplayLabel(
    context: android.content.Context,
    kind: AodChoiceKind,
    value: String
): String = when (kind) {
    AodChoiceKind.POSITION -> context.getString(when (value) {
        "below_stock_clock" -> R.string.option_below_clock
        "screen_center" -> R.string.option_screen_center
        "screen_top_safe" -> R.string.option_top_safe_area
        "screen_bottom_safe" -> R.string.option_bottom_safe_area
        "custom_vertical_bias" -> R.string.option_custom_vertical_position
        else -> R.string.option_below_clock
    })
    AodChoiceKind.WIDTH ->
        value.toFloatOrNull()?.let { "${(it * 100).roundToInt()}%" } ?: value
    AodChoiceKind.HEIGHT ->
        value.toFloatOrNull()?.let { "${(it * 100).roundToInt()}%" } ?: value
    AodChoiceKind.OVERLAP -> context.getString(when (value) {
        "avoid" -> R.string.option_avoid_system_content
        "behind_system" -> R.string.option_allow_overlap
        "hide_optional" -> R.string.option_hide_extra_text
        "hide_scene" -> R.string.option_hide_lyrics_blocked
        else -> R.string.option_avoid_system_content
    })
    AodChoiceKind.ALIGNMENT -> context.getString(when (value) {
        "auto" -> R.string.option_automatic
        "start" -> R.string.option_start
        "center" -> R.string.option_center
        "end" -> R.string.option_end
        else -> R.string.option_automatic
    })
    AodChoiceKind.SONG_INFO_POSITION -> context.getString(
        if (value == "bottom") R.string.option_bottom else R.string.option_top
    )
    AodChoiceKind.LYRIC_LINES -> if (value == "0") {
        context.getString(R.string.option_no_limit)
    } else {
        value
    }
    AodChoiceKind.FONT -> context.getString(when (value) {
        "noto" -> R.string.option_noto_sans
        "spotify" -> R.string.option_spotify_mix
        "apple" -> R.string.option_sf_pro_display
        else -> R.string.option_noto_sans
    })
    AodChoiceKind.TEXT_BRIGHTNESS -> context.getString(
        if (value == "dimmed") R.string.option_dimmed else R.string.option_default
    )
    AodChoiceKind.FONT_COLOR,
    AodChoiceKind.SONG_INFO_COLOR,
    AodChoiceKind.NEXT_LINE_COLOR -> context.getString(when (value) {
        "#FFD9A0" -> R.string.option_color_warm_gold
        "#A9D9FF" -> R.string.option_color_ice_blue
        "#B8F0C9" -> R.string.option_color_mint_green
        "#FFC9DE" -> R.string.option_color_sakura_pink
        "#FFF3A8" -> R.string.option_color_butter_yellow
        "#D9C9FF" -> R.string.option_color_lavender
        else -> R.string.option_default
    })
    AodChoiceKind.LINE_PROGRESS -> context.getString(when (value) {
        "None" -> R.string.option_none
        "Top to bottom" -> R.string.option_top_to_bottom
        "Left to right (main only)" -> R.string.option_left_to_right
        "Left to right (whole block)" -> R.string.option_left_to_right_all
        else -> R.string.option_none
    })
    AodChoiceKind.TRANSITION_SPEED -> context.getString(when (value) {
        "200" -> R.string.option_fast
        "500" -> R.string.option_slow
        else -> R.string.option_normal
    })
    AodChoiceKind.SECONDARY_TEXT -> context.getString(when (value) {
        "Transliteration" -> R.string.option_transliteration
        "Translation" -> R.string.option_translation
        "Both" -> R.string.option_both
        else -> R.string.option_main_only
    })
    AodChoiceKind.LONG_LINES -> context.getString(
        if (value == "Clip") R.string.option_clip else R.string.option_wrap
    )
    AodChoiceKind.TEXT_WEIGHT -> context.getString(when (value) {
        "Regular" -> R.string.option_regular
        "Bold" -> R.string.option_bold
        else -> R.string.option_medium
    })
    AodChoiceKind.TEXT_SIZE -> context.getString(when (value) {
        "small" -> R.string.option_small
        "large" -> R.string.option_large
        "xlarge" -> R.string.option_xlarge
        "custom" -> R.string.option_custom
        else -> R.string.option_normal
    })
    AodChoiceKind.WORD_ANIMATION -> context.getString(
        if (value == "Minimal") R.string.option_minimal else R.string.option_gradient
    )
    AodChoiceKind.CARD_COLOR -> context.getString(when (value) {
        "white" -> R.string.option_card_color_white
        "dark_gray" -> R.string.option_card_color_dark_gray
        "accent" -> R.string.option_card_color_accent
        "blur" -> R.string.option_card_color_blur
        else -> R.string.option_card_color_black
    })
}

private enum class AodChoiceKind(@param:StringRes val titleRes: Int) {
    POSITION(R.string.choice_position),
    WIDTH(R.string.choice_width),
    HEIGHT(R.string.choice_height),
    OVERLAP(R.string.choice_overlap_handling),
    ALIGNMENT(R.string.choice_alignment),
    SECONDARY_TEXT(R.string.choice_secondary_text),
    LONG_LINES(R.string.choice_long_lines),
    LYRIC_LINES(R.string.choice_lyric_lines),
    SONG_INFO_POSITION(R.string.choice_song_info_position),
    TEXT_WEIGHT(R.string.choice_text_weight),
    TEXT_SIZE(R.string.choice_text_size),
    FONT(R.string.choice_font),
    WORD_ANIMATION(R.string.choice_word_animation),
    LINE_PROGRESS(R.string.choice_line_progress_effect),
    TEXT_BRIGHTNESS(R.string.choice_text_brightness),
    FONT_COLOR(R.string.choice_font_color),
    SONG_INFO_COLOR(R.string.choice_song_info_color),
    NEXT_LINE_COLOR(R.string.choice_next_line_color),
    TRANSITION_SPEED(R.string.choice_scene_transition_speed),
    CARD_COLOR(R.string.choice_card_color)
}

private data class AodChoice(
    val kind: AodChoiceKind,
    val values: List<String>,
    val current: String,
    val onSelect: (String) -> Unit
)
