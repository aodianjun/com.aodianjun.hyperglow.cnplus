package com.eza.hyperglow.root.aod

// 职责：AOD 歌词字体的解析与缓存——按 family+weight 映射内置字体包资产，失败时回退系统字体。

import android.content.Context
import android.graphics.Typeface
import com.eza.hyperglow.BuildConfig

internal class AodTypefaceResolver(private val context: Context) {
    private val fontContext = runCatching {
        context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()
    private val cache = HashMap<TypefaceKey, Typeface>(3)

    fun resolve(family: String, weight: String): Typeface {
        val key = TypefaceKey(family, weight)
        cache[key]?.let { return it }
        val asset = if (family == "noto") {
            "fonts/NotoSans-" + when (weight) {
                "Bold" -> "Bold"
                "Medium" -> "Medium"
                else -> "Regular"
            } + ".ttf"
        } else if (family == "apple") {
            if (weight == "Regular") "fonts/lyrics_medium.ttf" else "fonts/sf-pro-display-bold.ttf"
        } else if (weight == "Bold") {
            "fonts/sf-pro-display-bold.ttf"
        } else {
            "fonts/spotifymix-medium.ttf"
        }
        val typeface = runCatching {
            Typeface.createFromAsset(fontContext?.assets ?: context.assets, asset)
        }.getOrElse {
            val fallback = if (family == "apple") "sans-serif" else "sans-serif-medium"
            Typeface.create(fallback, if (weight == "Bold") Typeface.BOLD else Typeface.NORMAL)
        }
        cache[key] = typeface
        return typeface
    }

    private data class TypefaceKey(val family: String, val weight: String)
}
