package com.eza.hyperglow.ui

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.customization.CARD_MATERIAL_GAUSSIAN
import com.eza.hyperglow.customization.CARD_MATERIAL_HIGHLIGHT
import com.eza.hyperglow.customization.CARD_MATERIAL_LIQUID
import com.eza.hyperglow.customization.CARD_MATERIAL_SOFT
import com.eza.hyperglow.customization.CardMaterialTuning
import com.eza.hyperglow.customization.darkenCurveAlpha
import com.eza.hyperglow.root.lockscreen.LyricCardMaterialRenderer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.noiseDither
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.material.EdgeLightSource
import top.yukonga.miuix.kmp.material.EdgePeak
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.GlassColorBlendMode
import top.yukonga.miuix.kmp.material.GlassColorLayer
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.edgeStroke
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.material.rememberTiltLight
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix 默认玻璃颗粒系数:极轻的噪点,用来压住大半径模糊的色带。 */
private const val APP_GLASS_NOISE = 0.0045f

/** 材质滑条统一是 -50..50;转成 -1..1 才能让整段行程都生效。 */
private fun Float.bipolarPercent(): Float = coerceIn(-50f, 50f) / 50f

/**
 * 玻璃材质参数:blur 半径与颜色调整都走 miuix-blur 的同一条 backdrop 效果链。
 * 材质之间只在数值与是否带边缘高光上不同,避免四套独立渲染路径。
 */
private data class AppGlassSpec(
    val blurRadius: Dp,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    /** 叠在模糊背景上的主题色透明度,用于压住壁纸细节、保证文字可读。 */
    val tintAlpha: Float
)

/** 柔光玻璃的宽光晕:大 bevel + 低强度双光,边缘不出现锐利描边。 */
private val SoftGlowStroke = EdgeStroke(
    width = 2.2.dp,
    bevel = 6.dp,
    color = Color.White.copy(alpha = 0.05f),
    primary = EdgeLightSource(0.5f, 0.42f, -0.5f, intensity = 0.24f),
    secondary = EdgeLightSource(0.5f, 0.92f, -0.5f, intensity = 0.18f),
    peak = EdgePeak.Vertical
)

/**
 * 给表面套用当前玻璃材质。调用点只需给出裁剪形状;材质开关、HDR、深色主题
 * 与 backdrop 都从 CompositionLocal / miuix 主题读取。
 *
 * - 材质为 [AppGlassMaterial.NONE]、没有 backdrop、或 RuntimeShader 不可用时原样返回,
 *   调用方继续走既有实色/半透明配色(fail-closed)。
 * - 液态玻璃的倾斜视差只在 [tiltReactive] 的表面上启用(顶栏/底栏);列表里的每张卡片
 *   保持静态双峰高光,避免一屏十几张卡同时订阅传感器、逐帧重建高光。
 * - HDR 高光通过提高高光强度与光晕深度实现加法混合的 HDR 观感;应用内合成仍是 SDR,
 *   实际峰值受屏幕与系统 HDR 策略约束。
 * - [progressive] 供顶栏使用:模糊从顶部满强度向下渐隐,底部边缘不再是一条硬切换;
 *   其余卡片/胶囊保持均匀模糊。
 */
