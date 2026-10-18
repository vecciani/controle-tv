package com.controletv

/**
 * Finds beats in the bass of the music, from one energy value per audio frame (~20 per second).
 * A beat is a frame clearly louder than the last second's average, not too soon after the last one.
 * Plain logic, no Android, so it can be tested.
 */
class BeatDetector(private val framesPerSecond: Int) {
    private val history = ArrayDeque<Float>()
    private var framesSinceBeat = Int.MAX_VALUE / 2
    private var peak = 1e-6f

    /** Smoothed loudness of the bass, 0 to 1, for effects that follow the volume. */
    var level = 0f
        private set

    /** Feeds one frame's bass energy. Returns the beat strength (0 = no beat, 0.3 to 1 = beat). */
    fun feed(bass: Float): Float {
        framesSinceBeat++
        val average = if (history.isEmpty()) bass else history.average().toFloat()
        val warmedUp = history.size >= framesPerSecond / 2
        history.addLast(bass)
        if (history.size > framesPerSecond) history.removeFirst()

        peak = maxOf(bass, peak * 0.995f)
        level = level * 0.6f + (bass / peak).coerceIn(0f, 1f) * 0.4f

        val minGap = (framesPerSecond * 0.28f).toInt().coerceAtLeast(1)
        val loudEnough = bass > peak * 0.15f
        val isBeat = warmedUp && loudEnough && bass > average * THRESHOLD && framesSinceBeat >= minGap
        if (!isBeat) return 0f
        framesSinceBeat = 0
        return ((bass / maxOf(average, 1e-6f) - THRESHOLD) / 1.5f + 0.3f).coerceIn(0.3f, 1f)
    }

    companion object {
        private const val THRESHOLD = 1.35f

        /**
         * Bass energy (about 30–150 Hz) of one FFT from Android's Visualizer: bytes are
         * [DC, Nyquist, re1, im1, re2, im2…]; [samplingRateMilliHz] as the Visualizer reports it.
         */
        fun bassOf(fft: ByteArray, samplingRateMilliHz: Int): Float {
            val bins = fft.size / 2
            if (bins < 2 || samplingRateMilliHz <= 0) return 0f
            val hzPerBin = samplingRateMilliHz / 1000f / fft.size
            val last = (150f / hzPerBin).toInt().coerceIn(1, bins - 1)
            var sum = 0f
            for (k in 1..last) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += re * re + im * im
            }
            return sum / last
        }
    }
}
