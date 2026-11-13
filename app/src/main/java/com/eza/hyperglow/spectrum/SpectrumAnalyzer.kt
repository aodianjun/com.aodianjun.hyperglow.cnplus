package com.eza.hyperglow.spectrum

import com.eza.hyperglow.aod.SpectrumContract
import com.eza.hyperglow.aod.SpectrumFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 单声道 PCM → 对数频段频谱的确定性分析器。
 *
 * 管线固定：去直流 → Hann 窗 → radix-2 FFT → 40Hz..16kHz 对数分频 → dB 归一化 →
 * 快攻慢放的包络平滑。不依赖任何 Android/GPU 组件，可直接在 JVM 单测里跑。
 *
 * 输出帧严格满足 [SpectrumContract]：频段数固定、全部 [0,1]、无 NaN/Inf。
 */
class SpectrumAnalyzer(
    private val sampleRate: Int,
    private val bandCount: Int = SpectrumContract.DEFAULT_BAND_COUNT,
    private val fftSize: Int = SpectrumContract.FFT_SIZE
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(fftSize >= 256 && Integer.bitCount(fftSize) == 1) {
            "fftSize must be a power of two >= 256"
        }
        require(bandCount in SpectrumContract.MIN_BAND_COUNT..SpectrumContract.MAX_BAND_COUNT) {
            "bandCount outside contract"
        }
    }

    private val window = FloatArray(fftSize) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1))).toFloat()
    }
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val smoothed = FloatArray(bandCount)
    private val bandLowBins = IntArray(bandCount)
    private val bandHighBins = IntArray(bandCount)
    private var sequence = 0L

    init {
        // 40Hz 起，最高到 16kHz 与 Nyquist*0.95 的较小值；频段按几何级数铺开。
        val lowHz = 40.0
        val highHz = min(16_000.0, sampleRate * 0.5 * 0.95)
        val ratio = highHz / lowHz
        val binHz = sampleRate.toDouble() / fftSize
        for (band in 0 until bandCount) {
            val fromHz = lowHz * Math.pow(ratio, band.toDouble() / bandCount)
            val toHz = lowHz * Math.pow(ratio, (band + 1).toDouble() / bandCount)
            val low = max(1, (fromHz / binHz).toInt())
            val high = max(low + 1, Math.ceil(toHz / binHz).toInt())
            bandLowBins[band] = low.coerceAtMost(fftSize / 2 - 1)
            bandHighBins[band] = high.coerceIn(bandLowBins[band] + 1, fftSize / 2)
        }
    }

    fun reset() {
        smoothed.fill(0f)
        sequence = 0L
    }

    /**
     * @param samples 单声道 PCM，[-1,1]。
     * @param length 有效样本数，允许小于 [samples] 长度。
     */
    fun analyze(samples: FloatArray, length: Int, capturedAtElapsedMs: Long): SpectrumFrame {
        val count = length.coerceIn(0, min(samples.size, fftSize))
        if (count == 0) {
            smoothed.fill(0f)
            sequence++
            return SpectrumFrame(sequence - 1, capturedAtElapsedMs, false, 0f, smoothed.copyOf())
        }

        var sum = 0f
        var sumSquares = 0.0
        for (index in 0 until count) {
            val raw = samples[index]
            val value = if (raw.isFinite()) raw.coerceIn(-1f, 1f) else 0f
            sum += value
            sumSquares += value.toDouble() * value
        }
        val mean = sum / count
        val rms = sqrt(sumSquares / count).toFloat().coerceIn(0f, 1f)

        for (index in 0 until fftSize) {
            val raw = if (index < count) samples[index] else 0f
            val sample = if (raw.isFinite()) raw.coerceIn(-1f, 1f) - mean else 0f - mean
            real[index] = sample * window[index]
            imaginary[index] = 0f
        }
        fft(real, imaginary)

        // Hann 窗相干增益 0.5；单边谱乘 2，使满幅正弦落到 1.0 附近。
        val magnitudeScale = 2f / (fftSize * 0.5f)
        var peak = 0f
        for (band in 0 until bandCount) {
            val low = bandLowBins[band]
            val high = bandHighBins[band]
            var power = 0.0
            for (bin in low until high) {
                val re = real[bin]
                val im = imaginary[bin]
                power += (re.toDouble() * re + im.toDouble() * im)
            }
            val magnitude = sqrt(power / max(1, high - low)).toFloat() * magnitudeScale
            val db = 20f * log10(max(magnitude, 1e-7f))
            val normalized = ((db + 60f) / 60f).coerceIn(0f, 1f)
            val target = if (normalized < 0.02f) 0f else normalized
            val previous = smoothed[band]
            smoothed[band] = if (target > previous) {
                previous + (target - previous) * ATTACK
            } else {
                previous + (target - previous) * RELEASE
            }.coerceIn(0f, 1f)
            peak = max(peak, smoothed[band])
        }

        sequence++
        val active = rms > SILENCE_RMS || peak > SILENCE_BAND
        return SpectrumFrame(
            sequence = sequence - 1,
            capturedAtElapsedMs = capturedAtElapsedMs,
            active = active,
            rms = rms,
            bands = smoothed.copyOf()
        )
    }

    /** 原地 radix-2 Cooley-Tukey FFT，输入长度必须是 2 的幂。 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                val half = length shr 1
                for (offset in 0 until half) {
                    val evenIndex = start + offset
                    val oddIndex = evenIndex + half
                    val oddRe = re[oddIndex]
                    val oddIm = im[oddIndex]
                    val vRe = oddRe * curRe - oddIm * curIm
                    val vIm = oddRe * curIm + oddIm * curRe
                    val uRe = re[evenIndex]
                    val uIm = im[evenIndex]
                    re[evenIndex] = uRe + vRe
                    im[evenIndex] = uIm + vIm
                    re[oddIndex] = uRe - vRe
                    im[oddIndex] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    private companion object {
        const val ATTACK = 0.58f
        const val RELEASE = 0.17f
        const val SILENCE_RMS = 0.0012f
        const val SILENCE_BAND = 0.045f
    }
}
