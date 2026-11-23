package com.eza.hyperglow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 柔光玻璃页面的底：实色主题底 + 两团低透明度径向柔光。
 *
 * 不在整屏套 `appGlassMaterial`——柔光玻璃的边缘高光会在屏幕四边画出一圈亮线，
 * 看起来像给页面加了边框。玻璃只用在真正需要材质的表面（顶栏/卡片/输入框），
 * 整屏保持干净的柔和底色。
 */
@Composable
internal fun AppSoftGlassBackground(modifier: Modifier = Modifier) {
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    val accent = MiuixTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surface)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.16f),
                        radius = size.maxDimension * 0.58f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.86f),
                        radius = size.maxDimension * 0.62f
                    )
                )
            }
    )
}
