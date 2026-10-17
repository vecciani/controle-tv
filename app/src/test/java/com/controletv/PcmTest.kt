package com.controletv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test
    fun downsamples48kTo16kByAveragingTriples() {
        val input = floatArrayOf(3f, 3f, 3f, 6f, 6f, 6f, 9f, 9f, 9f)
        val (out, phase) = Pcm.resample(input, 48_000, 16_000, 0.0)
        assertArrayEquals(shortArrayOf(3, 6, 9), out)
        assertEquals(0.0, phase, 1e-9)
    }

    @Test
    fun keepsRateAcrossBuffersAt44k() {
        // 44.1 kHz -> 16 kHz over many small buffers must produce ~16 000 samples per second
        var phase = 0.0
        var total = 0
        repeat(441) {
            val (out, next) = Pcm.resample(FloatArray(100) { 1000f }, 44_100, 16_000, phase)
            total += out.size
            phase = next
        }
        assertEquals(16_000.0, total.toDouble(), 3.0)
    }

    @Test
    fun clipsLoudSamples() {
        val (out, _) = Pcm.resample(floatArrayOf(40000f, 40000f, 40000f), 48_000, 16_000, 0.0)
        assertArrayEquals(shortArrayOf(32767), out)
    }
}