@Composable
internal fun Modifier.appGlassMaterial(
    surfaceShape: Shape,
    cornerRadius: Dp = 0.dp,
    tiltReactive: Boolean = false,
    progressive: Boolean = false,
    tintColor: Color? = null
): Modifier {
    val material = LocalAppGlassMaterial.current
    val backdrop = LocalAppLayerBackdrop.current
    if (material == AppGlassMaterial.NONE || backdrop == null || !isRuntimeShaderSupported()) {
        return this
    }
    val darkTheme = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val hdr = LocalAppGlassHdrHighlight.current
    val tuning = LocalAppGlassTuning.current
    val blurPercent = LocalAppGlassBlurPercent.current
    val spec = remember(material, darkTheme) { appGlassSpec(material, darkTheme) }
    val stroke = rememberAppGlassStroke(material, darkTheme, hdr, tiltReactive, tuning)
    val tintBase = tintColor ?: LocalAppControlColor.current ?: MiuixTheme.colorScheme.surface
    val soft = material == AppGlassMaterial.SOFT
    // 柔光滑条是 -50..50:归一化成 -1..1,否则只有半程生效。
    val softLight = if (soft) tuning.softLight.bipolarPercent() else 0f
    val softDarker = if (soft) tuning.softDarker.bipolarPercent() else 0f
    val softTransparency = if (soft) tuning.transparency.bipolarPercent() else 0f
    val burn = if (soft) tuning.burn.bipolarPercent() else 0f
    val darkenAlpha = darkenCurveAlpha(LocalAppBackgroundLuminance.current, tuning)
    val transparency = (1f + softTransparency).coerceIn(0f, 2f)
    val tint = tintBase.copy(alpha = spec.tintAlpha * transparency)
    val blendColor = remember(tuning.blendColor, tuning.blendOpacity, tuning.transparency) {
        appGlassBlendColor(tuning)
    }
    val blurEnabled = LocalAppBackgroundActive.current
    // 0% 直接关闭模糊(不再保留 0.5× 底噪),100% 保持 1.5× 上限。
    val blurRadius = spec.blurRadius * (
        blurPercent.coerceIn(0, 100) / 100f * 1.5f
        )
    val brightness = (
        spec.brightness + tuning.brightness / 100f + tuning.backgroundBrightness - burn * 0.10f
        ).coerceIn(-0.5f, 0.5f)
    val contrast = (spec.contrast * (1f + burn * 0.55f)).coerceIn(0.4f, 2f)
    val saturation = (
        spec.saturation *
            (1f + tuning.saturation / 100f + tuning.backgroundSaturation / 100f)
        ).coerceIn(0f, 2f)
    val blendLayers = buildList {
        add(BlendColorEntry(tint, BlurBlendMode.SrcOver))
        blendColor?.let { add(BlendColorEntry(it, BlurBlendMode.SrcOver)) }
        if (soft) {
            // 柔光强度:正值提亮、负值压暗;压暗滑块独立叠一层黑/白。
            val light = (0.10f * softLight).coerceIn(-1f, 1f)
            when {
                light > 0f -> add(BlendColorEntry(Color.White.copy(alpha = light), BlurBlendMode.SrcOver))
                light < 0f -> add(BlendColorEntry(Color.Black.copy(alpha = -light), BlurBlendMode.SrcOver))
            }
            val darker = (0.14f * softDarker).coerceIn(-1f, 1f)
            when {
                darker > 0f -> add(BlendColorEntry(Color.Black.copy(alpha = darker), BlurBlendMode.SrcOver))
                darker < 0f -> add(BlendColorEntry(Color.White.copy(alpha = -darker), BlurBlendMode.SrcOver))
            }
        }
        // 压暗曲线:背景越亮黑层越深。
        if (darkenAlpha > 0f) {
            add(BlendColorEntry(Color.Black.copy(alpha = darkenAlpha), BlurBlendMode.SrcOver))
        }
    }
    if (progressive) {
        // 顶栏渐进模糊:上部保持全强度,最后 22% 高度渐隐到清晰;
        // noise / colorControls / blend 由 progressiveTextureBlurEffect 一次串好。
        val glass = progressiveTextureBlur(
            backdrop = backdrop,
            shape = surfaceShape,
            blurRadius = if (blurEnabled) blurRadius.value else 0f,
            gradient = ProgressiveBlur(
                angle = 90f,
                startFraction = 0.78f,
                endFraction = 1f
            ),
            noiseCoefficient = APP_GLASS_NOISE,
            colors = BlurColors(
                blendColors = blendLayers,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation
            )
        )
        return stroke?.let { glass.edgeStroke(shape = surfaceShape, stroke = it) } ?: glass
    }
    val glass = drawBackdrop(
        backdrop = backdrop,
        shape = { surfaceShape },
        effects = {
            if (blurEnabled && blurRadius > 0.dp) {
                blur(radiusX = blurRadius.toPx())
                // 与 miuix GlassDefaults.NoiseCoefficient 同值:极轻颗粒压住模糊色带。
                noiseDither(APP_GLASS_NOISE)
            }
            colorControls(
                brightness = brightness,
                contrast = contrast,
                saturation = saturation
            )
            blendColors(
                BlurColors(
                    blendColors = blendLayers,
                    brightness = 0f,
                    contrast = 1f,
                    saturation = 1f
                )
            )
        }
    )
    // 边缘/折射/色散统一交给 miuix-material 的 EdgeStroke:绘制在 backdrop 之上,
    // HDR 的 Plus 混合仍能叠出高光峰值。
    return stroke?.let { glass.edgeStroke(shape = surfaceShape, stroke = it) } ?: glass
}

