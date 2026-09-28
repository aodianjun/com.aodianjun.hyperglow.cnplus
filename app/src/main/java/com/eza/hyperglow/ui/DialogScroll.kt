package com.eza.hyperglow.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 弹窗内容高度上限:取屏幕高度比例而非固定 dp,随屏幕与字体缩放自适应
 * (STYLE_GUIDE §10 "fixed-height clipping and overflow are defects")。
 */
@Composable
internal fun dialogContentMaxHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp * 0.6f

/**
 * 弹窗内容统一滚动外壳:选项弹窗在大字体/多选项下会超出屏幕高度被裁切,滚动兜底。
 */
@Composable
internal fun Modifier.dialogScrollable(): Modifier =
    this
        .fillMaxWidth()
        .heightIn(max = dialogContentMaxHeight())
        .verticalScroll(rememberScrollState())
