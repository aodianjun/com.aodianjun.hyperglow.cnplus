package com.eza.hyperglow.aod

import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.producer.LyricKind
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricLayoutGroup
import com.eza.hyperglow.producer.LyricRuby
import com.eza.hyperglow.producer.LyricWord

/**
 * 纯函数映射层：把 [LyricProducerState]（生产者边界）映射成 [AodDisplayState]（SystemUI 投递载荷）。
 *
 * 这是 `AodProjectionEngine.project()` 历史映射逻辑的提取，作为 Phase 1 纯重构的一步：
 * - **入参只有 [LyricProducerState]**，不再读 `SpicyBridgeState` / `SpicyBridgeDocumentStore`
 *   （spec clause 8：projection MUST NOT re-select the active line）。活动行、per-word 时序、
 *   渲染模式、下一行起点等全部由生产者预先计算好，本函数只做格式转换。
 * - **纯函数、无 Android 框架依赖**（`prefs`/`compiled` 作为入参注入），可单测覆盖全部分支。
 * - Phase 1 阶段引擎暂不调用本函数（仍走原 `project(state: SpicyBridgeState, ...)`），
 *   本函数先建立测试基线；Phase 3 引擎切换 `arbiter.active` 后作为唯一映射入口启用。
 *
 * 行为与原 `project()` 的映射分支严格对齐（见 [projectToDisplay] 内联注释引用的原判定），
 * 以便 Phase 3 切换时做字段级 diff 验证零回归。
 *
 * @param state 生产者发出的当前状态（已含活动行信息）。
 * @param now 当前单调时钟（`SystemClock.elapsedRealtime`），用于位置前向投影。
 * @param prefs AOD 渲染偏好（`aodEnabled`/`keepAwake`/`keepAwakeDurationMs`/`burnIn` 等，
 *   原引擎从 `appContext` 读取，这里注入以保持纯函数）。
 * @param compiled 编译后的定制配置（`profiles[SURFACE_AOD]` 决定 `aodEnabled`/`metadataVisible`
 *   的覆盖，原引擎从 `appContext` 读取，这里注入）。
 * @param metadataIntroPolicy 大元数据引导策略（有状态，注入以复用引擎实例）。
 * @param powerSessionPolicy 保活会话策略（有状态，注入以复用引擎实例）。
 * @param userId 当前进程用户 id（原引擎内联调用 `currentProcessUserId()`，此处注入以保持
 *   纯函数性——`currentProcessUserId()` 依赖 `android.os.UserHandle`/`android.os.Process`，
 *   在 JVM 单测里是 stub，调用会抛 `RuntimeException("Stub!")`）。
 */
