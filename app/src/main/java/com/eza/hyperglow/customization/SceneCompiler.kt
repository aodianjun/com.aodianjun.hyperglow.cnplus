package com.eza.hyperglow.customization

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object SceneCompiler {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun compile(document: CustomizationDocument): CompiledCustomization {
        val source = if (document.version == CURRENT_CUSTOMIZATION_VERSION) {
            document
        } else {
            safeDefaultDocument()
        }
        val lockscreenSource = (source.profiles[SURFACE_LOCKSCREEN] ?: safeLockscreenProfile())
            .withDocumentDefaults(source)
        val aodSource = (source.profiles[SURFACE_AOD] ?: safeAodProfile())
            .withDocumentDefaults(source)
        val linkedBase = aodSource.takeIf { source.linkSurfaces }
        val lockscreen = compileProfile(
            SURFACE_LOCKSCREEN,
            linkedBase?.copy(
                enabled = lockscreenSource.enabled,
                collisionPolicy = lockscreenSource.collisionPolicy,
                widgets = lockscreenSource.widgets,
                metadataVisible = lockscreenSource.metadataVisible,
                artworkVisible = lockscreenSource.artworkVisible,
                artworkShape = lockscreenSource.artworkShape,
                artworkSpin = lockscreenSource.artworkSpin,
                artworkSpinWhenPaused = lockscreenSource.artworkSpinWhenPaused,
                artworkAdaptiveScale = lockscreenSource.artworkAdaptiveScale,
                artworkSizeDp = lockscreenSource.artworkSizeDp,
                backgroundStyle = lockscreenSource.backgroundStyle,
                cardAlpha = lockscreenSource.cardAlpha,
                cardColor = lockscreenSource.cardColor
            ) ?: lockscreenSource
        )
        val aod = compileProfile(SURFACE_AOD, aodSource)
        val base = CompiledCustomization(
            version = CURRENT_CUSTOMIZATION_VERSION,
            revision = 0L,
            hash = "",
            sourceId = normalizeId(source.id),
            linkSurfaces = source.linkSurfaces,
            metadataParts = normalizeMetadataParts(source.metadataParts),
            metadataSeparators = normalizeMetadataSeparators(
                source.metadataSeparators,
                source.metadataParts
            ),
            hideAlbumWhenSameAsTitle = source.hideAlbumWhenSameAsTitle,
            duetMarkers = source.duetMarkers,
            lyricTimeOffsetMs = LyricTimeOffset.normalize(source.lyricTimeOffsetMs),
            profiles = linkedMapOf(SURFACE_LOCKSCREEN to lockscreen, SURFACE_AOD to aod)
        )
        return finalizeCompiled(base) ?: compileSafeDefault()
    }

    fun decodeDocument(raw: String): CustomizationDocument? {
        if (raw.toByteArray().size > MAX_CONFIG_BYTES) return null
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (containsUnsafeSchemaElement(element)) return null
        return runCatching { json.decodeFromString<CustomizationDocument>(raw) }.getOrNull()
    }

    internal fun finalizeCompiled(configuration: CompiledCustomization): CompiledCustomization? {
        val orderedProfiles = linkedMapOf<String, CompiledSurfaceProfile>()
        configuration.profiles[SURFACE_LOCKSCREEN]?.let { orderedProfiles[SURFACE_LOCKSCREEN] = it }
        configuration.profiles[SURFACE_AOD]?.let { orderedProfiles[SURFACE_AOD] = it }
        val base = configuration.copy(
            version = CURRENT_CUSTOMIZATION_VERSION,
            revision = 0L,
            hash = "",
            profiles = orderedProfiles
        )
        val encoded = json.encodeToString(base)
        if (encoded.toByteArray().size > MAX_CONFIG_BYTES) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
        return base.copy(
            revision = ByteBuffer.wrap(digest).long and Long.MAX_VALUE,
            hash = digest.joinToString("") { "%02x".format(it) }
        )
    }

    fun safeDefaultDocument(): CustomizationDocument = CustomizationDocument(
        profiles = linkedMapOf(
            SURFACE_LOCKSCREEN to safeLockscreenProfile(),
            SURFACE_AOD to safeAodProfile()
        )
    )

    fun safeLockscreenProfile(): SurfaceProfile = SurfaceProfile(enabled = false)

    fun safeAodProfile(): SurfaceProfile = SurfaceProfile(
        enabled = true,
        maxHeightFraction = 0.42f,
        widgets = listOf(WidgetSpec("lyrics"))
    )

    private fun compileSafeDefault(): CompiledCustomization {
        val safe = safeDefaultDocument()
        val lockscreen = compileProfile(SURFACE_LOCKSCREEN, safe.profiles.getValue(SURFACE_LOCKSCREEN))
        val aod = compileProfile(SURFACE_AOD, safe.profiles.getValue(SURFACE_AOD))
        val base = CompiledCustomization(
            version = CURRENT_CUSTOMIZATION_VERSION,
            revision = 1L,
            hash = "safe",
            sourceId = safe.id,
            linkSurfaces = safe.linkSurfaces,
            metadataParts = normalizeMetadataParts(safe.metadataParts),
            metadataSeparators = normalizeMetadataSeparators(
                safe.metadataSeparators,
                safe.metadataParts
            ),
            hideAlbumWhenSameAsTitle = safe.hideAlbumWhenSameAsTitle,
            duetMarkers = safe.duetMarkers,
            lyricTimeOffsetMs = LyricTimeOffset.normalize(safe.lyricTimeOffsetMs),
            profiles = linkedMapOf(SURFACE_LOCKSCREEN to lockscreen, SURFACE_AOD to aod)
        )
        return finalizeCompiled(base) ?: error("Safe customization exceeds hard limit")
    }

    private fun compileProfile(surface: String, profile: SurfaceProfile): CompiledSurfaceProfile {
        val aod = surface == SURFACE_AOD
        // per-surface 歌曲信息内容:未显式设置(null)时由 withDocumentDefaults 预填文档级默认值;
        // 这里再兜一层归一化,兼容 compileSafeDefault 直接传入未预填的默认 profile。
        val parts = normalizeMetadataParts(profile.metadataParts)
        val supportedWidgets = profile.widgets.asSequence()
            .filter { it.visible }
            .filter { it.type in KNOWN_WIDGETS }
            .filter { !aod || it.type in AOD_WIDGETS }
            .take(if (aod) MAX_AOD_WIDGETS else MAX_WIDGETS)
            .map { it.copy(style = normalizeId(it.style)) }
            .toMutableList()
        if (supportedWidgets.none { it.type == "lyrics" }) {
            if (supportedWidgets.size >= if (aod) MAX_AOD_WIDGETS else MAX_WIDGETS) {
                supportedWidgets.removeLast()
            }
            supportedWidgets.add(0, WidgetSpec("lyrics"))
        }
        val palette = profile.palette.asSequence()
            .filter { it.key in SEMANTIC_COLORS && isAllowedPaletteValue(it.value) }
            .take(SEMANTIC_COLORS.size)
            .associate { it.key to it.value }
        return CompiledSurfaceProfile(
            surface = surface,
            enabled = profile.enabled,
            anchor = profile.anchor.takeIf { it in ANCHORS } ?: "below_stock_clock",
            aodClockFollow = profile.aodClockFollow,
            widthFraction = profile.widthFraction.coerceIn(0.4f, 1f),
            maxHeightFraction = if (aod) {
                // 息屏不渲染卡片背景,高度档位没有视觉意义,固定到最小档(0.3):
                // 歌词块尽量贴合内容,摆放只由纵向位置(anchor/verticalBias)控制。
                AOD_FIXED_MAX_HEIGHT_FRACTION
            } else {
                profile.maxHeightFraction.coerceIn(0.15f, 0.8f)
            },
            verticalBias = profile.verticalBias.coerceIn(0f, 1f),
            collisionPolicy = profile.collisionPolicy.takeIf { it in COLLISION_POLICIES } ?: "avoid",
            widgets = supportedWidgets,
            transition = profile.transition.copy(
                id = profile.transition.id.takeIf { it in TRANSITIONS } ?: "continuity",
                durationMs = profile.transition.durationMs.coerceIn(150, 600),
                easing = profile.transition.easing.takeIf { it in EASINGS } ?: "fast_out_slow_in"
            ),
            alignment = profile.alignment.takeIf { it in ALIGNMENTS } ?: "auto",
            secondaryMode = normalizeAuxMode(profile.secondaryMode),
            secondaryTextBright = profile.secondaryTextBright,
            secondaryWordKaraoke = profile.secondaryWordKaraoke,
            secondaryTextSizePercent = normalizeSecondaryTextSizePercent(
                profile.secondaryTextSizePercent
            ),
            secondaryAutoSize = profile.secondaryAutoSize,
            lyricLineLimit = normalizeLyricLineLimit(profile.lyricLineLimit),
            showNextLine = profile.showNextLine,
            secondaryNextLine = profile.secondaryNextLine,
            nextLineAux = profile.nextLineAux,
            metadataVisible = profile.metadataVisible &&
                supportedWidgets.any { it.type == "metadata" },
            metadataAnchor = if (profile.metadataAnchor == "bottom") "bottom" else "top",
            metadataSizePercent = profile.metadataSizePercent.coerceIn(50, 200),
            metadataAlignment = profile.metadataAlignment.takeIf { it in ALIGNMENTS } ?: "auto",
            nextLineAlignment = profile.nextLineAlignment.takeIf { it in ALIGNMENTS } ?: "auto",
            artworkVisible = profile.artworkVisible,
            artworkShape = normalizeArtworkShape(profile.artworkShape),
            artworkSpin = profile.artworkSpin,
            artworkSpinWhenPaused = profile.artworkSpinWhenPaused,
            artworkAdaptiveScale = profile.artworkAdaptiveScale,
            artworkSizeDp = normalizeArtworkSizeDp(profile.artworkSizeDp),
            duetAlignment = profile.duetAlignment,
            duetConcurrent = profile.duetConcurrent,
            rubyVisible = profile.rubyVisible,
            weight = profile.weight.takeIf { it in WEIGHTS } ?: "Medium",
            textSize = profile.textSize.takeIf { it in TEXT_SIZES } ?: "normal",
            textSizeCustom = profile.textSizeCustom.coerceIn(50, 200),
            fontFamily = profile.fontFamily.takeIf {
                it in FONT_FAMILIES || CustomFontContract.isCustomFontFamily(it)
            } ?: "spotify",
            animation = when {
                // 息屏与锁屏共用同一画布词表(历史档曾把息屏非 Minimal 一律压回 Gradient;
                // 对既有词表两分支逐值等价,新档入词表后按成员放行,词表外仍回落 Gradient)。
                profile.animation in ANIMATIONS -> profile.animation
                else -> "Gradient"
            },
            glow = if (profile.glow == "On") "On" else "Off",
            lineSyncFillMode = normalizeLineSyncFillMode(profile.lineSyncFillMode),
            overflow = if (profile.overflow == "Clip") "Clip" else "Wrap",
            lineTransition = normalizeLineTransition(profile.lineTransition),
            lineTransitionSpeed = normalizeLineTransitionSpeed(profile.lineTransitionSpeed),
            adaptiveSectioning = profile.adaptiveSectioning,
            palette = palette,
            backgroundStyle = when {
                aod -> "none"
                profile.backgroundStyle == "none" -> "none"
                else -> "card"
            },
            cardAlpha = normalizeCardAlpha(profile.cardAlpha),
            cardColor = normalizeCardColor(profile.cardColor),
            metadataParts = parts,
            metadataSeparators = normalizeMetadataSeparators(profile.metadataSeparators, parts),
            hideAlbumWhenSameAsTitle = profile.hideAlbumWhenSameAsTitle == true,
            duetMarkers = profile.duetMarkers != false
        )
    }

    /**
     * 把 surface profile 里未显式设置(null)的 per-surface 内容项填成文档级默认值,
     * 使编译期只面对已解析的非空值(旧配置升级语义:未单独设置的曲面沿用文档级值)。
     */
    private fun SurfaceProfile.withDocumentDefaults(
        document: CustomizationDocument
    ): SurfaceProfile = copy(
        metadataParts = metadataParts ?: document.metadataParts,
        metadataSeparators = metadataSeparators ?: document.metadataSeparators,
        hideAlbumWhenSameAsTitle = hideAlbumWhenSameAsTitle ?: document.hideAlbumWhenSameAsTitle,
        duetMarkers = duetMarkers ?: document.duetMarkers
    )

    private fun normalizeId(value: String): String = value
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(64)
        .ifBlank { "default" }

    private fun containsUnsafeSchemaElement(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            val normalizedKey = key.lowercase()
            FORBIDDEN_SCHEMA_KEYS.any(normalizedKey::contains) ||
                containsUnsafeSchemaElement(value)
        }
        is JsonArray -> element.any(::containsUnsafeSchemaElement)
        is JsonPrimitive -> element.contentOrNull?.let(::containsUnsafeSchemaValue) == true
    }

    private fun containsUnsafeSchemaValue(value: String): Boolean {
        val trimmed = value.trim()
        val lower = trimmed.lowercase()
        return value.indexOf('\u0000') >= 0 ||
            "://" in lower ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("/") ||
            "../" in lower ||
            "..\\" in lower ||
            "\\" in value ||
            COMMAND_PREFIXES.any { lower == it || lower.startsWith("$it ") }
    }

    const val MAX_CONFIG_BYTES = 64 * 1024
    const val TARGET_CONFIG_BYTES = 32 * 1024
    const val MAX_WIDGETS = 8
    const val MAX_AOD_WIDGETS = 4
    /** 息屏歌词块固定高度档位(此前高度设置的最小可选值)。 */
    const val AOD_FIXED_MAX_HEIGHT_FRACTION = 0.3f
    const val SURFACE_LOCKSCREEN = "lockscreen"
    const val SURFACE_AOD = "aod"

    val KNOWN_WIDGETS = setOf("lyrics", "metadata", "media_progress")
    private val AOD_WIDGETS = setOf("lyrics", "metadata")
    private val ANCHORS = setOf(
        "below_stock_clock",
        "screen_center",
        "screen_top_safe",
        "screen_bottom_safe",
        "custom_vertical_bias"
    )
    private val COLLISION_POLICIES = setOf(
        "avoid",
        "behind_system",
        "hide_optional",
        "hide_scene"
    )
    private val TRANSITIONS = setOf("continuity", "crossfade", "none")
    private val EASINGS = setOf("fast_out_slow_in", "linear", "ease_out")
    private val ALIGNMENTS = setOf("auto", "start", "center", "end")
    // 辅助文字模式不再是固定档位集合:历史四档 + 多选内容集合,校验即归一
    // (见 normalizeAuxMode;词表外的值回落「仅主歌词」)。
    private val WEIGHTS = setOf("Regular", "Medium", "Bold")
    private val TEXT_SIZES = setOf("small", "normal", "large", "xlarge", "custom")
    private val FONT_FAMILIES = setOf("noto", "spotify", "apple", "noto-sc", "custom")
    private val ANIMATIONS = setOf("Minimal", "Gradient", "BetterLyrics")
    private fun normalizeLineSyncFillMode(value: String): String = when (value) {
        "None",
        "Top to bottom",
        "Left to right (whole block)" -> value
        else -> "Left to right (main only)"
    }
    private val SEMANTIC_COLORS = setOf(
        "secondaryText",
        "metadataText",
        "nextLineText",
        "sungText",
        "unsungText",
        "glow",
        "accent"
    )
    private val PALETTE_VALUES = setOf("default", "clock", "wallpaper", "white", "dimmed")

    /**
     * 调色板 token 是否允许通过编译白名单:预设名或合法 hex 色值("#RGB"/"#RRGGBB"/"#AARRGGBB")。
     * 字体颜色等自定义色以 hex token 存储,各编译/投影层共用本判定避免被预设白名单过滤掉。
     */
    fun isAllowedPaletteValue(value: String): Boolean =
        value in PALETTE_VALUES || isHexColorToken(value)

    private fun isHexColorToken(value: String): Boolean {
        if (value[0] != '#' || value.length !in intArrayOf(4, 7, 9)) return false
        for (c in value.substring(1)) {
            if (Character.digit(c, 16) < 0) return false
        }
        return true
    }
    private val FORBIDDEN_SCHEMA_KEYS = setOf(
        "class",
        "resource",
        "method",
        "path",
        "url",
        "uri",
        "command",
        "shell",
        "script",
        "intent",
        "component"
    )
    private val COMMAND_PREFIXES = setOf(
        "sh",
        "bash",
        "zsh",
        "cmd",
        "su",
        "exec",
        "am",
        "pm",
        "rm",
        "curl",
        "wget",
        "adb"
    )
}
