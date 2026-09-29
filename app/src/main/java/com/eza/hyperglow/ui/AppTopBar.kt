package com.eza.hyperglow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用统一的紧凑单行顶栏:状态栏内边距 + 52dp 单行(返回/标题/动作),取代 miuix 大标题两行式。
 * 背景图片生效时对壁纸做「上强下弱」的渐进式纹理模糊(渐变模糊)并叠加纵向渐变遮罩,
 * 标题浮在玻璃上且与页面内容衔接自然;未启用背景时为 [appTopBarColor] 实色。
 * RuntimeShader 不支持时退化为仅渐变遮罩(fail-closed,不阻塞渲染)。
 */
@Composable
internal fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val backdrop = LocalAppLayerBackdrop.current
    val scrimBase = LocalAppControlColor.current ?: MiuixTheme.colorScheme.surface
    Box(modifier = Modifier.fillMaxWidth()) {
        if (LocalAppBackgroundActive.current && backdrop != null && isRuntimeShaderSupported()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 24f,
                        gradient = ProgressiveBlur.Top
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to scrimBase.copy(alpha = (0.55f * LocalAppControlOpacity.current).coerceIn(0f, 1f)),
                            1f to Color.Transparent
                        )
                    )
            )
        } else {
            Box(modifier = Modifier.matchParentSize().background(appTopBarColor()))
        }
        val titleColor = appTopBarTitleColor()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        MiuixIcons.Back,
                        contentDescription = stringResource(R.string.action_back),
                        tint = titleColor
                    )
                }
            }
            Text(
                text = title,
                modifier = Modifier.padding(start = if (onBack != null) 0.dp else 12.dp, end = 12.dp),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = titleColor
            )
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}
