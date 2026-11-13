package com.eza.hyperglow.aod

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpectrumWireCodecTest {
    @Test
    fun roundTripPreservesFrame() {
        val frame = SpectrumFrame(
            sequence = 42L,
            capturedAtElapsedMs = 123_456L,
            active = true,
            rms = 0.25f,
            bands = FloatArray(SpectrumContract.DEFAULT_BAND_COUNT) { it / 32f }
        )
        val bundle = SpectrumWireCodec.toBundle(frame)
        assertNotNull(bundle)
        assertEquals(frame, SpectrumWireCodec.fromBundle(bundle!!))
    }

    @Test
    fun rejectsUnknownProtocol() {
        val bundle = validBundle().apply {
            putInt("protocol", SpectrumContract.PROTOCOL_VERSION + 1)
        }
        assertNull(SpectrumWireCodec.fromBundle(bundle))
    }

    @Test
    fun rejectsNonFiniteOrOutOfRangeBands() {
        val nanBands = FloatArray(SpectrumContract.MIN_BAND_COUNT) { 0.1f }
        nanBands[3] = Float.NaN
        val nan = validBundle().apply { putFloatArray("bands", nanBands) }
        assertNull(SpectrumWireCodec.fromBundle(nan))

        val empty = validBundle().apply { putFloatArray("bands", FloatArray(0)) }
        assertNull(SpectrumWireCodec.fromBundle(empty))

        val tooMany = validBundle().apply {
            putFloatArray("bands", FloatArray(SpectrumContract.MAX_BAND_COUNT + 1))
        }
        assertNull(SpectrumWireCodec.fromBundle(tooMany))
    }

    @Test
    fun clampsBandsOnDecode() {
        val bands = FloatArray(SpectrumContract.MIN_BAND_COUNT)
        bands[0] = -0.5f
        bands[1] = 0.5f
        bands[2] = 1.5f
        val frame = SpectrumWireCodec.fromBundle(validBundle().apply {
            putFloatArray("bands", bands)
        })
        assertNotNull(frame)
        assertEquals(0f, frame!!.bands[0], 0f)
        assertEquals(0.5f, frame.bands[1], 0f)
        assertEquals(1f, frame.bands[2], 0f)
    }

    private fun validBundle(): Bundle = Bundle().apply {
        putInt("protocol", SpectrumContract.PROTOCOL_VERSION)
        putLong("sequence", 1L)
        putLong("capturedAt", 2L)
        putBoolean("active", true)
        putFloat("rms", 0.1f)
        putFloatArray("bands", FloatArray(SpectrumContract.DEFAULT_BAND_COUNT) { 0.1f })
    }
}
