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
 * 该行是否带真实词窗(词级时间源):存在非空白且时间窗有效([isTimedKaraokeWord])的词。
 *
 * 行级源(无词表)与布局分组合成的零窗占位词都为 false —— 这类行继续按行级标记走共享
 * 扫光块(BLOCK_SWEEP,消费「行进度效果」四档);为 true 时非 Minimal 档一律走词级
 * 卡拉OK(见 [planOriginalLine]),歌词源自己的 `lineLevelSync` 不再拦下真实词窗。
 */
internal fun hasTimedWordWindows(words: List<AodCanvasWord>): Boolean =
    words.any { it.text.isNotBlank() && isTimedKaraokeWord(it.startMs, it.endMs) }

/**
 * 行级同步源(带行窗)是否走共享逐行扫光块。
 *
 * [animationMode] 为 "BetterLyrics" 时必须为 false:该档的契约是把逐字/音节时间源交给共享
 * 词级卡拉OK核心(逐字源用真实词窗、行级源按字符合成),此前缺这条豁免,带行窗的源
 * (Spicy/LyricInfo/SuperLyric)会在 drawRows 就被拦到共享扫光块,永远走不到
 * [planOriginalLine] 的卡拉OK分支——表现即「预览有逐字效果、实机没有」。
 *
 * [timed] 为 true(该行带真实词窗)时同样必须为 false:词窗压过歌词源的行级标记,
 * 共享扫光块只保留给没有真实词窗的行(真机日志:AMLL 插件补词后
 * `anim=Gradient timed=true words=13 lineSync=true`,行级标记曾把真实词窗整条忽略)。
 */
internal fun shouldUseSharedLineLevelSweep(
    lineLevelSync: Boolean,
    hasOriginalLines: Boolean,
    animationMode: String,
    lineStartMs: Long,
    lineEndMs: Long,
    timed: Boolean
): Boolean = lineLevelSync && hasOriginalLines && animationMode != "Minimal" &&
    animationMode != "BetterLyrics" && !timed &&
    lineEndMs > lineStartMs

