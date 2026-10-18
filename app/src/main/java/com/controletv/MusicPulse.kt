package com.controletv

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer

/**
 * Listens to what the TV is playing (Android's Visualizer on the output mix: it reads the
 * sound going out, never the microphone) and reports how the song moves (loudness, melody,
 * notes, beat, tempo, sections; see [MusicAnalyzer]) for the screensaver's effects.
 * Needs RECORD_AUDIO, which the phone's ADB connection grants.
 * If the TV doesn't allow it, [start] returns false and nothing moves with the music.
 */
class MusicPulse(private val onFrame: (MusicAnalyzer.Frame) -> Unit) {
    private var visualizer: Visualizer? = null

    fun start(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val rate = Visualizer.getMaxCaptureRate() // in mHz, usually 20 per second
            val analyzer = MusicAnalyzer((rate / 1000).coerceAtLeast(5))
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) {}
                override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) {
                    onFrame(analyzer.feed(fft, samplingRate))
                }
            }, rate, false, true)
            v.enabled = true
            visualizer = v
        }.isSuccess
    }

    fun stop() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
    }
}
