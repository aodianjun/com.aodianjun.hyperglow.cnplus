package com.eza.hyperglow.spectrum

import com.eza.hyperglow.aod.SpectrumContract
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumAnalyzerTest {
    private val sampleRate = 48_000

    @Test
    fun sineToneLandsInExpectedLogBand() {
        val analyzer = SpectrumAnalyzer(sampleRate)
        val frame = analyzer.analyze(tone(1_000.0, amplitude = 0.8f), SAMPLE_COUNT, 1L)

        assertEquals(SpectrumContract.DEFAULT_BAND_COUNT, frame.bands.size)
        val peakBand = frame.bands.indices.maxBy { frame.bands[it] }
        // 40Hz..16kHz 的 24 段对数分频里，1kHz 约落在第 12 段。
        assertTrue("peak band was $peakBand", peakBand in 10..14)
        assertTrue(frame.active)
        frame.bands.forEach { value ->
            assertTrue("band out of range: $value", value in 0f..1f)
            assertTrue(value.isFinite())
        }
    }

    @Test
    fun lowToneStaysInLowBands() {
        val analyzer = SpectrumAnalyzer(sampleRate)
        val frame = analyzer.analyze(tone(80.0, amplitude = 0.8f), SAMPLE_COUNT, 2L)
        val peakBand = frame.bands.indices.maxBy { frame.bands[it] }
        assertTrue("peak band was $peakBand", peakBand in 0..4)
    }

    @Test
    fun silenceProducesInactiveZeroFrame() {
        val analyzer = SpectrumAnalyzer(sampleRate)
        val frame = analyzer.analyze(FloatArray(SAMPLE_COUNT), SAMPLE_COUNT, 3L)
        assertFalse(frame.active)
        assertEquals(0f, frame.rms, 1e-6f)
        frame.bands.forEach { assertEquals(0f, it, 1e-6f) }
    }

    @Test
    fun outputSurvivesAdversarialInput() {
        val analyzer = SpectrumAnalyzer(sampleRate)
        val samples = FloatArray(SAMPLE_COUNT) { index ->
            if (index % 7 == 0) Float.NaN else if (index % 11 == 0) Float.POSITIVE_INFINITY else 0.3f
        }
        val frame = analyzer.analyze(samples, SAMPLE_COUNT, 4L)
        frame.bands.forEach { value ->
            assertTrue(value.isFinite())
            assertTrue(value in 0f..1f)
        }
    }

    private fun tone(frequency: Double, amplitude: Float): FloatArray {
        val samples = FloatArray(SAMPLE_COUNT)
        for (index in samples.indices) {
            samples[index] = (amplitude * sin(2.0 * PI * frequency * index / sampleRate)).toFloat()
        }
        return samples
    }

    private companion object {
        const val SAMPLE_COUNT = 2_048
    }
}
