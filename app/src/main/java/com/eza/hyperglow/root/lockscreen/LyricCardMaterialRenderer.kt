package com.eza.hyperglow.root.lockscreen

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.eza.hyperglow.customization.CARD_MATERIAL_GAUSSIAN
import com.eza.hyperglow.customization.CARD_MATERIAL_HIGHLIGHT
import com.eza.hyperglow.customization.CARD_MATERIAL_LIQUID
import com.eza.hyperglow.customization.CARD_MATERIAL_SOFT
import com.eza.hyperglow.customization.CARD_MATERIAL_SOLID
import com.eza.hyperglow.customization.CardMaterialTuning
import kotlin.math.hypot
import kotlin.math.max

/**
 * 锁屏歌词卡片的材质层:在底色圆角矩形之上叠加高光边、折射带与柔光。
 *
 * 边缘着色模型改编自 HyperIsland(MIT, Copyright (c) 2026 1812z)的液态玻璃边缘
 * shader:圆角矩形 SDF + 半球法线 + 两路定向光,配合内折射带与轻微色散。这里只保留
 * 卡片需要的一路主光/对面阴影,并按材质档位缩放参数。
 *
 * 所有绘制都依赖 API 33+ 的 RuntimeShader;构造失败时回落到低透明度描边,
 * 不阻断卡片本身。HDR 开关通过提高峰值亮度与额外的加法泛光渲染;SDR 合成下
 * 超过 1.0 的部分会被截断,观感上仍是更亮的高光与光晕。
 *
 * 真 F16 SurfaceView(峰值 4.99×SDR)方案在 HyperOS 3 真机上 `setUseAlpha` 未生效,
 * 会把整块锁屏盖黑;为避免不可接受的回归,当前不挂 HDR Surface,只保留本 SDR 增强路径。
 */
internal class LyricCardMaterialRenderer(private val density: Float) {

    private val shader: RuntimeShader? = runCatching { RuntimeShader(EDGE_SHADER) }.getOrNull()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val fallbackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val clipPath = Path()
    private val rect = RectF()

    fun draw(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        materialToken: String,
        hdrHighlight: Boolean,
        lightX: Float = DEFAULT_LIGHT_X,
        lightY: Float = DEFAULT_LIGHT_Y,
        tuning: CardMaterialTuning = CardMaterialTuning()
    ) {
        if (materialToken == CARD_MATERIAL_SOLID || bounds.width() <= 0f || bounds.height() <= 0f) {
            return
        }
        val spec = materialSpec(materialToken, hdrHighlight, tuning, bounds.height()) ?: return
        drawSheen(canvas, bounds, cornerRadiusPx, spec, lightX, lightY)
        if (spec.edgeAlpha > 0f) {
            drawEdge(canvas, bounds, cornerRadiusPx, spec, lightX, lightY)
        }
        if (hdrHighlight && spec.edgeAlpha > 0f) {
            drawBloom(canvas, bounds, cornerRadiusPx, spec)
        }
    }

