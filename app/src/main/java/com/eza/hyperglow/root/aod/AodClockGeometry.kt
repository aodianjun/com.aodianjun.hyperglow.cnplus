package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.transition.isDimmedAodDisplayState
import kotlin.math.roundToInt

internal data class AodRenderedClockBounds(
    val top: Int,
    val bottom: Int
) {
    val height: Int get() = bottom - top
}

private const val BRIGHT_LINKAGE_CLOCK_RESERVE_FRACTION = 0.35f

/**
 * zone 翻转滞回的相对阈值:CLOCK_TOP↔CLOCK_BOTTOM 的可用空间差须超过该比例,才允许翻转。
 * 避免系统时钟在自由空间几乎相等的临界点附近微移时,导致画布在全高与"一条"之间反复跳动
 * (issue #46)。值按根高比例计算,吸收临界抖动,同时允许真实、显著的下移触发切换。
 */
internal const val AOD_ZONE_FLIP_HYSTERESIS_FRACTION = 0.10f

internal fun resolveRenderedAodSceneZone(
    managedZone: AodSceneZone,
    renderedBounds: AodRenderedClockBounds?,
    rootHeight: Int,
    margin: Int,
    hysteresis: Int = 0
): AodSceneZone {
    if (managedZone == AodSceneZone.STOCK || renderedBounds == null ||
        renderedBounds.height <= 0 || rootHeight <= 0
    ) return managedZone
    val freeAbove = (renderedBounds.top - margin).coerceAtLeast(0)
    val freeBelow = (rootHeight - renderedBounds.bottom - margin).coerceAtLeast(0)
    // 滞回(hysteresis):在临界点附近,必须让一侧可用空间明显超过另一侧(超过阈值)才翻转 zone。
    // 没有滞回时,系统时钟在 freeAbove≈freeBelow 处微小移动即可令 CLOCK_TOP↔CLOCK_BOTTOM 反复
    // 翻转,可用画布在全高与"一条"之间骤变,导致歌词跳变到屏幕另一处(issue #46)。
    return when {
        freeAbove - freeBelow > hysteresis -> AodSceneZone.CLOCK_BOTTOM
        freeBelow - freeAbove > hysteresis -> AodSceneZone.CLOCK_TOP
        else -> managedZone
    }
}

/**
 * @param rememberedPhysicalBounds the last physical measurement taken on a root of the same height.
 *   The physical clock cannot be measured while the panel is dark, so every re-attach in that state
 *   falls through to the managed position — which is where the clock was asked to go, not where the
 *   stock AOD clock actually is. On this device those differ by hundreds of pixels, so the lyrics
 *   appeared far from their configured place until the panel lit and the real bounds resolved. A
 *   measurement already taken is better evidence than a position we merely requested.
 */
internal fun resolvedAodClockBounds(
    renderedBounds: AodRenderedClockBounds?,
    controlledTop: Int?,
    controlledBottom: Int?,
    measuredTop: Int,
    measuredBottom: Int,
    exactPhysicalBounds: AodRenderedClockBounds? = null,
    rememberedPhysicalBounds: AodRenderedClockBounds? = null
): AodRenderedClockBounds {
    val controlled = if (controlledTop != null && controlledBottom != null) {
        AodRenderedClockBounds(controlledTop, controlledBottom)
    } else {
        null
    }
    val validRendered = renderedBounds?.takeIf { it.height > 0 }
    val validControlled = controlled?.takeIf { it.height > 0 }
    val validPhysical = exactPhysicalBounds?.takeIf { it.height > 0 }
    val validRemembered = rememberedPhysicalBounds?.takeIf { it.height > 0 }
    return when {
        validPhysical != null -> validPhysical
        validRemembered != null -> validRemembered
        validControlled != null -> validControlled
        validRendered != null -> validRendered
        else -> AodRenderedClockBounds(measuredTop, measuredBottom)
    }
}

internal fun selectPhysicalAodClockBounds(
    systemUiBounds: AodRenderedClockBounds?,
    aodControllerBounds: AodRenderedClockBounds?
): AodRenderedClockBounds? = systemUiBounds ?: aodControllerBounds

/**
 * Held stable position of the AOD clock, used as an anchor for lyric placement.
 *
 * @param sinceElapsedMs monotonic time the held position was last confirmed (set to the current
 *   bounds).
 */
internal data class AodClockAnchor(
    val top: Int,
    val bottom: Int,
    val sinceElapsedMs: Long
)

/** How long a held clock position may go unconfirmed before it is treated as a genuine move. */
internal const val AOD_CLOCK_ANCHOR_HOLD_MS = 40_000L

/**
 * 重挂载时 remembered 物理时钟测量可被当作 fallback 的最长年龄(毫秒)。
 *
 * 物理时钟在面板熄灭时无法读取,remembered 缓存只用于覆盖「重挂载→面板点亮」这个短暂暗窗。
 * 但它只是一个原始采样,没有时效性就是隐患:#38 里一次瞬时错误的探针读数(布局用几何一度出现
 * `1160..2240`,而实测时钟稳定在 `493..1574`)被缓存后,在物理读取间歇反复回退,把歌词压到低位
 * 锁死。超过该窗口的旧值不再是可靠的时钟证据,直接弃用。
 */
