package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Typeface
import com.eza.hyperglow.customization.CustomFontContract
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词字体解析(预览与实机同源)。
 *
 * 两类上下文必须分开传入,这是 0.3.116 实机「自定义字体只同步预览」的根因:
 * - [assetContext] 用于内置字体:实机侧是模块包上下文(SystemUI 经
 *   `createPackageContext` 读模块 APK 的 `assets/fonts/**`),预览侧就是应用自身。
 * - [cacheContext] 用于自定义字体:文件本体在应用私有目录(`0700`,SystemUI 读不到),
 *   只能经 ContentProvider 取流后落到**本进程可写**的 cacheDir。此前实现把副本写进
 *   assetContext(模块包)的 cacheDir,SystemUI 无写权限、`runCatching` 吞掉异常后静默
 *   回落 sans-serif-medium —— 预览正常、实机永远是回退字体。
 */
object LyricTypefaceResolver {
    private data class TypefaceKey(val family: String, val weight: String, val variant: String = "")

    private val cache = ConcurrentHashMap<TypefaceKey, Typeface>()

    const val FAMILY_NOTO_SC = "noto-sc"
    const val FAMILY_CUSTOM = CustomFontContract.FAMILY_CUSTOM

    private const val CUSTOM_VERSION_TTL_MS = 10_000L

    // 版本缓存按字体 id 分键:多字体共存时,同一 TTL 窗口内切换字体不能串版本。
    private val customVersionCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    fun resolve(
        assetContext: Context,
        family: String,
        weight: String,
        cacheContext: Context = assetContext
    ): Typeface {
        if (CustomFontContract.isCustomFontFamily(family)) {
            return resolveCustom(assetContext, cacheContext, family, weight)
        }
        val key = TypefaceKey(family, weight)
        cache[key]?.let { return it }
        val typeface = runCatching {
            Typeface.createFromAsset(assetContext.assets, assetPath(family, weight))
        }.getOrElse {
            fallbackTypeface(family, weight)
        }
        cache[key] = typeface
        return typeface
    }

    private fun resolveCustom(
        assetContext: Context,
        cacheContext: Context,
        family: String,
        weight: String
    ): Typeface {
        val id = CustomFontContract.fontIdOf(family) ?: return fallbackTypeface(family, weight)
        val version = customVersion(assetContext, cacheContext, family)
            ?: return fallbackTypeface(family, weight)
        val key = TypefaceKey(family, weight, version)
        cache[key]?.let { return it }
        val local = CustomFontContract.fontFile(assetContext.filesDir, id)
        val base = if (local.canRead()) {
            runCatching { Typeface.createFromFile(local) }.getOrNull()
        } else {
            loadViaProvider(cacheContext, family, id, version)
        }
        val typeface = base?.let {
            Typeface.create(it, if (weight == "Bold") Typeface.BOLD else Typeface.NORMAL)
        } ?: fallbackTypeface(family, weight)
        cache[key] = typeface
        return typeface
    }

    fun invalidateCustomCache() {
        customVersionCache.clear()
    }

    fun customVersion(context: Context, cacheContext: Context, family: String): String? {
        val id = CustomFontContract.fontIdOf(family) ?: return null
        val now = System.currentTimeMillis()
        customVersionCache[id]?.let { (fetchedAt, version) ->
            if (now - fetchedAt < CUSTOM_VERSION_TTL_MS) return version
        }
        val local = CustomFontContract.fontFile(context.filesDir, id)
        val version = if (local.canRead()) {
            "${local.lastModified()}_${local.length()}"
        } else {
            queryProviderVersion(cacheContext, family)
        }
        customVersionCache[id] = now to version
        return version
    }

    private fun queryProviderVersion(cacheContext: Context, family: String): String? = runCatching {
        val result = cacheContext.contentResolver.call(
            CustomFontContract.customFontUri(family),
            CustomFontContract.METHOD_VERSION,
            null,
            null
        )
        result?.getString(CustomFontContract.EXTRA_VERSION)
    }.getOrNull()

    /**
     * 经 Provider 取流并缓存到 [cacheContext] 自己的 cacheDir(SystemUI 侧即 SystemUI 缓存),
     * 文件名带版本号,字体更新后自然失效;同时清掉同前缀的历史副本避免无限增长。
     */
    private fun loadViaProvider(
        cacheContext: Context,
        family: String,
        id: String,
        version: String
    ): Typeface? = runCatching {
        val safeVersion = version.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        val target = File(cacheContext.cacheDir, "$CUSTOM_CACHE_PREFIX${id}_$safeVersion.ttf")
        if (!target.exists()) {
            val copied = runCatching {
                cacheContext.contentResolver
                    .openInputStream(CustomFontContract.customFontUri(family))?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: false
            }.getOrDefault(false)
            if (!copied) return@runCatching null
            pruneStaleCopies(cacheContext, target.name)
        }
        Typeface.createFromFile(target)
    }.getOrNull()

    private fun pruneStaleCopies(cacheContext: Context, keepName: String) {
        runCatching {
            cacheContext.cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(CUSTOM_CACHE_PREFIX) && it.name != keepName }
                ?.forEach { it.delete() }
        }
    }

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

    private const val CUSTOM_CACHE_PREFIX = "custom_font_"
}
