package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAnalyzerTest {
    // 1024-byte FFT at 44.1 kHz: ~43 Hz per bin
    private val rate = 44_100_000

    /** An FFT frame with energy at the given bins (bin → amplitude). */
    private fun fft(vararg bins: Pair<Int, Int>) = ByteArray(1024).apply {
        for ((k, a) in bins) { this[2 * k] = a.toByte(); this[2 * k + 1] = 0 }
    }

    @Test
    fun higherNotesRaisePitch() {
        val low = MusicAnalyzer(20)
        repeat(60) { low.feed(fft(6 to 60), rate) }       // ~260 Hz
        val high = MusicAnalyzer(20)
        repeat(60) { high.feed(fft(70 to 60), rate) }     // ~3 kHz
        val lowPitch = low.feed(fft(6 to 60), rate).pitch
        val highPitch = high.feed(fft(70 to 60), rate).pitch
        assertTrue("low $lowPitch high $highPitch", highPitch > lowPitch + 0.3f)
    }

    @Test
    fun noteStartsAreOnsets() {
        val a = MusicAnalyzer(20)
        var onsets = 0
        // A note every 10 frames over silence
        for (i in 0 until 200) {
            val f = if (i % 10 < 3) fft(20 to 80) else fft(20 to 5)
            if (a.feed(f, rate).onset > 0f) onsets++
        }
        assertTrue("onsets $onsets", onsets in 15..20)
    }

    @Test
    fun steadyKicksGiveTheTempo() {
        val a = MusicAnalyzer(20)
        var last: MusicAnalyzer.Frame? = null
        // Kick every 10 frames = 500 ms (120 BPM)
        for (i in 0 until 300) last = a.feed(if (i % 10 == 0) fft(2 to 90, 20 to 20) else fft(2 to 15, 20 to 20), rate)
        assertNotNull(last!!.beatMs)
        assertEquals(500L, last.beatMs)
    }

    @Test
    fun suddenlyLouderIsANewSection() {
        val a = MusicAnalyzer(20)
        var sections = 0
        for (i in 0 until 400) {
            val loud = i >= 200
            if (a.feed(fft(20 to if (loud) 100 else 20, 40 to if (loud) 100 else 20), rate).section) sections++
        }
        assertEquals(1, sections)
    }
}
