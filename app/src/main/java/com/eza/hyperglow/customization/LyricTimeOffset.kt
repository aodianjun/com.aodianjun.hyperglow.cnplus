package com.eza.hyperglow.customization

/**
 * 歌词时间偏移(「歌词时间戳对齐」)纯策略。
 *
 * 参考 limczhh/HyperLyric 的「歌词时间偏移」(Lyric time offset):在其 Lyricon 歌词源入口对
 * 播放位置做 `position − offset` 后再选行,范围 ±5000ms、50ms 档,「正数延后显示,负数提前
 * 显示」。CN+ 按同一语义落在时间轴源生产者(Lyricon/LyricInfo/Spicy)的发射边界:选行查询、
 * `positionMs`、行窗、词级时间与 `nextLineStartMs` 全部换算到「显示时间轴」;位置外推、
 * 残留拒绝、seek 判定等机制层保持原始媒体坐标(跨源 seek 转发的仍是原始位置,不会被二次
 * 偏移)。SuperLyric 为逐行推流源(行到达即上屏,无整首时间轴),不受偏移影响。
 */
object LyricTimeOffset {
    /** 偏移下限(毫秒,负数=提前显示);与 HyperLyric 同值。 */
    const val MIN_OFFSET_MS = -5_000
    /** 偏移上限(毫秒,正数=延后显示);与 HyperLyric 同值。 */
    const val MAX_OFFSET_MS = 5_000
    /** 滑杆量化档距(毫秒);与 HyperLyric 滑杆粒度一致。 */
    const val STEP_MS = 50
    const val DEFAULT_OFFSET_MS = 0

    /**
     * 文档值规范化(fail-closed):null 回默认 0;钳制到 [MIN_OFFSET_MS]..[MAX_OFFSET_MS] 并按
     * [STEP_MS] 四舍五入到最近档。编译([SceneCompiler])与设置提交共用同一入口,保证落盘值
     * 与下发值恒在档位内。
     */
    fun normalize(raw: Int?): Int {
        if (raw == null) return DEFAULT_OFFSET_MS
        val clamped = raw.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        return Math.round(clamped / STEP_MS.toFloat()) * STEP_MS
    }

    /** 显示时间轴坐标 = 媒体位置 − 偏移(正偏移延后、负偏移提前),不早于 0。 */
    fun displayPositionMs(positionMs: Long, offsetMs: Int): Long =
        (positionMs - offsetMs).coerceAtLeast(0L)

    /** 行窗/词级时间戳平移到显示时间轴(正偏移延后、负偏移提前),不早于 0。 */
    fun displayMs(timestampMs: Long, offsetMs: Int): Long =
        (timestampMs - offsetMs).coerceAtLeast(0L)
}
