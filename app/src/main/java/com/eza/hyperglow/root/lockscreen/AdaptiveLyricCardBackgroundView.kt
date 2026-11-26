package com.eza.hyperglow.root.lockscreen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import com.eza.hyperglow.customization.CARD_MATERIAL_LIQUID
import com.eza.hyperglow.customization.CARD_MATERIAL_SOFT
import com.eza.hyperglow.customization.CARD_MATERIAL_SOLID
import com.eza.hyperglow.customization.CardMaterialTuning
import com.eza.hyperglow.customization.DEFAULT_CARD_MATERIAL_ALPHA
import com.eza.hyperglow.customization.darkenCurveAlpha
import com.eza.hyperglow.customization.normalizeCardMaterial
import com.eza.hyperglow.customization.normalizeCardMaterialAlpha
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.aod.AodLyricCanvasView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val CARD_HORIZONTAL_PADDING_DP = 16f

internal const val CARD_VERTICAL_PADDING_DP = 16f

private const val CARD_CORNER_RADIUS_DP = 28f

internal const val LOCKSCREEN_CARD_WIDTH_FRACTION = 0.92f

private val CARD_BACKGROUND_COLOR = 0xD91A1A1Au.toInt()

/** 卡片背景色 token → RGB(忽略 alpha,alpha 由 cardAlpha 单独控制)。预览(PreviewComponents)同源引用。 */
internal fun cardColorRgb(token: String): Int = when (token) {
    "white" -> 0xFFFFFF
    "dark_gray" -> 0x333333
    "accent" -> 0x1ED760.toInt() // Spotify-ish green;动态取色上线前作为占位强调色
    "blur" -> 0x1A1A1A // 与 black 同色,实际模糊由 surface scrim 提供
    else -> 0x1A1A1A // "black"
}