/** 材质页混色:blendColor 只在 blendOpacity > 0 时叠加,不替换主题表面色。 */
private fun appGlassBlendColor(tuning: CardMaterialTuning): Color? {
    if (tuning.blendOpacity <= 0) return null
    val argb = runCatching {
        android.graphics.Color.parseColor(tuning.blendColor)
    }.getOrDefault(android.graphics.Color.WHITE)
    val transparency = (1f + tuning.transparency.bipolarPercent()).coerceIn(0f, 2f)
    val alpha = tuning.blendOpacity.coerceIn(0, 100) / 100f *
        (android.graphics.Color.alpha(argb) / 255f) * transparency
    if (alpha <= 0f) return null
    return Color(
        android.graphics.Color.rgb(
            android.graphics.Color.red(argb),
            android.graphics.Color.green(argb),
            android.graphics.Color.blue(argb)
        )
    ).copy(alpha = alpha.coerceIn(0f, 1f))
}

/** 当前材质是否有可用玻璃效果;调用方据此决定容器色是否让位给 backdrop。 */
@Composable
internal fun isAppGlassMaterialActive(): Boolean =
    LocalAppGlassMaterial.current != AppGlassMaterial.NONE &&
        LocalAppLayerBackdrop.current != null &&
        isRuntimeShaderSupported()

private fun appGlassSpec(material: AppGlassMaterial, darkTheme: Boolean): AppGlassSpec =
    when (material) {
        // 浅色主题的白玻璃需要更实的 tint 才压得住亮壁纸,深色主题反过来轻一点。
        AppGlassMaterial.GAUSSIAN -> AppGlassSpec(
            blurRadius = 26.dp,
            brightness = if (darkTheme) 0.02f else 0f,
            contrast = 1.04f,
            saturation = 1.08f,
            tintAlpha = if (darkTheme) 0.12f else 0.18f
        )

        AppGlassMaterial.HIGHLIGHT -> AppGlassSpec(
            blurRadius = 22.dp,
            brightness = if (darkTheme) 0.03f else 0.01f,
            contrast = 1.06f,
            saturation = 1.12f,
            tintAlpha = if (darkTheme) 0.14f else 0.2f
        )

        // 液态玻璃保持低模糊与高饱和,让背景色在边缘折射感更强。
        AppGlassMaterial.LIQUID -> AppGlassSpec(
            blurRadius = 18.dp,
            brightness = if (darkTheme) 0.04f else 0.02f,
            contrast = 1.05f,
            saturation = 1.3f,
            tintAlpha = if (darkTheme) 0.1f else 0.15f
        )

        // 柔光玻璃靠更大的模糊半径和更低对比扩散背景。
        AppGlassMaterial.SOFT -> AppGlassSpec(
            blurRadius = 34.dp,
            brightness = if (darkTheme) 0.05f else 0.01f,
            contrast = 1f,
            saturation = 1.05f,
            tintAlpha = if (darkTheme) 0.2f else 0.26f
        )

        AppGlassMaterial.NONE -> AppGlassSpec(0.dp, 0f, 1f, 1f, 0f)
    }