    private fun drawSheen(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        spec: MaterialSpec,
        lightX: Float,
        lightY: Float
    ) {
        if (spec.sheenAlpha <= 0f) return
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        canvas.clipPath(clipPath)
        sheenPaint.shader = when (spec.sheen) {
            SheenKind.DIAGONAL -> LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                intArrayOf(
                    (0xFFFFFFFF.toInt()).withAlpha(spec.sheenAlpha),
                    (0xFFFFFFFF.toInt()).withAlpha(spec.sheenAlpha * 0.35f),
                    0x00FFFFFF
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )

            SheenKind.RADIAL -> RadialGradient(
                bounds.centerX(),
                bounds.top,
                max(bounds.height() * 1.35f, 1f),
                intArrayOf(
                    (0xFFFFFFFF.toInt()).withAlpha(spec.sheenAlpha),
                    0x00FFFFFF
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )

            SheenKind.BAND -> {
                // 液态档的斜向光带:光位变化时整条带子在卡片里滑动。
                val shift = lightShift(bounds, lightX, lightY)
                LinearGradient(
                    bounds.left + shift,
                    bounds.top,
                    bounds.right + shift,
                    bounds.bottom,
                    intArrayOf(
                        0x00FFFFFF,
                        (0xFFFFFFFF.toInt()).withAlpha(spec.sheenAlpha),
                        0x00FFFFFF
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        sheenPaint.alpha = 255
        canvas.drawRect(bounds, sheenPaint)
        canvas.restore()
    }

    private fun lightShift(bounds: RectF, lightX: Float, lightY: Float): Float =
        (lightX * 0.45f + lightY * 0.25f) * bounds.width()

    private fun drawEdge(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        spec: MaterialSpec,
        lightX: Float,
        lightY: Float
    ) {
        val length = hypot(lightX, lightY).coerceAtLeast(0.0001f)
        val runtimeShader = shader
        if (runtimeShader == null) {
            drawFallbackEdge(canvas, bounds, cornerRadiusPx, spec)
            return
        }
        val edgeWidth = spec.edgeWidthPx
        edgePaint.shader = runtimeShader
        edgePaint.alpha = 255
        runtimeShader.setFloatUniform("uOrigin", bounds.left, bounds.top)
        runtimeShader.setFloatUniform("uSize", bounds.width(), bounds.height())
        runtimeShader.setFloatUniform("uCornerRadius", cornerRadiusPx)
        runtimeShader.setFloatUniform("uLightDir", lightX / length, lightY / length)
        runtimeShader.setFloatUniform("uEdgeWidth", edgeWidth)
        runtimeShader.setFloatUniform("uRefraction", spec.refraction)
        runtimeShader.setFloatUniform("uEdgeAlpha", spec.edgeAlpha)
        runtimeShader.setFloatUniform("uEdgeShadow", spec.shadowAlpha)
        runtimeShader.setFloatUniform("uDispersion", spec.dispersion)
        runtimeShader.setFloatUniform("uHdrBoost", spec.hdrBoost)
        runtimeShader.setFloatUniform("uShapeEdgeWidth", spec.shapeEdgeWidth)
        runtimeShader.setFloatUniform("uShapeEdgePower", spec.shapeEdgePower)
        runtimeShader.setFloatUniform("uSoftLight", spec.softLight)
        runtimeShader.setFloatUniform("uReflectionLighten", spec.reflectionLighten)
        runtimeShader.setFloatUniform("uDirectionalLight", spec.directionalLight)
        canvas.drawRect(
            bounds.left - 1f,
            bounds.top - 1f,
            bounds.right + 1f,
            bounds.bottom + 1f,
            edgePaint
        )
    }

    /** RuntimeShader 不可用时的保底:一圈低透明度白描边,仍有玻璃轮廓但不带折射。 */
    private fun drawFallbackEdge(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        spec: MaterialSpec
    ) {
        fallbackStroke.color = (0xFFFFFFFF.toInt()).withAlpha(
            (spec.edgeAlpha * 0.35f).coerceIn(0f, 1f)
        )
        fallbackStroke.strokeWidth = max(0.8f * density, 1f)
        canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, fallbackStroke)
    }

    /**
     * HDR 档的额外泛光:宽描边 + BlurMaskFilter。
     *
     * 描边整体内缩半个线宽并裁剪到卡片形状内,让光晕从边缘向卡片内部扩散而不是向
     * View 外扩——卡片 View 的宽度就是卡片宽度,向外扩的光晕会在左右边缘/底部被
     * 父布局裁成一条硬边(左下角最明显)。
     */
    private fun drawBloom(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        spec: MaterialSpec
    ) {
        val stroke = max(spec.edgeWidthPx * 1.4f, 2f)
        val inset = stroke / 2f
        val inner = RectF(bounds)
        inner.inset(inset, inset)
        if (inner.width() <= 0f || inner.height() <= 0f) return
        bloomPaint.color = (0xFFFFFFFF.toInt()).withAlpha(
            (0.07f * (spec.hdrBoost.coerceAtLeast(0f) + 0.6f)).coerceIn(0f, 0.2f)
        )
        bloomPaint.strokeWidth = stroke
        bloomPaint.maskFilter = runCatching {
            BlurMaskFilter(max(4f * density, 1f), BlurMaskFilter.Blur.NORMAL)
        }.getOrNull()
        val innerRadius = (cornerRadiusPx - inset).coerceAtLeast(0f)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(inner, innerRadius, innerRadius, bloomPaint)
        canvas.restore()
        bloomPaint.maskFilter = null
    }

    private fun materialSpec(
        token: String,
        hdr: Boolean,
        tuning: CardMaterialTuning,
        cardHeightPx: Float
    ): MaterialSpec? {
        // 玻璃组按 HyperIsland 的 SDR 边缘比例换算:
        // edgeWidth = 卡片高度 × edgeThickness%;highlight ×0.56;shadow ×0.34;dispersion ×0.16。
        val glassEdgeWidth =
            (cardHeightPx * tuning.edgeThickness.coerceIn(4, 40) / 100f).coerceAtLeast(1f)
        val glassEdgeAlpha = tuning.reflectionStrength.coerceIn(0, 100) / 100f * 0.56f
        val glassShadow = tuning.darker.coerceIn(0, 100) / 100f * 0.34f
        val glassRefraction = tuning.refraction.coerceIn(0, 40) / 100f
        val glassDispersion = tuning.dispersion.coerceIn(0, 100) / 100f * 0.16f
        val base = when (token) {
            CARD_MATERIAL_GAUSSIAN -> MaterialSpec(
                edgeWidthPx = 0f,
                edgeAlpha = 0f,
                shadowAlpha = 0f,
                refraction = 0f,
                dispersion = 0f,
                sheen = SheenKind.DIAGONAL,
                sheenAlpha = 0.055f
            )

            CARD_MATERIAL_HIGHLIGHT -> MaterialSpec(
                edgeWidthPx = glassEdgeWidth,
                edgeAlpha = glassEdgeAlpha,
                shadowAlpha = glassShadow,
                refraction = glassRefraction,
                dispersion = glassDispersion,
                sheen = SheenKind.DIAGONAL,
                sheenAlpha = 0.1f
            )

            // 液态档自带光位移动与更强的折射带,倾斜时高光沿边缘滑动。
            CARD_MATERIAL_LIQUID -> MaterialSpec(
                edgeWidthPx = glassEdgeWidth,
                edgeAlpha = glassEdgeAlpha,
                shadowAlpha = glassShadow,
                refraction = glassRefraction,
                dispersion = glassDispersion,
                sheen = SheenKind.BAND,
                sheenAlpha = 0.12f
            )

            // 柔光组没有可逐项复刻的原生 Bionics 通道,这里把 HyperIsland 的
            // softLight/softReflection/softEdgeThickness 等映射到同一条 SDF 边缘:
            // 边缘宽度、环境光、反射强度、阴影、折射与光向都能直接看到变化。
            CARD_MATERIAL_SOFT -> {
                val softLight = tuning.softLight.coerceIn(-50f, 50f)
                val softReflection = tuning.softReflection.coerceIn(-50f, 50f)
                val softLighten = tuning.softReflectionLighten.coerceIn(-50f, 50f)
                val softEdge = (
                    tuning.softEdgeThickness.coerceIn(-50f, 50f) * 3f * density +
                        tuning.softShapeEdgeWidth.coerceIn(-50f, 50f) * 0.12f * density
                    ).coerceAtLeast(0f)
                val edgeAlpha = if (!tuning.highlight) {
                    0f
                } else {
                    (0.18f + softReflection / 100f * 0.5f + softLighten / 100f * 0.3f +
                        softLight * 0.004f).coerceIn(0f, 1f)
                }
                MaterialSpec(
                    edgeWidthPx = softEdge,
                    edgeAlpha = edgeAlpha,
                    shadowAlpha = (0.08f + tuning.softDarker.coerceIn(-50f, 50f) / 100f * 0.2f)
                        .coerceIn(0f, 1f),
                    refraction = (0.25f + tuning.softRefraction.coerceIn(-50f, 50f) / 100f * 0.5f)
                        .coerceIn(0f, 1f),
                    dispersion = 0f,
                    sheen = SheenKind.RADIAL,
                    sheenAlpha = (0.1f + softLight * 0.002f + softReflection * 0.001f)
                        .coerceIn(0f, 0.5f),
                    shapeEdgeWidth = tuning.softShapeEdgeWidth,
                    shapeEdgePower = tuning.softShapeEdgePower,
                    softLight = softLight,
                    reflectionLighten = softLighten,
                    directionalLight = tuning.directionalLightIntensity
                )
            }

            else -> return null
        }
        if (!hdr) return base
        // 高斯档本身没有边,开 HDR 时补一圈柔和高光;其余档整体抬峰值与光晕。
        return base.copy(
            edgeWidthPx = if (base.edgeWidthPx <= 0f) 5f * density else base.edgeWidthPx * 1.15f,
            edgeAlpha = if (base.edgeAlpha <= 0f) 0.2f else (base.edgeAlpha * 1.35f).coerceIn(0f, 1f),
            shadowAlpha = if (base.shadowAlpha <= 0f) 0.12f else base.shadowAlpha,
            refraction = base.refraction * 1.15f,
            sheenAlpha = (base.sheenAlpha * 1.25f).coerceIn(0f, 1f),
            hdrBoost = 0.45f + tuning.burn.coerceIn(-50f, 50f) / 100f * 0.5f
        )
    }

    private data class MaterialSpec(
        val edgeWidthPx: Float,
        val edgeAlpha: Float,
        val shadowAlpha: Float,
        val refraction: Float,
        val dispersion: Float,
        val sheen: SheenKind,
        val sheenAlpha: Float,
        val hdrBoost: Float = 0f,
        val shapeEdgeWidth: Float = 0f,
        val shapeEdgePower: Float = 0f,
        val softLight: Float = 0f,
        val reflectionLighten: Float = 0f,
        val directionalLight: Float = 1f
    )

    private enum class SheenKind { DIAGONAL, RADIAL, BAND }

    companion object {
        /** 与 HyperIsland 默认光位一致:左上方向光。 */
        const val DEFAULT_LIGHT_X = -0.454f
        const val DEFAULT_LIGHT_Y = -0.891f

        private val EDGE_SHADER = """
            uniform float2 uOrigin;
            uniform float2 uSize;
            uniform float uCornerRadius;
            uniform float2 uLightDir;
            uniform float uEdgeWidth;
            uniform float uRefraction;
            uniform float uEdgeAlpha;
            uniform float uEdgeShadow;
            uniform float uDispersion;
            uniform float uHdrBoost;
            uniform float uShapeEdgeWidth;
            uniform float uShapeEdgePower;
            uniform float uSoftLight;
            uniform float uReflectionLighten;
            uniform float uDirectionalLight;

            float sdRoundedBox(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + float2(radius);
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            float edgeCoverage(float distance) {
                // 1.5 px 过渡,避免依赖 drawRect 的边缘抗锯齿。
                return 1.0 - smoothstep(-0.75, 0.75, distance);
            }

            float3 edgeGeometry(
                float2 p,
                float2 halfSize,
                float radius,
                float edgeWidth,
                float2 lightDir
            ) {
                float distance = sdRoundedBox(p, halfSize, radius);
                float2 dx = float2(1.0, 0.0);
                float2 dy = float2(0.0, 1.0);
                float2 normal = normalize(float2(
                    sdRoundedBox(p + dx, halfSize, radius)
                        - sdRoundedBox(p - dx, halfSize, radius),
                    sdRoundedBox(p + dy, halfSize, radius)
                        - sdRoundedBox(p - dy, halfSize, radius)
                ) + float2(0.0001));
                float facing = dot(normal, normalize(lightDir)) * uDirectionalLight;
                float widened = max(edgeWidth * (1.0 + uShapeEdgeWidth / 100.0), 0.0001);
                float edge = pow(
                    smoothstep(-widened, 0.0, distance),
                    max(2.2 + uShapeEdgePower / 50.0, 0.5)
                );
                return float3(distance, facing, edge);
            }

            half4 main(float2 fragCoord) {
                float2 halfSize = uSize * 0.5;
                float2 p = fragCoord - uOrigin - halfSize;
                float3 geometry = edgeGeometry(
                    p, halfSize, uCornerRadius, uEdgeWidth, uLightDir
                );
                float distance = geometry.x;
                float coverage = edgeCoverage(distance);
                if (coverage <= 0.0) return half4(0.0);

                float facing = geometry.y;
                float edge = geometry.z;
                float ambient = max(uSoftLight, 0.0) * 0.002 * edge;
                float bright = max(facing, 0.0) * edge * uEdgeAlpha
                    * (1.0 + uReflectionLighten / 100.0);
                float opposite = max(-facing, 0.0) * edge * uEdgeShadow;
                float lensBand = pow(smoothstep(-uEdgeWidth * 2.2, -uEdgeWidth * 0.25, distance), 2.0)
                    * (1.0 - edge) * uRefraction;
                float dispersion = facing * edge * uDispersion * 0.22;
                float alpha = clamp(bright + opposite + lensBand + ambient, 0.0, 1.0);
                float3 primary = float3(1.0 + max(dispersion, 0.0), 0.99,
                    0.96 + max(-dispersion, 0.0)) * (bright + lensBand * 0.45 + ambient);
                float3 secondary = float3(0.92, 0.96, 1.0) * opposite;
                float3 rgb = (primary + secondary) * coverage;
                rgb *= (1.0 + uHdrBoost);
                alpha = clamp(alpha * coverage, 0.0, 1.0);
                return half4(half3(rgb), half(alpha));
            }
        """.trimIndent()
    }
}

private fun Int.withAlpha(alpha: Float): Int =
    (this and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)
