package com.eza.hyperglow.root.aod

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 换行动画入场基准时长(历史档;退场/入场共用同一 elapsed,入场较长者收尾)。 */
internal const val ENTER_TRANSITION_MS = 210L

/** 换行动画退场基准时长(历史档)。 */
internal const val EXIT_TRANSITION_MS = 130L

/**
 * 换行动画速率档 → 时长倍率:Slow 1.5×、Fast 0.6×、Normal/未知 1×。
 * 只等比缩放各档的退场/入场时长(见 [enterTransitionMs] / [exitTransitionMs]),
 * 帧配方与缓动曲线不动;速率词表见
 * [com.eza.hyperglow.customization.LINE_TRANSITION_SPEEDS],预览与实机共用这一份倍率。
 */
internal fun lineTransitionDurationScale(speed: String): Float = when (speed) {
    "Slow" -> 1.5f
    "Fast" -> 0.6f
    else -> 1f
}

// ---------------------------------------------------------------------------
// HyperLyric 复刻:预设配方表(退场→换字→进场序列)
// ---------------------------------------------------------------------------

/**
 * daimajia AndroidAnimations 2.4 动画器的变换种类([techFrame] 展开各自关键帧)。
 * 变换语义逐项对照库源:fading_exits / fading_entrances / sliders / flippers /
 * rotating_exits / rotating_entrances / zooming_exits / zooming_entrances,
 * 以及 HyperLyric 自带 LandingSoft(scale 1.2→1 + 淡入,Glider QuintEaseOut)。
 */
internal enum class LineTransitionTech {
    FADE_OUT,
    FADE_OUT_LEFT,
    FADE_OUT_RIGHT,
    FADE_OUT_UP,
    FADE_OUT_DOWN,
    SLIDE_OUT_LEFT,
    SLIDE_OUT_RIGHT,
    FLIP_OUT_X,
    FLIP_OUT_Y,
    ROTATE_OUT,
    ZOOM_OUT,
    FADE_IN,
    FADE_IN_LEFT,
    FADE_IN_RIGHT,
    FADE_IN_UP,
    FADE_IN_DOWN,
    SLIDE_IN_LEFT,
    SLIDE_IN_RIGHT,
    FLIP_IN_X,
    FLIP_IN_Y,
    ROTATE_IN,
    ZOOM_IN,
    ZOOM_IN_LEFT,
    ZOOM_IN_RIGHT,
    LANDING_SOFT
}

/** 预设缓动(同式移植):FastOutLinearIn / FastOutSlowIn / Android OvershootInterpolator / Glider QuintEaseOut。 */
internal enum class LineTransitionEase {
    FAST_OUT_LINEAR_IN,
    FAST_OUT_SLOW_IN,
    OVERSHOOT_1_6,
    OVERSHOOT_1_5,
    OVERSHOOT_2_0,
    OVERSHOOT_1_0,
    QUINT_OUT
}

/** 预设的单段配置:技术 + 时长 + 缓动(对应 YoYoPresets 的 AnimConfig)。 */
internal data class LineTransitionPreset(
    val outTech: LineTransitionTech,
    val outMs: Long,
    val outEase: LineTransitionEase,
    val inTech: LineTransitionTech,
    val inMs: Long,
    val inEase: LineTransitionEase
)

/**
 * HyperLyric「歌词切换动画」25 个预设(id 原名,顺序同其设置页):
 * 退场→换字→进场的序列式过渡,时长/缓动与 YoYoPresets 逐项一致
 * (退场统一 FastOutLinearIn;柔缓着陆档入场用其自带 QuintEaseOut)。
 */
internal val LINE_TRANSITION_PRESETS: Map<String, LineTransitionPreset> = linkedMapOf(
    "fade_out_fade_in" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN, 300L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_up_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_UP, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_down_fade_in_down" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_DOWN, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_DOWN, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_left_fade_in_right" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_RIGHT, 450L, LineTransitionEase.OVERSHOOT_1_6
    ),
    "fade_out_left_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_left_zoom_in" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 400L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_left_landing" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "fade_out_right_fade_in_left" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_LEFT, 450L, LineTransitionEase.OVERSHOOT_1_6
    ),
    "fade_out_right_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_right_zoom_in" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 200L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 400L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_right_landing" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "fade_out_left_zoom_in_right" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 250L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN_RIGHT, 600L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_right_zoom_in_left" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 250L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN_LEFT, 600L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_left_slide_in_right" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.SLIDE_IN_RIGHT, 450L, LineTransitionEase.OVERSHOOT_2_0
    ),
    "slide_out_left_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_left_zoom_in" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_left_landing" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "slide_out_right_slide_in_left" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.SLIDE_IN_LEFT, 450L, LineTransitionEase.OVERSHOOT_1_5
    ),
    "slide_out_right_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_right_zoom_in" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_right_landing" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "flip_out_x_flip_in_x" to LineTransitionPreset(
        LineTransitionTech.FLIP_OUT_X, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FLIP_IN_X, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "flip_out_y_flip_in_y" to LineTransitionPreset(
        LineTransitionTech.FLIP_OUT_Y, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FLIP_IN_Y, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "rotate_out_rotate_in" to LineTransitionPreset(
        LineTransitionTech.ROTATE_OUT, 200L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ROTATE_IN, 600L, LineTransitionEase.OVERSHOOT_1_0
    ),
    "zoom_out_zoom_in" to LineTransitionPreset(
        LineTransitionTech.ZOOM_OUT, 200L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 400L, LineTransitionEase.FAST_OUT_SLOW_IN
    )
)

/**
 * 模式 → 预设配方:HyperLyric 预设 id 直接查表;#95 短名档按别名归到同配方预设
 * (与 normalizeLineTransition 的别名一致);历史档返回 null 走原帧配方。
 */
