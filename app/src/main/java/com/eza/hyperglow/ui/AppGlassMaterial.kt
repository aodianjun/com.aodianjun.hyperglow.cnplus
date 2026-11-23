package com.eza.hyperglow.ui

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.customization.CARD_MATERIAL_GAUSSIAN
import com.eza.hyperglow.customization.CARD_MATERIAL_HIGHLIGHT
import com.eza.hyperglow.customization.CARD_MATERIAL_LIQUID
import com.eza.hyperglow.customization.CARD_MATERIAL_SOFT
import com.eza.hyperglow.customization.CardMaterialTuning
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
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.highlight.rememberTiltLight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

/** 高斯模糊 + HDR 高光时的顶部光边:不改变模糊体,只补一条受光边缘。 */
private val HdrTopSheen = Highlight(
    width = 1.dp,
    alpha = 0.9f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.06f),
        innerBlurRadius = 3.2.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, 0.35f, -0.5f),
            intensity = 0.85f
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.95f, -0.5f),
            intensity = 0.28f
        )
    )
)

/** 柔光玻璃的宽光晕:大内模糊半径 + 低强度双光,边缘不出现锐利描边。 */
private val SoftGlowStroke = BloomStroke(
    color = Color.White.copy(alpha = 0.05f),
    innerBlurRadius = 6.dp,
    primaryLight = LightSource(
        position = LightPosition(0.5f, 0.42f, -0.5f),
        intensity = 0.24f
    ),
    secondaryLight = LightSource(
        position = LightPosition(0.5f, 0.92f, -0.5f),
        intensity = 0.18f
    )
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
 */
@Composable
internal fun Modifier.appGlassMaterial(
    surfaceShape: Shape,
    cornerRadius: Dp = 0.dp,
    tiltReactive: Boolean = false,
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
    val tintBase = tintColor ?: LocalAppControlColor.current ?: MiuixTheme.colorScheme.surface
    val transparency = if (material == AppGlassMaterial.SOFT) {
        (1f + tuning.transparency.coerceIn(-50f, 50f) / 100f).coerceIn(0f, 1f)
    } else {
        1f
    }
    val tint = tintBase.copy(alpha = spec.tintAlpha * transparency)
    val blendColor = remember(tuning.blendColor, tuning.blendOpacity, tuning.transparency) {
        appGlassBlendColor(tuning)
    }
    val blurEnabled = LocalAppBackgroundActive.current
    val density = LocalDensity.current
    val edgeRenderer = remember(density.density) { LyricCardMaterialRenderer(density.density) }
    val materialToken = material.cardMaterialToken
    val radiusPx = with(density) { cornerRadius.toPx() }
    val lightAngle = tuning.lightDirection.coerceIn(0, 359) * PI.toFloat() / 180f
    val basePosition = remember(lightAngle) {
        LightPosition(
            cos(lightAngle) * 0.5f + 0.5f,
            sin(lightAngle) * 0.5f + 0.5f,
            -0.5f
        )
    }
    val tilt = if (material == AppGlassMaterial.LIQUID && tiltReactive) {
        rememberTiltLight(
            basePosition = basePosition,
            color = Color.White,
            intensity = 1f,
            sensitivity = 0.16f
        )
    } else {
        null
    }
    val lightX = tilt?.position?.x?.let { (it - 0.5f) * 2f } ?: cos(lightAngle)
    val lightY = tilt?.position?.y?.let { (it - 0.5f) * 2f } ?: sin(lightAngle)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { surfaceShape },
        effects = {
            if (blurEnabled) {
                blur(
                    radiusX = (spec.blurRadius *
                        (0.5f + blurPercent.coerceIn(0, 100) / 100f)).toPx()
                )
            }
            colorControls(
                brightness = (
                    spec.brightness + tuning.brightness / 100f + tuning.backgroundBrightness
                    ).coerceIn(-0.5f, 0.5f),
                contrast = spec.contrast,
                saturation = (
                    spec.saturation *
                        (1f + tuning.saturation / 100f + tuning.backgroundSaturation / 100f)
                    ).coerceIn(0f, 2f)
            )
            blendColors(
                BlurColors(
                    blendColors = buildList {
                        add(BlendColorEntry(tint, BlurBlendMode.SrcOver))
                        blendColor?.let { add(BlendColorEntry(it, BlurBlendMode.SrcOver)) }
                    },
                    brightness = 0f,
                    contrast = 1f,
                    saturation = 1f
                )
            )
        },
        // 边缘/折射/色散由 LyricCardMaterialRenderer 的 SDF shader 负责,miuix 只做
        // backdrop 采样与混色;避免两套高光叠加。
        highlight = { null }
    ).then(
        Modifier.drawWithContent {
            drawIntoCanvas { canvas ->
                edgeRenderer.draw(
                    canvas = canvas.nativeCanvas,
                    bounds = RectF(0f, 0f, size.width, size.height),
                    cornerRadiusPx = radiusPx,
                    materialToken = materialToken,
                    hdrHighlight = hdr,
                    lightX = lightX,
                    lightY = lightY,
                    tuning = tuning
                )
            }
            drawContent()
        }
    )
}

