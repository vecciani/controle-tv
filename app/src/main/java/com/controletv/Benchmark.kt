package com.controletv

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.StatFs
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * "Aguenta rodar filmes?": measures the TV (or projector) the way films need it. The hardware
 * (chip, cores, memory, screen), which video formats the chip decodes in hardware and up to
 * what size, the internet speed, the processor, and a real decode of short 1080p test clips
 * (Big Buck Bunny, CC-BY, H.264 and H.265), then a verdict per kind of film.
 * Takes about half a minute; run off the main thread.
 */
object Benchmark {
    @Volatile var running = false
        private set
    @Volatile var step = ""
        private set
    @Volatile var last: JSONObject? = null
        private set

    private const val CLIP = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/%s/1080/Big_Buck_Bunny_1080_10s_10MB.mp4"
    private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=25000000"

    @Synchronized
    fun run(context: Context): JSONObject {
        running = true
        try {
            val out = JSONObject()
            step = "Aparelho"; out.put("device", device(context))
            step = "Formatos de vídeo"; val codecs = codecs(); out.put("codecs", codecs)
            step = "Processador"; out.put("cpu", cpuScore())
            step = "Internet"; out.put("internetMbps", runCatching { speed() }.getOrNull())
            step = "Decodificando H.264"; out.put("decodeH264", runCatching { decodeTest(context, "h264") }.getOrElse { JSONObject().put("error", it.message) })
            step = "Decodificando H.265"; out.put("decodeH265", runCatching { decodeTest(context, "h265") }.getOrElse { JSONObject().put("error", it.message) })
            out.put("verdict", verdict(out))
            out.put("at", System.currentTimeMillis())
            last = out
            return out
        } finally {
            running = false
            step = ""
        }
    }