internal fun lineTransitionPreset(mode: String): LineTransitionPreset? =
    LINE_TRANSITION_PRESETS[mode] ?: when (mode) {
        "Fade left" -> LINE_TRANSITION_PRESETS["fade_out_left_fade_in_right"]
        "Landing" -> LINE_TRANSITION_PRESETS["fade_out_left_landing"]
        "Slide swap" -> LINE_TRANSITION_PRESETS["slide_out_left_slide_in_right"]
        else -> null
    }

/**
 * 下一行是否晋级为当前行:旧「下一行」文本 == 新「主行」文本(常规前进一行)。
 * 跳行/拖动/跨曲时两者不等 → 无晋级段,旧行组整体退场、新行组整体进场。
 *
 * 换行角色(owner 2026-09-30 定案):按「内容是否延续」给参与换行的每一行分流 ——
 * 离场行组(旧行组=主行+音标/翻译)播预设的**退场半段**(如「向上渐隐＆向上渐现」的
 * 「向上渐隐」);内容延续的晋级行(旧「下一行」即新「主行」)只做槽位平移+等比放大+
 * 亮度接续,不播退场/入场半段;新到行(新下一行、新辅助文字)播预设的**入场半段**
 * (同例的「向上渐现」)。词表每档的「X＆Y」两半段**分别**作用于离场行与进场行——
 * 判定角色时必须分辨,不可两行同播一段。歌曲信息行(固定行,
 * `AodCanvasRowBox.animated=false`)不参与。
 */
internal fun lineTransitionPromotes(oldNextLine: String, newOriginal: String): Boolean =
    oldNextLine.isNotBlank() && oldNextLine == newOriginal

/** 换行「晋级位移」段基准时长(同 HyperLyric 下一句晋级 220ms),同时是位移段的最小可视时长。 */
internal const val MOVE_TRANSITION_MS = 220L

/**
 * 晋级位移速度上限(px/s,按缓动**峰值**瞬时速度计):500px/s × 16.7ms ≈ 8.3px,
 * 即 60fps 等效单帧位移不超过 8.3px(判据「单帧 ≤8px」)。位移段按「距离 / 上限」定
 * 时长时还必须乘上缓动峰值因子 [MOVE_EASE_PEAK_FACTOR]——FastOutSlowIn 中段的瞬时
 * 速度可达平均速度的 ~2.7 倍,直接按平均速度定时长会让中段单帧位移重新顶破判据
 * (真机 A/B 实测:216px 位移段在 132ms 内走完,单帧峰值 -76px ≈ 5100px/s)。
 */
internal const val MOVE_MAX_VELOCITY_PX_PER_S = 500f

/**
 * 晋级位移缓动([moveTransitionEase] = FastOutSlowIn,cubic-bezier(0.4,0,0.2,1))的
 * 峰值速度倍数:数值求导实测 ≈2.7346(峰值出现在进度 ≈0.30 处),取 2.75 保守。
 * 单测 [AodCanvasTransitionTest.moveEasePeakFactorMatchesMeasuredSlope] 用真实曲线
 * 数值复核该常量,防止缓动改动后速度上限静默失效。
 */
internal const val MOVE_EASE_PEAK_FACTOR = 2.75f

/**
 * 晋级位移时长(纯函数):按**速度上限**定时长,而不是固定 220ms——
 *
 * `时长 = max(max(距离 × [MOVE_EASE_PEAK_FACTOR] / [MOVE_MAX_VELOCITY_PX_PER_S],
 * [MOVE_TRANSITION_MS]) × 速率倍率, 距离 × [MOVE_EASE_PEAK_FACTOR] / [MOVE_MAX_VELOCITY_PX_PER_S])`
 *
 * 即:Normal 档 = max(距离 × 5.5ms, 220ms);Slow 档 1.5×(更慢);Fast 档 0.6×(更快),
 * 但**任何速率档都不得突破速度上限**——长距离时 Fast 被硬下限钳回上限允许的最短时长
 * (位移段不再出现「Fast 档一帧暴跳」;倍率语义在短距离段与 Slow 档完整保留)。
 * [distancePx] ≤ 0 或非有限(取不到行位差)时回退历史固定时长
 * [MOVE_TRANSITION_MS] × 速率倍率,行为与改前逐帧一致。
 *
 * 距离由 [lineTransitionMoveDistancePx] 从起点布局与目标布局的实际行位差给出。
 */
internal fun moveTransitionMs(speed: String, distancePx: Float = 0f): Long {
    val scale = lineTransitionDurationScale(speed)
    if (!distancePx.isFinite() || distancePx <= 0f) {
        return (MOVE_TRANSITION_MS * scale).toLong()
    }
    val velocityMs = distancePx * MOVE_EASE_PEAK_FACTOR / MOVE_MAX_VELOCITY_PX_PER_S * 1000f
    val normalMs = maxOf(velocityMs, MOVE_TRANSITION_MS.toFloat())
    return maxOf(normalMs * scale, velocityMs).roundToLong()
}

/**
 * 晋级位移距离(px,纯函数):取起点布局与目标布局逐行基线差的绝对值最大者——主行对
 * (旧「下一行」基线 → 新「主行」基线)与辅助行对(旧下一行辅助行 → 新主行辅助行,
 * 与 [AodLyricCanvasView.drawPromotedAuxLayer] 同一配对)在位移段内以同一缓动推进,
 * 距离取最大者才能保证任一层单帧位移都不超速度上限。空列表/非有限值返回 0
 * (调用方回退固定时长)。
 */
internal fun lineTransitionMoveDistancePx(pairs: List<Pair<Float, Float>>): Float {
    var maxDistance = 0f
    pairs.forEach { (fromPx, toPx) ->
        if (fromPx.isFinite() && toPx.isFinite()) {
            val distance = abs(toPx - fromPx)
            if (distance.isFinite() && distance > maxDistance) maxDistance = distance
        }
    }
    return maxDistance
}

/**
 * 换行三段时间线:严格序列「退场 → 晋级位移 → 入场」,任意时刻至多一段在播 ——
 * 旧行/新行不再同帧叠加(此前历史档退场/入场共用 elapsed,旧行未走完新行已进场,
 * 同一句歌词在两层各画一次,表现为歌词重叠)。[moveMs] 仅在下一行晋级时非 0。
 */