internal class AdaptiveLyricCardBackgroundView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_BACKGROUND_COLOR }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val glassTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private val materialRenderer = LyricCardMaterialRenderer(density)
    private val backdropRenderer = LyricCardBackdropRenderer(context, density) { invalidate() }
    private val nativeBlur = NativeBlurSurface(this)
    private var lyricCanvas: AodLyricCanvasView? = null
    private var progress: View? = null
    private var cardEnabled = false
    private var materialToken = CARD_MATERIAL_SOLID
    private var hdrHighlight = false
    private var materialAlphaPercent = DEFAULT_CARD_MATERIAL_ALPHA
    private var tuning = CardMaterialTuning()
    private var lastNativeActive: Boolean? = null
    private var lastDarkenLogAlpha = -1f

    // 液态材质的光位:左上默认光,设备倾斜时低通跟随。其它材质保持材质页设置的光向。
    private var baseLightX = LyricCardMaterialRenderer.DEFAULT_LIGHT_X
    private var baseLightY = LyricCardMaterialRenderer.DEFAULT_LIGHT_Y
    private var lightX = baseLightX
    private var lightY = baseLightY
    private var filteredGravityX = 0f
    private var filteredGravityY = 0f
    private var sensorManager: SensorManager? = null
    private var tiltSensor: Sensor? = null
    private var tiltRegistered = false
    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val gx = (event.values.getOrNull(0) ?: 0f) / SensorManager.GRAVITY_EARTH
            val gy = (event.values.getOrNull(1) ?: 0f) / SensorManager.GRAVITY_EARTH
            filteredGravityX += (gx.coerceIn(-1f, 1f) - filteredGravityX) * TILT_FILTER_ALPHA
            filteredGravityY += (gy.coerceIn(-1f, 1f) - filteredGravityY) * TILT_FILTER_ALPHA
            updateTiltedLight()
            invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun bind(canvas: AodLyricCanvasView, progressView: View) {
        lyricCanvas = canvas
        progress = progressView
        canvas.setContentBoundsChangedListener(::invalidate)
        invalidate()
    }

    fun setCardEnabled(enabled: Boolean) {
        if (cardEnabled == enabled) return
        cardEnabled = enabled
        visibility = if (enabled) VISIBLE else GONE
        if (!enabled) {
            nativeBlur.hide()
            backdropRenderer.setSampleInterval(0)
        }
        syncTiltSensor()
        invalidate()
    }

    /**
     * 应用卡片背景色、不透明度与材质。[alphaPercent] 0-100;[colorToken] 见
     * [com.eza.hyperglow.customization.CARD_COLOR_VALUES];[materialToken] 见
     * [com.eza.hyperglow.customization.CARD_MATERIAL_VALUES]。在 backgroundStyle=="card"
     * 时由 [applyCardBackground] 调用。
     */
    fun setCardAppearance(
        alphaPercent: Int,
        colorToken: String,
        material: String = CARD_MATERIAL_SOLID,
        hdr: Boolean = false,
        materialAlpha: Int = DEFAULT_CARD_MATERIAL_ALPHA,
        materialTuning: CardMaterialTuning = CardMaterialTuning()
    ) {
        val token = normalizeCardMaterial(material)
        // 柔光透明度同时作用于卡片本体与材质模糊强度;只改混色层几乎看不出来。
        val transparencyScale = if (token == CARD_MATERIAL_SOFT) {
            (1f + materialTuning.transparency.coerceIn(-50f, 50f) / 50f).coerceIn(0f, 2f)
        } else {
            1f
        }
        val alpha = (alphaPercent.coerceIn(0, 100) * 255 / 100 * transparencyScale)
            .toInt().coerceIn(0, 255)
        val rgb = cardColorRgb(colorToken)
        paint.color = (alpha shl 24) or (rgb and 0x00FFFFFF)
        materialToken = token
        hdrHighlight = hdr
        materialAlphaPercent = (normalizeCardMaterialAlpha(materialAlpha) * transparencyScale)
            .toInt().coerceIn(0, 100)
        tuning = materialTuning
        backdropRenderer.setSampleInterval(tuning.darkenSampleMs)
        applyBaseLightDirection()
        if (materialToken != CARD_MATERIAL_SOLID) backdropRenderer.ensureLoaded()
        syncTiltSensor()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdropRenderer.ensureLoaded()
        syncTiltSensor()
    }

    override fun onDetachedFromWindow() {
        unregisterTiltSensor()
        nativeBlur.release()
        backdropRenderer.release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!cardEnabled) return
        val lyric = lyricCanvas ?: return
        val content = lyric.visibleContentVerticalBounds() ?: return
        val padding = CARD_VERTICAL_PADDING_DP * density
        val top = (lyric.top + content.top - padding).coerceAtLeast(0f)
        var bottom = lyric.top + content.bottom + padding
        progress?.takeIf { it.visibility == VISIBLE && it.height > 0 }?.let {
            bottom = maxOf(bottom, it.bottom + padding)
        }
        bottom = bottom.coerceAtMost(height.toFloat())
        if (bottom <= top) return
        rect.set(0f, top, width.toFloat(), bottom)
        val radius = CARD_CORNER_RADIUS_DP * density
        if (materialToken == CARD_MATERIAL_SOLID) {
            // 纯色材质才注入卡片颜色/透明度。
            nativeBlur.hide()
            canvas.drawRoundRect(rect, radius, radius, paint)
        } else {
            // 优先走 SurfaceFlinger 的背景模糊(动态壁纸也能模糊);仅 HyperOS 4 启用,
            // 非 OS4 或调用失败时回落到旧实现:静态壁纸预模糊/中性半透明。
            val blurDp = cardBlurRadiusDp(materialToken, materialAlphaPercent) ?: 0f
            if (blurDp <= 0.05f) {
                // 0% 允许关到接近不模糊:不挂 surface,也不走静态壁纸预模糊,
                // 只保留混色层与材质边缘,避免最低仍有一层 0.5× 模糊。
                nativeBlur.hide()
                drawTintOverlay(canvas, rect, radius)
            } else {
                val nativeActive = nativeBlur.update(
                    bounds = rect,
                    cornerRadiusPx = radius,
                    blurRadiusPx = blurDp * density
                )
                if (lastNativeActive != nativeActive) {
                    lastNativeActive = nativeActive
                    HookLogger.w("LyricCardNativeBlur", "active=$nativeActive sdk=${android.os.Build.VERSION.SDK_INT}")
                }
                val drewBackground = if (nativeActive) {
                    true
                } else {
                    val location = IntArray(2)
                    getLocationOnScreen(location)
                    backdropRenderer.draw(
                        canvas = canvas,
                        bounds = rect,
                        cornerRadiusPx = radius,
                        materialToken = materialToken,
                        materialAlphaPercent = materialAlphaPercent,
                        hostScreenX = location[0].toFloat(),
                        hostScreenY = location[1].toFloat(),
                        tuning = tuning
                    )
                }
                if (drewBackground) {
                    // 混色层与 HyperIsland 的 blendColor/blendOpacity 对齐;0% 时不压任何颜色,
                    // 让实时模糊自己透出来。
                    drawTintOverlay(canvas, rect, radius)
                } else {
                    // 动态壁纸没有静态图可采样时,回落为中性半透明玻璃。
                    canvas.drawRoundRect(rect, radius, radius, fallbackPaint)
                }
            }
        }
        if (materialToken != CARD_MATERIAL_SOLID) {
            drawMaterialDarken(canvas, rect, radius)
        }
        materialRenderer.draw(
            canvas = canvas,
            bounds = rect,
            cornerRadiusPx = radius,
            materialToken = materialToken,
            hdrHighlight = hdrHighlight,
            lightX = lightX,
            lightY = lightY,
            tuning = tuning
        )
    }

    /**
     * 材质内部压暗:OS4 走 SurfaceFlinger 实时模糊时不会经过静态壁纸渲染器,
     * 所以统一在卡片本体上叠,保证 API 37 与非 37 两条路径一致。
     */
    private fun drawMaterialDarken(canvas: Canvas, bounds: RectF, radius: Float) {
        val luminance = backdropRenderer.wallpaperLuminance
        val alpha = darkenCurveAlpha(luminance, tuning)
        if (kotlin.math.abs(alpha - lastDarkenLogAlpha) > 0.01f) {
            lastDarkenLogAlpha = alpha
            HookLogger.w(
                "LyricCardDarken",
                "luminance=$luminance alpha=$alpha start=${tuning.darkenStart} " +
                    "max=${tuning.whiteDarkenMax} sensitivity=${tuning.whiteDarkenSensitivity}"
            )
        }
        if (alpha <= 0f) return
        darkenPaint.color = Color.argb((alpha * 255f).roundToInt().coerceIn(0, 255), 0, 0, 0)
        canvas.drawRoundRect(bounds, radius, radius, darkenPaint)
    }

    /** 材质页的 blendColor/blendOpacity;柔光档再乘 transparency(HyperIsland 同义)。 */
    private fun drawTintOverlay(canvas: Canvas, bounds: RectF, radius: Float) {
        if (tuning.blendOpacity <= 0) return
        val base = runCatching { Color.parseColor(tuning.blendColor) }.getOrDefault(Color.WHITE)
        val colorAlpha = Color.alpha(base) / 255f
        val transparency = if (materialToken == CARD_MATERIAL_SOFT) {
            (1f + tuning.transparency.coerceIn(-50f, 50f) / 100f).coerceIn(0f, 1f)
        } else {
            1f
        }
        val alpha = (tuning.blendOpacity.coerceIn(0, 100) / 100f * colorAlpha * transparency)
            .coerceIn(0f, 1f)
        if (alpha <= 0f) return
        glassTintPaint.color = Color.argb(
            (alpha * 255f).roundToInt(),
            Color.red(base),
            Color.green(base),
            Color.blue(base)
        )
        canvas.drawRoundRect(bounds, radius, radius, glassTintPaint)
    }

    private fun applyBaseLightDirection() {
        val angle = tuning.lightDirection.coerceIn(0, 359) * PI.toFloat() / 180f
        baseLightX = cos(angle)
        baseLightY = sin(angle)
        updateTiltedLight()
    }

    private fun updateTiltedLight() {
        val nx = if (materialToken == CARD_MATERIAL_LIQUID) {
            baseLightX + filteredGravityX * TILT_STRENGTH
        } else {
            baseLightX
        }
        val ny = if (materialToken == CARD_MATERIAL_LIQUID) {
            baseLightY + filteredGravityY * TILT_STRENGTH
        } else {
            baseLightY
        }
        val length = hypot(nx, ny).coerceAtLeast(0.0001f)
        lightX = nx / length
        lightY = ny / length
    }

    /** 只有液态材质需要倾斜光;注册/注销在材质、可见性和窗口附着状态变化时同步。 */
    private fun syncTiltSensor() {
        val shouldRegister = cardEnabled && materialToken == CARD_MATERIAL_LIQUID && isAttachedToWindow
        if (shouldRegister == tiltRegistered) return
        if (!shouldRegister) {
            unregisterTiltSensor()
            return
        }
        runCatching {
            val manager = context.getSystemService(android.content.Context.SENSOR_SERVICE)
                as? SensorManager ?: return@runCatching
            val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?: return@runCatching
            manager.registerListener(tiltListener, sensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager = manager
            tiltSensor = sensor
            tiltRegistered = true
        }.onFailure {
            sensorManager = null
            tiltSensor = null
            tiltRegistered = false
        }
    }

    private fun unregisterTiltSensor() {
        if (!tiltRegistered) return
        runCatching { sensorManager?.unregisterListener(tiltListener) }
        sensorManager = null
        tiltSensor = null
        tiltRegistered = false
    }

    private companion object {
        const val TILT_FILTER_ALPHA = 0.18f
        const val TILT_STRENGTH = 0.6f
    }
}
