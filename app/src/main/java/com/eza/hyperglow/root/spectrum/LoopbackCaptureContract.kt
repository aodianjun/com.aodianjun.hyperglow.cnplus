package com.eza.hyperglow.root.spectrum

import android.media.AudioFormat

/**
 * HyperWave 兼容的 48k float mono loopback 契约。
 *
 * 源项目：https://github.com/leset0ng/hyperwave（Apache-2.0）。
 * HyperGlow 只移植其 AudioPolicy/AudioMix 采集与 framework 格式放行思路；
 * FFT/分频使用 HyperGlow 自己的 Kotlin SpectrumAnalyzer。
 */
internal object LoopbackCaptureContract {
    const val TAG = "HyperGlowLoopback"
    const val RULE_TAG = "com.aodianjun.hyperglow.cnplus.spectrum.48k.v1"
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
    const val ROUTE_FLAGS = 3
    const val TARGET_MIX_ROLE = 0
    const val SAMPLE_SIZE = 2_048
    const val PUBLISH_INTERVAL_MS = 45L
}

/** 线程局部格式放行作用域：只放行本次构建出的 exact AudioFormat 对象。 */
internal class CaptureFormatScope {
    private val current = ThreadLocal<AudioFormat?>()

    fun allows(format: AudioFormat): Boolean = current.get() === format

    fun <T> withFormat(format: AudioFormat, action: () -> T): T {
        val previous = current.get()
        current.set(format)
        return try {
            action()
        } finally {
            current.set(previous)
        }
    }
}

/** AudioMix/Builder 里读出的关键字段。 */
internal data class LoopbackMixShape(
    val rule: Any?,
    val routeFlags: Int,
    val format: AudioFormat
)