internal data class LineTransitionTimeline(
    val exitMs: Long,
    val moveMs: Long,
    val enterMs: Long
) {
    val moveStartMs: Long get() = exitMs
    val enterStartMs: Long get() = exitMs + moveMs
    val totalMs: Long get() = exitMs + moveMs + enterMs
}

/**
 * 时间线求解:[promoting] 为真时插入晋级位移段,否则退场直接接入场。退场/入场时长沿用
 * 各档配方([exitTransitionMs] / [enterTransitionMs]);位移段按速度上限定时长
 * ([moveTransitionMs],吃起点→目标布局的实际行位差 [moveDistancePx],取不到时回退
 * 历史 220ms 基准)。
 */
internal fun lineTransitionTimeline(
    mode: String,
    speed: String,
    promoting: Boolean,
    moveDistancePx: Float = 0f
): LineTransitionTimeline = LineTransitionTimeline(
    exitMs = exitTransitionMs(mode, speed),
    moveMs = if (promoting) moveTransitionMs(speed, moveDistancePx) else 0L,
    enterMs = enterTransitionMs(mode, speed)
)

/** 退场段进度 0..1(自过渡起点计时)。 */
internal fun lineTransitionExitProgress(elapsedMs: Long, timeline: LineTransitionTimeline): Float =
    if (timeline.exitMs <= 0L) 1f
    else (elapsedMs.toFloat() / timeline.exitMs.toFloat()).coerceIn(0f, 1f)

/** 晋级位移段进度 0..1(退场完成前恒 0;无晋级段恒 1)。 */
internal fun lineTransitionMoveProgress(elapsedMs: Long, timeline: LineTransitionTimeline): Float =
    if (timeline.moveMs <= 0L) 1f
    else ((elapsedMs - timeline.moveStartMs).toFloat() / timeline.moveMs.toFloat()).coerceIn(0f, 1f)

/** 入场段进度 0..1(退场/晋级完成前恒 0,新行层不可见)。 */
internal fun lineTransitionEnterProgress(elapsedMs: Long, timeline: LineTransitionTimeline): Float =
    if (timeline.enterMs <= 0L) 1f
    else ((elapsedMs - timeline.enterStartMs).toFloat() / timeline.enterMs.toFloat()).coerceIn(0f, 1f)

/**
 * 位置式换行过渡时钟:三段进度由歌词位置推导,而不是挂钟计时 —— 过渡开始时记下当时的
 * 歌词位置 [startPositionMs](调用方取画布既有的 projectedPosition()),之后每帧按
 * 「位置高水位 − 起点位置」在 [timeline] 的三段时长上换算(复用 [lineTransitionExitProgress] /
 * [lineTransitionMoveProgress] / [lineTransitionEnterProgress],段顺序/时长配方/缓动不动)。
 * 内容(位置/行窗/nextLine)晚到不再"飞着改目标":进度与位置同轴,内容到达顺序不影响动画状态。
 *
 * 高水位([highWaterPositionMs])由调用方在帧间存回:位置源 stall/resume 的毫秒级回漂
 * (同 producer 侧 EXTRAPOLATION_RESUME_TOLERANCE_MS 的语义)不倒带动画,进度只进不退。
 *
 * 边界语义:
 * - 正常推进:位置推进量即过渡进度,推进到总时长即走完([completed],末帧即静态形态);
 * - 暂停(位置冻结):位置不变 → 时钟不变,过渡停在当前进度;
 * - 位置跳变(seek/拖动):倒退超出采样回漂容差 [TRANSITION_REWIND_TOLERANCE_MS] 判
 *   [interrupted],进度钳在 0..1 且立即结束过渡,不允许反向"追"新位置;前进越过总时长
 *   同样钳到 1 并以 [completed] 结束(无挂钟无法区分快进与前进跳变,按位置推进处理)。
 *
 * 画布侧先用 [advanceTransitionPosition] 把裸位置采样限速成平滑位置,再喂给本函数
 * (批投递的位置跳变按实时速率补齐,不在一帧内推完);seek 判定仍按原始位置高水位
 * 单独做(见 [isTransitionSeekJump])。
 */
internal data class LineTransitionClock(
    val exitProgress: Float,
    val moveProgress: Float,
    val enterProgress: Float,
    /** 本次采样后的位置高水位,调用方存回供下一帧沿用(挂钟路径见 [lineTransitionClockAtElapsed]:语义为已用挂钟毫秒数)。 */
    val highWaterPositionMs: Long,
    /** 位置已推进到过渡总时长:过渡正常走完(末帧即静态形态)。 */
    val completed: Boolean,
    /** 位置倒退超出容差(seek/拖动跳变):过渡立即结束,不得反向"追"位置。 */
    val interrupted: Boolean
)

/**
 * 位置回漂容差:位置源 stall/resume 恢复时真实位置可能略低于外推值,属采样回漂而非
 * 跳变(producer 侧同类容差 300ms,见 LyriconLyricProducer.EXTRAPOLATION_RESUME_TOLERANCE_MS
 * 的注释);倒退超过该量才判 seek/拖动跳变并立即结束过渡。
 */
internal const val TRANSITION_REWIND_TOLERANCE_MS = 300L

internal fun lineTransitionClockAtPosition(
    positionMs: Long,
    startPositionMs: Long,
    highWaterPositionMs: Long,
    timeline: LineTransitionTimeline
): LineTransitionClock {
    val highWater = maxOf(highWaterPositionMs, positionMs, startPositionMs)
    val delta = (highWater - startPositionMs).coerceIn(0L, timeline.totalMs)
    return LineTransitionClock(
        exitProgress = lineTransitionExitProgress(delta, timeline),
        moveProgress = lineTransitionMoveProgress(delta, timeline),
        enterProgress = lineTransitionEnterProgress(delta, timeline),
        highWaterPositionMs = highWater,
        completed = highWater - startPositionMs >= timeline.totalMs,
        interrupted = positionMs < highWater - TRANSITION_REWIND_TOLERANCE_MS
    )
}

