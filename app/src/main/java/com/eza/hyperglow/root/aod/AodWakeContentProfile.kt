package com.eza.hyperglow.root.aod

/**
 * 当前 AOD 画布内容的唤醒需求画像（纯数据，便于测试与日志）。
 *
 * 目的：把“画面在动”与“画面只是存在”分开。`mWakeLock` 续期只应由真正逐帧变化的
 * 内容驱动；静态文本和纯行级扫光不应把一个 5.5s 的唤醒锁无限续下去。
 */
internal data class AodWakeContentProfile(
    val wordKaraoke: Boolean = false,
    val lineSweep: Boolean = false,
    val interludeDots: Boolean = false,
    val artworkSpin: Boolean = false,
    val lineTransition: Boolean = false,
    val wordEffect: Boolean = false,
    val auxKaraoke: Boolean = false,
    val paused: Boolean = false,
    val demoAnimation: Boolean = false
) {
    /**
     * 需要连续帧/连续唤醒的内容。行级扫光 [lineSweep] 也算连续（测试包按 24fps
     * 限帧，仍需要周期唤醒来合成）；行换场 [lineTransition] 不在其中，它只是短暂的
     * 内容变化，走内容变化短脉冲。逐字、圆点、封面旋转、Demo 仍连续。
     */
    val continuousAnimation: Boolean
        get() = wordKaraoke || lineSweep || wordEffect || auxKaraoke ||
            interludeDots || artworkSpin || demoAnimation

    /**
     * 只有行级扫光、没有其他逐帧效果（且不在换场/暂停）。画布据此把帧间隔钳到
     * 24fps；换场仍走用户刷新率上限，静态不续帧。
     */
    val lineSweepOnly: Boolean
        get() = lineSweep && !wordKaraoke && !wordEffect && !auxKaraoke &&
            !interludeDots && !artworkSpin && !lineTransition && !demoAnimation

    val lineLevelOnly: Boolean
        get() = lineSweepOnly

    val staticContent: Boolean
        get() = !continuousAnimation && !lineSweep

    fun summary(): String =
        "word=$wordKaraoke line=$lineSweep dots=$interludeDots spin=$artworkSpin " +
            "trans=$lineTransition effect=$wordEffect aux=$auxKaraoke " +
            "paused=$paused demo=$demoAnimation continuous=$continuousAnimation"

    companion object {
        val NONE = AodWakeContentProfile()

        /** 画布尚未挂载/无内容时的空画像。 */
        val STATIC = NONE
    }
}
