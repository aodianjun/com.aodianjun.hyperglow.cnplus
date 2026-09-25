package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import com.eza.hyperglow.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LyricTypefaceResolver {
    private data class TypefaceKey(val family: String, val weight: String, val variant: String = "")

    private val cache = ConcurrentHashMap<TypefaceKey, Typeface>()

    const val FAMILY_NOTO_SC = "noto-sc"
    const val FAMILY_CUSTOM = "custom"

    private const val CUSTOM_VERSION_TTL_MS = 10_000L

    @Volatile
    private var customVersionCache: Pair<Long, String?>? = null

    fun resolve(context: Context, family: String, weight: String): Typeface {
        if (family == FAMILY_CUSTOM) return resolveCustom(context, weight)
        val key = TypefaceKey(family, weight)
        cache[key]?.let { return it }
        val typeface = runCatching {
            Typeface.createFromAsset(context.assets, assetPath(family, weight))
        }.getOrElse {
            fallbackTypeface(family, weight)
        }
        cache[key] = typeface
        return typeface
    }

    private fun resolveCustom(context: Context, weight: String): Typeface {
        val version = customVersion(context)
            ?: return fallbackTypeface(FAMILY_CUSTOM, weight)
        val key = TypefaceKey(FAMILY_CUSTOM, weight, version)
        cache[key]?.let { return it }
        val local = File(context.filesDir, CustomFontContract.FONT_RELATIVE_PATH)
        val base = if (local.canRead()) {
            runCatching { Typeface.createFromFile(local) }.getOrNull()
        } else {
            loadViaProvider(context, version)
        }
        val typeface = base?.let { Typeface.create(it, if (weight == "Bold") Typeface.BOLD else Typeface.NORMAL) }
            ?: fallbackTypeface(FAMILY_CUSTOM, weight)
        cache[key] = typeface
        return typeface
    }

    fun invalidateCustomCache() {
        customVersionCache = null
    }

    fun customVersion(context: Context): String? {
        val now = System.currentTimeMillis()
        customVersionCache?.let { (fetchedAt, version) ->
            if (now - fetchedAt < CUSTOM_VERSION_TTL_MS) return version
        }
        val local = File(context.filesDir, CustomFontContract.FONT_RELATIVE_PATH)
        val version = if (local.canRead()) {
            "${local.lastModified()}_${local.length()}"
        } else {
            queryProviderVersion(context)
        }
        customVersionCache = now to version
        return version
    }

    private fun queryProviderVersion(context: Context): String? = runCatching {
        val result: Bundle? = context.contentResolver.call(
            customFontUri(),
            CustomFontContract.METHOD_VERSION,
            null,
            null
        )
        result?.getString(CustomFontContract.EXTRA_VERSION)
    }.getOrNull()

    private fun loadViaProvider(context: Context, version: String): Typeface? = runCatching {
        val target = File(context.cacheDir, "custom_font_$version.ttf")
        if (!target.exists()) {
            context.contentResolver.openInputStream(customFontUri())?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
        }
        Typeface.createFromFile(target)
    }.getOrNull()

    private fun customFontUri(): Uri = Uri.parse(
        "content://${BuildConfig.APPLICATION_ID}${CustomFontContract.AUTHORITY_SUFFIX}/${CustomFontContract.PATH_FONT}"
    )

    private fun fallbackTypeface(family: String, weight: String): Typeface {
        val fallback = if (family == "apple") "sans-serif" else "sans-serif-medium"
        return Typeface.create(fallback, if (weight == "Bold") Typeface.BOLD else Typeface.NORMAL)
    }

    internal fun assetPath(family: String, weight: String): String = when {
        family == "noto" -> "fonts/NotoSans-" + when (weight) {
            "Bold" -> "Bold"
            "Medium" -> "Medium"
            else -> "Regular"
        } + ".ttf"
        family == FAMILY_NOTO_SC -> "fonts/NotoSansSC-" + when (weight) {
            "Bold" -> "Bold"
            else -> "Regular"
        } + ".ttf"
        family == "apple" ->
            if (weight == "Regular") "fonts/lyrics_medium.ttf" else "fonts/sf-pro-display-bold.ttf"
        weight == "Bold" -> "fonts/sf-pro-display-bold.ttf"
        else -> "fonts/spotifymix-medium.ttf"
    }
}

object CustomFontContract {
    const val AUTHORITY_SUFFIX = ".customfont"
    const val PATH_FONT = "font"
    const val FONT_RELATIVE_PATH = "custom_fonts/custom.ttf"
    const val METHOD_VERSION = "font_version"
    const val EXTRA_VERSION = "version"
}