/**
 * 位置式过渡时钟的采样状态(纯数据):[smoothedPositionMs] 为限速后的平滑位置
 * (进度推导基准,只进不退),[rawHighWaterPositionMs] 为原始位置高水位(seek/拖动
 * 判定基准,只进不退)。两者分开存:批投递跳变后原始高水位立即到顶,平滑位置
 * 随后按实时速率补齐——seek 判定不受限速拖慢(见 [isTransitionSeekJump])。
 */
internal data class TransitionPositionState(
    val smoothedPositionMs: Long,
    val rawHighWaterPositionMs: Long
)

/**
 * 过渡位置限速(纯函数,一帧一次):把「裸位置采样」升级为「平滑位置」——两次采样之间
 * 位置可能整段跳进(doze 批投递真机实测:同一毫秒两条位置、跨度约 2 秒;逐帧条带追踪
 * 位移段单帧尖峰 -68px ≈ 60fps 的 64px/帧,为判据 8px/帧的 8 倍),直接采用会把三段
 * 进度在一帧内推完,形态即「起步慢 → 一/两帧暴跳 → 收尾慢」。
 *
 * 本函数把平滑位置向原始位置推进,一步最多 [elapsedSinceLastSampleMs] × [speed]
 * (实时播放速率),只进不退:
 * - 正常播放(位置按 speed 实时推进):与原始位置逐帧同步,时长/缓动逐帧不变;
 * - 批投递跳变:按实时速率补齐,过渡以自身时长平滑播完,不出现孤立尖峰;
 * - 位置回漂(倒退):平滑位置原地不动;是否 seek/拖动由 [isTransitionSeekJump] 按
 *   原始位置高水位单独判定;
 * - 暂停驻留(speed ≤ 0 或非法):不限速、直接跟随原始位置——暂停期位置变化只可能
 *   来自 seek/刷新,保持既有跳变语义(瞬间落位或 completed 立即结束),不引入
 *   「冻在半路」的新状态。
 */
internal fun advanceTransitionPosition(
    positionMs: Long,
    previous: TransitionPositionState,
    elapsedSinceLastSampleMs: Long,
    speed: Float
): TransitionPositionState {
    val target = maxOf(previous.smoothedPositionMs, positionMs)
    val maxAdvance = if (speed.isFinite() && speed > 0f) {
        (elapsedSinceLastSampleMs.coerceAtLeast(0L).toDouble() * speed.toDouble()).toLong()
    } else {
        Long.MAX_VALUE
    }
    val headroom = target - previous.smoothedPositionMs
    val smoothed = if (maxAdvance >= headroom) {
        target
    } else {
        previous.smoothedPositionMs + maxAdvance
    }
    return TransitionPositionState(
        smoothedPositionMs = smoothed,
        rawHighWaterPositionMs = maxOf(previous.rawHighWaterPositionMs, positionMs)
    )
}

/**
 * seek/拖动跳变判据(纯函数):原始位置倒退超出采样回漂容差
 * [TRANSITION_REWIND_TOLERANCE_MS] 即命中——过渡立即结束、静态落位,不反向追新位置。
 * 判定只看原始位置与原始高水位,与限速后的平滑位置无关(批跳变后的倒退 seek 同样命中)。
 */
internal fun isTransitionSeekJump(positionMs: Long, rawHighWaterPositionMs: Long): Boolean =
    positionMs < rawHighWaterPositionMs - TRANSITION_REWIND_TOLERANCE_MS

/** 旧账压缩补播的总时长:过期/同帧多条快照不再一帧硬切,三段连续播完约 140ms。 */
internal const val COMPRESSED_TRANSITION_TOTAL_MS = 140L

/** 旧账压缩补播的单段最短可视时长(退场/晋级位移/入场各一段)。 */
internal const val COMPRESSED_TRANSITION_MIN_SEGMENT_MS = 40L

/**
 * 旧账压缩补播时间线(纯函数):按 [lineTransitionTimeline] 的同一三段序列(同一档位
 * 配方)压缩,退场/入场压到 [COMPRESSED_TRANSITION_TOTAL_MS] 预算内、每段不低于
 * [COMPRESSED_TRANSITION_MIN_SEGMENT_MS]——压缩后两段合计 ~140ms。仅用于
 * [shouldSkipLineTransition] 命中的恢复路径:旧账快照已过期,再播整段只会把旧目标
 * 飞着改一遍;压缩补播让眼睛看到「快速但连续」而不是「啪一下」。段顺序/缓动/帧配方
 * 与正常过渡完全一致,目标几何仍在起点定死。基线总长本就不超过压缩总长时原样返回。
 *
 * 位移段同样吃速度上限([moveDistancePx] > 0 时保留 [moveTransitionMs] 给出的限速时长,
 * 不再压到 40ms 一帧暴跳——旧账场景的位移「晚一点、慢一点」补完);退场/入场不产生
 * 位移,维持原有短时长。距离取不到时维持历史行为:三段整体等比压缩到 ~140ms。
 */
internal fun compressedLineTransitionTimeline(
    mode: String,
    speed: String,
    promoting: Boolean,
    moveDistancePx: Float = 0f
): LineTransitionTimeline {
    val base = lineTransitionTimeline(mode, speed, promoting, moveDistancePx)
    if (base.totalMs <= COMPRESSED_TRANSITION_TOTAL_MS) return base
    if (base.moveMs > 0L && moveDistancePx.isFinite() && moveDistancePx > 0f) {
        val fixedMs = (base.exitMs + base.enterMs).coerceAtLeast(1L)
        val scale = COMPRESSED_TRANSITION_TOTAL_MS.toDouble() / fixedMs.toDouble()
        fun compressedFixed(segmentMs: Long): Long = maxOf(
            COMPRESSED_TRANSITION_MIN_SEGMENT_MS,
            (segmentMs * scale).roundToLong()
        )
        return LineTransitionTimeline(
            exitMs = compressedFixed(base.exitMs),
            moveMs = base.moveMs,
            enterMs = compressedFixed(base.enterMs)
        )
    }
    val scale = COMPRESSED_TRANSITION_TOTAL_MS.toDouble() / base.totalMs.toDouble()
    fun compressed(segmentMs: Long): Long = maxOf(
        COMPRESSED_TRANSITION_MIN_SEGMENT_MS,
        (segmentMs * scale).roundToLong()
    )
    return LineTransitionTimeline(
        exitMs = compressed(base.exitMs),
        moveMs = if (base.moveMs > 0L) compressed(base.moveMs) else 0L,
        enterMs = compressed(base.enterMs)
    )
}

