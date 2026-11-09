package com.eza.hyperglow.root.aod

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.customization.SpriteWire

/**
 * SystemUI 侧的自定义贴图注册表:配置回调内读到**已校验字节**后解码成 Bitmap,
 * 按编号(64..255)整批替换。绘制线程只读 [bitmap],不持有 Bundle/fd。
 *
 * 内存护栏:单张≤[MAX_SPRITE_DIM] 像素边长,整批累计像素≤[MAX_TOTAL_PIXELS]
 * (超预算的条目直接丢弃,不拖垮其余贴图)。换下来的旧位图不主动 recycle——
 * 绘制线程可能正在使用,交给 GC。
 */
internal object CustomSpriteRegistry {

    const val MAX_SPRITE_DIM = 1024
    const val MAX_TOTAL_PIXELS = 6_000_000L

    @Volatile
    private var bitmaps: Map<Int, Bitmap> = emptyMap()

    fun bitmap(spriteId: Int): Bitmap? =
        if (spriteId in SpriteWire.MIN_SPRITE_ID..SpriteWire.MAX_SPRITE_ID) {
            bitmaps[spriteId]
        } else {
            null
        }

    fun install(received: List<SpriteWire.Received>) {
        if (received.isEmpty()) {
            if (bitmaps.isNotEmpty()) {
                bitmaps = emptyMap()
                HookLogger.w(TAG, "Custom sprites cleared")
            }
            return
        }
        val installed = HashMap<Int, Bitmap>(received.size)
        var pixels = 0L
        for (sprite in received) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(sprite.bytes, 0, sprite.bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0 ||
                width > MAX_SPRITE_DIM || height > MAX_SPRITE_DIM
            ) {
                HookLogger.w(TAG, "Sprite ${sprite.id} rejected dims=${width}x$height")
                continue
            }
            val remaining = MAX_TOTAL_PIXELS - pixels
            if (remaining <= 0L) {
                HookLogger.w(TAG, "Sprite ${sprite.id} dropped: pixel budget exhausted")
                continue
            }
            // 预算不够整张时按 2 的幂降采样,尽量装上而不是整张丢弃。
            var sample = 1
            while ((width / sample).toLong() * (height / sample) > remaining) {
                sample *= 2
            }
            if (width / sample < 8 || height / sample < 8) {
                HookLogger.w(TAG, "Sprite ${sprite.id} dropped: too small at sample=$sample")
                continue
            }
            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(
                    sprite.bytes, 0, sprite.bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull() ?: continue
            installed[sprite.id] = bitmap
            pixels += bitmap.width.toLong() * bitmap.height
        }
        bitmaps = installed
        HookLogger.w(
            TAG,
            "Custom sprites installed count=${installed.size} pixels=$pixels"
        )
    }

    private const val TAG = "HyperGlowSprites"
}
