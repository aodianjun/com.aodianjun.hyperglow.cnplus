package com.eza.hyperglow.ui

import android.graphics.Typeface
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.eza.hyperglow.customization.CustomizationEditorState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.aod.CustomFontContract
import com.eza.hyperglow.root.aod.LyricTypefaceResolver
import com.eza.hyperglow.root.aod.metadataWidgetHeightDp
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.ResolvedPlacement
import com.eza.hyperglow.root.surface.WidgetMeasurement
import java.io.File
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
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
    var activeColorPicker by remember { mutableStateOf<PaletteColor?>(null) }
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

    BackHandler(
        enabled = activeChoice == null && activeColorPicker == null && !showResetDialog,
        onBack = onBack
    )

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

    var customFontAvailable by remember {
        mutableStateOf(File(context.filesDir, CustomFontContract.FONT_RELATIVE_PATH).isFile)
    }
    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readNBytes(MAX_CUSTOM_FONT_BYTES + 1)
            } ?: error("Font file unavailable")
            if (bytes.size > MAX_CUSTOM_FONT_BYTES || !hasFontMagic(bytes)) error("Invalid font file")
            val target = File(context.filesDir, CustomFontContract.FONT_RELATIVE_PATH)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            runCatching { Typeface.createFromFile(target) }.getOrElse {
                target.delete()
                error("Font file unreadable")
            }
        }.isSuccess
        if (imported) {
            LyricTypefaceResolver.invalidateCustomCache()
            customFontAvailable = true
            updateSelected { it.copy(fontFamily = LyricTypefaceResolver.FAMILY_CUSTOM) }
        }
        Toast.makeText(
            context,
            context.getString(
                if (imported) R.string.toast_custom_font_imported else R.string.toast_custom_font_invalid
            ),
            Toast.LENGTH_LONG
        ).show()
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
                    // 息屏没有卡片背景,高度档位无视觉意义(编译期固定为最小档),
                    // 不提供高度设置;锁屏卡片保留高度档位。
                    if (editorState.selectedSurface != SceneCompiler.SURFACE_AOD) {
                        AodChoiceRow(
                            AodChoiceKind.HEIGHT,
                            selectedProfile.maxHeightFraction.toString()
                        ) {
                            openChoice(
                                AodChoiceKind.HEIGHT,
                                listOf("0.4", "0.5", "0.6", "0.7"),
                                selectedProfile.maxHeightFraction.toString()
                            ) { value ->
                                updateSelected {
                                    it.copy(maxHeightFraction = value.toFloat())
                                }
                            }
                        }
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
                            buildList {
                                add("noto")
                                add("spotify")
                                add("apple")
                                add(LyricTypefaceResolver.FAMILY_NOTO_SC)
                                if (customFontAvailable) add(LyricTypefaceResolver.FAMILY_CUSTOM)
                            },
                            selectedProfile.fontFamily
                        ) { value -> updateSelected { it.copy(fontFamily = value) } }
                    }
                    ArrowPreference(
                        title = stringResource(R.string.action_import_custom_font),
                        onClick = { fontImportLauncher.launch(arrayOf("*/*")) }
                    )
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
                    AodChoiceRow(AodChoiceKind.GLOW, selectedProfile.glow) {
                        openChoice(AodChoiceKind.GLOW, listOf("Off", "On"), selectedProfile.glow) { value ->
                            updateSelected { it.copy(glow = value) }
                        }
                    }
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
                    AodChoiceRow(AodChoiceKind.TRANSITION_SPEED,
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
            item { SmallTitle(text = stringResource(R.string.section_colors)) }
            item {
                SettingsCard {
                    PaletteColor.entries.forEach { paletteKey ->
                        val currentToken = paletteValue(selectedProfile.palette, paletteKey)
                        ArrowPreference(
                            title = stringResource(paletteKey.titleRes),
                            summary = colorTokenLabel(context, currentToken),
                            startAction = {
                                ColorSwatch(
                                    argb = paletteEffectiveArgb(selectedProfile.palette, paletteKey)
                                )
                            },
                            onClick = {
                                activeColorPicker = paletteKey
                            }
                        )
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
                    RadioButtonPreference(
                        choiceDisplayLabel(context, selected.kind, value),
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

    activeColorPicker?.let { paletteKey ->
        PaletteColorPickerDialog(
            key = paletteKey,
            currentArgb = paletteEffectiveArgb(selectedProfile.palette, paletteKey),
            onPick = { argb ->
                updateSelected {
                    it.copy(palette = applyPaletteColor(it.palette, paletteKey, argbToColorToken(argb)))
                }
            },
            onReset = {
                updateSelected {
                    it.copy(palette = applyPaletteColor(it.palette, paletteKey, PALETTE_DEFAULT))
                }
                activeColorPicker = null
            },
            onDismiss = { activeColorPicker = null }
        )
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

internal fun resolvePreviewPlacement(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    width: Float,
    height: Float
): ResolvedPlacement {
    val environment = previewEnvironment(scenario, width, height)
    val metadataHeight = if (profile.metadataVisible &&
        profile.widgets.any { it.type == "metadata" }
    ) {
        height * 0.10f *
            (metadataWidgetHeightDp(profile.metadataSizePercent) / metadataWidgetHeightDp(100))
    } else 0f
    val progressHeight = if (profile.widgets.any { it.type == "media_progress" }) {
        height * 0.05f
    } else {
        0f
    }
    val desiredHeight = height * profile.maxHeightFraction
    val minimumLyricHeight = height * 0.22f
    val measurements = profile.widgets.mapNotNull { widget ->
        when (widget.type) {
            "lyrics" -> WidgetMeasurement(
                widget,
                (desiredHeight - metadataHeight - progressHeight)
                    .coerceAtLeast(minimumLyricHeight)
            )
            "metadata" -> WidgetMeasurement(widget, metadataHeight)
            "media_progress" -> WidgetMeasurement(widget, progressHeight)
            else -> null
        }
    }
    return PlacementEngine.resolve(profile, environment, measurements, minimumLyricHeight)
}

internal fun previewEnvironment(
    scenario: String,
    width: Float,
    height: Float
): PlacementEnvironment = PlacementEnvironment(
    safeCanvas = PlacementRect(0f, 0f, width, height),
    stockClockBottom = when (scenario) {
        "Full AOD" -> height * 0.18f
        "Normal AOD", "FOD safe region" -> height * 0.34f
        else -> height * 0.26f
    },
    bottomReserveTop = when (scenario) {
        "FOD safe region" -> height * 0.70f
        else -> height * 0.90f
    },
    notificationTop = if (scenario == "Lockscreen · notifications") height * 0.62f else null
)

/**
 * Demo snapshot that cycles through a few sample lines every couple of seconds, so the home
 * preview visibly updates even when no live lyric source is connected. When a real producer
 * starts feeding `arbiter.active`, the preview switches to the live snapshot instead.
 */
@Composable
internal fun collectDemoSnapshot(scenario: String): LyricSnapshot {
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DEMO_LINE_SWITCH_MS)
            index = (index + 1) % DEMO_LINES.size
        }
    }
    val line = DEMO_LINES[index]
    return LyricSnapshot(
        revision = index.toLong(),
        trackGeneration = 1,
        updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
        visible = true,
        original = line.original,
        romanized = line.romanized,
        translated = line.translated,
        metadata = "蝴蝶 · 洛天依",
        lineLevelSync = true,
        lineStartMs = 0,
        lineEndMs = DEMO_LINE_SWITCH_MS,
        durationMs = DEMO_LINES.size * DEMO_LINE_SWITCH_MS,
        positionMs = ((index * DEMO_LINE_SWITCH_MS).toFloat()).toLong(),
        sampledAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
        words = emptyList(),
        ruby = if (scenario == "Long/ruby/translated") {
            listOf(LyricRuby(0, 3, "kore wa"))
        } else {
            emptyList()
        }
    )
}

private class DemoLine(val original: String, val romanized: String, val translated: String)

private val DEMO_LINES = listOf(
    DemoLine(
        "你说你来到这世界的那天 神给了每个人快乐入场券",
        "nǐ shuō nǐ lái dào zhè shìjiè de nà tiān",
        "You said the day you came to this world, heaven gave everyone a ticket to joy"
    ),
    DemoLine(
        "那一只蝴蝶 拼了命破茧 却没有漂亮的鳞片",
        "nà yī zhī húdié pīn le mìng pò jiǎn",
        "That butterfly bursts its cocoon with all its might, yet bears no pretty scales"
    ),
    DemoLine(
        "走吧 就算我们无法让大雨停下",
        "zǒu ba jiùsuàn wǒmen wúfǎ ràng dàyǔ tíng xià",
        "Let's go, even if we can't make the heavy rain stop"
    ),
    DemoLine(
        "你我生来时就注定 天真而伟大",
        "nǐ wǒ shēnglái shí jiù zhùdìng tiānzhēn ér wěidà",
        "You and I are destined from birth to be innocent and great"
    )
)

/** How long each demo line stays on screen before cycling to the next. */
internal const val DEMO_LINE_SWITCH_MS = 2_500L

private const val MAX_CUSTOM_FONT_BYTES = 30 * 1024 * 1024

private fun hasFontMagic(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    val ttf = bytes[0] == 0x00.toByte() && bytes[1] == 0x01.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
    val otf = bytes[0] == 0x4F.toByte() && bytes[1] == 0x54.toByte() &&
        bytes[2] == 0x54.toByte() && bytes[3] == 0x4F.toByte()
    val ttc = bytes[0] == 0x74.toByte() && bytes[1] == 0x74.toByte() &&
        bytes[2] == 0x63.toByte() && bytes[3] == 0x66.toByte()
    val macTrueType = bytes[0] == 0x74.toByte() && bytes[1] == 0x72.toByte() &&
        bytes[2] == 0x75.toByte() && bytes[3] == 0x65.toByte()
    return ttf || otf || ttc || macTrueType
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

/** 圆角色块,展示某语义色键当前生效的颜色;放在颜色行的起始位置。 */
@Composable
private fun ColorSwatch(argb: Int) {
    Box(
        Modifier
            .padding(end = 12.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(argb))
    )
}

/**
 * 取色对话框:用 miuix ColorPicker 为单个语义色键挑选任意颜色。
 * 选择即时写入(与其它滑杆设置一致),「恢复默认」清除该键回落默认色。
 */
@Composable
private fun PaletteColorPickerDialog(
    key: PaletteColor,
    currentArgb: Int,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        title = stringResource(R.string.dialog_pick_color),
        summary = stringResource(key.titleRes),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column {
            ColorPicker(
                color = Color(currentArgb),
                onColorChanged = { onPick(it.toArgb()) }
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                TextButton(
                    text = stringResource(R.string.action_reset_color),
                    modifier = Modifier.weight(1f),
                    onClick = onReset
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_save),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = onDismiss
                )
            }
        }
    }
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
        LyricTypefaceResolver.FAMILY_NOTO_SC -> R.string.option_noto_sans_sc
        LyricTypefaceResolver.FAMILY_CUSTOM -> R.string.option_custom_font
        else -> R.string.option_noto_sans
    })
    AodChoiceKind.TEXT_BRIGHTNESS -> context.getString(
        if (value == "dimmed") R.string.option_dimmed else R.string.option_default
    )
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
    AodChoiceKind.GLOW -> context.getString(
        if (value == "On") R.string.option_on else R.string.option_off
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
    GLOW(R.string.choice_glow),
    LINE_PROGRESS(R.string.choice_line_progress_effect),
    TEXT_BRIGHTNESS(R.string.choice_text_brightness),
    TRANSITION_SPEED(R.string.choice_scene_transition_speed),
    CARD_COLOR(R.string.choice_card_color)
}

private data class AodChoice(
    val kind: AodChoiceKind,
    val values: List<String>,
    val current: String,
    val onSelect: (String) -> Unit
)

internal fun updateCustomizationSurfaceEnabled(
    context: android.content.Context,
    surface: String,
    enabled: Boolean
): Boolean {
    val document = CustomizationRepository.loadDocument(context)
    val profiles = document.profiles.toMutableMap()
    profiles[surface] = (profiles[surface] ?: SurfaceProfile()).copy(enabled = enabled)
    if (!CustomizationRepository.saveDocument(context, document.copy(profiles = profiles))) {
        return false
    }
    syncCustomizationRuntime(context, CustomizationRepository.loadDocument(context))
    return true
}

internal fun withMetadataVisible(profile: SurfaceProfile, visible: Boolean): SurfaceProfile {
    val widgets = profile.widgets.filterNot { it.type == "metadata" }.toMutableList()
    if (visible) {
        widgets += com.eza.hyperglow.customization.WidgetSpec(
            "metadata",
            optional = true
        )
    }
    return profile.copy(metadataVisible = visible, widgets = widgets)
}