private val AppGlassMaterial.cardMaterialToken: String
    get() = when (this) {
        AppGlassMaterial.GAUSSIAN -> CARD_MATERIAL_GAUSSIAN
        AppGlassMaterial.HIGHLIGHT -> CARD_MATERIAL_HIGHLIGHT
        AppGlassMaterial.LIQUID -> CARD_MATERIAL_LIQUID
        AppGlassMaterial.SOFT -> CARD_MATERIAL_SOFT
        AppGlassMaterial.NONE -> CARD_MATERIAL_GAUSSIAN
    }

/** 材质页混色:blendColor 只在 blendOpacity > 0 时叠加,不替换主题表面色。 */
private fun appGlassBlendColor(tuning: CardMaterialTuning): Color? {
    if (tuning.blendOpacity <= 0) return null
    val argb = runCatching {
        android.graphics.Color.parseColor(tuning.blendColor)
    }.getOrDefault(android.graphics.Color.WHITE)
    val transparency = (1f + tuning.transparency.coerceIn(-50f, 50f) / 100f)
        .coerceIn(0f, 1f)
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
private fun rememberAppGlassHighlight(
    material: AppGlassMaterial,
    darkTheme: Boolean,
    hdr: Boolean,
    tiltReactive: Boolean
): Highlight? {
    val base = remember(material, darkTheme) { appGlassBaseHighlight(material, darkTheme) }
    if (base == null) {
        return if (hdr) HdrTopSheen else null
    }
    if (material != AppGlassMaterial.LIQUID || !tiltReactive) {
        return base.withHdrHighlight(hdr)
    }
    val bloom = base.style as? BloomStroke ?: return base.withHdrHighlight(hdr)
    val primary = rememberTiltLight(
        basePosition = bloom.primaryLight.position,
        color = bloom.primaryLight.color,
        intensity = bloom.primaryLight.intensity,
        sensitivity = 0.16f
    )
    val secondary = rememberTiltLight(
        basePosition = bloom.secondaryLight.position,
        color = bloom.secondaryLight.color,
        intensity = bloom.secondaryLight.intensity,
        sensitivity = 0.12f
    )
    return base.copy(
        style = bloom.copy(primaryLight = primary, secondaryLight = secondary)
    ).withHdrHighlight(hdr)
}

private fun appGlassBaseHighlight(material: AppGlassMaterial, darkTheme: Boolean): Highlight? =
    when (material) {
        AppGlassMaterial.GAUSSIAN -> null

        AppGlassMaterial.HIGHLIGHT -> {
            val bloom = if (darkTheme) {
                BloomStroke.GlassStrokeMiddleDark
            } else {
                BloomStroke.GlassStrokeMiddleLight
            }
            Highlight(width = 1.dp, alpha = 0.95f, style = bloom)
        }

        // 液态玻璃用双峰光:两路光各自形成对称的镜面扫光,随倾斜滑过边缘。
        AppGlassMaterial.LIQUID -> {
            val bloom = if (darkTheme) {
                BloomStroke.GlassStrokeMiddleDark
            } else {
                BloomStroke.GlassStrokeMiddleLight
            }
            Highlight(
                width = 1.1.dp,
                alpha = 1f,
                style = bloom.copy(innerBlurRadius = 2.2.dp, dualPeak = true)
            )
        }

        AppGlassMaterial.SOFT -> Highlight(width = 2.2.dp, alpha = 0.85f, style = SoftGlowStroke)

        AppGlassMaterial.NONE -> null
    }

/**
 * HDR 高光:抬高高光强度与内光晕深度,并把描边色透明度提高;
 * 依赖 [BloomStroke] 的加法混合(BlendMode.Plus)叠出超过普通白的峰值。
 */
private fun Highlight.withHdrHighlight(enabled: Boolean): Highlight {
    if (!enabled) return this
    val bloom = style as? BloomStroke ?: return this
    return copy(
        width = width + 0.4.dp,
        alpha = (alpha * 1.12f).coerceIn(0f, 1f),
        style = bloom.copy(
            color = bloom.color.copy(alpha = (bloom.color.alpha * 1.7f).coerceIn(0f, 1f)),
            innerBlurRadius = bloom.innerBlurRadius * 1.35f,
            primaryLight = bloom.primaryLight.copy(
                intensity = bloom.primaryLight.intensity * 2.3f
            ),
            secondaryLight = bloom.secondaryLight.copy(
                intensity = bloom.secondaryLight.intensity * 1.9f
            )
        )
    )
}
