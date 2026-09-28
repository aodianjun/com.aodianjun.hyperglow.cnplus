package com.eza.hyperglow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 应用外观子屏:主题模式/主题颜色/背景图片/系统栏图标。
 * 设置即时写入并即时生效,无需重启;背景图片由 [onAppearanceChanged] 通知宿主重绘。
 */
@Composable
internal fun AppAppearanceScreen(
    onBack: () -> Unit,
    onAppearanceChanged: () -> Unit
) {
    val context = LocalContext.current
    var appearance by remember { mutableStateOf(loadAppUiAppearance(context)) }
    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showThemeColorDialog by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showBarIconsDialog by remember { mutableStateOf(false) }

    fun commit(next: AppUiAppearance) {
        if (updateAppUiAppearance(context, next)) {
            appearance = next
            onAppearanceChanged()
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (importAppBackgroundImage(context, uri)) {
            commit(
                appearance.copy(
                    hasBackgroundImage = true,
                    backgroundImageMtime = appBackgroundImageFile(context).lastModified()
                )
            )
        } else {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.toast_background_image_failed),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.section_app_appearance),
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
            item { SmallTitle(text = stringResource(R.string.setting_theme_mode)) }
            item {
                SettingsCard {
                    ArrowPreference(
                        title = stringResource(R.string.setting_theme_mode),
                        summary = appThemeModeLabel(context, appearance.themeMode),
                        onClick = { showThemeModeDialog = true }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.setting_theme_color),
                        summary = appThemeColorModeLabel(context, appearance.themeColorMode),
                        onClick = { showThemeColorDialog = true }
                    )
                    if (appearance.themeColorMode == AppThemeColorMode.CUSTOM) {
                        ArrowPreference(
                            title = stringResource(R.string.dialog_pick_color),
                            onClick = { showCustomColorDialog = true }
                        )
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.setting_background_image)) }
            item {
                SettingsCard {
                    SwitchPreference(
                        checked = appearance.hasBackgroundImage,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                commit(
                                    appearance.copy(
                                        hasBackgroundImage = false,
                                        backgroundImageMtime = 0L
                                    )
                                )
                            } else {
                                showBackgroundDialog = true
                            }
                        },
                        title = stringResource(R.string.setting_background_image),
                        summary = if (appearance.hasBackgroundImage) {
                            stringResource(R.string.background_image_set)
                        } else {
                            stringResource(R.string.background_image_unset)
                        }
                    )
                    SliderPreference(
                        value = appearance.backgroundDimPercent.toFloat(),
                        onValueChange = { value ->
                            commit(appearance.copy(backgroundDimPercent = value.toInt()))
                        },
                        title = stringResource(R.string.setting_background_dim),
                        valueText = appearance.backgroundDimPercent.toString() + "%",
                        valueRange = 0f..100f,
                        steps = 19,
                        enabled = appearance.hasBackgroundImage
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.setting_system_bar_icons)) }
            item {
                SettingsCard {
                    ArrowPreference(
                        title = stringResource(R.string.setting_system_bar_icons),
                        summary = appSystemBarIconsLabel(context, appearance.systemBarIcons),
                        onClick = { showBarIconsDialog = true }
                    )
                }
            }
        }
    }

    if (showThemeModeDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_theme_mode),
            show = true,
            onDismissRequest = { showThemeModeDialog = false }
        ) {
            Column {
                AppThemeMode.entries.forEach { mode ->
                    RadioButtonPreference(
                        appThemeModeLabel(context, mode),
                        appearance.themeMode == mode,
                        {
                            commit(appearance.copy(themeMode = mode))
                            showThemeModeDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showThemeColorDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_theme_color),
            show = true,
            onDismissRequest = { showThemeColorDialog = false }
        ) {
            Column {
                AppThemeColorMode.entries.forEach { mode ->
                    RadioButtonPreference(
                        appThemeColorModeLabel(context, mode),
                        appearance.themeColorMode == mode,
                        {
                            val picked = appearance.copy(themeColorMode = mode)
                            commit(picked)
                            showThemeColorDialog = false
                            if (mode == AppThemeColorMode.CUSTOM) {
                                showCustomColorDialog = true
                            }
                        }
                    )
                }
            }
        }
    }

    if (showCustomColorDialog) {
        var pendingArgb by remember {
            mutableStateOf(appearance.themeColorArgb)
        }
        WindowDialog(
            title = stringResource(R.string.dialog_pick_color),
            show = true,
            onDismissRequest = { showCustomColorDialog = false }
        ) {
            Column {
                ColorPicker(
                    color = Color(pendingArgb),
                    onColorChanged = { color -> pendingArgb = color.toArgb() }
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { showCustomColorDialog = false }
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.action_save),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            commit(
                                appearance.copy(
                                    themeColorMode = AppThemeColorMode.CUSTOM,
                                    themeColorArgb = pendingArgb
                                )
                            )
                            showCustomColorDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showBackgroundDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_background_image),
            show = true,
            onDismissRequest = { showBackgroundDialog = false }
        ) {
            Column {
                TextButton(
                    text = stringResource(R.string.action_pick_background_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onClick = {
                        showBackgroundDialog = false
                        pickImageLauncher.launch("image/*")
                    }
                )
                if (appearance.hasBackgroundImage) {
                    TextButton(
                        text = stringResource(R.string.action_remove_background_image),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showBackgroundDialog = false
                            removeAppBackgroundImage(context)
                            commit(
                                appearance.copy(
                                    hasBackgroundImage = false,
                                    backgroundImageMtime = 0L
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    if (showBarIconsDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_system_bar_icons),
            show = true,
            onDismissRequest = { showBarIconsDialog = false }
        ) {
            Column {
                AppSystemBarIcons.entries.forEach { icons ->
                    RadioButtonPreference(
                        appSystemBarIconsLabel(context, icons),
                        appearance.systemBarIcons == icons,
                        {
                            commit(appearance.copy(systemBarIcons = icons))
                            showBarIconsDialog = false
                        }
                    )
                }
            }
        }
    }
}