/**
 * 挂钟式过渡时钟(纯函数;仅供旧账压缩补播路径):三段进度按挂钟经过时间在 [timeline]
 * (压缩时间线)上换算,首帧由调用方起算。旧账快照已过期,位置推进量远超压缩时长,
 * 按位置驱动会瞬间推完(即本次修复要消除的一帧硬切),故补播以有界挂钟计时;目标几何
 * 仍在起点定死,seek/拖动由调用方按 [isTransitionSeekJump] 判 [interrupted] 立即结束。
 * 本路径返回值中的 [LineTransitionClock.highWaterPositionMs] 语义为「已用挂钟毫秒数」,
 * 调用方无需存回。
 */
internal fun lineTransitionClockAtElapsed(
    elapsedMs: Long,
    timeline: LineTransitionTimeline
): LineTransitionClock = LineTransitionClock(
    exitProgress = lineTransitionExitProgress(elapsedMs, timeline),
    moveProgress = lineTransitionMoveProgress(elapsedMs, timeline),
    enterProgress = lineTransitionEnterProgress(elapsedMs, timeline),
    highWaterPositionMs = elapsedMs,
    completed = elapsedMs >= timeline.totalMs,
    interrupted = false
)

/**
 * 晋级位移段单帧([progress] 已过 [moveTransitionEase]):被晋升行自旧槽位平移到当前行
 * 槽位([translateFraction] 1→0,乘两槽基线差),按两槽字号比 [sizeRatio](当前行字号/
 * 下一行字号,≥1)自小放大到落位,并自旧行亮度 [fromAlpha] 升至全亮。单层即换:旧行
 * 在位移段开始时即被本层接管,不与本层同帧叠加。
 */
internal data class LineTransitionMoveFrame(
    val translateFraction: Float,
    val scale: Float,
    val alpha: Float
)

internal fun lineTransitionMoveFrame(
    progress: Float,
    sizeRatio: Float,
    fromAlpha: Float
): LineTransitionMoveFrame {
    val p = progress.coerceIn(0f, 1f)
    val ratio = if (sizeRatio > 1f) sizeRatio else 1f
    val from = 1f / ratio
    val alphaFrom = fromAlpha.coerceIn(0f, 1f)
    return LineTransitionMoveFrame(
        translateFraction = 1f - p,
        scale = from + (1f - from) * p,
        alpha = alphaFrom + (1f - alphaFrom) * p
    )
}

/** 晋级位移缓动:FastOutSlowIn(cubic-bezier(0.4,0,0.2,1)),起步/落位两头平顺。 */
internal fun moveTransitionEase(progress: Float): Float = fastOutSlowInEase(progress)

/**
 * 入场基准时长:历史档 210ms;HyperLyric 预设按各自 inMs(300/400/450/600/700ms)。
 * 速档缩放后:历史 315/210/126ms,预设同倍率换算。
 */
internal fun enterTransitionMs(mode: String, speed: String): Long {
    val base = lineTransitionPreset(mode)?.inMs ?: ENTER_TRANSITION_MS
    return (base * lineTransitionDurationScale(speed)).toLong()
}

/**
 * 退场基准时长:历史档 130ms;HyperLyric 预设按各自 outMs(200/250/300ms)。
 * 速档缩放后:历史 195/130/78ms,预设同倍率换算。
 */
internal fun exitTransitionMs(mode: String, speed: String): Long {
    val base = lineTransitionPreset(mode)?.outMs ?: EXIT_TRANSITION_MS
    return (base * lineTransitionDurationScale(speed)).toLong()
}

// 各换行动画模式的运动参数(dp / 缩放比),预览与实机共用这一份配方。
private const val FADE_UP_DP = 14f
private const val SLIDE_DP = 28f
private const val ZOOM_EXIT_SCALE_GAIN = 0.06f
private const val ZOOM_ENTER_SCALE_FROM = 0.92f

// HyperLyric / daimajia 参考参数:Fade 族位移为行块宽(高)的 1/4;Slide 族整宽(高);
// ZoomInLeft/Right 的中间停点 48px 按 xxhdpi 折算 16dp;柔缓着陆自 1.2 收落。
private const val HYPER_DRIFT_FRACTION = 0.25f
private const val HYPER_NUDGE_DP = 16f
private const val LANDING_SCALE_FROM = 1.2f

/**
 * 换行动画单行的边界(px):[topPx]/[bottomPx] 为行盒上下沿(与 [AodCanvasVerticalBounds]
 * 同式:top = 基线 + ascent、bottom = top + 行高),[animated] 标记该行是否随换行进退
 * (歌曲信息行恒 false)。
 */
internal data class AodCanvasRowBox(
    val topPx: Float,
    val bottomPx: Float,
    val animated: Boolean
)

/**
 * 换行动画的行块高度(px):参与换行的歌词行(主歌词 + 辅助文字 + 下一行)的包围盒高
 * = max(bottomPx) − min(topPx)。
 *
 * 参考实现把位移施加在「歌词行视图」上(daimajia AndroidAnimations 2.4 各动画器的 target):
 * Fade 族抬起量为 `target.getHeight()/4`、Slide 族整宽,基准是该视图自身尺寸,而不是渲染
 * 画布的内容裁剪框——内容框按 surface 高度定高,可能远大于行块(行块只占其中几条行盒),
 * 拿它当基准会把竖向漂移放大数倍(真机实测:内容框高约 677px → 漂移约 169px,而行块高
  只有约 200px,1/4 约 50px),表现为旧行整块扫过歌曲信息行、新行自数行之外升起。
 * 空块或非法边界回落 [fallbackPx](调用方传内容框高),保持零除安全。
 */