@Suppress("unused") // Phase 1：仅单测引用，Phase 3 引擎切换后启用
internal fun projectToDisplay(
    state: LyricProducerState,
    now: Long,
    prefs: AodRenderConfig,
    compiled: CompiledCustomization?,
    metadataIntroPolicy: SongMetadataIntroPolicy,
    powerSessionPolicy: AodPowerSessionPolicy,
    userId: Int
): AodDisplayState {
    val position = projectedPosition(state, now)

    // 息屏外推防护：数据源（如网易云）停写位置后，切歌/重播/缓冲会让外推位置错位并被
    // projectedPosition 钳制在末行。当外推不可信时，清空活动行，避免 AOD 长期显示旧歌末尾行。
    val extrapolationInvalid = !extrapolationReliable(
        positionMs = state.positionMs,
        sampledAtElapsedMs = state.sampledAtElapsedMs,
        speed = state.speed,
        playing = state.playing,
        durationMs = state.durationMs,
        now = now
    )

    // --- 活动行判定（原 project() 用 document/row 派生，现直接读 producer 预计算字段）---
    val kind = state.lyricKind
    val unsynced = kind == LyricKind.UNSYNCED
    val noLyrics = kind == LyricKind.NONE
    val hasActiveLine = state.lineIndex >= 0 && state.line.isNotBlank() && !extrapolationInvalid
    val hasTimedLyrics = state.hasTimedLyrics
    // 原 fallbackLine 条件：!unsynced && !noLyrics && document == null && status == "ready" && it.isNotBlank()
    // document==null 对应 producer 无行级数据（lyricKind==NONE 但 line 非空 → 生产者塞了无时序一行）。
    val fallbackLine = state.line.takeIf {
        !extrapolationInvalid && !unsynced && !noLyrics && kind == LyricKind.NONE && state.status == "ready" && it.isNotBlank()
    }
    val presentable = hasActiveLine || fallbackLine != null

    // --- 元数据 ---
    val metadata = listOf(state.title, state.artist)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    // --- 引导大元数据状态（原 project() 的 lyricState 四分支）---
    val lyricState = when {
        unsynced || noLyrics -> SongIntroLyricState.NONE
        presentable -> SongIntroLyricState.ACTIVE
        hasTimedLyrics -> SongIntroLyricState.INTERLUDE
        else -> SongIntroLyricState.UNKNOWN
    }
    val showLargeMetadata = metadataIntroPolicy.shouldShowLargeMetadata(
        SongMetadataIntroInput(
            session = ProjectionSessionIdentity.from(state),
            metadataAvailable = metadata.isNotBlank(),
            lyricState = lyricState,
            positionMs = position,
            nextLyricStartMs = state.nextLineStartMs,
            speed = state.speed,
            nowElapsedMs = now
        )
    )

    // --- 原文/罗马音/翻译（原 project() 的 original/romanized/translated 分支）---
    val presentedLineText = state.line.takeIf { hasActiveLine && !showLargeMetadata }
    val original = when {
        showLargeMetadata -> metadata
        unsynced || noLyrics -> "♪"
        presentedLineText != null -> presentedLineText
        hasTimedLyrics || state.status == "loading" -> "♪"
        fallbackLine != null -> fallbackLine
        else -> "♪"
    }
    val romanized = if (showLargeMetadata || unsynced || noLyrics) "" else state.romanizedLine
    val translated = if (showLargeMetadata || unsynced || noLyrics) "" else state.translatedLine
    val nextLine = if (showLargeMetadata || unsynced || noLyrics) "" else state.nextLine

    // --- 渲染模式（原 project() 从 state.liveCard* + prefs 混合取，现统一从 renderModes 取）---
    // 原 project() 里 weight/textSize/textSizeCustom/secondaryMode/animationMode/glowMode/
    // overflowMode/fontFamily/alignmentMode/burnIn* 全部来自 prefs；
    // lineSyncFillMode/transitionMode 来自 state.liveCard*。
    // 生产者已把两者合并进 renderModes（Spicy 来自 liveCard*，Lyricon 来自 CompiledSurfaceProfile），
    // 这里统一读 renderModes，与 spec clause 5/7 一致。
    val modes = state.renderModes
    val aodProfile = compiled?.profiles?.get(SceneCompiler.SURFACE_AOD)
    val aodEnabled = aodProfile?.enabled ?: prefs.aodEnabled
    val lockscreenEnabled = compiled?.profiles?.get(SceneCompiler.SURFACE_LOCKSCREEN)?.enabled
        ?: prefs.lockscreenEnabled

    // --- 保活（原 project() 的 persistentKeepAlive + powerDecision）---
    val persistentKeepAlive = shouldKeepAodAliveFor(
        playing = state.playing,
        aodEnabled = aodEnabled,
        keepAwake = prefs.keepAwake,
        keepAwakeUnsynced = prefs.keepAwakeUnsynced,
        hasTimedLyrics = hasTimedLyrics
    )
    val powerDecision = powerSessionPolicy.resolve(
        state = SpicyPowerSessionState(
            session = ProjectionSessionIdentity.from(state),
            playing = state.playing,
            aodEnabled = aodEnabled,
            keepAwake = prefs.keepAwake,
            keepAliveDurationMs = prefs.keepAwakeDurationMs
        ),
        nowElapsedMs = now,
        persistentKeepAlive = persistentKeepAlive
    )

    // --- per-word（透传真实词级数据）---
    // 真实词级时间戳随 words 下发：统一扫光管线以其计算整块进度（无行级时间时），
    // 逐字卡拉OK路径（逐字源+关闭发光）以逐词时间驱动缩放/渐变。
    val effectiveWords = if (showLargeMetadata || !hasActiveLine) emptyList() else state.words.orEmpty()

    // --- 行级同步标志（统一走整行水平扫光）---
    // 有活动歌词行时一律行级同步，以整行水平扫光为主要效果；
    // NONE/UNSYNCED 无活动行 → false。
    val lineLevelSync = hasActiveLine && !showLargeMetadata

    // --- ruby / layoutGroup（原 presentedRow.words/ruby/layoutGroups）---
    val words = if (showLargeMetadata || !hasActiveLine) emptyList() else effectiveWords.map(::toDisplayWord)
    val ruby = if (showLargeMetadata || !hasActiveLine) emptyList() else state.ruby.map(::toDisplayRuby)
    val layoutGroups = if (showLargeMetadata || !hasActiveLine) emptyList() else state.layoutGroups.map(::toDisplayLayoutGroup)

    return AodDisplayState(
        visible = original.isNotBlank(),
        playbackActive = state.playing,
        userId = userId,
        trackGeneration = trackGeneration(state),
        aodEnabled = aodEnabled,
        lockscreenEnabled = lockscreenEnabled,
        seamlessTransitionEnabled = prefs.seamlessTransitionEnabled,
        keepAlive = powerDecision.keepAlive,
        positionFollowingEnabled = prefs.experimentalPositionFollowing,
        burnInPattern = prefs.burnInPattern,
        burnInIntervalMs = prefs.burnInIntervalMs,
        wakeSignal = sessionWakeSignal(state, hasTimedLyrics),
        original = original,
        romanized = romanized,
        translated = translated,
        nextLine = nextLine,
        metadata = metadata,
        alignedRight = state.alignedRight,
        lineLevelSync = lineLevelSync,
        lineStartMs = if (hasActiveLine) state.lineStartMs else 0L,
        lineEndMs = if (hasActiveLine) state.lineEndMs else 0L,
        durationMs = state.durationMs,
        positionMs = position,
        sampledAtElapsedMs = now,
        speed = state.speed,
        words = words,
        ruby = ruby,
        layoutGroups = layoutGroups,
        weight = modes.weight,
        textSizeMode = modes.textSize,
        textSizeCustom = modes.textSizeCustom,
        secondaryMode = modes.secondary,
        animationMode = modes.animation,
        glowMode = modes.glow,
        lineSyncFillMode = modes.lineSyncFill,
        overflowMode = modes.overflow,
        transitionMode = if (noLyrics) "None" else modes.transition,
        fontFamily = modes.font,
        alignmentMode = prefs.alignment,
        metadataVisible = aodProfile?.metadataVisible ?: (prefs.metadataVisible != "hide"),
        metadataAnchor = prefs.metadataAnchor,
        adaptiveSectioning = prefs.adaptiveSectioning
    )
}

