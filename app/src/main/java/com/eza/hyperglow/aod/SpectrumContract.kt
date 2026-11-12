package com.eza.hyperglow.aod

/**
 * 音乐频谱帧契约，协议版本 1。
 *
 * 设计沿用 hyper-spectrum 的契约思路：帧带显式协议版本、单调序号与采集时刻，所有数组在
 * 边界处做 fail-closed 校验（长度、NaN/Inf、取值范围），消费端只接受自己认识的协议版本。
 * 频谱帧与歌词快照完全解耦——它不参与 revision、新鲜度与 keepalive 状态机，丢失一帧只会
 * 让动画短暂停一格。
 */
object SpectrumContract {
    const val PROTOCOL_VERSION: Int = 1

    /** AOD/锁屏两侧共用的固定频段数；渲染端可再做一次重采样。 */
    const val DEFAULT_BAND_COUNT: Int = 24
    const val MIN_BAND_COUNT: Int = 8
    const val MAX_BAND_COUNT: Int = 32

    const val DEFAULT_SAMPLE_RATE: Int = 48_000
    const val FFT_SIZE: Int = 2_048

    /** 超过该时长未收到新帧即视为停播，渲染端开始淡出。 */
    const val MAX_FRAME_AGE_MS: Long = 1_500L

    /** app 侧发布节流；20Hz 足够，渲染端在本地插值到显示帧率。 */
    const val MIN_PUBLISH_INTERVAL_MS: Long = 45L
}

/**
 * 一帧频谱数据。bands 为 [0,1] 的归一化幅度，顺序从低频到高频。
 *
 * @property sequence 单调递增的帧序号，仅用于诊断与丢弃乱序帧。
 * @property capturedAtElapsedMs 采集时刻（SystemClock.elapsedRealtime）。
 * @property active 当前是否有可辨识的音频能量。false 时渲染端应衰减到零。
 * @property rms 归一化 RMS，供诊断与静音检测。
 */
data class SpectrumFrame(
    val sequence: Long,
    val capturedAtElapsedMs: Long,
    val active: Boolean,
    val rms: Float,
    val bands: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpectrumFrame) return false
        return sequence == other.sequence &&
            capturedAtElapsedMs == other.capturedAtElapsedMs &&
            active == other.active &&
            rms.equals(other.rms) &&
            bands.contentEquals(other.bands)
    }

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + capturedAtElapsedMs.hashCode()
        result = 31 * result + active.hashCode()
        result = 31 * result + rms.hashCode()
        result = 31 * result + bands.contentHashCode()
        return result
    }

    companion object {
        fun idle(
            sequence: Long,
            nowElapsedMs: Long,
            bandCount: Int = SpectrumContract.DEFAULT_BAND_COUNT
        ): SpectrumFrame = SpectrumFrame(
            sequence = sequence,
            capturedAtElapsedMs = nowElapsedMs,
            active = false,
            rms = 0f,
            bands = FloatArray(
                bandCount.coerceIn(
                    SpectrumContract.MIN_BAND_COUNT,
                    SpectrumContract.MAX_BAND_COUNT
                )
            )
        )
    }
}

internal fun isValidSpectrumFrame(frame: SpectrumFrame): Boolean {
    if (frame.sequence < 0L || frame.capturedAtElapsedMs < 0L) return false
    if (!frame.rms.isFinite() || frame.rms !in 0f..1f) return false
    if (frame.bands.size !in SpectrumContract.MIN_BAND_COUNT..SpectrumContract.MAX_BAND_COUNT) {
        return false
    }
    return frame.bands.all { it.isFinite() && it in 0f..1f }
}

/** 把任意长度的频段数组线性重采样到 [targetCount]，返回值恒为 [0,1]。 */
internal fun resampleSpectrumBands(bands: FloatArray, targetCount: Int): FloatArray {
    val count = targetCount.coerceAtLeast(1)
    val result = FloatArray(count)
    if (bands.isEmpty()) return result
    if (bands.size == 1) {
        result.fill(bands[0].coerceIn(0f, 1f))
        return result
    }
    for (index in 0 until count) {
        val position = if (count == 1) 0f else index.toFloat() * (bands.size - 1) / (count - 1)
        val left = position.toInt().coerceIn(0, bands.size - 1)
        val right = (left + 1).coerceAtMost(bands.size - 1)
        val fraction = position - left
        val value = bands[left] + (bands[right] - bands[left]) * fraction
        result[index] = value.coerceIn(0f, 1f)
    }
    return result
}
