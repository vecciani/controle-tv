package com.controletv

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipInputStream

/**
 * Automatic subtitles: offline speech recognition on the movie's own audio, so the text can't
 * drift out of sync. Uses sherpa-onnx with the Kroko English streaming model (Banafo, CC-BY-SA),
 * which writes punctuation and capitals and copes well with background music. The model (~55 MB)
 * is downloaded once, the first time it's turned on.
 */
object AutoCaptions {
    enum class Status { MISSING, DOWNLOADING, LOADING, READY, FAILED }

    @Volatile var status = Status.MISSING
        private set
    @Volatile var progress = 0
        private set
    @Volatile var error: String? = null
        private set

    private var recognizer: OnlineRecognizer? = null
    private val listeners = mutableListOf<() -> Unit>()

    private const val MODEL_URL = "https://github.com/vecciani/controle-tv/releases/download/modelos/kroko-en.zip"
    private val MODEL_FILES = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    private fun dir(context: Context) = File(context.filesDir, "kroko-en")
    private fun installed(context: Context) = File(dir(context), ".ok").exists()

    fun json(): JSONObject = JSONObject().put("status", status.name.lowercase()).put("progress", progress).put("error", error)

    /** The loaded recognizer, or null (starting the download or load) if it isn't ready yet. */
    @Synchronized
    fun recognizerOrPrepare(context: Context, onReady: () -> Unit): OnlineRecognizer? {
        recognizer?.let { return it }
        listeners += onReady
        if (status == Status.DOWNLOADING || status == Status.LOADING) return null
        Thread {
            try {
                if (!installed(context)) download(context)
                status = Status.LOADING
                val loaded = load(context)
                val waiting = synchronized(this) {
                    recognizer = loaded
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

    private fun load(context: Context): OnlineRecognizer {
        val d = dir(context).absolutePath
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(16_000, 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$d/encoder.onnx", decoder = "$d/decoder.onnx", joiner = "$d/joiner.onnx",
                ),
                tokens = "$d/tokens.txt",
                // Leave the other cores to video decoding and the UI
                numThreads = 2,
                provider = "cpu",
            ),
            // A sentence ends after a short pause in speech, or when it gets long
            endpointConfig = EndpointConfig(
                EndpointRule(false, 2.4f, 0f),
                EndpointRule(true, 0.8f, 0f),
                EndpointRule(false, 0f, 12f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(null, config)
    }

    private fun download(context: Context) {
        status = Status.DOWNLOADING
        progress = 0
        val zip = File(context.cacheDir, "kroko-en.zip")
        var current = URL(MODEL_URL)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode in 300..399 && hops++ < 5) {
                current = URL(current, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            break
        }
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode} ao baixar o modelo")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: 55_000_000L
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
        val target = dir(context).apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.isEmpty()) continue
                File(target, name).outputStream().use { input.copyTo(it) }
            }
        }
        zip.delete()
        MODEL_FILES.forEach { if (!File(target, it).exists()) throw IllegalStateException("Modelo incompleto: falta $it") }
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
class CaptionEngine(private val recognizer: OnlineRecognizer, private val onText: (String, Boolean) -> Unit) :
    TeeAudioProcessor.AudioBufferSink {
    private var stream = recognizer.createStream("")
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var sampleRate = 48_000
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT
    @Volatile private var running = true

    // Fractional read position for resampling, kept across buffers
    private var phase = 0.0

    private val worker = Thread {
        var lastPartial = ""
        while (running) {
            val chunk = try { queue.take() } catch (e: InterruptedException) { break }
            try {
                if (chunk.isEmpty()) {
                    // Flush marker (seek or new audio format): start a fresh utterance
                    recognizer.reset(stream)
                    lastPartial = ""
                    onText("", false)
                    continue
                }
                stream.acceptWaveform(FloatArray(chunk.size) { chunk[it] / 32768f }, TARGET_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (recognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) onText(text, true)
                    recognizer.reset(stream)
                    lastPartial = ""
                } else if (text != lastPartial) {
                    lastPartial = text
                    onText(text, false)
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
        // Let a recognition in progress finish before freeing the native stream
        worker.join(1_000)
        runCatching { stream.release() }
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val MAX_QUEUE = 40
    }
}
