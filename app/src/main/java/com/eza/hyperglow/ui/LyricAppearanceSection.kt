package com.eza.hyperglow.ui

import android.graphics.Typeface
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.eza.hyperglow.customization.CustomFontContract
import com.eza.hyperglow.customization.CustomFontEntry
import com.eza.hyperglow.customization.CustomFontStore
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.CustomizationEditorState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.LyricTimeOffset
import com.eza.hyperglow.customization.LINE_TRANSITION_MODES
import com.eza.hyperglow.customization.LINE_TRANSITION_SPEEDS
import com.eza.hyperglow.customization.METADATA_PART_ALBUM
import com.eza.hyperglow.customization.METADATA_PART_ARTIST
import com.eza.hyperglow.customization.METADATA_PART_TITLE
import com.eza.hyperglow.customization.METADATA_PARTS
import com.eza.hyperglow.customization.ARTWORK_SHAPES
import com.eza.hyperglow.customization.ARTWORK_SHAPE_CIRCLE
import com.eza.hyperglow.customization.ARTWORK_SIZE_MAX_DP
import com.eza.hyperglow.customization.ARTWORK_SIZE_MIN_DP
import com.eza.hyperglow.customization.METADATA_SEPARATORS
import com.eza.hyperglow.customization.METADATA_SEPARATOR_NEWLINE
import com.eza.hyperglow.customization.SECONDARY_TEXT_SIZE_PERCENT_MAX
import com.eza.hyperglow.customization.SECONDARY_TEXT_SIZE_PERCENT_MIN
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.customization.composeSongMetadata
import com.eza.hyperglow.customization.metadataGapCount
import com.eza.hyperglow.customization.metadataSeparatorText
import com.eza.hyperglow.customization.normalizeArtworkShape
import com.eza.hyperglow.customization.normalizeArtworkSizeDp
import com.eza.hyperglow.customization.normalizeMetadataParts
import com.eza.hyperglow.customization.normalizeMetadataSeparator
import com.eza.hyperglow.customization.normalizeMetadataSeparators
import com.eza.hyperglow.customization.normalizeSecondaryTextSizePercent
import com.eza.hyperglow.root.aod.LyricTypefaceResolver
import com.eza.hyperglow.root.aod.metadataWidgetHeightDp
import com.eza.hyperglow.root.projection.LyricDuetLine
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricWord
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.ResolvedPlacement
import com.eza.hyperglow.root.surface.WidgetMeasurement
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 歌词外观设置本体(息屏/锁屏共用一份实现,[surface] 决定编辑哪一面):顶部常驻可折叠实时预览
 * + 位置/文字与语言/效果/颜色/锁屏卡片/两个显示区域全部外观项 + 各选项弹窗。
 *
 * 原独立外观编辑器页已整块并入「设置」页的息屏/锁屏分段(去掉入口行),本组件即该分段的内容:
 * 自带滚动(预览常驻在列表上方,调节下方选项时效果实时可见),调用方以 weight 占满剩余高度,
 * [header]/[footer] 用来把分段自己的条目(息屏歌词开关 / 息屏行为入口 / 锁屏唤醒)插在列表首尾。
 */