internal fun animatedBlockHeightPx(rows: List<AodCanvasRowBox>, fallbackPx: Float): Float {
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    rows.forEach { box ->
        if (!box.animated) return@forEach
        if (box.topPx < top) top = box.topPx
        if (box.bottomPx > bottom) bottom = box.bottomPx
    }
    val height = if (top.isFinite() && bottom.isFinite() && bottom > top) bottom - top else 0f
    return if (height > 0f) height else fallbackPx
}

/**
 * 换行动画单帧参数:alpha 直接作图层透明度;translate* 为 dp 位移(调用方乘 density);
 * scale 为绕内容中心的放缩比;rotation* 为绕内容中心的角度(度)。
 * 退场/入场共用同一数据形状,由方向不同的两个函数产出。
 */
internal data class LineTransitionFrame(
    val alpha: Float,
    val translateXDp: Float = 0f,
    val translateYDp: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val rotationXDeg: Float = 0f,
    val rotationYDeg: Float = 0f
)

/**
 * 退场帧(progress 0→1,对应 [exitTransitionMs]):
 * - 历史档:`Fade up` 淡出并上移 14dp(历史默认,逐像素不变)、`Crossfade` 纯淡出、
 *   `Slide up`/`Slide left` 上/左滑淡出(透明度 1-p² 保持更久、位移 28dp)、
 *   `Zoom` 淡出并轻微放大;未知值退化为 `Fade up`(与 wire 归一化兜底一致)。
 * - HyperLyric 预设档(含 #95 短名别名):按 [LINE_TRANSITION_PRESETS] 的退场技术
 *   展开 daimajia 关键帧([techFrame]),progress 已过退场缓动。
 */
internal fun lineTransitionExitFrame(
    mode: String,
    progress: Float,
    blockWidthDp: Float = 0f,
    blockHeightDp: Float = 0f
): LineTransitionFrame {
    val preset = lineTransitionPreset(mode)
    if (preset != null) {
        return techFrame(preset.outTech, progress.coerceAtLeast(0f), blockWidthDp, blockHeightDp)
    }
    val p = progress.coerceIn(0f, 1f)
    return when (mode) {
        "Crossfade", "None" -> LineTransitionFrame(alpha = 1f - p)
        "Slide up" -> LineTransitionFrame(alpha = 1f - p * p, translateYDp = -SLIDE_DP * p)
        "Slide left" -> LineTransitionFrame(alpha = 1f - p * p, translateXDp = -SLIDE_DP * p)
        "Zoom" -> LineTransitionFrame(alpha = 1f - p, scale = 1f + ZOOM_EXIT_SCALE_GAIN * p)
        else -> LineTransitionFrame(alpha = 1f - p, translateYDp = -FADE_UP_DP * p)
    }
}

/**
 * 入场帧(progress 0→1,对应 [enterTransitionMs]):
 * - 历史档:`Fade up` 淡入并从 14dp 下方升至原位(历史默认,逐像素不变)、
 *   `Crossfade` 纯淡入、`Slide up`/`Slide left` 自下方/右侧 28dp 滑入(透明度
 *   提前拉满)、`Zoom` 自 0.92 放大至 1;未知值退化为 `Fade up`。
 * - HyperLyric 预设档(含 #95 短名别名):按入场技术展开 daimajia 关键帧;
 *   过冲缓动可使 progress 短暂 >1,位移/缩放/角度保留越过量,alpha 钳制 1。
 */
internal fun lineTransitionEnterFrame(
    mode: String,
    progress: Float,
    blockWidthDp: Float = 0f,
    blockHeightDp: Float = 0f
): LineTransitionFrame {
    val preset = lineTransitionPreset(mode)
    if (preset != null) {
        return techFrame(preset.inTech, progress.coerceAtLeast(0f), blockWidthDp, blockHeightDp)
    }
    val p = progress.coerceIn(0f, 1f)
    return when (mode) {
        "Crossfade", "None" -> LineTransitionFrame(alpha = p)
        "Slide up" -> LineTransitionFrame(
            alpha = minOf(1f, p * 1.6f),
            translateYDp = SLIDE_DP * (1f - p)
        )
        "Slide left" -> LineTransitionFrame(
            alpha = minOf(1f, p * 1.6f),
            translateXDp = SLIDE_DP * (1f - p)
        )
        "Zoom" -> LineTransitionFrame(
            alpha = p,
            scale = ZOOM_ENTER_SCALE_FROM + (1f - ZOOM_ENTER_SCALE_FROM) * p
        )
        else -> LineTransitionFrame(alpha = p, translateYDp = FADE_UP_DP * (1f - p))
    }
}

/**
 * 技术 → 帧:daimajia 各动画器的关键帧逐项展开([p] 为已缓动进度,可 >1 过冲外插)。
 * ObjectAnimator 多值语义 = 均匀分段关键帧([sample]);位移 dp 以该层行块自身宽/高为基准
 * (见 [animatedBlockHeightPx]:退场层用旧行块、入场层用新行块)。
 */
