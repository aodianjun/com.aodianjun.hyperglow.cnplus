package com.eza.hyperglow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 *
 * [overlay] 用于盖在其它页面上的全屏覆盖层:不做壁纸模糊(否则下层页面内容会透出来),
 * 底色改用不透明的 [appOverlaySurfaceColor]。
 * [glassOverlay] 为 true 时,覆盖层允许走玻璃材质(配合 [AppSoftGlassBackground] 的整屏玻璃);
 * 未启用材质/不支持 shader 时仍回落不透明底色。
 */
@Composable
internal fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: Boolean = false,
    glassOverlay: Boolean = false
) {
    val backdrop = LocalAppLayerBackdrop.current
    val scrimBase = LocalAppControlColor.current ?: MiuixTheme.colorScheme.surface
    Box(modifier = Modifier.fillMaxWidth()) {
        if ((!overlay || glassOverlay) && isAppGlassMaterialActive()) {
            // 玻璃材质优先于渐变模糊:整条顶栏统一模糊,再由材质决定是否带边缘高光。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // 用 0 圆角的 CornerBasedShape,而不是 RectangleShape:
                    // 高光 shader 只认 CornerBasedShape,RectangleShape 会退化成半高胶囊圆角。
                    .appGlassMaterial(
                        RoundedCornerShape(0.dp),
                        cornerRadius = 0.dp,
                        tiltReactive = true
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to scrimBase.copy(alpha = (0.45f * LocalAppControlOpacity.current).coerceIn(0f, 1f)),
                            1f to Color.Transparent
                        )
                    )
            )
        } else if (!overlay && LocalAppBackgroundActive.current && backdrop != null && isRuntimeShaderSupported()) {
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
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (overlay) appOverlaySurfaceColor() else appTopBarColor())
            )
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
