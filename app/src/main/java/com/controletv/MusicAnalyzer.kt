package com.controletv

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Follows a song from its spectrum (one FFT per frame, ~20 per second): how loud it is, how
 * strong and how high the melody is, when notes start, the beat and its tempo, and when the
 * song changes section (verse → chorus). Plain logic, no Android, so it can be tested.
 */
class MusicAnalyzer(private val framesPerSecond: Int) {

    data class Frame(
        /** Overall loudness, 0..1, smooth (rises fast, falls slowly). */
        val loudness: Float,
        /** Strength of the melody/voice range (200–2500 Hz), 0..1, smooth. */
        val melody: Float,
        /** How high the sound is (spectral centroid), 0 = low, 1 = high, smooth. */
        val pitch: Float,
        /** A new note started (0 = none, up to 1). */
        val onset: Float,
        /** A beat in the bass (0 = none, 0.3 to 1). */
        val beat: Float,
        /** Time between beats, once it's steady; null while unknown. */
        val beatMs: Long?,
        /** The song just changed section (much louder or quieter for a few seconds). */
        val section: Boolean,
    )

    private val beats = BeatDetector(framesPerSecond)
    private var frame = 0L
    private var prev: FloatArray? = null

    private var loudPeak = 1e-6f
    private var melodyPeak = 1e-6f
    private var loudness = 0f
    private var melody = 0f
    private var pitch = 0.5f

    private val flux = ArrayDeque<Float>()
    private var lastOnset = -1000L

    private val beatFrames = ArrayDeque<Long>()

    private val longLoud = ArrayDeque<Float>()
    private var lastSection = -1000L

    fun feed(fft: ByteArray, samplingRateMilliHz: Int): Frame {
        frame++
        val bins = fft.size / 2
        val hzPerBin = if (samplingRateMilliHz > 0 && fft.isNotEmpty()) samplingRateMilliHz / 1000f / fft.size else 43f
        val mag = FloatArray(bins)
        for (k in 1 until bins) {
            val re = fft[2 * k].toFloat()
            val im = fft[2 * k + 1].toFloat()
            mag[k] = sqrt(re * re + im * im)
        }
        fun bin(hz: Float) = (hz / hzPerBin).toInt().coerceIn(1, bins - 1)

        // Loudness (30 Hz – 8 kHz) and melody (200 – 2500 Hz), each against its own recent peak
        var all = 0f
        for (k in bin(30f)..bin(8000f)) all += mag[k] * mag[k]
        var mid = 0f
        for (k in bin(200f)..bin(2500f)) mid += mag[k] * mag[k]
        loudPeak = maxOf(all, loudPeak * 0.998f)
        melodyPeak = maxOf(mid, melodyPeak * 0.998f)
        loudness = follow(loudness, (all / loudPeak).coerceIn(0f, 1f), 0.5f, 0.08f)
        melody = follow(melody, (mid / melodyPeak).coerceIn(0f, 1f), 0.35f, 0.06f)

        // Pitch: where the sound's weight sits between 150 Hz and 5 kHz, on a log scale
        var weighted = 0f
        var total = 0f
        for (k in bin(150f)..bin(5000f)) {
            weighted += k * hzPerBin * mag[k]
            total += mag[k]
        }
        if (total > 1e-3f) {
            val centroid = (weighted / total).coerceIn(150f, 5000f)
            val p = (ln(centroid / 150f) / ln(5000f / 150f))
            pitch += (p - pitch) * 0.15f
        }

        // Note starts: sudden rise of energy above the bass (spectral flux)
        var rise = 0f
        prev?.let { last -> for (k in bin(200f)..bin(5000f)) rise += maxOf(0f, mag[k] - last[k]) }
        prev = mag
        val meanFlux = if (flux.isEmpty()) rise else flux.average().toFloat()
        flux.addLast(rise)
        if (flux.size > framesPerSecond) flux.removeFirst()
        var onset = 0f
        val gap = (framesPerSecond * 0.12f).toLong().coerceAtLeast(1)
        if (flux.size >= framesPerSecond / 2 && rise > meanFlux * 1.6f && rise > 1f && frame - lastOnset >= gap) {
            lastOnset = frame
            onset = ((rise / maxOf(meanFlux, 1e-3f) - 1.6f) / 2f + 0.3f).coerceIn(0.3f, 1f)
        }

        // Beat and tempo: the typical gap between recent beats
        val beat = beats.feed(BeatDetector.bassOf(fft, samplingRateMilliHz))
        if (beat > 0f) {
            beatFrames.addLast(frame)
            if (beatFrames.size > 17) beatFrames.removeFirst()
        }
        val beatMs = tempoMs()

        // Section change: the last 3 s much louder or quieter than the 3 s before
        longLoud.addLast(all)
        val window = framesPerSecond * 3
        if (longLoud.size > window * 2) longLoud.removeFirst()
        var section = false
        if (longLoud.size == window * 2 && frame - lastSection > framesPerSecond * 6) {
            val before = longLoud.take(window).average().toFloat()
            val now = longLoud.drop(window).average().toFloat()
            val ratio = now / maxOf(before, 1e-3f)
            if (ratio > 1.8f || ratio < 0.55f) {
                section = true
                lastSection = frame
            }
        }

        return Frame(loudness, melody, pitch.coerceIn(0f, 1f), onset, beat, beatMs, section)
    }

    /** Median gap between the last beats, if it's a plausible, steady tempo (50–200 BPM). */
    private fun tempoMs(): Long? {
        if (beatFrames.size < 5) return null
        val gaps = beatFrames.zipWithNext { a, b -> b - a }.sorted()
        val median = gaps[gaps.size / 2]
        val ms = median * 1000 / framesPerSecond
        if (ms !in 300..1200) return null
        // Steady enough: most gaps close to the median
        val close = gaps.count { kotlin.math.abs(it - median) <= maxOf(1L, median / 5) }
        return if (close * 3 >= gaps.size * 2) ms else null
    }

    private fun follow(current: Float, target: Float, attack: Float, release: Float) =
        current + (target - current) * (if (target > current) attack else release)
}
