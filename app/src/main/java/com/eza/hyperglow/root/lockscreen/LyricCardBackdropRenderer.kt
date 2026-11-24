package com.eza.hyperglow.root.lockscreen

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.customization.CARD_MATERIAL_GAUSSIAN
import com.eza.hyperglow.customization.CARD_MATERIAL_HIGHLIGHT
import com.eza.hyperglow.customization.CARD_MATERIAL_LIQUID
import com.eza.hyperglow.customization.CARD_MATERIAL_SOFT
import com.eza.hyperglow.customization.CARD_MATERIAL_SOLID
import com.eza.hyperglow.customization.CardMaterialTuning
import com.eza.hyperglow.root.HookLogger
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 锁屏卡片背后的真实壁纸模糊。
 *
 * 卡片是普通 View,Canvas 不能采样"背后的窗口内容";这里读取锁屏壁纸,按屏幕坐标
 * 裁出卡片区域,在后台线程做多趟盒式模糊(1/4 缩略图上计算再放大),缓存成
 * 屏幕尺寸位图。卡片绘制时只做一次 bitmap 裁剪绘制,不逐帧跑 GPU 模糊。
 *
 * 取图顺序:锁屏壁纸(FLAG_LOCK) → 系统壁纸(getBitmap) → 壁纸 Drawable。
 * 壁纸按屏幕中心裁切;锁屏若有视差/缩放,裁切位置会有轻微偏差。
 * 读取失败或非硬件画布时静默跳过,卡片回落为原来的半透明底色。
 */
