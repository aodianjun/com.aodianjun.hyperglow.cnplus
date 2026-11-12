package com.eza.hyperglow.aod

import android.os.Bundle

/**
 * 频谱帧的 Binder Bundle 编解码。
 *
 * 与歌词状态线一样，解码发生在 Binder 还持有 Bundle 的时候；跨回调只传不可变的
 * [SpectrumFrame]。任何字段越界都整帧丢弃，绝不把损坏数据带进渲染层。
 */
internal object SpectrumWireCodec {
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_SEQUENCE = "sequence"
    private const val KEY_CAPTURED_AT = "capturedAt"
    private const val KEY_ACTIVE = "active"
    private const val KEY_RMS = "rms"
    private const val KEY_BANDS = "bands"

    fun toBundle(frame: SpectrumFrame): Bundle? {
        if (!isValidSpectrumFrame(frame)) return null
        return Bundle().apply {
            putInt(KEY_PROTOCOL, SpectrumContract.PROTOCOL_VERSION)
            putLong(KEY_SEQUENCE, frame.sequence)
            putLong(KEY_CAPTURED_AT, frame.capturedAtElapsedMs)
            putBoolean(KEY_ACTIVE, frame.active)
            putFloat(KEY_RMS, frame.rms)
            putFloatArray(KEY_BANDS, frame.bands.copyOf())
        }
    }

    fun fromBundle(bundle: Bundle): SpectrumFrame? {
        if (bundle.getInt(KEY_PROTOCOL, 0) != SpectrumContract.PROTOCOL_VERSION) return null
        val sequence = bundle.getLong(KEY_SEQUENCE, -1L)
        val capturedAt = bundle.getLong(KEY_CAPTURED_AT, -1L)
        if (sequence < 0L || capturedAt < 0L) return null
        val bands = bundle.getFloatArray(KEY_BANDS) ?: return null
        if (bands.size !in SpectrumContract.MIN_BAND_COUNT..SpectrumContract.MAX_BAND_COUNT) {
            return null
        }
        if (bands.any { !it.isFinite() }) return null
        val rms = bundle.getFloat(KEY_RMS, 0f)
        if (!rms.isFinite() || rms !in 0f..1f) return null
        return SpectrumFrame(
            sequence = sequence,
            capturedAtElapsedMs = capturedAt,
            active = bundle.getBoolean(KEY_ACTIVE, false),
            rms = rms,
            bands = FloatArray(bands.size) { bands[it].coerceIn(0f, 1f) }
        )
    }
}
