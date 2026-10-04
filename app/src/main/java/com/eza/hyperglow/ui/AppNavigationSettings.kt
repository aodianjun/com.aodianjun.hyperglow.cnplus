package com.eza.hyperglow.ui

import android.content.Context

/**
 * App 内导航行为设置(预测性返回开关 / 返回触发阈值)。
 *
 * 仅应用侧使用:不进 AOD wire,也不进插件契约;读取一律 fail-closed(越界/未知值回落默认)。
 */
internal object AppNavigationPreferences {
    private const val PREFS = "app_navigation"
    private const val KEY_PREDICTIVE_BACK = "predictive_back"
    private const val KEY_BACK_TRIGGER_PERCENT = "back_trigger_percent"

    /** 默认开启:与 miuix-nav 原生预测性返回一致。 */
    const val DEFAULT_PREDICTIVE_BACK = true

    /** 默认 0:提交判定交还系统(原生行为),不启用应用侧阈值。 */
    const val DEFAULT_BACK_TRIGGER_PERCENT = 0

    /** 阈值上限:再高也只会让返回几乎无法触发(系统自身的判定阈值远低于此)。 */
    const val MAX_BACK_TRIGGER_PERCENT = 60

    /** 滑杆步进,与设置页 steps 一致。 */
    const val BACK_TRIGGER_STEP_PERCENT = 5

    fun readPredictiveBack(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PREDICTIVE_BACK, DEFAULT_PREDICTIVE_BACK)

    fun writePredictiveBack(context: Context, enabled: Boolean): Boolean =
        prefs(context).edit().putBoolean(KEY_PREDICTIVE_BACK, enabled).commit()

    fun readBackTriggerPercent(context: Context): Int = normalizeBackTriggerPercent(
        prefs(context).getInt(KEY_BACK_TRIGGER_PERCENT, DEFAULT_BACK_TRIGGER_PERCENT)
    )

    fun writeBackTriggerPercent(context: Context, percent: Int): Boolean =
        prefs(context).edit()
            .putInt(KEY_BACK_TRIGGER_PERCENT, normalizeBackTriggerPercent(percent))
            .commit()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** 阈值归一化:取整到步进倍数并夹进 [0, MAX](负数/超大值一律收敛,读取与写入同口径)。 */
internal fun normalizeBackTriggerPercent(value: Int): Int =
    (value / AppNavigationPreferences.BACK_TRIGGER_STEP_PERCENT *
        AppNavigationPreferences.BACK_TRIGGER_STEP_PERCENT)
        .coerceIn(0, AppNavigationPreferences.MAX_BACK_TRIGGER_PERCENT)

/**
 * 快甩判定阈值(progress-units/s):与 miuix 内部 COMMIT_VELOCITY_THRESHOLD 同量纲同值 ——
 * 一屏宽度/秒。高于它时按方向直接判定,阈值不再卡住轻快的一甩。
 */
internal const val BACK_FLING_VELOCITY = 1.0f

/**
 * 松手时是否达到触发阈值(纯函数,单测钉住)。
 *
 * 规则与 miuix 的位置/速度判定同形 —— velocity-first、position-fallback:
 * - 朝返回方向的快甩(velocity ≥ [flingVelocity])直接提交;
 * - 反向快甩直接取消(实际 miuix 已在提交前按速度拦下,这里只是兜底);
 * - 其余按拖动比例:达到 [thresholdPercent] 才提交。
 *
 * @param progress 松手时的拖动比例(0..1,来自手势上下文)。
 * @param velocity 松手速度(progress-units/s,正 = 朝返回方向)。
 * @param thresholdPercent 触发阈值百分比(0 = 全部提交,即交还系统判定)。
 */
internal fun backTriggerReached(
    progress: Float,
    velocity: Float,
    thresholdPercent: Int,
    flingVelocity: Float = BACK_FLING_VELOCITY,
): Boolean = when {
    velocity >= flingVelocity -> true
    velocity <= -flingVelocity -> false
    else -> progress >= normalizeBackTriggerPercent(thresholdPercent) / 100f
}
