package com.eza.hyperglow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.CARD_MATERIAL_HIGHLIGHT
import com.eza.hyperglow.customization.CARD_MATERIAL_LIQUID
import com.eza.hyperglow.customization.CARD_MATERIAL_SOFT
import com.eza.hyperglow.customization.CardMaterialTuning
import com.eza.hyperglow.customization.DARKEN_SAMPLE_VALUES
import com.eza.hyperglow.customization.DARKEN_START_HIGHLIGHT
import com.eza.hyperglow.customization.DARKEN_START_MID
import com.eza.hyperglow.customization.DARKEN_START_SHADOW
import com.eza.hyperglow.customization.DARKEN_START_VALUES
import com.eza.hyperglow.customization.DARKEN_START_WHITE
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * HyperIsland 材质页的完整滑条组:玻璃组(highlight/liquid)直接映射到卡片 SDF
 * 边缘 shader,柔光组映射到同一条 shader 的可表达通道,背景饱和度/亮度用于
 * 静态壁纸预模糊(实时模糊由 SurfaceFlinger 合成,不能逐像素改色)。
 */
@Composable
internal fun CardMaterialTuningRows(
    material: String,
    tuning: CardMaterialTuning,
    onChange: (CardMaterialTuning) -> Unit,
    onPickBlendColor: () -> Unit
) {
    when (material) {
        CARD_MATERIAL_HIGHLIGHT, CARD_MATERIAL_LIQUID -> GlassTuningRows(tuning, onChange)
        CARD_MATERIAL_SOFT -> SoftTuningRows(tuning, onChange)
    }
    if (material == CARD_MATERIAL_SOFT) {
        Text(
            text = stringResource(R.string.material_white_darken_section),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )
        var darkenStartExpanded by remember { mutableStateOf(false) }
        ArrowPreference(
            title = stringResource(R.string.material_darken_start),
            summary = stringResource(darkenStartLabelRes(tuning.darkenStart)),
            onClick = { darkenStartExpanded = !darkenStartExpanded }
        )
        if (darkenStartExpanded) {
            DARKEN_START_VALUES.forEach { value ->
                RadioButtonPreference(
                    stringResource(darkenStartLabelRes(value)),
                    tuning.darkenStart == value,
                    {
                        onChange(tuning.copy(darkenStart = value))
                        darkenStartExpanded = false
                    }
                )
            }
        }
        var darkenSampleExpanded by remember { mutableStateOf(false) }
        ArrowPreference(
            title = stringResource(R.string.material_darken_sample),
            summary = stringResource(darkenSampleLabelRes(tuning.darkenSampleMs)),
            onClick = { darkenSampleExpanded = !darkenSampleExpanded }
        )
        if (darkenSampleExpanded) {
            DARKEN_SAMPLE_VALUES.forEach { value ->
                RadioButtonPreference(
                    stringResource(darkenSampleLabelRes(value)),
                    tuning.darkenSampleMs == value,
                    {
                        onChange(tuning.copy(darkenSampleMs = value))
                        darkenSampleExpanded = false
                    }
                )
            }
        }
        IntSlider(
            title = stringResource(R.string.material_white_darken_max),
            value = tuning.whiteDarkenMax,
            range = 0..100,
            summary = stringResource(R.string.summary_material_white_darken_max)
        ) { onChange(tuning.copy(whiteDarkenMax = it)) }
        IntSlider(
            title = stringResource(R.string.material_white_darken_sensitivity),
            value = tuning.whiteDarkenSensitivity,
            range = 0..100,
            summary = stringResource(R.string.summary_material_white_darken_sensitivity)
        ) { onChange(tuning.copy(whiteDarkenSensitivity = it)) }
    }
    Text(
        text = stringResource(R.string.material_blend_section),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
    ArrowPreference(
        title = stringResource(R.string.material_blend_color),
        summary = tuning.blendColor,
        startAction = {
            val argb = runCatching { android.graphics.Color.parseColor(tuning.blendColor) }
                .getOrDefault(android.graphics.Color.WHITE)
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
            )
        },
        onClick = onPickBlendColor
    )
    IntSlider(
        title = stringResource(R.string.material_blend_opacity),
        value = tuning.blendOpacity,
        range = 0..100,
        onChange = { onChange(tuning.copy(blendOpacity = it)) }
    )
    if (material == CARD_MATERIAL_SOFT) {
        SwitchPreference(
            checked = tuning.highlight,
            onCheckedChange = { onChange(tuning.copy(highlight = it)) },
            title = stringResource(R.string.material_highlight_switch)
        )
    }
}

@Composable
private fun GlassTuningRows(
    tuning: CardMaterialTuning,
    onChange: (CardMaterialTuning) -> Unit
) {
    IntSlider(
        stringResource(R.string.material_edge_thickness),
        tuning.edgeThickness,
        4..40
    ) { onChange(tuning.copy(edgeThickness = it)) }
    IntSlider(
        stringResource(R.string.material_refraction),
        tuning.refraction,
        0..40
    ) { onChange(tuning.copy(refraction = it)) }
    IntSlider(
        stringResource(R.string.material_reflection_strength),
        tuning.reflectionStrength,
        0..100
    ) { onChange(tuning.copy(reflectionStrength = it)) }
    IntSlider(
        stringResource(R.string.material_darker),
        tuning.darker,
        0..100
    ) { onChange(tuning.copy(darker = it)) }
    IntSlider(
        stringResource(R.string.material_light_direction),
        tuning.lightDirection,
        0..359
    ) { onChange(tuning.copy(lightDirection = it)) }
    IntSlider(
        stringResource(R.string.material_dispersion),
        tuning.dispersion,
        0..100
    ) { onChange(tuning.copy(dispersion = it)) }
}