/** 原始主行的渲染路径(实机画布与 App 内预览三选一,见 [planOriginalLine])。 */
internal enum class OriginalLinePath {
    /** 静态全亮:Minimal 档(「行进度效果=None」只在无真实词窗的行上落静态)。 */
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
 *  2. 带真实词窗([timed])→ 词级卡拉OK:非 Minimal 档一律走共享 [LyricWordKaraokeRenderer]
 *     (Gradient 及基础档 = 历史词内扫光逐字动效,BetterLyrics = BetterLyrics 动效),
 *     歌词源的行级标记(`lineLevelSync`)与「行进度效果」四档都不再拦它;
 *  3. 无真实词窗 + 行级同步且带行窗 → 共享逐行扫光(按配置解析四档行进度效果,整块兼容档
 *     仅在显式选择时出现);
 *  4. BetterLyrics 档 + 无真实词窗 + 进度效果非 None → 词级卡拉OK(行级源按字符合成逐字);
 *  5. 其余(无真实词窗的静态/引导态)→ 行级扫光。
 *
 * 第 3 条对 BetterLyrics 与带真实词窗的行关闭(见 [shouldUseSharedLineLevelSweep]);第 5 条
 * 取生效进度效果而非整块常量,否则整块横扫会在用户选了逐行/纵向/None 时仍然出现
 * (契约:整块只保留给显式选择)。
 *
 * [timed] 为「该行带真实词窗」(`words` 中存在非空白且 endMs > startMs 的词,见
 * [hasTimedWordWindows]);预览侧按快照词表同一判据取值,与实机词行布局判定同义。
 */
internal fun planOriginalLine(
    animationMode: String,
    timed: Boolean,
    lineLevelSync: Boolean,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long
): OriginalLinePlan {
    val effectiveFill = resolvedLineSyncFillMode(lineLevelSync, lineSyncFillMode)
    if (animationMode == "Minimal") {
        return OriginalLinePlan(OriginalLinePath.STATIC, "None")
    }
    // 真实词窗压过歌词源的行级标记:非 Minimal 档一律词级卡拉OK。行级源(无词窗)才按
    // 行级标记走共享扫光块,「行进度效果」四档也只对后者生效。
    if (timed) {
        return OriginalLinePlan(OriginalLinePath.WORD_KARAOKE, effectiveFill)
    }
    if (shouldUseSharedLineLevelSweep(
            lineLevelSync,
            hasOriginalLines = true,
            animationMode,
            lineStartMs,
            lineEndMs,
            timed = timed
        )
    ) {
        return OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, effectiveFill)
    }
    if (animationMode == "BetterLyrics" && effectiveFill != "None") {
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
    // 该提前返回只对**没有真实词窗**的行成立——带真实词窗的行压过行级标记,非 Minimal 档
    // 走词级卡拉OK(见 [planOriginalLine]),逐字动画仍需逐帧推进。辅助文字逐字效果
    // 不受主行进度效果影响,开启时照常驱动帧。
    if (lineLevelSync && resolvedLineSyncFillMode(true, lineSyncFillMode) == "None" &&
        !hasTimedWordWindows(words)
    ) {
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

/**
 * 下一行文本是否「未就绪」:与主行同文即视为未就绪。
 *
 * 换行时旧「下一行」被晋级成主行,而上游的 nextLine 要等下一句推来才推进——中间这段时间
 * 来件 nextLine 仍等于刚被晋级的那句(真机实测:换行后 0.5s 内下一行行与主行同文)。
 * 此时不能把它当新下一行:行集合/行高随文本切换会重排整块,表现为「换行动画后跳一下」。
 * 纯函数,可单测。
 */
internal fun isNextLineStale(nextLine: String, original: String): Boolean =
    nextLine.isNotBlank() && nextLine.trim() == original.trim()

/** 同行内容稳定化的宽限窗:行开始前这段时间内仍接受「无词→带词」升级。 */
internal const val LINE_ENHANCEMENT_UPGRADE_GRACE_MS = 300L

/** 折行签名:词表文本序列 + 布局组(折行引擎据此选路径/折行;时间戳与罗马音不进签名)。 */
internal fun aodLineLayoutSignature(
    words: List<AodCanvasWord>,
    groups: List<AodCanvasLayoutGroup>
): Pair<List<String>, List<AodCanvasLayoutGroup>> = words.map { it.text } to groups

/**
 * 同一行内容稳定化决策 —— 是否采用来件的增强数据(词表 / 布局组 / 注音)。
 *
 * 同一行会被上游两阶段下发(SuperLyric 推来的 SuperLyricLine 里带不带 words 取决于它那一笔),
 * 而折行引擎按「有无词表」走两条不同路径(layoutWordLines / layoutTextByGroups),于是每次形态
 * 切换都会重排:换行瞬间下一行被顶下去一行高、填充映射被换掉(owner 真机录屏逐帧实测:下一行
 * 下移 80px、填充前缘中途倒退重填)。这里按行身份(见 [aodCanvasLineIdentity])稳定化:
 *
 *  1. 换行(行身份变了)→ 采用;
 *  2. 同行且折行签名未变(只有时间戳/罗马音细化)→ 采用(不会重排);
 *  3. 同行且来件是「无词 → 带词」升级 → 仅在行开始前的 [graceMs] 宽限窗内采用(此时该行
 *     尚未演唱,重排不可见);
 *  4. 其余(「带词 → 无词」回退、演唱中词表文本变化)→ 拒绝,沿用当前版本。
 *
 * 纯函数,可单测。
 */
internal fun shouldAdoptLineEnhancements(
    sameLine: Boolean,
    layoutSignatureChanged: Boolean,
    incomingEnriches: Boolean,
    positionMs: Long,
    lineStartMs: Long,
    graceMs: Long = LINE_ENHANCEMENT_UPGRADE_GRACE_MS
): Boolean = when {
    !sameLine -> true
    !layoutSignatureChanged -> true
    incomingEnriches -> positionMs <= lineStartMs + graceMs
    else -> false
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
