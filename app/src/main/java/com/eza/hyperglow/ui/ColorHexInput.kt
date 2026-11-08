package com.eza.hyperglow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.root.aod.parseColorOrNull
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PRESET_COLORS_PER_ROW = 4

/**
 * 解析用户输入的十六进制颜色代码:允许省略 `#`、大小写混写与首尾空白,
 * 语义与 [parseColorOrNull] 一致(`#RGB`/`#RRGGBB`/`#AARRGGBB`,**保留 alpha**);
 * 非法输入返回 null(纯函数,可单测)。
 */
internal fun parseHexColorInput(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val token = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    return parseColorOrNull(token)
}

/**
 * 取色弹窗底部的「色块预览 + 预设 + 代码输入」组合:色块只作当前色预览,
 * 预设颜色与代码输入常显,不需要再点按展开。
 * 预设色块([PRESET_COLORS])点按即写入 [onColorChange],当前项描边高亮;
 * 代码输入 `#66CCFF`/`#AARRGGBB` 形式(可省略 `#`)后点「应用」或回车同样写入。
 * 非法代码就地提示且不改动当前颜色。
 */
@Composable
internal fun ColorSwatchHexInput(
    argb: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(argb) { mutableStateOf(argbToColorToken(argb)) }
    // 换色(滑杆/应用成功)即复位错误提示,避免对新颜色残留旧报错
    var showError by remember(argb) { mutableStateOf(false) }

    fun applyTypedColor() {
        val parsed = parseHexColorInput(text)
        if (parsed == null) {
            showError = true
        } else {
            showError = false
            text = argbToColorToken(parsed)
            onColorChange(parsed)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(argb))
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PRESET_COLORS.chunked(PRESET_COLORS_PER_ROW).forEach { rowTokens ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTokens.forEach { (token, labelRes) ->
                        val presetArgb = parseColorOrNull(token) ?: return@forEach
                        PresetColorSwatch(
                            argb = presetArgb,
                            label = stringResource(labelRes),
                            selected = argbToColorToken(argb) == token,
                            onClick = { onColorChange(presetArgb) }
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    showError = false
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = stringResource(R.string.color_hex_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { applyTypedColor() })
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = stringResource(R.string.action_apply),
                onClick = { applyTypedColor() },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        if (showError) {
            Text(
                text = stringResource(R.string.color_hex_invalid),
                color = MiuixTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

/** 预设颜色小色块:点按应用该色;与当前色一致时描边高亮。 */
@Composable
private fun PresetColorSwatch(argb: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
    )
}