internal const val REMEMBERED_CLOCK_MAX_AGE_MS = 10_000L

/** Minimum downward clock drift (px) the settle watchdog reacts to. */
internal const val STOCK_SETTLE_DRIFT_PX = 24

/** Recheck cadence after the initial settle schedule is exhausted. */
internal const val STOCK_SETTLE_RETRY_MS = 300_000L

/**
 * Stabilizes the clock bounds used for lyric placement against fast upward oscillation (e.g. the
 * media header toggling the AOD layout, which squeezes the clock upward by hundreds of pixels). The
 * anchor holds the last *confirmed* clock position: as long as the raw bounds keep returning to it
 * (oscillation), the anchor stays put so the lyric never jumps.
 *
 * 仅对**上行**(clock 顶部/底部高于 anchor)做 [holdMs] 防抖:持定期内保持旧锚,仅当持久上移
 * 离开持定位未再确认满 [holdMs] 才重锚。**下行(clock 底部低于 anchor 底部)立即硬同步、不做
 * 防抖**:歌词 surface 位于 anchor 底部之下,若时钟向下漂移(小米 burn-in 每次唤醒步进
 * 90-160px)却等防抖窗口,时钟会在滞后期间叠在歌词上;#24 曾把下行也纳入 holdMs(issue #23),
 * 实机(0.3.86)验证反而导致「歌词不跟随时钟下移、长期错位」,故回退——下移防抖只能依靠布局层
 * 的 [avoidStockClockOverlap] 避让,不能压制 anchor 更新。[seedSinceElapsedMs] 用于锚定被丢弃
 * 后继承防抖记忆。
 */
internal fun stabilizeAodClockAnchor(
    previous: AodClockAnchor?,
    raw: AodRenderedClockBounds,
    nowElapsedMs: Long,
    holdMs: Long = AOD_CLOCK_ANCHOR_HOLD_MS,
    seedSinceElapsedMs: Long = -1L
): AodClockAnchor {
    if (raw.top >= raw.bottom) return previous ?: AodClockAnchor(raw.top, raw.bottom, nowElapsedMs)
    if (previous == null) {
        val since = if (seedSinceElapsedMs >= 0L) seedSinceElapsedMs else nowElapsedMs
        return AodClockAnchor(raw.top, raw.bottom, since)
    }
    if (raw.top == previous.top && raw.bottom == previous.bottom) {
        // Held position reconfirmed: refresh so oscillation never ages it out.
        return previous.copy(sinceElapsedMs = nowElapsedMs)
    }
    // 下行立即硬同步:避免防抖持有期内时钟向下漂移时压在歌词上(issue #23 补充)。
    if (raw.bottom > previous.bottom) {
        return AodClockAnchor(raw.top, raw.bottom, nowElapsedMs)
    }
    // 上行参与 holdMs 防抖:持定期内保持旧锚,过期才重锚。
    return if (nowElapsedMs - previous.sinceElapsedMs >= holdMs) {
        AodClockAnchor(raw.top, raw.bottom, nowElapsedMs)
    } else {
        previous
    }
}

/**
 * 锚定模式(实时时钟跟随关闭)下决定本帧歌词布局采用的时钟几何锚。
 *
 * 物理时钟在面板熄灭等场景会瞬时不可读。**读数缺失不等于时钟真的移动了**:此时若用
 * (可能是过期的) remembered/managed 值取代上一帧喂进锚定器,一次瞬时错误采样会被当成
 * "下行"硬同步进锚,把歌词压到低位;而真实位置上行恢复又被 [AOD_CLOCK_ANCHOR_HOLD_MS]
 * 防抖压住,长期锁死(#38)。因此只要有已建立的稳定锚且本帧无新鲜物理读数,就直接沿用该锚。
 *
 * @param hasFreshPhysical 本帧是否拿到了物理时钟读数(而非回退来源)。
 * @return 本帧应采用的 [AodClockAnchor];调用方负责回写该值。
 */
internal fun resolveAnchoredAodClockBounds(
    hasFreshPhysical: Boolean,
    previousAnchor: AodClockAnchor?,
    rawClockBounds: AodRenderedClockBounds,
    nowElapsedMs: Long,
    seedSinceElapsedMs: Long = -1L
): AodClockAnchor {
    if (!hasFreshPhysical && previousAnchor != null) {
        return previousAnchor
    }
    return stabilizeAodClockAnchor(
        previousAnchor,
        rawClockBounds,
        nowElapsedMs,
        seedSinceElapsedMs = seedSinceElapsedMs
    )
}

internal fun brightLinkageClockBounds(rootHeight: Int): AodRenderedClockBounds =
    AodRenderedClockBounds(0, (rootHeight * BRIGHT_LINKAGE_CLOCK_RESERVE_FRACTION).roundToInt())

internal fun shouldUseBrightClockMorphGeometry(
    linkageMode: Boolean,
    morphingToAod: Boolean,
    linkageAwaitingDim: Boolean,
    displayState: Int
): Boolean = linkageMode && !isDimmedAodDisplayState(displayState) &&
    (morphingToAod || linkageAwaitingDim)
