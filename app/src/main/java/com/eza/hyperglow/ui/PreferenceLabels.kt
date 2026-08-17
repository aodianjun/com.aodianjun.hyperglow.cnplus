package com.eza.hyperglow.ui

import com.eza.hyperglow.R
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState

/** 设置项的显示标签与候选项集合(防烧屏模式/间隔/保持时长/暂停停留等)。 */

internal fun supportStateLabel(
    context: android.content.Context,
    state: XiaomiRuntimeSupportState
): String = context.getString(
    when (state) {
        XiaomiRuntimeSupportState.NO_SYSTEM_UI_REPORT -> R.string.status_no_systemui_report
        XiaomiRuntimeSupportState.VERIFIED_PROFILE -> R.string.status_verified_profile
        XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ->
            R.string.status_verified_profile_missing_symbols
        XiaomiRuntimeSupportState.UNSUPPORTED_PROFILE -> R.string.status_unsupported_profile
        XiaomiRuntimeSupportState.EXPERIMENTAL_ELIGIBLE -> R.string.status_experimental_eligible
        XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE -> R.string.status_experimental_active
    }
)

internal fun burnInPatternLabel(context: android.content.Context, value: String): String =
    context.getString(
        when (value) {
            "static_top" -> R.string.pattern_keep_top
            "six_zone" -> R.string.pattern_six_positions
            "four_corner" -> R.string.pattern_four_corners
            "vertical_swap" -> R.string.pattern_top_bottom
            else -> R.string.pattern_keep_bottom
        }
    )

internal fun aodMovementLabel(
    context: android.content.Context,
    positionFollowing: Boolean,
    pattern: String
): String = if (positionFollowing) {
    burnInPatternLabel(context, pattern)
} else {
    context.getString(R.string.option_follow_xiaomi)
}

internal fun burnInIntervalLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            30_000L -> R.string.duration_30_seconds
            120_000L -> R.string.duration_2_minutes
            300_000L -> R.string.duration_5_minutes
            else -> R.string.duration_1_minute
        }
    )

internal fun pauseLingerLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            0L -> R.string.duration_clear_immediately
            10_000L -> R.string.duration_10_seconds
            30_000L -> R.string.duration_30_seconds
            -1L -> R.string.duration_keep_indefinitely
            else -> R.string.duration_5_seconds
        }
    )

internal val BURN_IN_PATTERNS = listOf(
    "static_top",
    "static_bottom",
    "six_zone",
    "four_corner",
    "vertical_swap"
)

internal fun keepAwakeDurationLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            300_000L -> R.string.duration_5_minutes
            600_000L -> R.string.duration_10_minutes
            1_800_000L -> R.string.duration_30_minutes
            3_600_000L -> R.string.duration_1_hour
            7_200_000L -> R.string.duration_2_hours
            else -> R.string.duration_indefinitely
        }
    )

internal val KEEP_AWAKE_DURATIONS = listOf(
    300_000L,
    600_000L,
    1_800_000L,
    3_600_000L,
    7_200_000L,
    -1L
)

internal val PAUSE_LINGER_OPTIONS = listOf(0L, 5_000L, 10_000L, 30_000L, -1L)

internal val BURN_IN_INTERVALS = listOf(30_000L, 60_000L, 120_000L, 300_000L)