@Composable
private fun rememberAppGlassStroke(
    material: AppGlassMaterial,
    darkTheme: Boolean,
    hdr: Boolean,
    tiltReactive: Boolean,
    tuning: CardMaterialTuning
): EdgeStroke? {
    val base = remember(material, darkTheme) { appGlassBaseStroke(material, darkTheme) }
    val soft = material == AppGlassMaterial.SOFT
    val softLight = if (soft) tuning.softLight.bipolarPercent() else 0f
    val softDarker = if (soft) tuning.softDarker.bipolarPercent() else 0f
    val softRefraction = if (soft) tuning.softRefraction.bipolarPercent() else 0f
    val softEdgeWidth = if (soft) tuning.softShapeEdgeWidth.bipolarPercent() else 0f
    val softEdgePower = if (soft) tuning.softShapeEdgePower.bipolarPercent() else 0f
    val edgeAlpha = when (material) {
        AppGlassMaterial.GAUSSIAN -> if (hdr) 0.2f else 0f
        AppGlassMaterial.HIGHLIGHT,
        AppGlassMaterial.LIQUID -> tuning.reflectionStrength.coerceIn(0, 100) / 100f
        AppGlassMaterial.SOFT -> if (!tuning.highlight) {
            0f
        } else {
            (
                0.20f + tuning.softReflection.bipolarPercent() * 0.45f +
                    tuning.softReflectionLighten.bipolarPercent() * 0.30f +
                    softLight * 0.18f + softEdgePower * 0.20f + softRefraction * 0.10f
                ).coerceIn(0f, 1f)
        }
        AppGlassMaterial.NONE -> 0f
    }
    if (edgeAlpha <= 0f && !hdr) return null
    val width = when (material) {
        AppGlassMaterial.GAUSSIAN -> 1.dp
        AppGlassMaterial.HIGHLIGHT,
        AppGlassMaterial.LIQUID -> (tuning.edgeThickness.coerceIn(4, 40) / 10f).dp
        AppGlassMaterial.SOFT -> (
            1.5f + tuning.softEdgeThickness.bipolarPercent() * 2.5f +
                softEdgeWidth * 1.5f
            ).dp.coerceAtLeast(0.5.dp)
        AppGlassMaterial.NONE -> 1.dp
    }
    val strokeBase = base ?: SoftGlowStroke
    val angle = tuning.lightDirection.coerceIn(0, 359) * PI.toFloat() / 180f
    // 方向光滑条也是 -50..50;映射成 0..2 倍,默认 1 不变。
    val intensityScale = (1f + tuning.directionalLightIntensity.bipolarPercent()).coerceIn(0f, 2f)
    val bevel = if (soft) {
        (1.5f + tuning.refraction / 100f * 4f + softRefraction * 2f).dp.coerceAtLeast(0.2.dp)
    } else {
        (1.5f + tuning.refraction / 100f * 4f).dp
    }
    val edgePowerScale = if (soft) (1f + softEdgePower * 0.6f).coerceIn(0.3f, 1.8f) else 1f
    val darkenScale = if (soft) (1f + softDarker * 0.4f).coerceIn(0.4f, 1.6f) else 1f
    val tuned = strokeBase.copy(
        width = width,
        bevel = bevel,
        color = strokeBase.color.copy(
            alpha = (
                strokeBase.color.alpha * edgeAlpha * (0.6f + tuning.refraction / 100f) *
                    edgePowerScale
                ).coerceIn(0f, 1f)
        ),
        primary = strokeBase.primary.copy(
            x = cos(angle) * 0.5f + 0.5f,
            y = sin(angle) * 0.5f + 0.5f,
            z = -0.5f,
            intensity = strokeBase.primary.intensity * intensityScale
        ),
        secondary = strokeBase.secondary.copy(
            intensity = strokeBase.secondary.intensity *
                (0.8f + tuning.darker / 100f * 0.8f) * darkenScale
        )
    )
    val tilted = if (material != AppGlassMaterial.LIQUID || !tiltReactive) {
        tuned
    } else {
        tuned.copy(
            primary = rememberTiltLight(base = tuned.primary, sensitivity = 0.16f),
            secondary = rememberTiltLight(base = tuned.secondary, sensitivity = 0.12f)
        )
    }
    return tilted.withHdrStroke(hdr)
}

private fun appGlassBaseStroke(material: AppGlassMaterial, darkTheme: Boolean): EdgeStroke? =
    when (material) {
        AppGlassMaterial.GAUSSIAN -> null

        AppGlassMaterial.HIGHLIGHT -> {
            if (darkTheme) GlassEdgeStrokes.MiddleDark else GlassEdgeStrokes.MiddleLight
        }

        // 液态玻璃用双峰光:两路光各自形成对称的镜面扫光,随倾斜滑过边缘。
        AppGlassMaterial.LIQUID -> {
            val base = if (darkTheme) {
                GlassEdgeStrokes.MiddleDark
            } else {
                GlassEdgeStrokes.MiddleLight
            }
            base.copy(bevel = 2.2.dp, peak = EdgePeak.Dual)
        }

        AppGlassMaterial.SOFT -> SoftGlowStroke

        AppGlassMaterial.NONE -> null
    }

/**
 * HDR 高光:抬高高光强度与 bevel 深度,并把描边色透明度提高;
 * 依赖 [EdgeStroke] 的加法混合(BlendMode.Plus)叠出超过普通白的峰值。
 */
private fun EdgeStroke.withHdrStroke(enabled: Boolean): EdgeStroke {
    if (!enabled) return this
    return copy(
        width = width + 0.4.dp,
        bevel = bevel * 1.35f,
        color = color.copy(alpha = (color.alpha * 1.7f).coerceIn(0f, 1f)),
        primary = primary.copy(intensity = primary.intensity * 2.3f),
        secondary = secondary.copy(intensity = secondary.intensity * 1.9f)
    )
}

/**
 * 与 [addSquircleRect] 同轮廓的玻璃形状:圆角从圆弧换成连续圆角(超椭圆),
 * 边缘高光/裁剪/backdrop 都走同一条 Path,避免胶囊两端出现折角。
 * RuntimeShader 不可用时自动退回普通圆角矩形。
 */
