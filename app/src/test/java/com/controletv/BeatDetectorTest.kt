package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatDetectorTest {
    /** A kick every [period] frames over a quieter bed, as 20 frames per second. */
    private fun beats(period: Int, frames: Int, kick: Float = 10f, bed: Float = 2f): List<Int> {
        val detector = BeatDetector(20)
        return (0 until frames).filter { i -> detector.feed(if (i % period == 0) kick else bed) > 0f }
    }

    @Test
    fun findsRegularKicks() {
        // 120 BPM = a kick every 10 frames at 20 frames per second
        val found = beats(period = 10, frames = 200)
        assertTrue("found ${found.size}", found.size >= 17)
        assertTrue(found.all { it % 10 == 0 })
    }

    @Test
    fun steadySoundIsNotABeat() {
        val detector = BeatDetector(20)
        val hits = (0 until 200).count { detector.feed(5f) > 0f }
        assertEquals(0, hits)
    }

    @Test
    fun beatsAreSpacedOut() {
        // Very fast hits (every 2 frames = 100 ms) are thinned to at most one per ~280 ms
        val found = beats(period = 2, frames = 200, kick = 10f, bed = 1f)
        assertTrue(found.zipWithNext().all { (a, b) -> b - a >= 5 })
    }

    @Test
    fun bassComesFromLowBins() {
        // 1024-byte FFT at 44.1 kHz: ~43 Hz per bin, so bins 1–3 are bass
        val fft = ByteArray(1024)
        fft[2] = 50; fft[3] = 50
        val withBass = BeatDetector.bassOf(fft, 44_100_000)
        val treble = ByteArray(1024).apply { this[400] = 100 }
        assertTrue(withBass > 0f)
        assertEquals(0f, BeatDetector.bassOf(treble, 44_100_000))
    }
}