/** [LyricWord] → [AodDisplayWord]（与原 presentedRow.words.map 同构）。 */
private fun toDisplayWord(word: LyricWord) = AodDisplayWord(
    text = word.text,
    romanized = word.romanized,
    startMs = word.startMs,
    endMs = word.endMs,
    boundaryAfter = word.boundaryAfter,
    sourceStart = word.sourceStart,
    sourceEnd = word.sourceEnd
)

/** [LyricRuby] → [AodDisplayRuby]。 */
private fun toDisplayRuby(ruby: LyricRuby) = AodDisplayRuby(ruby.start, ruby.end, ruby.reading)

/** [LyricLayoutGroup] → [AodDisplayLayoutGroup]。 */
private fun toDisplayLayoutGroup(group: LyricLayoutGroup) = AodDisplayLayoutGroup(
    start = group.start,
    end = group.end,
    kind = group.kind,
    keepTogether = group.keepTogether,
    confidence = group.confidence
)

/**
 * [LyricProducerState] 适配的 trackGeneration——与引擎 [AodProjectionEngine.trackGeneration]
 * 的 SpicyBridgeState 版本同构（producerId\0generation\0trackUri 的 hash）。
 */
internal fun trackGeneration(state: LyricProducerState): Long {
    val identity = "${state.producerId}\u0000${state.generation}\u0000${state.trackUri}"
    return identity.hashCode().toLong() and Long.MAX_VALUE
}

/**
 * [LyricProducerState] 适配的 sessionWakeSignal——与引擎 [AodProjectionEngine.sessionWakeSignal]
 * 的 SpicyBridgeState 版本同构（producerId|generation|trackUri|phase 的 hash）。
 */
internal fun sessionWakeSignal(state: LyricProducerState, hasTimedLyrics: Boolean): Long {
    val phase = if (hasTimedLyrics) "timed" else "song"
    return "${state.producerId}|${state.generation}|${state.trackUri}|$phase"
        .hashCode()
        .toLong()
        .takeUnless { it == 0L } ?: 1L
}

/**
 * AOD 保活判定——与引擎 [AodProjectionEngine.shouldKeepAodAlive] 同构，提取为顶层以便
 * [projectToDisplay] 复用（纯函数，不依赖引擎单例状态）。命名加 `For` 后缀以避免与引擎
 * 成员函数同名产生歧义（引擎成员版本保留以兼容现有测试）。
 */
internal fun shouldKeepAodAliveFor(
    playing: Boolean,
    aodEnabled: Boolean,
    keepAwake: Boolean,
    keepAwakeUnsynced: Boolean,
    hasTimedLyrics: Boolean
): Boolean = playing && aodEnabled && keepAwake &&
    (hasTimedLyrics || keepAwakeUnsynced)
