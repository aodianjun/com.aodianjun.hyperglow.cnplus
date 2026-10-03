package com.eza.hyperglow.root.aod

internal data class AodCanvasVerticalBounds(val top: Float, val bottom: Float)

internal fun unionAodCanvasVerticalBounds(
    first: AodCanvasVerticalBounds?,
    second: AodCanvasVerticalBounds?
): AodCanvasVerticalBounds? = when {
    first == null -> second
    second == null -> first
    else -> AodCanvasVerticalBounds(
        top = minOf(first.top, second.top),
        bottom = maxOf(first.bottom, second.bottom)
    )
}

internal fun edgeSafeAlignedStart(
    canvasWidth: Float,
    paddingLeft: Float,
    paddingRight: Float,
    visualLeft: Float,
    visualRight: Float,
    alignment: String,
    safetyInset: Float = 0f
): Float {
    val leftEdge = paddingLeft + safetyInset
    val rightEdge = canvasWidth - paddingRight - safetyInset
    return when (alignment) {
        "end" -> rightEdge - visualRight
        "center" -> (leftEdge + rightEdge - visualLeft - visualRight) / 2f
        else -> leftEdge - visualLeft
    }
}

internal fun sharedBlockClipBottom(progress: Float, top: Float, bottom: Float): Float =
    top + (bottom - top).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)

/**
 * 行级同步源(带行窗)是否走共享逐行扫光块。
 *
 * [animationMode] 为 "BetterLyrics" 时必须为 false:该档的契约是把逐字/音节时间源交给共享
 * 词级卡拉OK核心(逐字源用真实词窗、行级源按字符合成),此前缺这条豁免,带行窗的源
 * (Spicy/LyricInfo/SuperLyric)会在 drawRows 就被拦到共享扫光块,永远走不到
 * [planOriginalLine] 的卡拉OK分支——表现即「预览有逐字效果、实机没有」。
 */
internal fun shouldUseSharedLineLevelSweep(
    lineLevelSync: Boolean,
    hasOriginalLines: Boolean,
    animationMode: String,
    lineStartMs: Long,
    lineEndMs: Long
): Boolean = lineLevelSync && hasOriginalLines && animationMode != "Minimal" &&
    animationMode != "BetterLyrics" &&
    lineEndMs > lineStartMs

/** 原始主行的渲染路径(实机画布与 App 内预览三选一,见 [planOriginalLine])。 */
internal enum class OriginalLinePath {
    /** 静态全亮:Minimal 档或行进度效果 None(无扫光、无发光)。 */
    STATIC,

    /** 词级卡拉OK(共享 [LyricWordKaraokeRenderer]):逐字源用真实词窗,行级源按字符合成。 */
    WORD_KARAOKE,

    /** 共享扫光块([LyricGlowRenderer]):[OriginalLinePlan.fillMode] 决定逐行/整块/纵向推进。 */
    BLOCK_SWEEP
}

/** 主行渲染决策:路径 + 生效的行进度效果(仅 [OriginalLinePath.BLOCK_SWEEP] 消费 fillMode)。 */
internal data class OriginalLinePlan(
    val path: OriginalLinePath,
    val fillMode: String
)

/**
 * 原始主行渲染决策 —— 实机 `drawRows` 的共享扫光门 + `drawOriginal` 的分支树 + App 内预览
 * 的主行分支三处**只此一份**,由两侧调用同一函数保证「预览即实机」。逐条对应:
 *
 *  1. Minimal 档 → 静态全亮;
 *  2. 行级同步且带行窗 → 共享逐行扫光(按配置解析四档行进度效果,整块兼容档仅在显式选择时出现);
 *  3. BetterLyrics 档且进度效果非 None → 词级卡拉OK(逐字源真实词窗 / 行级源字符合成);
 *  4. 行级(无逐字时间)源 → 行级扫光;
 *  5. 逐字源 + 关闭发光 + 非行级同步(大元数据引导态)→ 基础词级卡拉OK;
 *  6. 其余 → 行级扫光。
 *
 * 第 2 条对 BetterLyrics 关闭(见 [shouldUseSharedLineLevelSweep]);第 6 条取生效进度效果而
 * 非整块常量,否则整块横扫会在用户选了逐行/纵向/None 时仍然出现(契约:整块只保留给显式选择)。
 *
 * [timed] 为「该行带逐字时间」;预览侧取 `words.isNotEmpty()`,与实机词行布局判定同义。
 */
internal fun planOriginalLine(
    animationMode: String,
    timed: Boolean,
    lineLevelSync: Boolean,
    glowMode: String,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long
): OriginalLinePlan {
    val effectiveFill = resolvedLineSyncFillMode(lineLevelSync, lineSyncFillMode)
    if (animationMode == "Minimal") {
        return OriginalLinePlan(OriginalLinePath.STATIC, "None")
    }
    if (shouldUseSharedLineLevelSweep(
            lineLevelSync,
            hasOriginalLines = true,
            animationMode,
            lineStartMs,
            lineEndMs
        )
    ) {
        return OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, effectiveFill)
    }
    if (animationMode == "BetterLyrics" && effectiveFill != "None") {
        return OriginalLinePlan(OriginalLinePath.WORD_KARAOKE, effectiveFill)
    }
    if (!timed) {
        return OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, effectiveFill)
    }
    if (!usesPreviewGlowPipeline(animationMode, timed, lineLevelSync, glowMode)) {
        return OriginalLinePlan(OriginalLinePath.WORD_KARAOKE, effectiveFill)
    }
    return OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, effectiveFill)
}

internal fun hasActiveCanvasTiming(
    lineLevelSync: Boolean,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long,
    words: List<AodCanvasWord>,
    speed: Float = 1f,
    /**
     * 辅助文字逐字效果是否在本帧内容上生效(开关开启且确有第一行辅助文字行):
     * 它是独立于主行进度效果的时序动画,行级同步 + 进度效果 None(主行静态)时仍需续帧。
     */
    auxKaraoke: Boolean = false
): Boolean {
    if (speed <= 0f) return false
    // 行级同步 + 进度效果选 None:不驱动主行进度时序,歌词静态呈现(与预览 None 一致);
    // 辅助文字逐字效果不受主行进度效果影响,开启时照常驱动帧。
    if (lineLevelSync && resolvedLineSyncFillMode(true, lineSyncFillMode) == "None") {
        return auxKaraoke
    }
    if (lineEndMs > lineStartMs) return true
    return words.any { it.endMs > it.startMs }
}

/**
 * 行级同步下的生效进度效果词表。四种取值各自对应一种真实渲染(见 LyricGlowRenderer.fillMode):
 * "None" 静态全亮、"Top to bottom" 纵向推进、"Left to right (main only)" 逐行依次横向扫光、
 * "Left to right (whole block)" 整块同时横向扫光。仅把旧词表/未知值归一到主行水平扫光,
 * 不再把"None"/"Top to bottom"吞掉(否则选项形同虚设)。
 */
internal fun resolvedLineSyncFillMode(lineLevelSync: Boolean, configuredMode: String): String =
    if (!lineLevelSync) configuredMode
    else when (configuredMode) {
        "None" -> "None"
        "Top to bottom" -> "Top to bottom"
        "Left to right (whole block)" -> "Left to right (whole block)"
        else -> "Left to right (main only)"
    }

internal fun resolvedLyricLayoutLineLimit(
    configuredLimit: Int,
    originalLength: Int,
    wordCount: Int
): Int = if (configuredLimit in 1..5) {
    configuredLimit
} else {
    maxOf(originalLength, wordCount, 1)
}

internal enum class AodCanvasVerticalAlignment { TOP, CENTER }
