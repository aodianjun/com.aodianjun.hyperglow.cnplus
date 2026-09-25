package com.eza.hyperglow.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.MAX_CANVAS_PADDING_PERCENT
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import com.eza.hyperglow.root.capability.XiaomiCapability
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AodBehaviorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AodRenderPreferences.PREFS, 0) }
    var capabilityReport by remember { mutableStateOf(XiaomiCapabilityStore.read(context)) }
    DisposableEffect(context) {
        val capabilityPrefs = context.getSharedPreferences(XiaomiCapabilityStore.PREFS, 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            capabilityReport = XiaomiCapabilityStore.read(context)
        }
        capabilityPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { capabilityPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val initialConfig = remember { AodRenderPreferences.read(context) }
    // 实验开关在概览页维护,这里直接叠加其持久化值推导能力;子页面每次进入都会重建,
    // 读取到的总是最新开关状态。
    val effectiveReport = capabilityReport.copy(
        experimentalModeEnabled = initialConfig.experimentalMode
    )
    val aodSupported = effectiveReport.has(XiaomiCapability.AOD_SURFACE)
    val positionFollowingSupported = effectiveReport.has(XiaomiCapability.AOD_POSITION_UPDATES)

    var keepAwake by remember { mutableStateOf(initialConfig.keepAwake) }
    var keepAwakeUnsynced by remember { mutableStateOf(initialConfig.keepAwakeUnsynced) }
    var keepAwakeDurationMs by remember { mutableStateOf(initialConfig.keepAwakeDurationMs) }
    var aodClockFollow by remember { mutableStateOf(initialConfig.aodClockFollow) }
    var aodClockYOffset by remember { mutableStateOf(initialConfig.aodClockYOffset) }
    var positionFollowing by remember {
        mutableStateOf(initialConfig.experimentalPositionFollowing)
    }
    var burnInPattern by remember { mutableStateOf(initialConfig.burnInPattern) }
    var burnInIntervalMs by remember { mutableStateOf(initialConfig.burnInIntervalMs) }
    var aodBrightnessBoost by remember { mutableStateOf(initialConfig.aodBrightnessBoost) }
    var aodBrightnessOverride by remember { mutableStateOf(initialConfig.aodBrightnessOverride) }
    var aodBrightnessLevel by remember { mutableStateOf(initialConfig.aodBrightnessLevel) }
    var suppressStockAodContent by remember { mutableStateOf(initialConfig.suppressStockAodContent) }
    var aodRotateWithDevice by remember { mutableStateOf(initialConfig.aodRotateWithDevice) }
    var aodRotationMode by remember { mutableStateOf(initialConfig.aodRotationMode) }
    var aodRotationSettleMs by remember { mutableStateOf(initialConfig.aodRotationSettleMs) }
    var aodLandscapeTextScale by remember { mutableStateOf(initialConfig.aodLandscapeTextScale) }
    var aodCanvasAnchorLandscape by remember { mutableStateOf(initialConfig.aodCanvasAnchorLandscape) }
    var aodCanvasPaddingPortraitXPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingPortraitXPercent)
    }
    var aodCanvasPaddingPortraitYPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingPortraitYPercent)
    }
    var aodCanvasPaddingLandscapeXPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingLandscapeXPercent)
    }
    var aodCanvasPaddingLandscapeYPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingLandscapeYPercent)
    }
    var aodLandscapeHideStock by remember { mutableStateOf(initialConfig.aodLandscapeHideStock) }
    var aodLandscapeFullscreen by remember { mutableStateOf(initialConfig.aodLandscapeFullscreen) }
    var aodDebugShowCanvasFrame by remember { mutableStateOf(initialConfig.aodDebugShowCanvasFrame) }

    var showKeepAwakeDurationDialog by remember { mutableStateOf(false) }
    var showBurnInPatternDialog by remember { mutableStateOf(false) }
    var showBurnInIntervalDialog by remember { mutableStateOf(false) }
    var showRotationModeDialog by remember { mutableStateOf(false) }
    var showRotationSettleDialog by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.section_aod_behavior),
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
            item { SmallTitle(text = stringResource(R.string.section_aod_keep_awake)) }
            item {
                SettingsCard {
                    SwitchPreference(
                        keepAwake,
                        { enabled ->
                            prefs.edit().putBoolean(AodRenderPreferences.KEEP_AWAKE, enabled).apply()
                            keepAwake = enabled
                        },
                        stringResource(R.string.setting_keep_aod_active),
                        summary =
                            if (aodSupported) {
                                stringResource(R.string.summary_keep_aod_active)
                            } else {
                                stringResource(R.string.summary_unavailable_systemui_profile)
                            },
                        enabled = aodSupported
                    )
                    ArrowPreference(
                        title = stringResource(R.string.setting_keep_aod_active_for),
                        summary = keepAwakeDurationLabel(context, keepAwakeDurationMs),
                        onClick = { showKeepAwakeDurationDialog = true },
                        enabled = aodSupported && keepAwake
                    )
                    SwitchPreference(
                        keepAwakeUnsynced,
                        { enabled ->
                            prefs.edit().putBoolean(
                                AodRenderPreferences.KEEP_AWAKE_UNSYNCED,
                                enabled
                            ).apply()
                            keepAwakeUnsynced = enabled
                        },
                        stringResource(R.string.setting_keep_aod_unsynced),
                        enabled = aodSupported && keepAwake
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_aod_clock_placement)) }
            item {
                SettingsCard {
                    SwitchPreference(
                        aodClockFollow,
                        { enabled ->
                            if (updateAodClockFollow(context, enabled)) {
                                aodClockFollow = enabled
                            }
                        },
                        stringResource(R.string.setting_aod_clock_follow),
                        summary = stringResource(R.string.summary_aod_clock_follow),
                        enabled = aodSupported
                    )
                    if (!aodClockFollow) {
                        SliderPreference(
                            value = aodClockYOffset.toFloat(),
                            onValueChange = { px ->
                                if (updateAodClockYOffset(context, px.toInt())) {
                                    aodClockYOffset = px.toInt()
                                }
                            },
                            title = stringResource(R.string.setting_aod_clock_y_offset),
                            summary = stringResource(R.string.summary_aod_clock_y_offset),
                            valueText = aodClockYOffset.toString(),
                            valueRange = com.eza.hyperglow.aod.MIN_AOD_CLOCK_Y_OFFSET.toFloat()..
                                com.eza.hyperglow.aod.MAX_AOD_CLOCK_Y_OFFSET.toFloat(),
                            steps =
                                com.eza.hyperglow.aod.MAX_AOD_CLOCK_Y_OFFSET -
                                com.eza.hyperglow.aod.MIN_AOD_CLOCK_Y_OFFSET
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.setting_aod_clock_image),
                        summary = if (positionFollowingSupported) {
                            val movementLabel = aodMovementLabel(context, positionFollowing, burnInPattern)
                            if (!aodClockFollow) {
                                movementLabel + "\n" + stringResource(R.string.summary_burn_in_paused_when_pinned)
                            } else {
                                movementLabel
                            }
                        } else {
                            stringResource(R.string.summary_aod_placement_unsupported)
                        },
                        onClick = {
                            if (positionFollowingSupported) {
                                showBurnInPatternDialog = true
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.summary_aod_placement_unsupported),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = aodSupported && positionFollowingSupported
                    )
                    if (aodSupported && positionFollowingSupported && positionFollowing &&
                        !burnInPattern.isStaticClockPlacement()
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.setting_movement_interval),
                            summary = burnInIntervalLabel(context, burnInIntervalMs),
                            onClick = { showBurnInIntervalDialog = true }
                        )
                    }
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_aod_brightness_display)) }
            item {
                SettingsCard {
                    SwitchPreference(
                        aodBrightnessBoost,
                        { enabled ->
                            if (updateAodBrightnessBoost(context, enabled)) {
                                aodBrightnessBoost = enabled
                            }
                        },
                        stringResource(R.string.setting_aod_brightness_boost),
                        summary = stringResource(R.string.summary_aod_brightness_boost),
                        enabled = aodSupported
                    )
                    if (aodBrightnessBoost) {
                        SwitchPreference(
                            aodBrightnessOverride,
                            { enabled ->
                                if (updateAodBrightnessOverride(context, enabled)) {
                                    aodBrightnessOverride = enabled
                                }
                            },
                            stringResource(R.string.setting_aod_brightness_custom),
                            summary = stringResource(
                                if (aodBrightnessOverride) {
                                    R.string.summary_aod_brightness_custom_on
                                } else {
                                    R.string.summary_aod_brightness_custom_off
                                }
                            ),
                            enabled = aodSupported
                        )
                        if (aodBrightnessOverride) {
                            SliderPreference(
                                value = aodBrightnessLevel.toFloat(),
                                onValueChange = { pct ->
                                    if (updateAodBrightnessLevel(context, pct.toInt())) {
                                        aodBrightnessLevel = pct.toInt()
                                    }
                                },
                                title = stringResource(R.string.setting_aod_brightness_level),
                                summary = stringResource(R.string.summary_aod_brightness_level),
                                valueText = aodBrightnessLevel.toString(),
                                valueRange = 10f..255f,
                                steps = 244
                            )
                        }
                    }
                    SwitchPreference(
                        suppressStockAodContent,
                        { enabled ->
                            prefs.edit().putBoolean(
                                AodRenderPreferences.SUPPRESS_STOCK_AOD_CONTENT,
                                enabled
                            ).apply()
                            suppressStockAodContent = enabled
                        },
                        stringResource(R.string.setting_suppress_stock_aod),
                        summary = stringResource(R.string.summary_suppress_stock_aod),
                        enabled = aodSupported
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.section_aod_rotation)) }
            item {
                SettingsCard {
                    SwitchPreference(
                        aodRotateWithDevice,
                        { enabled ->
                            prefs.edit().putBoolean(
                                AodRenderPreferences.AOD_ROTATE_WITH_DEVICE,
                                enabled
                            ).apply()
                            if (enabled) {
                                // issue #29:开启开关时若从未存过有效旋转模式,写入默认
                                // auto,避免偏好长期停留在 portrait 导致永不旋转。
                                val storedMode =
                                    prefs.getString(
                                        AodRenderPreferences.AOD_ROTATION_MODE,
                                        null
                                    ).orEmpty()
                                if (storedMode.isBlank()) {
                                    prefs.edit().putString(
                                        AodRenderPreferences.AOD_ROTATION_MODE,
                                        com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
                                    ).apply()
                                    aodRotationMode =
                                        com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
                                }
                            }
                            aodRotateWithDevice = enabled
                        },
                        stringResource(R.string.setting_aod_rotate_with_device),
                        summary = stringResource(R.string.summary_aod_rotate_with_device),
                        enabled = aodSupported
                    )
                    if (aodRotateWithDevice) {
                        ArrowPreference(
                            title = stringResource(R.string.setting_aod_rotation_mode),
                            summary = aodRotationModeLabel(context, aodRotationMode),
                            onClick = { showRotationModeDialog = true },
                            enabled = aodSupported
                        )
                        ArrowPreference(
                            title = stringResource(R.string.setting_aod_rotation_settle),
                            summary = aodRotationSettleLabel(context, aodRotationSettleMs),
                            onClick = { showRotationSettleDialog = true },
                            enabled = aodSupported
                        )
                        SliderPreference(
                            value = aodLandscapeTextScale,
                            onValueChange = { v ->
                                if (updateAodLandscapeTextScale(context, v)) {
                                    aodLandscapeTextScale = v
                                }
                            },
                            title = stringResource(R.string.setting_aod_landscape_scale),
                            summary = stringResource(R.string.summary_aod_landscape_scale),
                            valueText = (aodLandscapeTextScale * 100).toInt().toString() + "%",
                            valueRange = 0.5f..2f,
                            steps = 14
                        )
                        SliderPreference(
                            value = aodCanvasAnchorLandscape,
                            onValueChange = { v ->
                                if (updateAodCanvasAnchorLandscape(context, v)) {
                                    aodCanvasAnchorLandscape = v
                                }
                            },
                            title = stringResource(R.string.setting_aod_landscape_anchor),
                            summary = stringResource(R.string.summary_aod_landscape_anchor),
                            valueText =
                                (aodCanvasAnchorLandscape * 100).roundToInt().toString() + "%",
                            valueRange = 0f..1f,
                            steps = 10
                        )
                        SliderPreference(
                            value = aodCanvasPaddingPortraitXPercent,
                            onValueChange = { v ->
                                if (updateAodCanvasPaddingPercent(
                                        context,
                                        AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT,
                                        v
                                    )
                                ) {
                                    aodCanvasPaddingPortraitXPercent = v
                                }
                            },
                            title = stringResource(R.string.setting_aod_padding_portrait_x),
                            summary = stringResource(R.string.summary_aod_padding),
                            valueText = aodCanvasPaddingPortraitXPercent.toInt().toString() + "%",
                            valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                            steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                        )
                        SliderPreference(
                            value = aodCanvasPaddingPortraitYPercent,
                            onValueChange = { v ->
                                if (updateAodCanvasPaddingPercent(
                                        context,
                                        AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT,
                                        v
                                    )
                                ) {
                                    aodCanvasPaddingPortraitYPercent = v
                                }
                            },
                            title = stringResource(R.string.setting_aod_padding_portrait_y),
                            summary = stringResource(R.string.summary_aod_padding),
                            valueText = aodCanvasPaddingPortraitYPercent.toInt().toString() + "%",
                            valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                            steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                        )
                        SliderPreference(
                            value = aodCanvasPaddingLandscapeXPercent,
                            onValueChange = { v ->
                                if (updateAodCanvasPaddingPercent(
                                        context,
                                        AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT,
                                        v
                                    )
                                ) {
                                    aodCanvasPaddingLandscapeXPercent = v
                                }
                            },
                            title = stringResource(R.string.setting_aod_padding_landscape_x),
                            summary = stringResource(R.string.summary_aod_padding),
                            valueText =
                                aodCanvasPaddingLandscapeXPercent.toInt().toString() + "%",
                            valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                            steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                        )
                        SliderPreference(
                            value = aodCanvasPaddingLandscapeYPercent,
                            onValueChange = { v ->
                                if (updateAodCanvasPaddingPercent(
                                        context,
                                        AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT,
                                        v
                                    )
                                ) {
                                    aodCanvasPaddingLandscapeYPercent = v
                                }
                            },
                            title = stringResource(R.string.setting_aod_padding_landscape_y),
                            summary = stringResource(R.string.summary_aod_padding),
                            valueText =
                                aodCanvasPaddingLandscapeYPercent.toInt().toString() + "%",
                            valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                            steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                        )
                        SwitchPreference(
                            aodLandscapeHideStock,
                            { enabled ->
                                prefs.edit().putBoolean(
                                    AodRenderPreferences.AOD_LANDSCAPE_HIDE_STOCK,
                                    enabled
                                ).apply()
                                aodLandscapeHideStock = enabled
                            },
                            stringResource(R.string.setting_aod_landscape_hide_stock),
                            summary = stringResource(
                                R.string.summary_aod_landscape_hide_stock
                            )
                        )
                        SwitchPreference(
                            aodLandscapeFullscreen,
                            { enabled ->
                                prefs.edit().putBoolean(
                                    AodRenderPreferences.AOD_LANDSCAPE_FULLSCREEN,
                                    enabled
                                ).apply()
                                aodLandscapeFullscreen = enabled
                            },
                            stringResource(R.string.setting_aod_landscape_fullscreen),
                            summary = stringResource(
                                R.string.summary_aod_landscape_fullscreen
                            )
                        )
                        SwitchPreference(
                            aodDebugShowCanvasFrame,
                            { enabled ->
                                prefs.edit().putBoolean(
                                    AodRenderPreferences.AOD_DEBUG_SHOW_CANVAS_FRAME,
                                    enabled
                                ).apply()
                                aodDebugShowCanvasFrame = enabled
                            },
                            stringResource(R.string.setting_aod_debug_show_canvas_frame),
                            summary = stringResource(
                                R.string.summary_aod_debug_show_canvas_frame
                            )
                        )
                    }
                }
            }
        }
    }

    if (showKeepAwakeDurationDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_keep_aod_active_for),
            summary = stringResource(R.string.dialog_keep_aod_duration_summary),
            show = true,
            onDismissRequest = { showKeepAwakeDurationDialog = false }
        ) {
            Column {
                KEEP_AWAKE_DURATIONS.forEach { value ->
                    RadioButtonPreference(
                        keepAwakeDurationLabel(context, value),
                        keepAwakeDurationMs == value,
                        {
                            if (updateKeepAwakeDuration(context, value)) keepAwakeDurationMs = value
                            showKeepAwakeDurationDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showBurnInPatternDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_clock_image),
            show = true,
            onDismissRequest = { showBurnInPatternDialog = false }
        ) {
            Column {
                RadioButtonPreference(
                    stringResource(R.string.option_follow_xiaomi),
                    !positionFollowing,
                    {
                        prefs.edit().putBoolean(
                            AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING,
                            false
                        ).apply()
                        positionFollowing = false
                        showBurnInPatternDialog = false
                    }
                )
                BURN_IN_PATTERNS.forEach { value ->
                    RadioButtonPreference(
                        burnInPatternLabel(context, value),
                        positionFollowing && burnInPattern == value,
                        {
                            prefs.edit()
                                .putBoolean(
                                    AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING,
                                    true
                                )
                                .putString(AodRenderPreferences.BURN_IN_PATTERN, value)
                                .apply()
                            positionFollowing = true
                            burnInPattern = value
                            showBurnInPatternDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showBurnInIntervalDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_movement_interval),
            show = true,
            onDismissRequest = { showBurnInIntervalDialog = false }
        ) {
            Column {
                BURN_IN_INTERVALS.forEach { value ->
                    RadioButtonPreference(
                        burnInIntervalLabel(context, value),
                        burnInIntervalMs == value,
                        {
                            prefs.edit().putLong(AodRenderPreferences.BURN_IN_INTERVAL_MS, value).apply()
                            burnInIntervalMs = value
                            showBurnInIntervalDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showRotationModeDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_rotation_mode),
            show = true,
            onDismissRequest = { showRotationModeDialog = false }
        ) {
            Column {
                AOD_ROTATION_MODES.forEach { mode ->
                    RadioButtonPreference(
                        aodRotationModeOptionLabel(context, mode),
                        aodRotationMode == mode,
                        {
                            if (updateAodRotationMode(context, mode)) aodRotationMode = mode
                            showRotationModeDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showRotationSettleDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_rotation_settle),
            show = true,
            onDismissRequest = { showRotationSettleDialog = false }
        ) {
            Column {
                AOD_ROTATION_SETTLES.forEach { ms ->
                    RadioButtonPreference(
                        aodRotationSettleLabel(context, ms),
                        aodRotationSettleMs == ms,
                        {
                            if (updateAodRotationSettleMs(context, ms)) aodRotationSettleMs = ms
                            showRotationSettleDialog = false
                        }
                    )
                }
            }
        }
    }
}