@Composable
private fun SoftTuningRows(
    tuning: CardMaterialTuning,
    onChange: (CardMaterialTuning) -> Unit
) {
    DecimalSlider(stringResource(R.string.material_soft_light), tuning.softLight) {
        onChange(tuning.copy(softLight = it))
    }
    DecimalSlider(stringResource(R.string.material_saturation), tuning.saturation) {
        onChange(tuning.copy(saturation = it))
    }
    DecimalSlider(stringResource(R.string.material_brightness), tuning.brightness) {
        onChange(tuning.copy(brightness = it))
    }
    DecimalSlider(stringResource(R.string.material_soft_darker), tuning.softDarker) {
        onChange(tuning.copy(softDarker = it))
    }
    DecimalSlider(stringResource(R.string.material_transparency), tuning.transparency) {
        onChange(tuning.copy(transparency = it))
    }
    DecimalSlider(stringResource(R.string.material_burn), tuning.burn) {
        onChange(tuning.copy(burn = it))
    }
    DecimalSlider(stringResource(R.string.material_soft_refraction), tuning.softRefraction) {
        onChange(tuning.copy(softRefraction = it))
    }
    DecimalSlider(stringResource(R.string.material_soft_edge_thickness), tuning.softEdgeThickness) {
        onChange(tuning.copy(softEdgeThickness = it))
    }
    DecimalSlider(stringResource(R.string.material_shape_edge_width), tuning.softShapeEdgeWidth) {
        onChange(tuning.copy(softShapeEdgeWidth = it))
    }
    DecimalSlider(stringResource(R.string.material_shape_edge_power), tuning.softShapeEdgePower) {
        onChange(tuning.copy(softShapeEdgePower = it))
    }
    DecimalSlider(
        stringResource(R.string.material_reflection_lighten),
        tuning.softReflectionLighten
    ) { onChange(tuning.copy(softReflectionLighten = it)) }
    DecimalSlider(stringResource(R.string.material_reflection), tuning.softReflection) {
        onChange(tuning.copy(softReflection = it))
    }
    DecimalSlider(
        stringResource(R.string.material_directional_light),
        tuning.directionalLightIntensity
    ) { onChange(tuning.copy(directionalLightIntensity = it)) }
    DecimalSlider(
        stringResource(R.string.material_background_saturation),
        tuning.backgroundSaturation,
        summary = stringResource(R.string.summary_material_background_static_only)
    ) { onChange(tuning.copy(backgroundSaturation = it)) }
    DecimalSlider(
        stringResource(R.string.material_background_brightness),
        tuning.backgroundBrightness,
        summary = stringResource(R.string.summary_material_background_static_only)
    ) { onChange(tuning.copy(backgroundBrightness = it)) }
}

private fun darkenStartLabelRes(value: String): Int = when (value) {
    DARKEN_START_SHADOW -> R.string.material_darken_start_shadow
    DARKEN_START_HIGHLIGHT -> R.string.material_darken_start_highlight
    DARKEN_START_WHITE -> R.string.material_darken_start_white
    DARKEN_START_MID -> R.string.material_darken_start_mid
    else -> R.string.material_darken_start_mid
}

private fun darkenSampleLabelRes(ms: Int): Int = when (ms) {
    500 -> R.string.material_darken_sample_500
    1000 -> R.string.material_darken_sample_1000
    2000 -> R.string.material_darken_sample_2000
    5000 -> R.string.material_darken_sample_5000
    else -> R.string.material_darken_sample_once
}

@Composable
private fun IntSlider(
    title: String,
    value: Int,
    range: IntRange,
    summary: String? = null,
    onChange: (Int) -> Unit
) {
    SliderPreference(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        title = title,
        summary = summary,
        valueText = value.toString(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0)
    )
}

@Composable
private fun DecimalSlider(
    title: String,
    value: Float,
    summary: String? = null,
    onChange: (Float) -> Unit
) {
    // 与 HyperIsland 的 DecimalMaterialSlider 同步:-50..50、0.1 量化、999 个中间档。
    SliderPreference(
        value = value.coerceIn(-50f, 50f),
        onValueChange = { onChange((it * 10f).toInt() / 10f) },
        title = title,
        summary = summary,
        valueText = String.format(Locale.ROOT, "%.1f", value),
        valueRange = -50f..50f,
        steps = 999
    )
}

/** blendColor/blendOpacity 取色弹窗:颜色取 RGB,alpha 写回 blendOpacity(HyperIsland 同义)。 */
@Composable
internal fun MaterialBlendColorPickerDialog(
    currentArgb: Int,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        motion = appDialogMotion,
        title = stringResource(R.string.material_blend_color),
        summary = stringResource(R.string.dialog_pick_color),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth()) {
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