    private fun device(context: Context): JSONObject {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }.getOrNull()
        }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else
            runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim() }.getOrNull() ?: Build.HARDWARE
        val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("chip", soc)
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("maxMHz", freqs.maxOrNull()?.takeIf { it > 0 })
            .put("ramMB", mem.totalMem / 1_048_576)
            .put("ramFreeMB", mem.availMem / 1_048_576)
            .put("lowRam", am.isLowRamDevice)
            .put("screen", mode?.let { "${it.physicalWidth}x${it.physicalHeight} ${it.refreshRate.toInt()}Hz" })
            .put("screenModes", JSONArray(display?.supportedModes.orEmpty().map { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}" }.distinct()))
            .put("hdr", JSONArray(display?.hdrCapabilities?.supportedHdrTypes?.map { when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "$it" } }.orEmpty()))
            .put("freeStorageMB", free / 1_048_576)
    }

    private val FORMATS = listOf(
        "H.264" to MediaFormat.MIMETYPE_VIDEO_AVC,
        "H.265 (HEVC)" to MediaFormat.MIMETYPE_VIDEO_HEVC,
        "VP9" to MediaFormat.MIMETYPE_VIDEO_VP9,
        "AV1" to "video/av01",
        "MPEG-2" to MediaFormat.MIMETYPE_VIDEO_MPEG2,
    )

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") && !info.name.contains("sw", ignoreCase = true)

    /** Per format: hardware decoder or not, and which sizes and frame rates it takes. */
    private fun codecs(): JSONArray {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        val out = JSONArray()
        for ((label, mime) in FORMATS) {
            val decoders = all.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            val best = decoders.firstOrNull { isHardware(it) } ?: decoders.firstOrNull()
            val entry = JSONObject().put("format", label).put("hardware", best != null && isHardware(best)).put("decoder", best?.name)
            if (best != null) {
                val caps = best.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities
                fun takes(w: Int, h: Int, fps: Double) = runCatching { video.areSizeAndRateSupported(w, h, fps) }.getOrDefault(false)
                entry.put("1080p30", takes(1920, 1080, 30.0))
                    .put("1080p60", takes(1920, 1080, 60.0))
                    .put("4k30", takes(3840, 2160, 30.0))
                    .put("4k60", takes(3840, 2160, 60.0))
                    .put("maxSize", "${video.supportedWidths.upper}x${video.supportedHeights.upper}")
                    .put("maxMbps", video.bitrateRange.upper / 1_000_000)
                // The vendor's own measured speed for 1080p, when it publishes one
                runCatching { video.getAchievableFrameRatesFor(1920, 1080) }.getOrNull()?.let { entry.put("measured1080fps", it.upper.toInt()) }
                if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    entry.put("10bit", caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 })
                }
            }
            out.put(entry)
        }
        return out
    }

    /** SHA-256 over and over for 2 seconds, on one core and on all: hashes per second (thousands). */
    private fun cpuScore(): JSONObject {
        fun hashes(ms: Long): Long {
            val md = MessageDigest.getInstance("SHA-256")
            val data = ByteArray(1024) { it.toByte() }
            var n = 0L
            val end = System.nanoTime() + ms * 1_000_000
            while (System.nanoTime() < end) { md.update(data); md.digest(); n++ }
            return n
        }
        val single = hashes(2000) / 2
        val cores = Runtime.getRuntime().availableProcessors()
        val counts = LongArray(cores)
        (0 until cores).map { i -> Thread { counts[i] = hashes(2000) / 2 } }.onEach { it.start() }.forEach { it.join() }
        return JSONObject().put("singleK", single / 1000).put("multiK", counts.sum() / 1000)
    }

    /** Download speed for up to 8 seconds, in megabits per second. */
    private fun speed(): Double {
        val conn = URL(SPEED_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        try {
            val buf = ByteArray(64 * 1024)
            val input = conn.inputStream
            val begin = System.nanoTime()
            // The first second (connection warming up) doesn't count
            val warm = begin + 1_000_000_000L
            val end = begin + 9_000_000_000L
            var counted = 0L
            var countFrom = 0L
            while (System.nanoTime() < end) {
                val r = input.read(buf)
                if (r < 0) break
                val now = System.nanoTime()
                if (now >= warm) {
                    if (countFrom == 0L) countFrom = now
                    counted += r
                }
            }
            val secs = (System.nanoTime() - countFrom) / 1e9
            if (countFrom == 0L || secs <= 0.2) throw IllegalStateException("download curto demais para medir")
            return Math.round(counted * 8 / secs / 1e6 * 10) / 10.0
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Decodes a 10-second 1080p clip as fast as the chip can and counts the frames per second.
     * A film needs 24–30: well above that leaves room for subtitles, the network and the app.
     */
    private fun decodeTest(context: Context, codec: String): JSONObject {
        val file = File(context.cacheDir, "bench_$codec.mp4")
        if (!file.exists() || file.length() < 1_000_000) {
            val tmp = File(context.cacheDir, "bench_$codec.part")
            val conn = URL(CLIP.format(codec)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            tmp.renameTo(file)
        }
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(mime)
        val name = decoder.name
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var inputDone = false
        val started = System.nanoTime()
        val limit = started + 30_000_000_000L
        try {
            while (System.nanoTime() < limit) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    if (info.size > 0) frames++
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
        val secs = (System.nanoTime() - started) / 1e9
        return JSONObject().put("decoder", name).put("frames", frames).put("fps", Math.round(frames / secs * 10) / 10.0)
    }

    /** Plain answers per kind of film, from what was measured. */
    private fun verdict(r: JSONObject): JSONArray {
        val codecs = (0 until r.getJSONArray("codecs").length()).map { r.getJSONArray("codecs").getJSONObject(it) }
        fun codec(label: String) = codecs.firstOrNull { it.getString("format").startsWith(label) }
        val avc = codec("H.264")
        val hevc = codec("H.265")
        val net = r.optDouble("internetMbps", Double.NaN)
        val fps264 = r.optJSONObject("decodeH264")?.optDouble("fps", 0.0) ?: 0.0
        val fps265 = r.optJSONObject("decodeH265")?.optDouble("fps", 0.0) ?: 0.0
        val ram = r.getJSONObject("device").optLong("ramMB")
        val screen = r.getJSONObject("device").optString("screen")
        val out = JSONArray()
        fun line(ok: String, title: String, why: String) = out.put(JSONObject().put("level", ok).put("title", title).put("why", why))
        fun netOk(mbps: Double) = net.isNaN() || net >= mbps

        line(
            when { avc?.optBoolean("hardware") == true && fps264 >= 40 && netOk(8.0) -> "ok"; fps264 >= 26 -> "warn"; else -> "bad" },
            "Filmes 1080p comuns (H.264, streaming)",
            "decodifica a ${fps264.toInt()} quadros/s (filme precisa de 24–30)" + if (!net.isNaN()) " · internet ${net} Mbps (precisa de ~8)" else "",
        )
        val hevcHw = hevc?.optBoolean("hardware") == true
        line(
            when {
                hevcHw && fps265 >= 40 && netOk(6.0) -> "ok"
                fps265 >= 26 -> "warn" // plays, by software or just in time
                else -> "bad"
            },
            "Filmes 1080p em H.265/HEVC (arquivos menores)",
            when {
                hevcHw -> "o chip decodifica H.265 a ${fps265.toInt()} quadros/s"
                fps265 >= 26 -> "sem H.265 no chip, mas o processador dá conta (${fps265.toInt()} quadros/s): roda, esquentando mais"
                else -> "sem H.265 no chip e o processador não dá conta (${fps265.toInt()} quadros/s): prefira H.264"
            },
        )
        line(
            when { !netOk(25.0) -> "bad"; net.isNaN() -> "warn"; fps264 >= 50 && ram >= 1500 -> "ok"; fps264 >= 30 -> "warn"; else -> "bad" },
            "1080p em alta qualidade (remux / Blu-ray, 25–40 Mbps)",
            (if (!net.isNaN()) "internet ${net} Mbps" else "internet não medida") + " · memória ${ram} MB",
        )
        line(
            when { hevc?.optBoolean("4k30") == true && hevc.optBoolean("10bit") && netOk(25.0) -> "warn"; else -> "bad" },
            "4K / HDR",
            if (hevc?.optBoolean("4k30") == true) "o chip aceita 4K, mas a tela é $screen: não vale a pena, prefira 1080p" else "o chip não decodifica 4K: escolha 1080p",
        )
        if (ram in 1..1200) line("warn", "Memória pouca (${ram} MB)", "feche outros apps antes de filmes pesados; prefira o player da TV")
        return out
    }
}
