package com.eza.hyperglow.root.lockscreen

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.eza.hyperglow.root.aod.AodLyricCanvasView

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
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private var lyricCanvas: AodLyricCanvasView? = null
    private var progress: View? = null
    private var cardEnabled = false

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
        invalidate()
    }

    /**
     * 应用卡片背景色与不透明度。[alphaPercent] 0-100;[colorToken] 见
     * [com.eza.hyperglow.customization.CARD_COLOR_VALUES]。在 backgroundStyle=="card"
     * 时由 [applyCardBackground] 调用。
     */
    fun setCardAppearance(alphaPercent: Int, colorToken: String) {
        val alpha = (alphaPercent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        val rgb = cardColorRgb(colorToken)
        paint.color = (alpha shl 24) or (rgb and 0x00FFFFFF)
        invalidate()
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
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}
