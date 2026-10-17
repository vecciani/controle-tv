package com.controletv

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipInputStream

/**
 * Automatic subtitles: speech recognition on the movie's own audio, offline, with Vosk.
 * Since the text comes from the sound being played, it can't drift out of sync.
 * The English model (~40 MB) is downloaded once, the first time it's turned on.
 */
object AutoCaptions {
    enum class Status { MISSING, DOWNLOADING, LOADING, READY, FAILED }

    @Volatile var status = Status.MISSING
        private set
    @Volatile var progress = 0
        private set
    @Volatile var error: String? = null
        private set

    private var model: Model? = null
    private val listeners = mutableListOf<() -> Unit>()

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    private fun dir(context: Context) = File(context.filesDir, "vosk-en")
    private fun installed(context: Context) = File(dir(context), ".ok").exists()

    fun json(): JSONObject = JSONObject().put("status", status.name.lowercase()).put("progress", progress).put("error", error)

    /** The loaded model, or null (starting the download or load) if it isn't ready yet. */
    @Synchronized
    fun modelOrPrepare(context: Context, onReady: () -> Unit): Model? {
        model?.let { return it }
        listeners += onReady
        if (status == Status.DOWNLOADING || status == Status.LOADING) return null
        Thread {
            try {
                if (!installed(context)) download(context)
                status = Status.LOADING
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val loaded = Model(dir(context).absolutePath)
                val waiting = synchronized(this) {
                    model = loaded
                    status = Status.READY
                    listeners.toList().also { listeners.clear() }
                }
                waiting.forEach { it() }
            } catch (e: Throwable) {
                Log.e("AutoCaptions", "model failed", e)
                synchronized(this) { listeners.clear() }
                error = "${e.javaClass.simpleName}: ${e.message}"
                status = Status.FAILED
            }
        }.apply { name = "autocc-model"; isDaemon = true }.start()
        return null
    }

    private fun download(context: Context) {
        status = Status.DOWNLOADING
        progress = 0
        val zip = File(context.cacheDir, "vosk-en.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: 41_000_000L
        conn.inputStream.use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress = (done * 100 / total).toInt().coerceAtMost(99)
                }
            }
        }
        // The zip has one top folder (vosk-model-small-en-us-0.15/...): drop it
        val target = dir(context).apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val file = File(target, relative)
                if (!file.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
                if (entry.isDirectory) file.mkdirs() else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
        zip.delete()
        File(target, ".ok").writeText("ok")
        progress = 100
    }
}

object Pcm {
    /**
     * Averaging resampler: each output sample is the mean of the input samples it covers.
     * [phase] is the fractional start position carried over from the previous buffer.
     * Returns the samples and the phase for the next buffer.
     */
    fun resample(mono: FloatArray, fromRate: Int, toRate: Int, phase: Double): Pair<ShortArray, Double> {
        val step = fromRate.toDouble() / toRate
        val count = if (mono.size <= phase) 0 else Math.ceil((mono.size - phase) / step).toInt()
        val out = ShortArray(count)
        var pos = phase
        for (i in 0 until count) {
            val from = pos.toInt()
            val to = (pos + step).toInt().coerceAtMost(mono.size).coerceAtLeast(from + 1)
            var sum = 0f
            for (j in from until to) sum += mono[j]
            out[i] = (sum / (to - from)).toInt().coerceIn(-32768, 32767).toShort()
            pos += step
        }
        return out to (pos - mono.size).coerceAtLeast(0.0)
    }
}

/**
 * Receives the decoded audio from the player (a copy of what goes to the speakers), turns it
 * into 16 kHz mono and feeds the recognizer on its own thread. [onText] gets the words so far
 * (final = false) and each finished sentence (final = true).
 */
@OptIn(UnstableApi::class)
class CaptionEngine(model: Model, private val onText: (String, Boolean) -> Unit) : TeeAudioProcessor.AudioBufferSink {
    private val recognizer = Recognizer(model, TARGET_RATE.toFloat())
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var sampleRate = 48_000
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT
    @Volatile private var running = true

    // Fractional read position for resampling, kept across buffers
    private var phase = 0.0

    private val worker = Thread {
        while (running) {
            val chunk = try { queue.take() } catch (e: InterruptedException) { break }
            if (chunk.isEmpty()) { recognizer.reset(); continue } // flush marker (seek / new format)
            try {
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    JSONObject(recognizer.result).optString("text").takeIf { it.isNotBlank() }?.let { onText(it, true) }
                } else {
                    JSONObject(recognizer.partialResult).optString("partial").let { onText(it, false) }
                }
            } catch (e: Exception) {
                Log.w("CaptionEngine", "recognition failed", e)
            }
        }
    }.apply { name = "autocc"; isDaemon = true; start() }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        phase = 0.0
        queue.clear()
        queue.offer(ShortArray(0))
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!running) return
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val mono = when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frames = data.remaining() / (2 * channels)
                FloatArray(frames) { _ ->
                    var sum = 0
                    repeat(channels) { sum += data.short.toInt() }
                    sum.toFloat() / channels
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frames = data.remaining() / (4 * channels)
                FloatArray(frames) { _ ->
                    var sum = 0f
                    repeat(channels) { sum += data.float }
                    sum / channels * 32767f
                }
            }
            else -> return
        }
        val (out, nextPhase) = Pcm.resample(mono, sampleRate, TARGET_RATE, phase)
        phase = nextPhase
        // If recognition falls behind, drop audio instead of lagging further and further
        if (queue.size < MAX_QUEUE) queue.offer(out)
    }

    fun close() {
        running = false
        worker.interrupt()
        // Let a recognition in progress finish before freeing the native recognizer
        worker.join(1_000)
        runCatching { recognizer.close() }
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val MAX_QUEUE = 40
    }
}