internal class LyricCardBackdropRenderer(
    private val context: Context,
    private val density: Float,
    private val onReady: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LyricCardBackdrop").apply { isDaemon = true }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val noisePaint = Paint().apply { alpha = 18 }
    private val clipPath = Path()
    private val bitmapLock = Any()
    private val screenWidth: Int
    private val screenHeight: Int
    private var screenWallpaper: Bitmap? = null
    private var blurredWallpaper: Bitmap? = null
    private var blurredKey: String? = null
    private var noiseBitmap: Bitmap? = null
    private var loading = false
    private var blurLoading = false
    private var pendingRequest: BlurRequest? = null
    private var released = false
    private var lastDrawLog: String? = null

    private data class BlurRequest(
        val material: String,
        val strengthPercent: Int,
        val brightness: Float,
        val saturation: Float
    ) {
        val key: String get() = "$material@$strengthPercent@$brightness@$saturation"
    }

    init {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels.coerceAtLeast(1)
        screenHeight = metrics.heightPixels.coerceAtLeast(1)
    }

    fun ensureLoaded() {
        if (released || screenWallpaper != null || loading) return
        loading = true
        executor.execute {
            val loaded = loadWallpaper()
            handler.post {
                loading = false
                if (released) {
                    loaded?.recycle()
                    return@post
                }
                screenWallpaper = loaded
                if (loaded != null) onReady()
            }
        }
    }

    fun draw(
        canvas: Canvas,
        bounds: RectF,
        cornerRadiusPx: Float,
        materialToken: String,
        materialAlphaPercent: Int,
        hostScreenX: Float,
        hostScreenY: Float,
        tuning: CardMaterialTuning
    ): Boolean {
        val brightness = tuning.brightness / 100f + tuning.backgroundBrightness
        val saturation = 1f + tuning.saturation / 100f + tuning.backgroundSaturation / 100f
        val logKey =
            "mat=$materialToken strength=$materialAlphaPercent hw=${canvas.isHardwareAccelerated} " +
                "wp=${screenWallpaper != null} blur=${blurredWallpaper != null} key=$blurredKey"
        if (logKey != lastDrawLog) {
            lastDrawLog = logKey
            HookLogger.w("LyricCardBackdrop", "draw $logKey")
        }
        if (released || materialToken == CARD_MATERIAL_SOLID) return false
        if (!canvas.isHardwareAccelerated) return false
        val source = screenWallpaper ?: run {
            ensureLoaded()
            return false
        }
        val request = BlurRequest(
            materialToken,
            materialAlphaPercent.coerceIn(0, 100),
            brightness,
            saturation
        )
        val blurred = if (blurredKey == request.key) {
            blurredWallpaper
        } else {
            requestBlur(source, request)
            blurredWallpaper
        } ?: return false
        if (bounds.width() <= 0f || bounds.height() <= 0f) return false
        val screenX = hostScreenX + bounds.left
        val screenY = hostScreenY + bounds.top
        val src = RectF(
            screenX.coerceIn(0f, screenWidth.toFloat()),
            screenY.coerceIn(0f, screenHeight.toFloat()),
            (screenX + bounds.width()).coerceIn(0f, screenWidth.toFloat()),
            (screenY + bounds.height()).coerceIn(0f, screenHeight.toFloat())
        )
        if (src.width() <= 0f || src.height() <= 0f) return false
        val srcRect = Rect(
            src.left.roundToInt(),
            src.top.roundToInt(),
            src.right.roundToInt(),
            src.bottom.roundToInt()
        )
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        canvas.clipPath(clipPath)
        // 与 app 玻璃一致:模糊层不透明铺满卡片,透明感来自上面的半透明 surface tint,
        // 而不是把模糊层本身做成半透明(那样会与清晰壁纸混成颜色遮罩)。
        paint.alpha = 255
        canvas.drawBitmap(blurred, srcRect, bounds, paint)
        // 细颗粒噪点:纯色/平滑壁纸区域也能看出磨砂质感,而不是一块平色。
        val grain = ensureNoise()
        if (grain != null) {
            noisePaint.shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            canvas.drawRect(bounds, noisePaint)
            noisePaint.shader = null
        }
        canvas.restore()
        return true
    }

    fun release() {
        if (released) return
        released = true
        executor.shutdownNow()
        synchronized(bitmapLock) {
            screenWallpaper?.recycle()
            screenWallpaper = null
            blurredWallpaper?.recycle()
            blurredWallpaper = null
        }
        noiseBitmap?.recycle()
        noiseBitmap = null
    }

    private fun requestBlur(source: Bitmap, request: BlurRequest) {
        if (blurLoading) {
            pendingRequest = request
            return
        }
        val radiusDp = cardBlurRadiusDp(request.material, request.strengthPercent) ?: return
        blurLoading = true
        val requestedKey = request.key
        executor.execute {
            val blurred = runCatching {
                synchronized(bitmapLock) {
                    if (released) null else blurWallpaper(
                        source,
                        radiusDp * density,
                        request.material,
                        request.brightness,
                        request.saturation
                    )
                }
            }.onFailure {
                HookLogger.w("LyricCardBackdrop", "blur failed: ${it.message}")
            }.getOrNull()
            if (blurred == null) {
                handler.post { blurLoading = false }
                return@execute
            }
            handler.post {
                blurLoading = false
                if (!released) {
                    blurredWallpaper?.recycle()
                    blurredWallpaper = blurred
                    blurredKey = requestedKey
                    onReady()
                } else {
                    blurred.recycle()
                }
                val pending = pendingRequest
                pendingRequest = null
                if (pending != null && pending.key != requestedKey) {
                    screenWallpaper?.let { requestBlur(it, pending) }
                }
            }
        }
    }

    /** 128×128 的确定性灰噪点,平铺在模糊层上模拟磨砂颗粒。 */
    private fun ensureNoise(): Bitmap? {
        noiseBitmap?.let { return it }
        return runCatching {
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            val random = java.util.Random(0x5EED)
            for (index in pixels.indices) {
                val value = random.nextInt(256)
                pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            noiseBitmap = bitmap
            bitmap
        }.getOrNull()
    }

    private fun loadWallpaper(): Bitmap? {
        val manager = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return null
        val lockBitmap = runCatching {
            @Suppress("DEPRECATION")
            manager.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { file ->
                BitmapFactory.decodeFileDescriptor(file.fileDescriptor)
            }
        }.onFailure {
            HookLogger.w("LyricCardBackdrop", "lock wallpaper unavailable: ${it.message}")
        }.getOrNull()
        val lockSource = lockBitmap
            ?: runCatching {
                (manager.getDrawable(WallpaperManager.FLAG_LOCK) as? BitmapDrawable)?.bitmap
            }.getOrNull()
        if (lockSource != null) return fitToScreen(lockSource)
        // 动态壁纸(Wallpaper Engine 等)没有静态锁屏图;此时 getDrawable() 会返回
        // MIUI 默认金色兜底图,拿它做模糊只会得到一张与锁屏完全无关的颜色遮罩。
        val liveWallpaper = runCatching { manager.wallpaperInfo }.getOrNull()
        if (liveWallpaper != null) {
            HookLogger.w(
                "LyricCardBackdrop",
                "live wallpaper=${liveWallpaper.component}; static blur unavailable"
            )
            return null
        }
        val homeSource = runCatching {
            (manager.drawable as? BitmapDrawable)?.bitmap
        }.getOrNull() ?: return null
        return fitToScreen(homeSource)
    }

    /** 壁纸按屏幕中心裁切;不改动 source 本身,返回新的屏幕尺寸位图。 */
    private fun fitToScreen(source: Bitmap): Bitmap {
        val scale = max(
            screenWidth / source.width.toFloat(),
            screenHeight / source.height.toFloat()
        )
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val left = (screenWidth - targetWidth) / 2f
        val top = (screenHeight - targetHeight) / 2f
        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + targetWidth, top + targetHeight),
            paint
        )
        return output
    }

    /**
     * 1/4 缩略图上做三趟盒式模糊再放大。锁屏壁纸是静态的,只在加载/切材质时算一次;
     * 1080×2400 的缩略图约 270×600,三趟计算量在后台线程可接受。
     */
    private fun blurWallpaper(
        source: Bitmap,
        radiusPx: Float,
        materialToken: String,
        brightnessAdjust: Float,
        saturationAdjust: Float
    ): Bitmap {
        val scale = 4
        val smallWidth = max(1, source.width / scale)
        val smallHeight = max(1, source.height / scale)
        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
        val pixels = IntArray(smallWidth * smallHeight)
        small.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
        val radius = (radiusPx / scale).roundToInt().coerceIn(1, 40)
        val scratch = IntArray(pixels.size)
        var current = pixels
        var other = scratch
        repeat(3) {
            horizontalBlur(current, other, smallWidth, smallHeight, radius)
            verticalBlur(other, current, smallWidth, smallHeight, radius)
        }
        applyColorControls(current, materialToken, brightnessAdjust, saturationAdjust)
        small.setPixels(current, 0, smallWidth, 0, 0, smallWidth, smallHeight)
        val output = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()
        return output
    }

    /**
     * 与 app 的 colorControls 对齐:暗色主题下轻微提亮 + 对比度/饱和度调整。
     * 只改模糊层本身,不引入额外颜色。材质页的 brightness/saturation/背景两项
     * 作为相对偏移叠在材质基线上;实时模糊路径由系统合成,无法应用这两个值。
     */
    private fun applyColorControls(
        pixels: IntArray,
        materialToken: String,
        brightnessAdjust: Float,
        saturationAdjust: Float
    ) {
        val brightness = when (materialToken) {
            CARD_MATERIAL_GAUSSIAN -> 0.02f
            CARD_MATERIAL_HIGHLIGHT -> 0.03f
            CARD_MATERIAL_LIQUID -> 0.04f
            CARD_MATERIAL_SOFT -> 0.05f
            else -> 0.02f
        } + brightnessAdjust
        val contrast = when (materialToken) {
            CARD_MATERIAL_GAUSSIAN -> 1.04f
            CARD_MATERIAL_HIGHLIGHT -> 1.06f
            CARD_MATERIAL_LIQUID -> 1.05f
            CARD_MATERIAL_SOFT -> 1.0f
            else -> 1.04f
        }
        val saturation = (
            when (materialToken) {
                CARD_MATERIAL_GAUSSIAN -> 1.08f
                CARD_MATERIAL_HIGHLIGHT -> 1.12f
                CARD_MATERIAL_LIQUID -> 1.30f
                CARD_MATERIAL_SOFT -> 1.05f
                else -> 1.08f
            } * saturationAdjust.coerceIn(0f, 2f)
            ).coerceIn(0f, 2f)
        val brightnessOffset = brightness.coerceIn(-0.3f, 0.3f) * 255f
        for (index in pixels.indices) {
            val color = pixels[index]
            val alpha = color ushr 24
            var red = ((color shr 16) and 0xFF).toFloat()
            var green = ((color shr 8) and 0xFF).toFloat()
            var blue = (color and 0xFF).toFloat()
            val luma = 0.299f * red + 0.587f * green + 0.114f * blue
            red = luma + (red - luma) * saturation
            green = luma + (green - luma) * saturation
            blue = luma + (blue - luma) * saturation
            red = (red - 128f) * contrast + 128f + brightnessOffset
            green = (green - 128f) * contrast + 128f + brightnessOffset
            blue = (blue - 128f) * contrast + 128f + brightnessOffset
            val r = red.roundToInt().coerceIn(0, 255)
            val g = green.roundToInt().coerceIn(0, 255)
            val b = blue.roundToInt().coerceIn(0, 255)
            pixels[index] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun horizontalBlur(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            val row = y * width
            for (i in -radius..radius) {
                val color = src[row + i.coerceIn(0, width - 1)]
                sumA += color ushr 24
                sumR += (color shr 16) and 0xFF
                sumG += (color shr 8) and 0xFF
                sumB += color and 0xFF
            }
            for (x in 0 until width) {
                dst[row + x] = ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)
                val remove = src[row + (x - radius).coerceIn(0, width - 1)]
                val add = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                sumA += (add ushr 24) - (remove ushr 24)
                sumR += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                sumG += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }

    private fun verticalBlur(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val color = src[i.coerceIn(0, height - 1) * width + x]
                sumA += color ushr 24
                sumR += (color shr 16) and 0xFF
                sumG += (color shr 8) and 0xFF
                sumB += color and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)
                val remove = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sumA += (add ushr 24) - (remove ushr 24)
                sumR += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                sumG += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }
}

/**
 * 材质模糊半径(基础值) × 强度系数:
 * 0% = 0.5×基础半径,50% = 1×,100% = 1.5×。
 */
internal fun cardBlurRadiusDp(materialToken: String, strengthPercent: Int): Float? {
    val base = when (materialToken) {
        CARD_MATERIAL_GAUSSIAN -> 16f
        CARD_MATERIAL_HIGHLIGHT -> 14f
        CARD_MATERIAL_LIQUID -> 12f
        CARD_MATERIAL_SOFT -> 20f
        else -> return null
    }
    // 0% 允许到接近 0;100% 仍是 1.5× 基础半径。
    return base * (strengthPercent.coerceIn(0, 100) / 100f * 1.5f)
}
