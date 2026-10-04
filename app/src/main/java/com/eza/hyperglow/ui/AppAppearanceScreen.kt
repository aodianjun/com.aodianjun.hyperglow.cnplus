package com.eza.hyperglow.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.CustomFontContract
import com.eza.hyperglow.customization.CustomFontStore
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 应用外观子屏:主题模式/主题颜色/背景图片(变暗与模糊)/控件与文字颜色/字体/系统栏图标。
 * 主题与系统栏即时写入并即时生效;背景图片与背景参数在弹窗内调整,
 * 预览实时反映待应用效果,「保存」后写入并经 [onAppearanceChanged] 通知宿主重绘。
 * 交互结构参照 HyperBackground(hyperbg)模块的背景设置:入口行 + 预览弹窗、
 * 直接下拉选项、颜色行色块预览、恢复默认/保存成对操作。
 */
@Composable
internal fun AppAppearanceScreen(
    onBack: () -> Unit,
    onAppearanceChanged: () -> Unit
) {
    val context = LocalContext.current
    var appearance by remember { mutableStateOf(loadAppUiAppearance(context)) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var pendingThemeColorArgb by remember { mutableStateOf(appearance.themeColorArgb) }
    var showControlColorDialog by remember { mutableStateOf(false) }
    var pendingControlColorArgb by remember { mutableStateOf<Int?>(appearance.controlColorArgb) }
    var showTextColorDialog by remember { mutableStateOf(false) }
    var pendingTextColorArgb by remember { mutableStateOf<Int?>(appearance.textColorArgb) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var pendingBackgroundUri by remember { mutableStateOf<Uri?>(null) }
    var pendingBackgroundDim by remember { mutableStateOf(appearance.backgroundDimPercent) }
    var pendingBackgroundBlur by remember { mutableStateOf(appearance.backgroundBlurPercent) }

    fun commit(next: AppUiAppearance) {
        if (updateAppUiAppearance(context, next)) {
            appearance = next
            onAppearanceChanged()
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) pendingBackgroundUri = uri
    }

    // 字体选项 = 跟随系统/衬线/等宽 + 已导入字体(歌词渲染共用的 CustomFontStore 清单,
    // 按导入名展示;应用进程直读自己的私有字体文件,无需跨进程 Provider)。
    val importedFonts = remember { CustomFontStore.list(context) }
    val followSystemLabel = stringResource(R.string.option_font_follow_system)
    val serifLabel = stringResource(R.string.option_font_serif)
    val monospaceLabel = stringResource(R.string.option_font_monospace)
    val customFontLabel = stringResource(R.string.option_custom_font)
    val fontTokens = remember(importedFonts) {
        listOf(FONT_FAMILY_SYSTEM, FONT_FAMILY_SERIF, FONT_FAMILY_MONO) +
            importedFonts.map { CustomFontContract.customFontFamily(it.id) }
    }
    val fontLabels = listOf(followSystemLabel, serifLabel, monospaceLabel) +
        importedFonts.map { it.name.takeIf { name -> name.isNotBlank() } ?: customFontLabel }
    // 已导入字体被删除后旧令牌不再出现在选项里,展示层回落跟随系统(实际渲染同步回落)。
    val effectiveFontToken = fontTokens.firstOrNull { it == appearance.fontFamily }
        ?: FONT_FAMILY_SYSTEM

    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.section_app_appearance),
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
            item { SmallTitle(text = stringResource(R.string.section_appearance)) }
            item {
                SettingsCard {
                    OverlayDropdownPreference(
                        items = AppThemeMode.entries.map { appThemeModeLabel(context, it) },
                        selectedIndex = appearance.themeMode.ordinal,
                        title = stringResource(R.string.setting_theme_mode),
                        onSelectedIndexChange = { index ->
                            commit(appearance.copy(themeMode = AppThemeMode.entries[index]))
                        }
                    )
                    OverlayDropdownPreference(
                        items = AppThemeColorMode.entries.map { appThemeColorModeLabel(context, it) },
                        selectedIndex = appearance.themeColorMode.ordinal,
                        title = stringResource(R.string.setting_theme_color),
                        onSelectedIndexChange = { index ->
                            val mode = AppThemeColorMode.entries[index]
                            commit(appearance.copy(themeColorMode = mode))
                            if (mode == AppThemeColorMode.CUSTOM) {
                                pendingThemeColorArgb = appearance.themeColorArgb
                                showCustomColorDialog = true
                            }
                        }
                    )
                    AnimatedVisibility(
                        visible = appearance.themeColorMode == AppThemeColorMode.CUSTOM,
                        enter = expandVertically(animationSpec = tween(300)) +
                            fadeIn(animationSpec = tween(220)),
                        exit = shrinkVertically(animationSpec = tween(300)) +
                            fadeOut(animationSpec = tween(180))
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.dialog_pick_color),
                            summary = argbToColorToken(appearance.themeColorArgb),
                            startAction = {
                                ColorSwatch(
                                    argb = appearance.themeColorArgb,
                                    size = 24.dp,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            },
                            onClick = {
                                pendingThemeColorArgb = appearance.themeColorArgb
                                showCustomColorDialog = true
                            }
                        )
                    }
                    BasicComponent(
                        title = stringResource(R.string.setting_control_color),
                        summary = appearance.controlColorArgb?.let { argbToColorToken(it) }
                            ?: stringResource(R.string.option_default),
                        startAction = {
                            ColorSwatch(
                                argb = appearance.controlColorArgb
                                    ?: MiuixTheme.colorScheme.surfaceContainer.toArgb(),
                                size = 24.dp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        },
                        onClick = {
                            pendingControlColorArgb = appearance.controlColorArgb
                            showControlColorDialog = true
                        }
                    )
                    SliderPreference(
                        value = appearance.controlOpacityPercent.toFloat(),
                        onValueChange = { value ->
                            commit(appearance.copy(controlOpacityPercent = value.toInt()))
                        },
                        title = stringResource(R.string.setting_control_opacity),
                        valueText = "${appearance.controlOpacityPercent}%",
                        valueRange = 0f..100f,
                        steps = 19
                    )
                    BasicComponent(
                        title = stringResource(R.string.setting_text_color),
                        summary = appearance.textColorArgb?.let { argbToColorToken(it) }
                            ?: stringResource(R.string.option_default),
                        startAction = {
                            ColorSwatch(
                                argb = appearance.textColorArgb
                                    ?: MiuixTheme.colorScheme.onBackground.toArgb(),
                                size = 24.dp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        },
                        onClick = {
                            pendingTextColorArgb = appearance.textColorArgb
                            showTextColorDialog = true
                        }
                    )
                    OverlayDropdownPreference(
                        items = fontLabels,
                        selectedIndex = fontTokens.indexOf(effectiveFontToken),
                        title = stringResource(R.string.setting_app_font),
                        onSelectedIndexChange = { index ->
                            commit(appearance.copy(fontFamily = fontTokens[index]))
                        }
                    )
                    SwitchPreference(
                        appearance.floatingNavBar,
                        { enabled -> commit(appearance.copy(floatingNavBar = enabled)) },
                        stringResource(R.string.setting_floating_nav_bar),
                        summary = stringResource(R.string.summary_floating_nav_bar)
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.setting_background_image)) }
            item {
                SettingsCard {
                    BasicComponent(
                        title = stringResource(R.string.setting_background_image),
                        summary = if (appearance.hasBackgroundImage) {
                            stringResource(R.string.background_image_set)
                        } else {
                            stringResource(R.string.background_image_unset)
                        },
                        endActions = {
                            Icon(
                                imageVector = MiuixIcons.Basic.ArrowRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            pendingBackgroundUri = null
                            pendingBackgroundDim = appearance.backgroundDimPercent
                            pendingBackgroundBlur = appearance.backgroundBlurPercent
                            showBackgroundDialog = true
                        }
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.setting_system_bar_icons)) }
            item {
                SettingsCard {
                    OverlayDropdownPreference(
                        items = AppSystemBarIcons.entries.map { appSystemBarIconsLabel(context, it) },
                        selectedIndex = appearance.systemBarIcons.ordinal,
                        title = stringResource(R.string.setting_system_bar_icons),
                        // MIUI/HyperOS 上图标颜色由系统按状态栏背后的内容自动反色决定,显式设置会被压过;
                        // 与其让用户反复调了没反应,不如就地说明(见 docs/REGRESSION.md 同日条目)。
                        summary = if (isMiuiFamilySystem()) {
                            stringResource(R.string.summary_system_bar_icons_system_managed)
                        } else {
                            null
                        },
                        onSelectedIndexChange = { index ->
                            commit(appearance.copy(systemBarIcons = AppSystemBarIcons.entries[index]))
                        }
                    )
                }
            }
        }
    }

    if (showCustomColorDialog) {
        CustomThemeColorDialog(
            pendingArgb = pendingThemeColorArgb,
            onColorChange = { pendingThemeColorArgb = it },
            onSave = {
                commit(
                    appearance.copy(
                        themeColorMode = AppThemeColorMode.CUSTOM,
                        themeColorArgb = pendingThemeColorArgb
                    )
                )
                showCustomColorDialog = false
            },
            onDismiss = { showCustomColorDialog = false }
        )
    }

    if (showControlColorDialog) {
        ControlColorDialog(
            title = stringResource(R.string.setting_control_color),
            pendingArgb = pendingControlColorArgb,
            fallbackArgb = MiuixTheme.colorScheme.surfaceContainer.toArgb(),
            onColorChange = { pendingControlColorArgb = it },
            onRestoreDefault = {
                commit(appearance.copy(controlColorArgb = null))
                showControlColorDialog = false
            },
            onSave = {
                commit(appearance.copy(controlColorArgb = pendingControlColorArgb))
                showControlColorDialog = false
            },
            onDismiss = { showControlColorDialog = false }
        )
    }

    if (showTextColorDialog) {
        ControlColorDialog(
            title = stringResource(R.string.setting_text_color),
            pendingArgb = pendingTextColorArgb,
            fallbackArgb = MiuixTheme.colorScheme.onBackground.toArgb(),
            onColorChange = { pendingTextColorArgb = it },
            onRestoreDefault = {
                commit(appearance.copy(textColorArgb = null))
                showTextColorDialog = false
            },
            onSave = {
                commit(appearance.copy(textColorArgb = pendingTextColorArgb))
                showTextColorDialog = false
            },
            onDismiss = { showTextColorDialog = false }
        )
    }

    if (showBackgroundDialog) {
        BackgroundImageDialog(
            hasBackgroundImage = appearance.hasBackgroundImage,
            backgroundImageMtime = appearance.backgroundImageMtime,
            pendingUri = pendingBackgroundUri,
            dimPercent = pendingBackgroundDim,
            blurPercent = pendingBackgroundBlur,
            darkTheme = isDarkTheme(appearance, isSystemInDarkTheme()),
            onPickImage = { pickImageLauncher.launch("image/*") },
            onDimChange = { pendingBackgroundDim = it },
            onBlurChange = { pendingBackgroundBlur = it },
            onRestoreDefault = {
                showBackgroundDialog = false
                pendingBackgroundUri = null
                removeAppBackgroundImage(context)
                commit(
                    appearance.copy(
                        hasBackgroundImage = false,
                        backgroundImageMtime = 0L,
                        backgroundDimPercent = DEFAULT_BACKGROUND_DIM_PERCENT,
                        backgroundBlurPercent = DEFAULT_BACKGROUND_BLUR_PERCENT
                    )
                )
            },
            onSave = {
                val picked = pendingBackgroundUri
                val imported = picked == null || importAppBackgroundImage(context, picked)
                if (imported) {
                    val file = appBackgroundImageFile(context)
                    commit(
                        appearance.copy(
                            hasBackgroundImage = file.isFile,
                            backgroundImageMtime = file.lastModified(),
                            backgroundDimPercent = pendingBackgroundDim,
                            backgroundBlurPercent = pendingBackgroundBlur
                        )
                    )
                    showBackgroundDialog = false
                    pendingBackgroundUri = null
                } else {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.toast_background_image_failed),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDismiss = {
                showBackgroundDialog = false
                pendingBackgroundUri = null
            }
        )
    }
}

/**
 * 自定义主题色弹窗:色板取色,色块实时预览;拖动过程不落盘,
 * 「保存」一次性写入,「取消」放弃本次调整。
 */
@Composable
private fun CustomThemeColorDialog(
    pendingArgb: Int,
    onColorChange: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        title = stringResource(R.string.dialog_pick_color),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column {
            Column(modifier = Modifier.dialogScrollable()) {
                ColorPicker(
                    color = Color(pendingArgb),
                    onColorChanged = { onColorChange(it.toArgb()) }
                )
                ColorSwatchHexInput(
                    argb = pendingArgb,
                    onColorChange = onColorChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
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
                    onClick = onSave
                )
            }
        }
    }
}

/**
 * 可空颜色弹窗(控件颜色/文字颜色共用):色板取色 + 色块实时预览;「恢复默认」立即清除
 * 自定义回落主题,「保存」写入所选(null=恢复默认)。null 状态下色板/色块以当前生效色兜底展示。
 */
@Composable
private fun ControlColorDialog(
    title: String,
    pendingArgb: Int?,
    fallbackArgb: Int,
    onColorChange: (Int?) -> Unit,
    onRestoreDefault: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        title = title,
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column {
            Column(modifier = Modifier.dialogScrollable()) {
                ColorPicker(
                    color = Color(pendingArgb ?: fallbackArgb),
                    onColorChanged = { onColorChange(it.toArgb()) }
                )
                ColorSwatchHexInput(
                    argb = pendingArgb ?: fallbackArgb,
                    onColorChange = { onColorChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            TextButton(
                text = stringResource(R.string.action_restore_default),
                modifier = Modifier.fillMaxWidth(),
                onClick = onRestoreDefault
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
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
                    onClick = onSave
                )
            }
        }
    }
}

/**
 * 背景图片弹窗:变暗/模糊实时作用于预览(所见即所得),点按预览换图;
 * 「恢复默认」清除图片并复位参数,「保存」落盘图片并写入外观。
 */
@Composable
private fun BackgroundImageDialog(
    hasBackgroundImage: Boolean,
    backgroundImageMtime: Long,
    pendingUri: Uri?,
    dimPercent: Int,
    blurPercent: Int,
    darkTheme: Boolean,
    onPickImage: () -> Unit,
    onDimChange: (Int) -> Unit,
    onBlurChange: (Int) -> Unit,
    onRestoreDefault: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val previewBitmap = remember(pendingUri, hasBackgroundImage, backgroundImageMtime) {
        when {
            pendingUri != null -> decodePreviewBitmap(context, pendingUri)
            hasBackgroundImage -> loadAppBackgroundBitmap(context)
            else -> null
        }
    }
    val caption = when {
        pendingUri != null ->
            queryUriFileSize(context, pendingUri)?.let {
                stringResource(R.string.background_image_size, humanFileSize(it))
            } ?: stringResource(R.string.background_image_set)

        hasBackgroundImage ->
            stringResource(
                R.string.background_image_size,
                humanFileSize(appBackgroundImageFile(context).length())
            )

        else -> stringResource(R.string.background_image_unset)
    }
    val backgroundEditable = pendingUri != null || hasBackgroundImage
    WindowDialog(
        title = stringResource(R.string.setting_background_image),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column {
            Column(
                modifier = Modifier.dialogScrollable(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BackgroundImagePreview(
                    bitmap = previewBitmap,
                    dimPercent = dimPercent,
                    blurPercent = blurPercent,
                    darkTheme = darkTheme,
                    onClick = onPickImage
                )
                Text(
                    text = caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                SliderPreference(
                    value = dimPercent.toFloat(),
                    onValueChange = { onDimChange(it.toInt()) },
                    title = stringResource(R.string.setting_background_dim),
                    valueText = "$dimPercent%",
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = backgroundEditable
                )
                SliderPreference(
                    value = blurPercent.toFloat(),
                    onValueChange = { onBlurChange(it.toInt()) },
                    title = stringResource(R.string.setting_background_blur),
                    valueText = "$blurPercent%",
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = backgroundEditable
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                TextButton(
                    text = stringResource(R.string.action_restore_default),
                    modifier = Modifier.weight(1f),
                    onClick = onRestoreDefault
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_save),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = onSave
                )
            }
        }
    }
}

/**
 * 背景弹窗内的实时预览:按待应用的图片/变暗/模糊渲染,点按换图。
 * 高度取屏幕高度比例并夹在 150-260dp,宽度按 0.72 竖屏比例反推
 * (勿改回「固定宽 + heightIn + aspectRatio」组合,约束不满足时高度兜底会失效)。
 */
@Composable
private fun BackgroundImagePreview(
    bitmap: Bitmap?,
    dimPercent: Int,
    blurPercent: Int,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val previewHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.34f).coerceIn(150.dp, 260.dp)
    Box(
        modifier = Modifier
            .height(previewHeight)
            .aspectRatio(0.72f, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (blurPercent > 0) {
                            Modifier.blur(
                                radius = backgroundBlurRadius(blurPercent),
                                edgeTreatment = BlurredEdgeTreatment.Unbounded
                            )
                        } else {
                            Modifier
                        }
                    )
            )
            val dim = dimPercent / 100f
            if (dim > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            (if (darkTheme) Color.Black else Color.White).copy(alpha = dim)
                        )
                )
            }
        } else {
            Text(
                text = stringResource(R.string.action_pick_background_image),
                color = MiuixTheme.colorScheme.primary
            )
        }
    }
}

/** 圆角色块,展示颜色当前值;用于颜色行起始位与取色弹窗内预览。 */
@Composable
private fun ColorSwatch(argb: Int, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(argb))
    )
}

/**
 * 从待应用 URI 解码预览图:先探边界再按最长边 [PREVIEW_MAX_SIDE] 抽样,
 * 避免大图一次性解码把弹窗进程撑爆或 OOM。
 */
private fun decodePreviewBitmap(context: android.content.Context, uri: Uri): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX_SIDE) {
                sample *= 2
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        }
    }.getOrNull()

/** 文档提供方上报的文件大小;查不到返回 null,由调用方回退到「已设置」状态文案。 */
private fun queryUriFileSize(context: android.content.Context, uri: Uri): Long? =
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

private fun humanFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
    bytes >= 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024f)
    else -> "$bytes B"
}

private const val PREVIEW_MAX_SIDE = 1080