private fun techFrame(
    tech: LineTransitionTech,
    p: Float,
    blockWidthDp: Float,
    blockHeightDp: Float
): LineTransitionFrame {
    val q = p.coerceAtLeast(0f)
    val w = blockWidthDp
    val h = blockHeightDp
    val driftX = HYPER_DRIFT_FRACTION * w
    val driftY = HYPER_DRIFT_FRACTION * h
    return when (tech) {
        LineTransitionTech.FADE_OUT -> frameOf(q, alpha = floatArrayOf(1f, 0f))
        LineTransitionTech.FADE_OUT_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, -driftX)
        )
        LineTransitionTech.FADE_OUT_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, driftX)
        )
        LineTransitionTech.FADE_OUT_UP -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateY = floatArrayOf(0f, -driftY)
        )
        LineTransitionTech.FADE_OUT_DOWN -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateY = floatArrayOf(0f, driftY)
        )
        LineTransitionTech.SLIDE_OUT_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, -w)
        )
        LineTransitionTech.SLIDE_OUT_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, w)
        )
        LineTransitionTech.FLIP_OUT_X -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            rotationX = floatArrayOf(0f, 90f)
        )
        LineTransitionTech.FLIP_OUT_Y -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            rotationY = floatArrayOf(0f, 90f)
        )
        LineTransitionTech.ROTATE_OUT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            rotation = floatArrayOf(0f, 200f)
        )
        LineTransitionTech.ZOOM_OUT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f, 0f),
            scale = floatArrayOf(1f, 0.3f, 0f)
        )
        LineTransitionTech.FADE_IN -> frameOf(q, alpha = floatArrayOf(0f, 1f))
        LineTransitionTech.FADE_IN_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(-driftX, 0f)
        )
        LineTransitionTech.FADE_IN_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(driftX, 0f)
        )
        LineTransitionTech.FADE_IN_UP -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateY = floatArrayOf(driftY, 0f)
        )
        LineTransitionTech.FADE_IN_DOWN -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateY = floatArrayOf(-driftY, 0f)
        )
        LineTransitionTech.SLIDE_IN_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(-w, 0f)
        )
        LineTransitionTech.SLIDE_IN_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(w, 0f)
        )
        LineTransitionTech.FLIP_IN_X -> frameOf(
            q,
            alpha = floatArrayOf(0.25f, 0.5f, 0.75f, 1f),
            rotationX = floatArrayOf(90f, -15f, 15f, 0f)
        )
        LineTransitionTech.FLIP_IN_Y -> frameOf(
            q,
            alpha = floatArrayOf(0.25f, 0.5f, 0.75f, 1f),
            rotationY = floatArrayOf(90f, -15f, 15f, 0f)
        )
        LineTransitionTech.ROTATE_IN -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            rotation = floatArrayOf(-200f, 0f)
        )
        LineTransitionTech.ZOOM_IN -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            scale = floatArrayOf(0.45f, 1f)
        )
        LineTransitionTech.ZOOM_IN_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f, 1f),
            translateX = floatArrayOf(-w, HYPER_NUDGE_DP, 0f),
            scale = floatArrayOf(0.1f, 0.475f, 1f)
        )
        LineTransitionTech.ZOOM_IN_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f, 1f),
            translateX = floatArrayOf(w, -HYPER_NUDGE_DP, 0f),
            scale = floatArrayOf(0.1f, 0.475f, 1f)
        )
        LineTransitionTech.LANDING_SOFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            scale = floatArrayOf(LANDING_SCALE_FROM, 1f)
        )
    }
}

/** 由关键帧停点组帧:alpha 钳制 0..1,其余通道保留过冲越过量。 */
private fun frameOf(
    p: Float,
    alpha: FloatArray,
    translateX: FloatArray = floatArrayOf(0f),
    translateY: FloatArray = floatArrayOf(0f),
    scale: FloatArray = floatArrayOf(1f),
    rotation: FloatArray = floatArrayOf(0f),
    rotationX: FloatArray = floatArrayOf(0f),
    rotationY: FloatArray = floatArrayOf(0f)
): LineTransitionFrame = LineTransitionFrame(
    alpha = sample(p, alpha).coerceIn(0f, 1f),
    translateXDp = sample(p, translateX),
    translateYDp = sample(p, translateY),
    scale = sample(p, scale),
    rotationDeg = sample(p, rotation),
    rotationXDeg = sample(p, rotationX),
    rotationYDeg = sample(p, rotationY)
)

/** 多段均匀关键帧采样(ObjectAnimator 多值语义):2 停点线性(可过冲外插),多停点分段线性。 */
private fun sample(p: Float, stops: FloatArray): Float {
    if (stops.size == 1) return stops[0]
    val last = stops.size - 1
    val x = p * last
    val i = x.toInt()
    return when {
        i < 0 -> stops[0]
        i >= last -> {
            if (p <= 1f) {
                stops[last]
            } else {
                val t = x - (last - 1)
                stops[last - 1] + (stops[last] - stops[last - 1]) * t
            }
        }
        else -> stops[i] + (stops[i + 1] - stops[i]) * (x - i)
    }
}

/** 换行退场缓动(历史档):起步慢、加速离场,避免线性滑出显得僵硬。 */
internal fun transitionExitEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * t * t
}

/** 换行入场缓动(历史档):起步快、减速落位,收尾无弹跳(AOD 小字场景保持克制)。 */
internal fun transitionEnterEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val u = 1f - t
    return 1f - u * u * u
}

/**
 * 退场缓动按档分派:历史档沿用 [transitionExitEasing](cubic easeIn);
 * HyperLyric 预设统一 FastOutLinearIn(0.4,0,1,1)——起步即带速度地加速离场。
 */
internal fun lineTransitionExitEasing(mode: String, progress: Float): Float =
    lineTransitionPreset(mode)?.let { applyLineTransitionEase(it.outEase, progress) }
        ?: transitionExitEasing(progress)

/**
 * 入场缓动按档分派:历史档沿用 [transitionEnterEasing] 无弹跳;
 * HyperLyric 预设按配方(过冲 OvershootInterpolator 1.6/1.5/2.0/1.0 落位、
 * 柔缓着陆 QuintEaseOut、其余 FastOutSlowIn)。
 */
internal fun lineTransitionEnterEasing(mode: String, progress: Float): Float =
    lineTransitionPreset(mode)?.let { applyLineTransitionEase(it.inEase, progress) }
        ?: transitionEnterEasing(progress)