@Composable
internal fun rememberSquircleShape(
    cornerRadius: Dp,
    extension: Float = SquircleDefaults.Extension
): Shape {
    val squircleEnabled = isSquircleEnabled()
    return remember(cornerRadius, extension, squircleEnabled) {
        SquircleCornerShape(cornerRadius, extension, squircleEnabled)
    }
}

private class SquircleCornerShape(
    private val cornerRadius: Dp,
    private val extension: Float,
    private val squircleEnabled: Boolean
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path()
        with(density) {
            path.addSquircleRect(
                width = size.width,
                height = size.height,
                cornerRadius = cornerRadius.toPx(),
                extension = extension,
                squircleEnabled = squircleEnabled
            )
        }
        return Outline.Generic(path)
    }
}

/** 供 miuix-glass 官方组件使用的材质映射:模糊半径、色层与边缘描边来自同一套调参。 */
internal data class AppGlassComponentStyle(
    val material: GlassMaterial,
    val stroke: EdgeStroke?
)

/**
 * 把应用的四套材质/材质微调映射成 [GlassMaterial] + [EdgeStroke]。
 * 官方组件(GlassNavigationBar / GlassSegmentedTabRow)只接受 material/stroke,
 * 拿不到 [LocalAppLayerBackdrop] 时返回 null,调用方回退旧控件。
 */
@Composable
internal fun rememberAppGlassComponentStyle(
    tintColor: Color? = null,
    tiltReactive: Boolean = false
): AppGlassComponentStyle? {
    val glassMaterial = LocalAppGlassMaterial.current
    if (glassMaterial == AppGlassMaterial.NONE || LocalAppLayerBackdrop.current == null ||
        !isRuntimeShaderSupported()
    ) {
        return null
    }
    val darkTheme = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val hdr = LocalAppGlassHdrHighlight.current
    val tuning = LocalAppGlassTuning.current
    val blurPercent = LocalAppGlassBlurPercent.current
    val spec = remember(glassMaterial, darkTheme) { appGlassSpec(glassMaterial, darkTheme) }
    val tintBase = tintColor ?: LocalAppControlColor.current ?: MiuixTheme.colorScheme.surface
    val soft = glassMaterial == AppGlassMaterial.SOFT
    val darkenAlpha = darkenCurveAlpha(LocalAppBackgroundLuminance.current, tuning)
    val transparency = if (soft) {
        (1f + tuning.transparency.bipolarPercent()).coerceIn(0f, 2f)
    } else {
        1f
    }
    val tint = tintBase.copy(alpha = spec.tintAlpha * transparency)
    val blendColor = remember(tuning.blendColor, tuning.blendOpacity, tuning.transparency) {
        appGlassBlendColor(tuning)
    }
    val softOverlay = if (soft) {
        val net = 0.10f * tuning.softLight.bipolarPercent() -
            0.14f * tuning.softDarker.bipolarPercent()
        when {
            net > 0.001f -> GlassColorLayer(Color.White.copy(alpha = net), GlassColorBlendMode.SrcOver)
            net < -0.001f -> GlassColorLayer(Color.Black.copy(alpha = -net), GlassColorBlendMode.SrcOver)
            else -> null
        }
    } else {
        null
    }
    val materialDarkenLayer = if (darkenAlpha > 0f) {
        GlassColorLayer(Color.Black.copy(alpha = darkenAlpha), GlassColorBlendMode.SrcOver)
    } else {
        null
    }
    val layers = buildList {
        add(GlassColorLayer(tint, GlassColorBlendMode.SrcOver))
        if (blendColor != null) {
            add(GlassColorLayer(blendColor, GlassColorBlendMode.SrcOver))
            materialDarkenLayer?.let { add(it) }
        } else {
            softOverlay?.let { add(it) }
            materialDarkenLayer?.let { add(it) }
        }
    }
    val blurRadius = spec.blurRadius * (
        blurPercent.coerceIn(0, 100) / 100f * 1.5f
        )
    val material = GlassMaterial(
        blurRadius = blurRadius,
        first = layers[0],
        second = layers.getOrNull(1),
        third = layers.getOrNull(2)
    )
    val stroke = rememberAppGlassStroke(
        material = glassMaterial,
        darkTheme = darkTheme,
        hdr = hdr,
        tiltReactive = tiltReactive,
        tuning = tuning
    )
    return AppGlassComponentStyle(material = material, stroke = stroke)
}