@Composable
internal fun LyricAppearanceSection(
    surface: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    // 状态按曲面重建:两个分段复用同一实现,切分段即换编辑面(文档同源,各自只改本面)。
    var editorState by remember(surface) {
        mutableStateOf(
            CustomizationEditorState(
                CustomizationRepository.loadDocument(context),
                surface
            )
        )
    }
    var activeChoice by remember { mutableStateOf<AodChoice?>(null) }
    var activeColorPicker by remember { mutableStateOf<PaletteColor?>(null) }
    var activePartsEditor by remember { mutableStateOf(false) }
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

    /** 更新文档级(全局,两个曲面共用)设置,如歌词时间偏移;歌曲信息内容/识别对唱标记已为 per-surface,走 updateSelected。 */
    fun updateDocument(update: (CustomizationDocument) -> CustomizationDocument) {
        saveEditor(
            CustomizationEditorState(
                update(editorState.document),
                editorState.selectedSurface
            )
        )
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

    // 已导入字体清单:每次导入只新增一条,不再覆盖上一个(旧行为是固定单槽 custom.ttf);
    // 导入支持一次多选批量落盘。
    var customFonts by remember { mutableStateOf(CustomFontStore.list(context)) }
    // 展示名与内置字体同风格:字体文件 name 表真名优先,导入文件名兜底,
    // 不再显示「自定义字体」泛称(仅解析不出时才回落该文案)。
    val customFontNames = remember(customFonts) {
        customFonts.associate { entry ->
            entry.id to (CustomFontStore.label(context.filesDir, entry) ?: "")
        }
    }
    val fontImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<android.net.Uri>? ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val imported = uris.mapNotNull { importCustomFontFromUri(context, it) }
        if (imported.isNotEmpty()) {
            LyricTypefaceResolver.invalidateCustomCache()
            customFonts = CustomFontStore.list(context)
            updateSelected {
                it.copy(fontFamily = CustomFontContract.customFontFamily(imported.last().id))
            }
        }
        Toast.makeText(
            context,
            context.getString(
                if (imported.isNotEmpty()) R.string.toast_custom_font_imported
                else R.string.toast_custom_font_invalid
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    val selectedProfile = editorState.document.profiles[editorState.selectedSurface] ?: SurfaceProfile()
    // per-surface 内容项(歌曲信息内容/分隔符/识别对唱标记):本面未显式设置(null)时继承文档级
    // 默认值(与 SceneCompiler.withDocumentDefaults 同口径)。读取用有效值,写入落到本面,改一面
    // 不再联动另一面。文档级字段仅作旧配置升级的兜底默认值。
    val effectiveMetadataParts = selectedProfile.metadataParts ?: editorState.document.metadataParts
    val effectiveMetadataSeparators =
        selectedProfile.metadataSeparators ?: editorState.document.metadataSeparators
    val effectiveHideAlbumWhenSameAsTitle =
        selectedProfile.hideAlbumWhenSameAsTitle ?: editorState.document.hideAlbumWhenSameAsTitle
    val effectiveDuetMarkers = selectedProfile.duetMarkers ?: editorState.document.duetMarkers
    // 预览走与实机相同的编译管线(归一化/白名单),编辑后立即反映最终生效效果,所见即所得
    val compiledPreviewProfile = remember(editorState.document, editorState.selectedSurface) {
        SceneCompiler.compile(editorState.document)
            .profiles.getValue(editorState.selectedSurface)
    }
    var previewCollapsed by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        // 顶部常驻悬浮预览:调节下方选项时效果实时可见;点击标题栏可折叠让位给长列表
        AppearancePreviewHeader(
            expanded = !previewCollapsed,
            onToggle = { previewCollapsed = !previewCollapsed }
        )
        AnimatedVisibility(visible = !previewCollapsed) {
            AppearanceLivePreview(
                profile = compiledPreviewProfile,
                scenario = editorState.selectedSurface,
                // 内容项取本面编译后的已解析值(含文档级兜底),预览与实机同源。
                metadataParts = compiledPreviewProfile.metadataParts,
                metadataSeparators = compiledPreviewProfile.metadataSeparators,
                duetMarkers = compiledPreviewProfile.duetMarkers
            )
        }
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.weight(1f)
        ) {
        header?.let { slot -> item { slot() } }
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
                // 对唱分侧:仅在主对齐为「自动」时可感知(显式对齐整体覆盖分侧结果)。
                SwitchPreference(
                    selectedProfile.duetAlignment,
                    { enabled -> updateSelected { it.copy(duetAlignment = enabled) } },
                    stringResource(R.string.setting_duet_alignment),
                    summary = stringResource(R.string.summary_duet_alignment)
                )
                // 显示并发歌词(对唱):per-surface 开关,锁屏与息屏各自独立
                // (上游为 AOD-only,CN+ 扩展到锁屏卡片)。
                SwitchPreference(
                    selectedProfile.duetConcurrent,
                    { enabled -> updateSelected { it.copy(duetConcurrent = enabled) } },
                    stringResource(R.string.setting_duet_concurrent),
                    summary = stringResource(R.string.summary_duet_concurrent)
                )
                // 长间奏倒计时圆点(per-surface):本行 end 与下一行 start 空隙 ≥4s 时,歌词行
                // 槽位改画三个倒计时圆点(参考 HyperLyric 同名方案)。息屏与锁屏各自独立开关;
                // 关闭后间奏期恢复原来的上一行滞留/下一行预览呈现,零变化。
                SwitchPreference(
                    selectedProfile.interludeCountdown,
                    { enabled -> updateSelected { it.copy(interludeCountdown = enabled) } },
                    stringResource(R.string.setting_interlude_countdown),
                    summary = stringResource(R.string.summary_interlude_countdown)
                )
                // 识别对唱标记(per-surface):标记是内容级解释,息屏与锁屏各自独立生效,
                // 改本面不影响另一面。本面未显式设置时以文档级值作有效值。
                SwitchPreference(
                    effectiveDuetMarkers,
                    { enabled -> updateSelected { it.copy(duetMarkers = enabled) } },
                    stringResource(R.string.setting_duet_markers),
                    summary = stringResource(R.string.summary_duet_markers)
                )
                // 歌词时间偏移(文档级全局):显示时间轴 = 播放位置 − 偏移,正数延后、
                // 负数提前(参考 HyperLyric 同名能力);50ms 量化、±5s 封顶,编译归一,
                // 息屏与锁屏同源生效。
                SliderPreference(
                    value = editorState.document.lyricTimeOffsetMs.toFloat(),
                    onValueChange = { raw ->
                        val quantized = LyricTimeOffset.normalize(
                            (raw / LyricTimeOffset.STEP_MS.toFloat()).roundToInt() * LyricTimeOffset.STEP_MS
                        )
                        updateDocument { it.copy(lyricTimeOffsetMs = quantized) }
                    },
                    title = stringResource(R.string.setting_lyric_time_offset),
                    summary = stringResource(R.string.summary_lyric_time_offset),
                    valueText = run {
                        val v = editorState.document.lyricTimeOffsetMs
                        if (v > 0) "+$v ms" else "$v ms"
                    },
                    valueRange = LyricTimeOffset.MIN_OFFSET_MS.toFloat()..LyricTimeOffset.MAX_OFFSET_MS.toFloat(),
                    steps = 199
                )
                // 辅助文字内容:多选(转写 / 翻译 / 和声 可任意组合,逐项勾选即时生效)。
                // 值为逗号连接的内容集合 + 和声状态位(词表见 SECONDARY_CONTENT_TOKENS);
                // 历史四档按「显示和声」解释,归一与校验共用 normalizeAuxMode。
                AodChoiceRow(AodChoiceKind.SECONDARY_TEXT, selectedProfile.secondaryMode) {
                    openChoice(
                        AodChoiceKind.SECONDARY_TEXT,
                        com.eza.hyperglow.customization.SECONDARY_CONTENT_TOKENS,
                        selectedProfile.secondaryMode
                    ) { value -> updateSelected { it.copy(secondaryMode = value) } }
                }
                // 辅助文字大小(per-surface):相对主行的倍率,100% = 历史值,硬顶 0.62×主行。
                // 常显:和声行不受「辅助文字模式=仅主行」门控,照抄高亮/逐字两条的可见条件
                // 会漏掉和声场景(见 SurfaceProfile.secondaryTextSizePercent)。
                TextSizePreference(
                    title = stringResource(R.string.setting_secondary_text_size),
                    percent = selectedProfile.secondaryTextSizePercent.coerceIn(
                        SECONDARY_TEXT_SIZE_PERCENT_MIN,
                        SECONDARY_TEXT_SIZE_PERCENT_MAX
                    ),
                    minPercent = SECONDARY_TEXT_SIZE_PERCENT_MIN,
                    maxPercent = SECONDARY_TEXT_SIZE_PERCENT_MAX,
                    onDecrease = {
                        updateSelected {
                            it.copy(
                                secondaryTextSizePercent = normalizeSecondaryTextSizePercent(
                                    it.secondaryTextSizePercent - 5
                                )
                            )
                        }
                    },
                    onIncrease = {
                        updateSelected {
                            it.copy(
                                secondaryTextSizePercent = normalizeSecondaryTextSizePercent(
                                    it.secondaryTextSizePercent + 5
                                )
                            )
                        }
                    }
                )
                // 辅助文字自适应大小(per-surface):装得下恒用设定字号(既有呈现逐像素不变),
                // 装不下缩到可读性下限;第二行辅助行/和声行/并发行辅助行一并生效。
                SwitchPreference(
                    selectedProfile.secondaryAutoSize,
                    { enabled -> updateSelected { it.copy(secondaryAutoSize = enabled) } },
                    stringResource(R.string.setting_secondary_auto_size),
                    summary = stringResource(R.string.summary_secondary_auto_size)
                )
                // 露出门槛:任一辅助内容在显示(音标/翻译/和声,和声默认在——见 auxHarmonyShown)
                // 时露出;多选里三者都不勾时没有辅助行可作用,不露出。
                val auxAnyContent =
                    com.eza.hyperglow.customization.auxShowsReading(selectedProfile.secondaryMode) ||
                        com.eza.hyperglow.customization.auxShowsTranslation(
                            selectedProfile.secondaryMode
                        ) ||
                        com.eza.hyperglow.customization.auxHarmonyShown(
                            selectedProfile.secondaryMode
                        )
                if (auxAnyContent || selectedProfile.secondaryNextLine) {
                    SwitchPreference(
                        selectedProfile.secondaryTextBright,
                        { bright -> updateSelected { it.copy(secondaryTextBright = bright) } },
                        stringResource(R.string.setting_bright_secondary_text)
                    )
                }
                // 辅助文字逐字效果(per-surface):第一行辅助文字行(音标/翻译)与和声行随歌词逐字点亮。
                // 只在有辅助内容显示时露出(没有任何辅助行时该开关无对象,见 auxAnyContent);
                // 第二行歌词及其辅助行不参与。
                if (auxAnyContent) {
                    SwitchPreference(
                        selectedProfile.secondaryWordKaraoke,
                        { enabled ->
                            updateSelected { it.copy(secondaryWordKaraoke = enabled) }
                        },
                        stringResource(R.string.setting_secondary_word_karaoke),
                        summary = stringResource(R.string.summary_secondary_word_karaoke)
                    )
                }
                SwitchPreference(
                    selectedProfile.secondaryNextLine,
                    { enabled -> updateSelected { it.copy(secondaryNextLine = enabled) } },
                    stringResource(R.string.setting_secondary_next_line)
                )
                // 「显示第二行辅助文字」以第二行歌词行实际显示为前提:「显示下一行歌词」
                // 或「辅助文字显示第二行歌词」任一开启时露出;两者都关时没有第二行歌词行,
                // 本开关无第二行辅助文字行可追加,故不露出。
                if (selectedProfile.showNextLine || selectedProfile.secondaryNextLine) {
                    SwitchPreference(
                        selectedProfile.nextLineAux,
                        { enabled -> updateSelected { it.copy(nextLineAux = enabled) } },
                        stringResource(R.string.setting_next_line_aux)
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
                if (selectedProfile.showNextLine || selectedProfile.secondaryNextLine ||
                    selectedProfile.nextLineAux
                ) {
                    AodChoiceRow(
                        AodChoiceKind.SECOND_LINE_ALIGNMENT,
                        selectedProfile.nextLineAlignment
                    ) {
                        openChoice(
                            AodChoiceKind.SECOND_LINE_ALIGNMENT,
                            listOf("auto", "start", "center", "end"),
                            selectedProfile.nextLineAlignment
                        ) { value -> updateSelected { it.copy(nextLineAlignment = value) } }
                    }
                }
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
                    AodChoiceRow(
                        AodChoiceKind.SONG_INFO_ALIGNMENT,
                        selectedProfile.metadataAlignment
                    ) {
                        openChoice(
                            AodChoiceKind.SONG_INFO_ALIGNMENT,
                            listOf("auto", "start", "center", "end"),
                            selectedProfile.metadataAlignment
                        ) { value -> updateSelected { it.copy(metadataAlignment = value) } }
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
                    // 内容编辑:勾选/排序显示部分(歌名/歌手/专辑),并逐槽独立选择相邻两项之间的分隔符。
                    ArrowPreference(
                        title = stringResource(R.string.setting_song_info_parts),
                        summary = metadataPartsDisplayLabel(context, effectiveMetadataParts),
                        onClick = { activePartsEditor = true }
                    )
                    // 专辑与歌名一致时隐藏专辑:仅在专辑作为显示部分时露出(否则无专辑可隐藏)。
                    if (normalizeMetadataParts(effectiveMetadataParts)
                            .split(',').contains(METADATA_PART_ALBUM)
                    ) {
                        SwitchPreference(
                            effectiveHideAlbumWhenSameAsTitle,
                            { enabled ->
                                updateSelected {
                                    it.copy(hideAlbumWhenSameAsTitle = enabled)
                                }
                            },
                            stringResource(R.string.setting_hide_album_same_as_title),
                            summary = stringResource(R.string.summary_hide_album_same_as_title)
                        )
                    }
                    // 歌曲图片(歌曲信息左侧):显示开关 → 形状(方形/圆形) → 自适应缩放
                    // (关闭时露出自定义大小拖动条) → 旋转(仅圆形)。
                    SwitchPreference(
                        selectedProfile.artworkVisible,
                        { visible -> updateSelected { it.copy(artworkVisible = visible) } },
                        stringResource(R.string.setting_show_song_artwork)
                    )
                    if (selectedProfile.artworkVisible) {
                        AodChoiceRow(
                            AodChoiceKind.SONG_ARTWORK_SHAPE,
                            selectedProfile.artworkShape
                        ) {
                            openChoice(
                                AodChoiceKind.SONG_ARTWORK_SHAPE,
                                ARTWORK_SHAPES,
                                selectedProfile.artworkShape
                            ) { value ->
                                updateSelected {
                                    it.copy(artworkShape = normalizeArtworkShape(value))
                                }
                            }
                        }
                        // 自适应缩放:开启时边长随歌曲信息字号缩放;关闭时露出自定义大小拖动条。
                        SwitchPreference(
                            selectedProfile.artworkAdaptiveScale,
                            { adaptive ->
                                updateSelected { it.copy(artworkAdaptiveScale = adaptive) }
                            },
                            stringResource(R.string.setting_song_artwork_adaptive)
                        )
                        if (!selectedProfile.artworkAdaptiveScale) {
                            val sizeDp = normalizeArtworkSizeDp(selectedProfile.artworkSizeDp)
                            SliderPreference(
                                value = sizeDp.toFloat(),
                                onValueChange = { value ->
                                    updateSelected {
                                        it.copy(
                                            artworkSizeDp =
                                                normalizeArtworkSizeDp(value.roundToInt())
                                        )
                                    }
                                },
                                title = stringResource(R.string.setting_song_artwork_size),
                                summary = stringResource(R.string.summary_song_artwork_size),
                                valueText = "${sizeDp}dp",
                                valueRange =
                                    ARTWORK_SIZE_MIN_DP.toFloat()..ARTWORK_SIZE_MAX_DP.toFloat(),
                                steps = ARTWORK_SIZE_MAX_DP - ARTWORK_SIZE_MIN_DP - 1
                            )
                        }
                        if (selectedProfile.artworkShape == ARTWORK_SHAPE_CIRCLE) {
                            SwitchPreference(
                                selectedProfile.artworkSpin,
                                { spin -> updateSelected { it.copy(artworkSpin = spin) } },
                                stringResource(R.string.setting_song_artwork_spin)
                            )
                            if (selectedProfile.artworkSpin) {
                                SwitchPreference(
                                    selectedProfile.artworkSpinWhenPaused,
                                    { enabled ->
                                        updateSelected { it.copy(artworkSpinWhenPaused = enabled) }
                                    },
                                    stringResource(R.string.setting_song_artwork_spin_paused)
                                )
                            }
                        }
                    }
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
                AodChoiceRow(AodChoiceKind.FONT, selectedProfile.fontFamily, customFontNames) {
                    openChoice(
                        AodChoiceKind.FONT,
                        buildList {
                            add("noto")
                            add("spotify")
                            add("apple")
                            add(LyricTypefaceResolver.FAMILY_NOTO_SC)
                            customFonts.forEach {
                                add(CustomFontContract.customFontFamily(it.id))
                            }
                            // 当前选中的自定义字体(含历史单槽)已被删除时仍列出,保证可见可改。
                            val currentId = CustomFontContract.fontIdOf(selectedProfile.fontFamily)
                            if (currentId != null && customFonts.none { it.id == currentId }) {
                                add(CustomFontContract.customFontFamily(currentId))
                            }
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
                        listOf("Minimal", "Gradient", "BetterLyrics"),
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
                AodChoiceRow(AodChoiceKind.LINE_TRANSITION, selectedProfile.lineTransition) {
                    openChoice(
                        AodChoiceKind.LINE_TRANSITION,
                        LINE_TRANSITION_MODES,
                        selectedProfile.lineTransition
                    ) { value -> updateSelected { it.copy(lineTransition = value) } }
                }
                AodChoiceRow(AodChoiceKind.LINE_TRANSITION_SPEED, selectedProfile.lineTransitionSpeed) {
                    openChoice(
                        AodChoiceKind.LINE_TRANSITION_SPEED,
                        LINE_TRANSITION_SPEEDS,
                        selectedProfile.lineTransitionSpeed
                    ) { value -> updateSelected { it.copy(lineTransitionSpeed = value) } }
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
            Card(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = appCardContainerColor(),
                    contentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer)
                )
            ) {
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
        footer?.let { slot -> item { slot() } }
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

    if (activePartsEditor) {
        WindowDialog(
            title = stringResource(R.string.setting_song_info_parts),
            show = true,
            onDismissRequest = { activePartsEditor = false }
        ) {
            Column(Modifier.dialogScrollable()) {
                val enabledParts = normalizeMetadataParts(effectiveMetadataParts)
                    .split(',')
                val disabledParts = METADATA_PARTS.filterNot { it in enabledParts }
                val separatorTokens = normalizeMetadataSeparators(
                    effectiveMetadataSeparators,
                    effectiveMetadataParts
                ).split(',').filter { it.isNotEmpty() }

                fun partLabel(part: String): String = context.getString(
                    when (part) {
                        METADATA_PART_ARTIST -> R.string.option_song_info_part_artist
                        METADATA_PART_ALBUM -> R.string.option_song_info_part_album
                        else -> R.string.option_song_info_part_title
                    }
                )
                fun tokenAt(index: Int): String =
                    separatorTokens.getOrNull(index) ?: METADATA_SEPARATOR_NEWLINE
                fun commitParts(next: List<String>) {
                    updateSelected {
                        it.copy(metadataParts = normalizeMetadataParts(next.joinToString(",")))
                    }
                }
                fun movePart(from: Int, to: Int) {
                    if (to !in enabledParts.indices) return
                    val next = enabledParts.toMutableList()
                    next[from] = next[to].also { next[to] = next[from] }
                    commitParts(next)
                }
                fun setGap(index: Int, value: String) {
                    val gaps = metadataGapCount(effectiveMetadataParts)
                    if (index !in 0 until gaps) return
                    val tokens = MutableList(gaps) { tokenAt(it) }
                    tokens[index] = normalizeMetadataSeparator(value)
                    updateSelected {
                        it.copy(metadataSeparators = tokens.joinToString(","))
                    }
                }

                // 已选部分:开关关闭即移除,上/下按钮调整顺序(顺序即显示顺序)。
                enabledParts.forEachIndexed { index, part ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SwitchPreference(
                            checked = true,
                            onCheckedChange = {
                                if (enabledParts.size > 1) commitParts(enabledParts - part)
                            },
                            title = partLabel(part),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        MetadataOrderButton(
                            text = "↑",
                            enabled = index > 0,
                            onClick = { movePart(index, index - 1) }
                        )
                        Spacer(Modifier.width(6.dp))
                        MetadataOrderButton(
                            text = "↓",
                            enabled = index < enabledParts.lastIndex,
                            onClick = { movePart(index, index + 1) }
                        )
                    }
                    // 相邻两项之间的分隔符:逐槽独立选择。
                    if (index < enabledParts.lastIndex) {
                        ArrowPreference(
                            title = context.getString(
                                R.string.label_song_info_pair,
                                partLabel(part),
                                partLabel(enabledParts[index + 1])
                            ),
                            summary = metadataSeparatorDisplayLabel(context, tokenAt(index)),
                            onClick = {
                                openChoice(
                                    AodChoiceKind.SONG_INFO_SEPARATOR,
                                    METADATA_SEPARATORS,
                                    tokenAt(index)
                                ) { value -> setGap(index, value) }
                            }
                        )
                    }
                }
                // 未选部分:开关打开即追加到末尾。
                disabledParts.forEach { part ->
                    SwitchPreference(
                        checked = false,
                        onCheckedChange = { commitParts(enabledParts + part) },
                        title = partLabel(part)
                    )
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

    // 选项弹窗最后合成:保证在内容编辑弹窗之上弹出(逐槽分隔符选择需要覆盖在其上方)。
    activeChoice?.let { selected ->
        if (selected.kind == AodChoiceKind.SECONDARY_TEXT) {
            // 辅助文字内容:多选。逐项勾选即时生效(不关闭弹窗);勾选态用本地状态推进——
            // AodChoice.current 是打开弹窗那一刻的快照,拿它判勾选会永远停在打开时的样子。
            var mode by remember(selected) { mutableStateOf(selected.current) }
            WindowDialog(
                title = stringResource(selected.kind.titleRes),
                show = true,
                onDismissRequest = { activeChoice = null }
            ) {
                Column {
                    selected.values.forEach { value ->
                        SwitchPreference(
                            com.eza.hyperglow.customization.auxContentChecked(mode, value),
                            { _ ->
                                val next = com.eza.hyperglow.customization.toggleAuxContent(
                                    mode,
                                    value
                                )
                                mode = next
                                selected.onSelect(next)
                            },
                            auxContentItemLabel(context, value)
                        )
                    }
                }
            }
        } else if (selected.kind == AodChoiceKind.FONT) {
            FontChoiceDialog(
                selected = selected,
                customNames = customFontNames,
                onDismiss = { activeChoice = null }
            )
        } else {
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
    }
}

internal fun resolvePreviewPlacement(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    width: Float,
    height: Float,
    metadataExtraLines: Int = 0
): ResolvedPlacement {
    val environment = previewEnvironment(scenario, width, height)
    val metadataHeight = if (profile.metadataVisible &&
        profile.widgets.any { it.type == "metadata" }
    ) {
        height * 0.10f *
            (metadataWidgetHeightDp(profile.metadataSizePercent, metadataExtraLines) /
                metadataWidgetHeightDp(100))
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
 *
 * 演示歌词跟随界面语言:English 走英文演示曲([demoLines]、歌名/歌手见 [DEMO_TRACK_ENGLISH]),
 * 其余(跟随系统/简体中文)走中文演示曲。见 [demoLines]。
 */
@Composable
internal fun collectDemoSnapshot(
    metadataParts: String,
    metadataSeparators: String,
    hideAlbumWhenSameAsTitle: Boolean = false
): LyricSnapshot {
    val context = LocalContext.current
    val lines = demoLines(context)
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DEMO_LINE_SWITCH_MS)
            index = (index + 1) % lines.size
        }
    }
    val line = lines[index]
    val track = demoTrack(context)
    return LyricSnapshot(
        revision = index.toLong(),
        trackGeneration = 1,
        updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
        visible = true,
        original = line.original,
        romanized = line.romanized,
        translated = line.translated,
        // 演示快照携带下一行文本与其辅助文字,让「显示下一行歌词」「辅助文字显示第二行歌词」
        // 与「显示第二行辅助文字」在预览可见。
        nextLine = lines[(index + 1) % lines.size].original,
        nextLineRomanized = lines[(index + 1) % lines.size].romanized,
        nextLineTranslated = lines[(index + 1) % lines.size].translated,
        metadata = composeSongMetadata(
            title = track.title,
            artist = track.artist,
            album = track.album,
            parts = metadataParts,
            separators = metadataSeparators,
            hideAlbumWhenSameAsTitle = hideAlbumWhenSameAsTitle
        ),
        lineLevelSync = true,
        lineStartMs = 0,
        lineEndMs = DEMO_LINE_SWITCH_MS,
        durationMs = lines.size * DEMO_LINE_SWITCH_MS,
        positionMs = ((index * DEMO_LINE_SWITCH_MS).toFloat()).toLong(),
        sampledAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
        // 演示快照携带逐字时间戳:让「BetterLyrics」档的逐字扫光、未唱下沉/已唱上浮与
        // 长音节放大辉光在无实时歌词时也能在预览里看出效果(纯行级源仍退化为逐行扫光)。
        words = demoWords(line.original),
        // 演示快照携带注音:让「注音」开关在无实时歌词时也能在预览里看出效果
        // (实机仅在 rubyVisible == false 时清空,见 LyricCanvasMapper)。
        ruby = line.ruby,
        // 演示并发行(和声):让「显示并发歌词(对唱)」开关在无实时歌词时也能在预览里
        // 看出效果;装配规则见 [demoDuetLine](纯函数,JVM 可测)。
        duetLine = demoDuetLine(line),
        // 演示长间奏窗口:让「长间奏显示倒计时圆点」开关在无实时歌词时也能在预览里看出效果。
        // 只在一轮循环里的一拍携带(装配见 [demoInterludeSpan],纯函数 JVM 可测),
        // 其余各拍照常显示歌词文本——与演示并发行同式:演示数据只承载"这一拍有该特性"。
        interludeStartMs = demoInterludeSpan(index)?.first ?: 0L,
        interludeEndMs = demoInterludeSpan(index)?.last ?: 0L
    )
}

/**
 * 演示快照的长间奏窗口(纯函数,JVM 可测):只在循环里的一拍([DEMO_INTERLUDE_INDEX])携带,
 * 其余各拍返回 null(无长间奏),预览照常显示歌词文本。
 *
 * 窗口取 [DEMO_INTERLUDE_WINDOW_MS](5s,确实过 4s 阈值),并把该拍的演示位置放在窗口内
 * [DEMO_INTERLUDE_ENTRY_PROGRESS](40%)处 —— 落在"前两个点已点亮、尚未开始渐隐"的
 * 阶段,是最能说明该特性的定格形态。演示位置在一拍内不变(演示快照逐拍切换,不像实机
 * 逐帧投影),故圆点是定格的;这与演示并发行同为静态演示数据,不是逐帧动画。
 */
internal fun demoInterludeSpan(index: Int): LongRange? {
    if (index != DEMO_INTERLUDE_INDEX) return null
    val positionMs = index * DEMO_LINE_SWITCH_MS
    val startMs = positionMs - (DEMO_INTERLUDE_WINDOW_MS * DEMO_INTERLUDE_ENTRY_PROGRESS).toLong()
    return startMs..(startMs + DEMO_INTERLUDE_WINDOW_MS)
}

/**
 * 演示副行 → 并发行(纯函数,JVM 可测):文本为空不产出(与实机 duetLine 文本为空整条
 * 丢弃同口径)。[DemoLine.duetHarmony] 决定它走哪条车道:带括号的回声句(AMLL x-bg 形态)
 * 按**和声**下发,预览与实机同源走辅助行车道(小字号辅助行)——不再堆主行同款大字行
 * (两条一样的大字行是真机 2026-10-07 反馈的错观感);不同声部的对唱句按**对唱**下发,
 * 走主行同款大字行(与实机非 BG 行同源)。两种样式都要在无实时歌词时可见。
 */
internal fun demoDuetLine(line: DemoLine): LyricDuetLine? =
    line.duet?.takeIf { it.isNotBlank() }?.let { duet ->
        LyricDuetLine(
            text = duet,
            harmony = line.duetHarmony,
            lineStartMs = 0,
            lineEndMs = DEMO_LINE_SWITCH_MS,
            words = demoWords(duet)
        )
    }

/**
 * 演示歌词行按界面语言选择:English 用英文演示曲,其余(跟随系统/简体中文)用中文演示曲。
 *
 * 判据复用界面语言策略的 [currentUiLanguage] / [resolveUiLanguage] —— 与「界面语言」设置
 * 同一来源,不另造一套;系统语言为英文但用户选了「简体中文」时以用户选择为准。
 */
@Composable
private fun demoLines(context: android.content.Context): List<DemoLine> =
    demoLines(currentUiLanguage(context))

/** 演示曲的歌曲信息(歌名/歌手/专辑),按界面语言选择;与 [demoLines] 同一判据。 */
@Composable
private fun demoTrack(context: android.content.Context): DemoTrack =
    demoTrack(currentUiLanguage(context))

/** 纯函数判据(JVM 可测):只有 English 走英文演示曲,SYSTEM/简体中文都走中文演示曲。 */
internal fun demoLines(language: UiLanguage): List<DemoLine> =
    if (language == UiLanguage.ENGLISH) DEMO_LINES_EN else DEMO_LINES_ZH

/** 演示曲的歌曲信息(纯函数,与 [demoLines] 同一判据)。 */
internal fun demoTrack(language: UiLanguage): DemoTrack =
    if (language == UiLanguage.ENGLISH) DEMO_TRACK_ENGLISH else DEMO_TRACK_CHINESE

internal class DemoLine(
    val original: String,
    val romanized: String,
    val translated: String,
    /**
     * 演示注音段:整行逐词标注(词间空白不属任何段),让「显示振假名」开关在无实时歌词时也能
     * 在预览里看出效果。必须覆盖整行 —— 只标首词会在预览里留下一截拼音(owner 2026-10-06
     * 反馈的「文字上方零星的转写内容」),且各段读音拼接后要与 [romanized] 逐字一致。
     */
    val ruby: List<LyricRuby>,
    /**
     * 同句副行(可空,仅演示数据用):让「显示并发歌词(对唱)」在无实时歌词时也能在预览里
     * 看出来。演示快照不走按面标记剥离(见 collectDemoSnapshot),这里直接写剥离后的形态,
     * 避免预览出现实机默认设置下不会上屏的行首标记文本。
     */
    val duet: String? = null,
    /**
     * 副行是否和声(role=BG 的 x-bg 回声):true 走辅助行车道(小字号),false 走主行同款
     * 大字行。演示数据两种都给(回声句 + 不同声部的对唱句),否则「对唱=主行同款」这一样式
     * 在无实时歌词时看不到(见 [demoDuetLine])。
     */
    val duetHarmony: Boolean = true
)

internal class DemoTrack(
    val title: String,
    val artist: String,
    val album: String
)

internal val DEMO_TRACK_CHINESE = DemoTrack("蝴蝶", "洛天依", "专辑示例")

internal val DEMO_TRACK_ENGLISH = DemoTrack(
    "Take My Hand",
    "DAISHI DANCE, Cécile Corbel",
    "Take Me Hand"
)

internal val DEMO_LINES_ZH = listOf(
    DemoLine(
        "你说你来到这世界的那天 神给了每个人快乐入场券",
        "nǐ shuō nǐ lái dào zhè shìjiè de nà tiān shén gěi le měi gè rén kuàilè rùchǎngquàn",
        "You said the day you came to this world, heaven gave everyone a ticket to joy",
        listOf(
            LyricRuby(0, 3, "nǐ shuō nǐ"),
            LyricRuby(3, 5, "lái dào"),
            LyricRuby(5, 6, "zhè"),
            LyricRuby(6, 8, "shìjiè"),
            LyricRuby(8, 9, "de"),
            LyricRuby(9, 11, "nà tiān"),
            LyricRuby(12, 13, "shén"),
            LyricRuby(13, 15, "gěi le"),
            LyricRuby(15, 18, "měi gè rén"),
            LyricRuby(18, 20, "kuàilè"),
            LyricRuby(20, 23, "rùchǎngquàn")
        )
    ),
    DemoLine(
        "那一只蝴蝶 拼了命破茧 却没有漂亮的鳞片",
        "nà yī zhī húdié pīn le mìng pò jiǎn què méi yǒu piàoliang de lín piàn",
        "That butterfly bursts its cocoon with all its might, yet bears no pretty scales",
        listOf(
            LyricRuby(0, 3, "nà yī zhī"),
            LyricRuby(3, 5, "húdié"),
            LyricRuby(6, 8, "pīn le"),
            LyricRuby(8, 9, "mìng"),
            LyricRuby(9, 11, "pò jiǎn"),
            LyricRuby(12, 14, "què méi"),
            LyricRuby(14, 15, "yǒu"),
            LyricRuby(15, 17, "piàoliang"),
            LyricRuby(17, 18, "de"),
            LyricRuby(18, 20, "lín piàn")
        ),
        // 和声副行:带括号的回声句(x-bg 形态),走辅助行车道(见 demoDuetLine)。
        duet = "（也要飞向那片蓝天）"
    ),
    DemoLine(
        "走吧 就算我们无法让大雨停下",
        "zǒu ba jiùsuàn wǒmen wúfǎ ràng dàyǔ tíng xià",
        "Let's go, even if we can't make the heavy rain stop",
        listOf(
            LyricRuby(0, 2, "zǒu ba"),
            LyricRuby(3, 5, "jiùsuàn"),
            LyricRuby(5, 7, "wǒmen"),
            LyricRuby(7, 9, "wúfǎ"),
            LyricRuby(9, 10, "ràng"),
            LyricRuby(10, 12, "dàyǔ"),
            LyricRuby(12, 14, "tíng xià")
        )
    ),
    DemoLine(
        "你我生来时就注定 天真而伟大",
        "nǐ wǒ shēnglái shí jiù zhùdìng tiānzhēn ér wěidà",
        "You and I are destined from birth to be innocent and great",
        listOf(
            LyricRuby(0, 2, "nǐ wǒ"),
            LyricRuby(2, 4, "shēnglái"),
            LyricRuby(4, 5, "shí"),
            LyricRuby(5, 6, "jiù"),
            LyricRuby(6, 8, "zhùdìng"),
            LyricRuby(9, 11, "tiānzhēn"),
            LyricRuby(11, 12, "ér"),
            LyricRuby(12, 14, "wěidà")
        ),
        // 对唱副行:不同声部的答句(非回声),走主行同款大字行(见 demoDuetLine);
        // 文本与主行不同文,避免预览出现「两条一样的大字行」的错观感。
        duet = "哪怕世界从未回答",
        duetHarmony = false
    )
)

/**
 * 英文演示歌词行(《Take My Hand》— DAISHI DANCE / Cécile Corbel)。
 *
 * 英文曲没有拼音可注,但辅助文字行必须有真实内容 —— 「转写」「翻译」开关在英文界面下才看
 * 得出效果(owner 2026-10-06:语言为英文时预览要带翻译与转写,且受下方辅助文字选项控制)。
 * romanized 用国际音标(英文的「转写」即音标),translated 用中译(与中文演示曲给英译对称);
 * [DemoLine.ruby] 仍留空:英文曲没有振假名,填假注音只会让「显示振假名」开关的预览失真。
 */
internal val DEMO_LINES_EN = listOf(
    DemoLine(
        "In my dreams, I feel your light",
        "ɪn maɪ driːmz aɪ fiːl jɔː laɪt",
        "在我的梦里，我感受到你的光芒",
        emptyList(),
        // 和声副行:带括号的回声句(x-bg 形态),走辅助行车道(见 demoDuetLine)。
        duet = "(Shining through the endless night)"
    ),
    DemoLine(
        "I feel love is born again",
        "aɪ fiːl lʌv ɪz bɔːn əˈgen",
        "我感到爱再次诞生",
        emptyList()
    ),
    DemoLine(
        "Fireflies in the moonlight",
        "ˈfaɪəflaɪz ɪn ðə ˈmuːnlaɪt",
        "月光下的萤火虫",
        emptyList()
    ),
    DemoLine(
        "Take my hand now, stay close to me",
        "teɪk maɪ hænd naʊ steɪ kloʊs tə miː",
        "现在握住我的手，靠近我",
        emptyList(),
        // 对唱副行:不同声部的答句(非回声),走主行同款大字行(见 demoDuetLine);
        // 文本与主行不同文,避免预览出现「两条一样的大字行」的错观感。
        duet = "And I will never let you go",
        duetHarmony = false
    )
)

/** How long each demo line stays on screen before cycling to the next. */
internal const val DEMO_LINE_SWITCH_MS = 2_500L

/**
 * 演示快照携带长间奏窗口的拍号(纯函数 [demoInterludeSpan] 用):只在这一拍演示圆点,
 * 其余各拍照常显示歌词文本。
 */
private const val DEMO_INTERLUDE_INDEX = 1

/** 演示长间奏窗口长度:5s,确实过 [MIN_INTERLUDE_GAP_MS](4s) 阈值。 */
private const val DEMO_INTERLUDE_WINDOW_MS = 5_000L

/**
 * 演示圆点的定格进度(窗口内):40% —— 前两个点已点亮、渐隐段(60%)尚未开始,
 * 是最能说明该特性的形态。
 */
private const val DEMO_INTERLUDE_ENTRY_PROGRESS = 0.4f

/**
 * 演示逐字时间戳:把演示行切成词(空格处切分,否则每 2 字一块),首词按长音节加权
 * (占整行 30%,即 750ms ≥ 700ms 阈值),其余均分。目的是让「BetterLyrics」档的逐字效果
 * (逐字扫光、未唱下沉/已唱上浮、长音节放大辉光)在无实时歌词时也能在预览里可见;
 * 区间为原文下标,与实机词位同语义(见 PreviewComponents.previewWordRuns)。
 */
private fun demoWords(text: String): List<LyricWord> {
    val spans = demoWordSpans(text)
    if (spans.isEmpty()) return emptyList()
    val firstMs = DEMO_LINE_SWITCH_MS * 30L / 100L
    val restCount = (spans.size - 1).coerceAtLeast(1)
    val restMs = ((DEMO_LINE_SWITCH_MS - firstMs) / restCount).coerceAtLeast(1L)
    var elapsed = 0L
    return spans.mapIndexed { index, span ->
        val duration = if (index == 0) firstMs else restMs
        val start = elapsed
        val end = start + duration
        elapsed = end
        LyricWord(
            text = text.substring(span.first, span.last + 1),
            romanized = "",
            startMs = start,
            endMs = end,
            boundaryAfter = true,
            sourceStart = span.first,
            sourceEnd = span.last + 1
        )
    }
}

/** 演示行的词区间:空格处切分,否则每 2 字一块(区间为原文下标,含首不含尾)。 */
private fun demoWordSpans(text: String): List<IntRange> {
    val spans = ArrayList<IntRange>()
    var start = -1
    var count = 0
    var index = 0
    while (index < text.length) {
        if (text[index].isWhitespace()) {
            if (start >= 0) {
                spans += start until index
                start = -1
                count = 0
            }
        } else {
            if (start < 0) start = index
            count++
            if (count >= 2) {
                spans += start until index + 1
                start = -1
                count = 0
            }
        }
        index++
    }
    if (start >= 0) spans += start until text.length
    return spans
}

/** SAF 文档选择器里取原始文件名,作为字体展示名;取不到时由存储层回落到 id。 */
private fun queryDisplayName(
    context: android.content.Context,
    uri: android.net.Uri
): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()

/**
 * 导入单个字体文档:读流 → 落盘登记 → 用真实 Typeface 校验可读性,不可读立即回滚删除。
 * 批量导入逐个调用,单个失败不影响其它。
 */
private fun importCustomFontFromUri(
    context: android.content.Context,
    uri: android.net.Uri
): CustomFontEntry? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        input.readNBytes(CustomFontContract.MAX_FONT_BYTES + 1)
    } ?: error("Font file unavailable")
    val entry = CustomFontStore.import(
        fontRoot = context.filesDir,
        bytes = bytes,
        displayName = queryDisplayName(context, uri),
        nowMs = System.currentTimeMillis()
    ) ?: error("Invalid font file")
    val target = CustomFontContract.fontFile(context.filesDir, entry.id)
    runCatching { Typeface.createFromFile(target) }.getOrElse {
        CustomFontStore.delete(context.filesDir, entry.id)
        error("Font file unreadable")
    }
    entry
}.getOrNull()

/**
 * 字体选择对话框:每个候选行下方给出中英文混排预览("[font_preview_sample]",
 * 中文 + 拉丁 + 数字),用该项真实解析出的 Typeface 渲染 —— 所见即实机所得。
 * 已导入的自定义字体与内置字体同列同款:按字体真名展示(name 表优先,导入文件名兜底),
 * 批量导入多个互不覆盖。
 */
@Composable
private fun FontChoiceDialog(
    selected: AodChoice,
    customNames: Map<String, String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    WindowDialog(
        title = stringResource(selected.kind.titleRes),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = dialogContentMaxHeight())
                .verticalScroll(rememberScrollState())
        ) {
            selected.values.forEach { value ->
                Column {
                    RadioButtonPreference(
                        choiceDisplayLabel(context, AodChoiceKind.FONT, value, customNames),
                        selected.current == value,
                        {
                            selected.onSelect(value)
                            onDismiss()
                        }
                    )
                    FontPreviewLine(value)
                }
            }
        }
    }
}

@Composable
private fun FontPreviewLine(family: String) {
    val context = LocalContext.current
    val typeface = remember(family) {
        LyricTypefaceResolver.resolve(context, family, "Regular")
    }
    Text(
        text = stringResource(R.string.font_preview_sample),
        fontFamily = FontFamily(typeface),
        fontSize = 16.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 24.dp, end = 20.dp, bottom = 10.dp)
    )
}

@Composable
private fun AodChoiceRow(
    kind: AodChoiceKind,
    value: String,
    customFontNames: Map<String, String> = emptyMap(),
    onClick: () -> Unit
) {
    val context = LocalContext.current
    ArrowPreference(
        title = stringResource(kind.titleRes),
        summary = choiceDisplayLabel(context, kind, value, customFontNames),
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
            ColorSwatchHexInput(
                argb = currentArgb,
                onColorChange = onPick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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

/** 歌曲信息部分排序按钮(上/下移),与字号步进按钮同风格。 */
@Composable
private fun MetadataOrderButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
        cornerRadius = 24.dp,
        minHeight = 44.dp,
        minWidth = 44.dp
    ) {
        Text(text, fontSize = 20.sp)
    }
}

@Composable
private fun TextSizePreference(
    title: String,
    percent: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    minPercent: Int = 50,
    maxPercent: Int = 200
) {
    BasicComponent(
        title = title,
        endActions = {
            IconButton(
                onClick = onDecrease,
                enabled = percent > minPercent,
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                cornerRadius = 24.dp,
                minHeight = 48.dp,
                minWidth = 48.dp
            ) {
                Text("−", fontSize = 28.sp)
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
                enabled = percent < maxPercent,
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

/** 辅助文字多选条目标签(内容词表条目;摘要另按集合拼接,见 [choiceDisplayLabel])。 */
private fun auxContentItemLabel(context: android.content.Context, token: String): String =
    context.getString(
        when (token) {
            "Transliteration" -> R.string.option_transliteration
            "Translation" -> R.string.option_translation
            else -> R.string.option_background_vocal
        }
    )

private fun choiceDisplayLabel(
    context: android.content.Context,
    kind: AodChoiceKind,
    value: String,
    customFontNames: Map<String, String> = emptyMap()
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
    AodChoiceKind.ALIGNMENT,
    AodChoiceKind.SONG_INFO_ALIGNMENT,
    AodChoiceKind.SECOND_LINE_ALIGNMENT -> context.getString(when (value) {
        "auto" -> R.string.option_automatic
        "start" -> R.string.option_start
        "center" -> R.string.option_center
        "end" -> R.string.option_end
        else -> R.string.option_automatic
    })
    AodChoiceKind.SONG_INFO_POSITION -> context.getString(
        if (value == "bottom") R.string.option_bottom else R.string.option_top
    )
    AodChoiceKind.SONG_INFO_SEPARATOR -> metadataSeparatorDisplayLabel(context, value)
    AodChoiceKind.SONG_ARTWORK_SHAPE -> context.getString(
        if (value == ARTWORK_SHAPE_CIRCLE) {
            R.string.option_song_artwork_shape_circle
        } else {
            R.string.option_song_artwork_shape_square
        }
    )
    AodChoiceKind.LYRIC_LINES -> if (value == "0") {
        context.getString(R.string.option_no_limit)
    } else {
        value
    }
    AodChoiceKind.FONT -> CustomFontContract.fontIdOf(value)
        ?.let { id ->
            customFontNames[id]?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.option_custom_font)
        }
        ?: context.getString(when (value) {
            "noto" -> R.string.option_noto_sans
            "spotify" -> R.string.option_spotify_mix
            "apple" -> R.string.option_sf_pro_display
            LyricTypefaceResolver.FAMILY_NOTO_SC -> R.string.option_noto_sans_sc
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
    AodChoiceKind.LINE_TRANSITION -> context.getString(when (value) {
        "Fade up" -> R.string.option_fade_up
        "Crossfade" -> R.string.option_crossfade
        "Slide up" -> R.string.option_slide_up
        "Slide left" -> R.string.option_slide_left
        "Zoom" -> R.string.option_zoom
        "Fade left" -> R.string.option_fade_left
        "Landing" -> R.string.option_landing
        "Slide swap" -> R.string.option_slide_swap
        "fade_out_fade_in" -> R.string.option_anim_fade
        "fade_out_up_fade_in_up" -> R.string.option_anim_fade_up
        "fade_out_down_fade_in_down" -> R.string.option_anim_fade_down
        "fade_out_left_fade_in_right" -> R.string.option_anim_fade_left_right
        "fade_out_left_fade_in_up" -> R.string.option_anim_fade_left_up
        "fade_out_left_zoom_in" -> R.string.option_anim_fade_left_zoom
        "fade_out_left_landing" -> R.string.option_anim_fade_left_landing
        "fade_out_right_fade_in_left" -> R.string.option_anim_fade_right_left
        "fade_out_right_fade_in_up" -> R.string.option_anim_fade_right_up
        "fade_out_right_zoom_in" -> R.string.option_anim_fade_right_zoom
        "fade_out_right_landing" -> R.string.option_anim_fade_right_landing_focus
        "fade_out_left_zoom_in_right" -> R.string.option_anim_fade_left_zoom_right
        "fade_out_right_zoom_in_left" -> R.string.option_anim_fade_right_zoom_left
        "slide_out_left_slide_in_right" -> R.string.option_anim_slide_left_right
        "slide_out_left_fade_in_up" -> R.string.option_anim_slide_left_up
        "slide_out_left_zoom_in" -> R.string.option_anim_slide_left_zoom
        "slide_out_left_landing" -> R.string.option_anim_slide_left_landing
        "slide_out_right_slide_in_left" -> R.string.option_anim_slide_right_left
        "slide_out_right_fade_in_up" -> R.string.option_anim_slide_right_up
        "slide_out_right_zoom_in" -> R.string.option_anim_slide_right_zoom
        "slide_out_right_landing" -> R.string.option_anim_slide_right_landing
        "flip_out_x_flip_in_x" -> R.string.option_anim_flip_x
        "flip_out_y_flip_in_y" -> R.string.option_anim_flip_y
        "rotate_out_rotate_in" -> R.string.option_anim_rotate
        "zoom_out_zoom_in" -> R.string.option_anim_zoom_switch
        "None" -> R.string.option_none
        else -> R.string.option_auto_follow_source
    })
    AodChoiceKind.LINE_TRANSITION_SPEED -> context.getString(when (value) {
        "Slowest" -> R.string.option_slowest
        "Slow" -> R.string.option_slow
        "Fast" -> R.string.option_fast
        "Fastest" -> R.string.option_fastest
        else -> R.string.option_normal
    })
    AodChoiceKind.TRANSITION_SPEED -> context.getString(when (value) {
        "200" -> R.string.option_fast
        "500" -> R.string.option_slow
        else -> R.string.option_normal
    })
    AodChoiceKind.SECONDARY_TEXT -> {
        // 多选摘要:已勾选内容按词表序以 " + " 连接;一个都不勾显示「仅主歌词」。
        val parts = buildList {
            if (com.eza.hyperglow.customization.auxShowsReading(value)) {
                add(context.getString(R.string.option_transliteration))
            }
            if (com.eza.hyperglow.customization.auxShowsTranslation(value)) {
                add(context.getString(R.string.option_translation))
            }
            if (com.eza.hyperglow.customization.auxHarmonyShown(value)) {
                add(context.getString(R.string.option_background_vocal))
            }
        }
        if (parts.isEmpty()) {
            context.getString(R.string.option_main_only)
        } else {
            parts.joinToString(" + ")
        }
    }
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
    AodChoiceKind.WORD_ANIMATION -> context.getString(when (value) {
        "Minimal" -> R.string.option_minimal
        "BetterLyrics" -> R.string.option_betterlyrics
        else -> R.string.option_gradient
    })
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
    SONG_INFO_ALIGNMENT(R.string.choice_song_info_alignment),
    SECOND_LINE_ALIGNMENT(R.string.choice_second_line_alignment),
    SECONDARY_TEXT(R.string.choice_secondary_text),
    LONG_LINES(R.string.choice_long_lines),
    LYRIC_LINES(R.string.choice_lyric_lines),
    SONG_INFO_POSITION(R.string.choice_song_info_position),
    SONG_INFO_SEPARATOR(R.string.choice_song_info_separator),
    SONG_ARTWORK_SHAPE(R.string.choice_song_artwork_shape),
    TEXT_WEIGHT(R.string.choice_text_weight),
    TEXT_SIZE(R.string.choice_text_size),
    FONT(R.string.choice_font),
    WORD_ANIMATION(R.string.choice_word_animation),
    GLOW(R.string.choice_glow),
    LINE_PROGRESS(R.string.choice_line_progress_effect),
    LINE_TRANSITION(R.string.choice_line_transition),
    LINE_TRANSITION_SPEED(R.string.choice_line_transition_speed),
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

/** 歌曲信息内容行摘要:已选部分按用户排序以 " · " 连接,如「歌名 · 歌手」;逐槽分隔符在编辑弹窗内调整。 */
internal fun metadataPartsDisplayLabel(
    context: android.content.Context,
    parts: String
): String = normalizeMetadataParts(parts).split(',').joinToString(" · ") { part ->
    context.getString(
        when (part) {
            METADATA_PART_ARTIST -> R.string.option_song_info_part_artist
            METADATA_PART_ALBUM -> R.string.option_song_info_part_album
            else -> R.string.option_song_info_part_title
        }
    )
}

/** 分隔符选项标签:换行显示本地化文案,行内分隔符直接显示分隔符字面量。 */
internal fun metadataSeparatorDisplayLabel(
    context: android.content.Context,
    value: String
): String = when (normalizeMetadataSeparator(value)) {
    METADATA_SEPARATOR_NEWLINE -> context.getString(R.string.option_song_info_separator_newline)
    else -> metadataSeparatorText(value)
}