private fun applyLineTransitionEase(ease: LineTransitionEase, progress: Float): Float =
    when (ease) {
        LineTransitionEase.FAST_OUT_LINEAR_IN -> fastOutLinearInEase(progress)
        LineTransitionEase.FAST_OUT_SLOW_IN -> fastOutSlowInEase(progress)
        LineTransitionEase.OVERSHOOT_1_6 -> overshootEase(progress, 1.6f)
        LineTransitionEase.OVERSHOOT_1_5 -> overshootEase(progress, 1.5f)
        LineTransitionEase.OVERSHOOT_2_0 -> overshootEase(progress, 2.0f)
        LineTransitionEase.OVERSHOOT_1_0 -> overshootEase(progress, 1.0f)
        LineTransitionEase.QUINT_OUT -> quintOutEase(progress)
    }

/** Android OvershootInterpolator 同式:进度越过 1 后回弹落位,tension 越大越过越多。 */
internal fun overshootEase(progress: Float, tension: Float): Float {
    val t = progress.coerceAtLeast(0f) - 1f
    return t * t * ((tension + 1f) * t + tension) + 1f
}

/** 五次方缓出(1-(1-t)⁵,Glider Skill.QuintEaseOut 同式),柔缓着陆的收落曲线。 */
internal fun quintOutEase(progress: Float): Float {
    val u = 1f - progress.coerceIn(0f, 1f)
    return 1f - u * u * u * u * u
}

/** FastOutLinearIn 等价曲线:cubic-bezier(0.4, 0, 1, 1),与 Android 同名插值器同参。 */
internal fun fastOutLinearInEase(progress: Float): Float =
    cubicBezierEase(progress.coerceIn(0f, 1f), 0.4f, 0f, 1f, 1f)

/** FastOutSlowIn 等价曲线:cubic-bezier(0.4, 0, 0.2, 1),与 Android 同名插值器同参。 */
internal fun fastOutSlowInEase(progress: Float): Float =
    cubicBezierEase(progress.coerceIn(0f, 1f), 0.4f, 0f, 0.2f, 1f)

/** cubic-bezier(x1,y1,x2,y2) 在 t 处的取值:反解 x(u)=t 后求 y(u)(Newton 迭代 6 步)。 */
internal fun cubicBezierEase(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val target = t.coerceIn(0f, 1f)
    var u = target
    repeat(6) {
        val dx = bezierSlope(u, x1, x2)
        if (dx > 1e-6f) {
            u = (u - (bezierCurve(u, x1, x2) - target) / dx).coerceIn(0f, 1f)
        }
    }
    return bezierCurve(u, y1, y2)
}

private fun bezierCurve(u: Float, p1: Float, p2: Float): Float {
    val v = 1f - u
    return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u
}

private fun bezierSlope(u: Float, p1: Float, p2: Float): Float {
    val v = 1f - u
    return 3f * v * v * p1 + 6f * v * u * (p2 - p1) + 3f * u * u * (1f - p2)
}

internal fun rubyClipTop(baseBaseline: Float, baseAscent: Float, rubyHeight: Float): Float =
    baseBaseline + baseAscent - rubyHeight

internal fun shouldStartLineTransition(
    lineChanged: Boolean,
    transitionMode: String,
    handoffActive: Boolean,
    resuming: Boolean = false
): Boolean = lineChanged && transitionMode != "None" && !handoffActive && !resuming

/**
 * 过渡不追旧账的跳过判据(纯函数,接入 [AodLyricCanvasView.setContent]):满足任一条即
 * 不播整段过渡,改走压缩补播路径([compressedLineTransitionTimeline]:退场/入场压到
 * ~140ms、每段 ≥40ms,位移段保留速度上限时长,不再是旧版的一帧硬切)——
 *
 * - 快照年龄超过本次过渡总时长([transitionTotalMs] = 退场 + 晋级位移 + 入场):内容与
 *   位置都来自过期批次,再补整段动画只会把旧目标飞着改一遍;doze 批投递实测 19ms 内
 *   12 条、位置跨度约 18s,正是「换行后跳两次」的旧账来源;
 * - 同一帧内到达 ≥2 条换行快照([lineChangesInFrame]):多条会把过渡在 1–2 帧内反复重置,
 *   改以最后一条为目标几何、屏上现有旧快照为起点的压缩补播。
 *
 * [snapshotAgeMs] 为 null 表示年龄未知(预览/直接构造的画布内容):只按同帧条数判定。
 * 边界:年龄恰好等于总时长不跳过(严格「超过」才跳过);单条且年轻照常播过渡。
 */
internal fun shouldSkipLineTransition(
    snapshotAgeMs: Long?,
    transitionTotalMs: Long,
    lineChangesInFrame: Int
): Boolean = lineChangesInFrame >= 2 ||
    (snapshotAgeMs != null && snapshotAgeMs > transitionTotalMs)

internal fun isSongChangeMetadataPlaceholder(
    original: String,
    metadata: String,
    lineStartMs: Long,
    lineEndMs: Long,
    hasTimedWords: Boolean
): Boolean = metadata.isNotBlank() && original == metadata &&
    lineEndMs <= lineStartMs && !hasTimedWords

internal fun shouldMorphSongChangeMetadata(
    previousOriginal: String,
    previousMetadata: String,
    previousLineStartMs: Long,
    previousLineEndMs: Long,
    previousHasTimedWords: Boolean,
    nextMetadata: String,
    nextMetadataVisible: Boolean
): Boolean = nextMetadataVisible && previousMetadata == nextMetadata &&
    isSongChangeMetadataPlaceholder(
        previousOriginal,
        previousMetadata,
        previousLineStartMs,
        previousLineEndMs,
        previousHasTimedWords
    )

internal fun interpolateAodColor(start: Int, end: Int, progress: Float): Int {
    val value = progress.coerceIn(0f, 1f)
    fun channel(from: Int, to: Int): Int = (from + (to - from) * value).roundToInt()
    return Color.argb(
        channel(Color.alpha(start), Color.alpha(end)),
        channel(Color.red(start), Color.red(end)),
        channel(Color.green(start), Color.green(end)),
        channel(Color.blue(start), Color.blue(end))
    )
}
