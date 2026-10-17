package com.controletv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full-screen player for video links sent from the phone ("Tocar na TV").
 *
 * Before playing, it peeks at the first bytes of the link to tell HLS, DASH, plain video
 * and web pages apart, since many links don't say what they are. If our player still
 * can't handle it, the link is handed to another player installed on the TV (VLC, MX…).
 * Media keys from the remote or the phone control it: play/pause, and rewind/forward seek 10 s.
 *
 * Subtitles: files (sent by the video app or loaded from the phone) are drawn by us on top of
 * the video, so they can be shifted in time from the phone to fix bad sync. Subtitles inside the
 * stream itself are drawn by the player. The chosen language turns on by itself.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    private class Request(
        val url: String, val title: String?, val type: String?, val subtitles: List<String>,
        val subtitleNames: List<String>, val position: Long, val headers: Map<String, String>,
    )

    private class Sub(val label: String, val language: String?, val captions: List<Caption>)

    private var current: Request? = null
    private var currentUrl: String? = null
    private var currentMime: String? = null

    // Automatic subtitles (speech recognition on the movie's audio)
    private var autoActive = false
    private var engine: CaptionEngine? = null
    private var autoPartial = ""
    private var autoFinal = ""
    private var autoFinalAt = 0L

    // Subtitle files we draw ourselves, which one is on (-1 = none) and how far it's shifted
    private val subtitleFiles = mutableListOf<Sub>()
    private var activeSub = -1
    private var offsetMs = 0L
    private lateinit var captionView: TextView
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            drawCaption()
            ticker.postDelayed(this, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this).apply {
            keepScreenOn = true
            setShowSubtitleButton(true)
            // White text with a dark outline and translucent box: readable over any scene
            subtitleView?.setStyle(
                CaptionStyleCompat(
                    0xFFFFFFFF.toInt(), 0x80000000.toInt(), 0x00000000,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), null,
                )
            )
            subtitleView?.setFractionalTextSize(0.06f)
        }
        captionView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.displayMetrics.heightPixels * 0.055f)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 6, 24, 8)
            visibility = android.view.View.GONE
        }
        val root = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                captionView,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    .apply { bottomMargin = (resources.displayMetrics.heightPixels * 0.08f).toInt() }
            )
        }
        setContentView(root)
        ticker.post(tick)
        handle(intent)
    }

    /** Shows the caption for the current moment, shifted by [offsetMs] (positive = later). */
    private fun drawCaption() {
        if (autoActive) return drawAutoCaption()
        val p = player
        val sub = subtitleFiles.getOrNull(activeSub)
        if (p == null || sub == null) {
            captionView.visibility = android.view.View.GONE
            return
        }
        val at = p.currentPosition - offsetMs
        val captions = sub.captions
        // Binary search for the last caption starting at or before "at"
        var lo = 0
        var hi = captions.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (captions[mid].start <= at) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        // Overlapping captions: show every one still on screen
        val text = buildString {
            var i = found
            while (i >= 0 && i > found - 4) {
                val c = captions[i]
                if (at < c.end) { if (isNotEmpty()) insert(0, "\n"); insert(0, c.text) }
                i--
            }
        }
        if (text.isEmpty()) {
            captionView.visibility = android.view.View.GONE
        } else {
            if (captionView.text != text) captionView.text = text
            captionView.visibility = android.view.View.VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val headers = HashMap<String, String>()
        intent.getBundleExtra(EXTRA_HEADERS)?.let { bundle ->
            bundle.keySet().forEach { key -> bundle.getString(key)?.let { headers[key] = it } }
        }
        val request = Request(
            url, intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_TYPE),
            intent.getStringArrayListExtra(EXTRA_SUBTITLES).orEmpty(),
            intent.getStringArrayListExtra(EXTRA_SUBTITLE_NAMES).orEmpty(),
            intent.getLongExtra(EXTRA_POSITION, 0), headers,
        )
        request.title?.let { toast(it) }
        Thread {
            val subs = loadSubtitles(request)
            val probe = sniff(request)
            lastPlay = probe.summary + if (subs.isNotEmpty()) " · ${subs.size} legenda(s)" else " · sem legenda externa"
            runOnUiThread {
                if (probe.kind == Kind.PAGE) {
                    toast("O link é uma página da web, não um vídeo. Tentando outro player…")
                    if (!openInOtherPlayer(request)) toast("Nenhum outro player na TV conseguiu abrir")
                } else {
                    subtitleFiles.clear()
                    subtitleFiles += subs
                    offsetMs = 0
                    val wantsAuto = subtitleLanguage(this) == "auto"
                    activeSub = if (wantsAuto) -1 else defaultSubtitle()
                    autoActive = false
                    start(request, probe.finalUrl, probe.kind.mime ?: mimeFromHints(request))
                    if (wantsAuto) enableAuto()
                }
            }
        }.start()
    }

    /** Words being recognized now, or the last sentence for a few seconds after it ends. */
    private fun drawAutoCaption() {
        val recent = if (System.currentTimeMillis() - autoFinalAt < 3_500) autoFinal else ""
        var text = autoPartial.ifBlank { recent }
        // Keep it to about two lines: the most recent words
        if (text.length > 84) text = "…" + text.takeLast(84).substringAfter(' ')
        if (text.isBlank()) {
            captionView.visibility = android.view.View.GONE
        } else {
            if (captionView.text != text) captionView.text = text
            captionView.visibility = android.view.View.VISIBLE
        }
    }

    /** Turns automatic subtitles on for this video, downloading/loading the model if needed. */
    private fun enableAuto() {
        autoActive = true
        activeSub = -1
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        val model = AutoCaptions.recognizerOrPrepare(this) {
            runOnUiThread { if (autoActive && !isDestroyed) restartAt(player?.currentPosition ?: 0) }
        }
        if (model == null) {
            toast(if (AutoCaptions.status == AutoCaptions.Status.DOWNLOADING) "Baixando a legenda automática (55 MB, só na primeira vez)…" else "Preparando a legenda automática…")
        } else if (engine == null) {
            restartAt(player?.currentPosition ?: 0)
        }
    }

    private fun disableAuto() {
        if (!autoActive) return
        autoActive = false
        autoPartial = ""
        autoFinal = ""
        if (engine != null) restartAt(player?.currentPosition ?: 0)
    }

    /** Rebuilds the player at the same spot (needed to attach or detach the audio tap). */
    private fun restartAt(position: Long) {
        val request = current ?: return
        val url = currentUrl ?: return
        start(request, url, currentMime, retriedAsHls = true, position = position)
    }

    /** Downloads (or reads) every subtitle the video app sent and parses it. */
    private fun loadSubtitles(request: Request): List<Sub> =
        request.subtitles.mapIndexedNotNull { i, sub ->
            try {
                val file = if (sub.startsWith("http")) Subtitles.download(this, sub, request.headers) else File(Uri.parse(sub).path!!)
                val captions = SubtitleParser.parse(file.readText())
                if (captions.isEmpty()) return@mapIndexedNotNull null
                val name = request.subtitleNames.getOrNull(i).orEmpty()
                val language = Subtitles.languageOf("$name $sub")
                Sub(name.ifEmpty { Subtitles.labelFor(language, i) }, language, captions)
            } catch (e: Exception) {
                null
            }
        }

    private fun start(
        request: Request, url: String, mime: String?, retriedAsHls: Boolean = false, position: Long = request.position,
    ) {
        current = request
        currentUrl = url
        currentMime = mime
        val headers = HashMap(request.headers)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers.remove("User-Agent") ?: headers.remove("user-agent") ?: USER_AGENT)
            .setDefaultRequestProperties(headers)

        val item = MediaItem.Builder()
            .setUri(url)
            .apply { mime?.let { setMimeType(it) } }
            .build()

        player?.release()
        engine?.close()
        engine = null
        val model = if (autoActive) AutoCaptions.recognizerOrPrepare(this) {} else null
        val renderers = if (model == null) DefaultRenderersFactory(this) else {
            val tap = CaptionEngine(model) { text, final ->
                runOnUiThread {
                    if (final) { autoFinal = text; autoFinalAt = System.currentTimeMillis(); autoPartial = "" }
                    else autoPartial = text
                }
            }
            engine = tap
            // Decode to plain stereo PCM (no passthrough to the TV) so we can listen to it
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setAudioProcessors(arrayOf(TeeAudioProcessor(tap)))
                        .build()
            }
        }
        player = ExoPlayer.Builder(this, renderers)
            // Start streams at high quality instead of climbing up from the lowest one
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(INITIAL_BITRATE).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { p ->
                view.player = p
                // Subtitles inside the stream turn on by themselves in the chosen language,
                // unless one of our subtitle files is already showing
                val language = subtitleLanguage(this)
                p.trackSelectionParameters = withQuality(p.trackSelectionParameters.buildUpon(), quality(this))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, language == "off" || activeSub >= 0 || autoActive)
                    .setPreferredTextLanguages(*Subtitles.codesFor(language))
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
                p.setMediaItem(item, position)
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        lastPlay = "${lastPlay.orEmpty()} | erro do player: ${error.errorCodeName}"
                        when {
                            // Links without an extension are often HLS streams in disguise
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                                !retriedAsHls && mime != MimeTypes.APPLICATION_M3U8 ->
                                start(request, url, MimeTypes.APPLICATION_M3U8, retriedAsHls = true)

                            openInOtherPlayer(request) ->
                                toast("Nosso player não abriu (${error.errorCodeName}). Abrindo em outro player da TV…")

                            else -> toast("Não deu para tocar: ${error.errorCodeName}. Instale o VLC na TV para mais formatos.")
                        }
                    }
                })
                p.prepare()
                p.play()
            }
    }

    /** Hands the link to another video player on the TV, passing headers the MX Player way. */
    private fun openInOtherPlayer(request: Request): Boolean {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(request.url), "video/*")
        val other = packageManager.queryIntentActivities(view, 0)
            .firstOrNull { it.activityInfo.packageName != packageName } ?: return false
        view.setClassName(other.activityInfo.packageName, other.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        request.title?.let { view.putExtra("title", it) }
        request.subtitles.firstOrNull { it.startsWith("http") }?.let { view.putExtra("subtitles_location", it) }
        if (request.headers.isNotEmpty()) {
            view.putExtra("headers", request.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        }
        return try {
            startActivity(view)
            finish()
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Sniffing ----------

    private enum class Kind(val mime: String?) {
        HLS(MimeTypes.APPLICATION_M3U8), DASH(MimeTypes.APPLICATION_MPD), PAGE(null), VIDEO(null), UNKNOWN(null)
    }

    private class Probe(val kind: Kind, val finalUrl: String, val summary: String)

    /** Reads the first bytes of the link (with the same headers the player will send). */
    private fun sniff(request: Request): Probe {
        if (request.url.startsWith("file:")) {
            val name = request.url.substringAfterLast('/')
            val kind = if (name.endsWith(".m3u8", true)) Kind.HLS else Kind.VIDEO
            return Probe(kind, request.url, "Arquivo da TV: $name")
        }
        return try {
            var current = URL(request.url)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = current.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Range", "bytes=0-4095")
                if (request.headers.keys.none { it.equals("User-Agent", true) }) conn.setRequestProperty("User-Agent", USER_AGENT)
                request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    current = URL(current, conn.getHeaderField("Location"))
                    conn.disconnect()
                    continue
                }
                break
            }
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty()
            val head = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.use { input -> ByteArray(4096).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) } }
                ?: ByteArray(0)
            conn.disconnect()

            val text = String(head, Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r', '\t')
            val kind = when {
                text.startsWith("#EXTM3U") || "mpegurl" in contentType.lowercase() -> Kind.HLS
                "<MPD" in text || "dash+xml" in contentType.lowercase() -> Kind.DASH
                text.startsWith("<") && Regex("(?i)<(!doctype html|html|head|body)").containsMatchIn(text) -> Kind.PAGE
                contentType.startsWith("video/") || contentType.startsWith("audio/") -> Kind.VIDEO
                else -> Kind.UNKNOWN
            }
            val preview = text.take(60).replace(Regex("[^\\x20-\\x7E]"), "·")
            Probe(kind, current.toString(), "HTTP $code · $contentType · ${kind.name} · \"$preview\"")
        } catch (e: Exception) {
            Probe(Kind.UNKNOWN, request.url, "Falha ao examinar o link: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun mimeFromHints(request: Request): String? {
        val path = Uri.parse(request.url).path.orEmpty().lowercase()
        val type = request.type?.lowercase().orEmpty()
        return when {
            "mpegurl" in type || path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            "dash" in type || path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    // ---------- Controls from the phone ----------

    private fun textTracks(): List<Pair<Tracks.Group, Int>> {
        val p = player ?: return emptyList()
        return p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
    }

    private fun stateJson(): JSONObject {
        val p = player
        val tracks = JSONArray()
        tracks.put(JSONObject().put("id", "auto").put("label", "Automática (CC, inglês)").put("selected", autoActive).put("sync", false))
        subtitleFiles.forEachIndexed { i, sub ->
            tracks.put(JSONObject().put("id", "e$i").put("label", sub.label).put("selected", activeSub == i).put("sync", true))
        }
        val streamOff = p?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        textTracks().forEachIndexed { i, (group, index) ->
            val format = group.getTrackFormat(index)
            tracks.put(
                JSONObject().put("id", "i$i")
                    .put("label", (format.label ?: format.language?.let { Subtitles.labelFor(Subtitles.languageOf(it), i) } ?: "Legenda ${i + 1}") + " (do vídeo)")
                    .put("selected", !autoActive && activeSub < 0 && !streamOff && group.isTrackSelected(index))
                    .put("sync", false)
            )
        }
        return JSONObject()
            .put("title", current?.title)
            .put("playing", p?.isPlaying == true)
            .put("position", p?.currentPosition ?: 0)
            .put("duration", p?.duration?.takeIf { it > 0 } ?: 0)
            .put("subtitles", tracks)
            .put("offsetMs", offsetMs)
            .put("canSync", activeSub >= 0 && !autoActive)
            .put("autoCc", AutoCaptions.json())
            .put("quality", quality(this))
            .put("currentHeight", p?.videoSize?.height ?: 0)
            .put("heights", JSONArray(videoTracks().map { it.first.getTrackFormat(it.second).height }.filter { it > 0 }.distinct()))
    }

    // ---------- Quality and seeking ----------

    /** Video tracks of the current stream, best first. */
    private fun videoTracks(): List<Pair<Tracks.Group, Int>> {
        val p = player ?: return emptyList()
        return p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
            .sortedWith(compareByDescending<Pair<Tracks.Group, Int>> { it.first.getTrackFormat(it.second).height }
                .thenByDescending { it.first.getTrackFormat(it.second).bitrate })
    }

    /**
     * Applies a quality policy: "auto" (adapts to the network), "max" (always the best the TV
     * can play) or "h<height>" (the best up to that height, e.g. "h720").
     */
    private fun withQuality(builder: androidx.media3.common.TrackSelectionParameters.Builder, policy: String) =
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO).apply {
            val cap = policy.removePrefix("h").toIntOrNull()
            when {
                policy == "max" -> setForceHighestSupportedBitrate(true).clearVideoSizeConstraints()
                cap != null -> setForceHighestSupportedBitrate(true).setMaxVideoSize(Int.MAX_VALUE, cap)
                else -> setForceHighestSupportedBitrate(false).clearVideoSizeConstraints()
            }
        }

    private fun applyQuality(policy: String) {
        setQuality(this, policy)
        val p = player ?: return
        p.trackSelectionParameters = withQuality(p.trackSelectionParameters.buildUpon(), policy).build()
    }

    private fun seek(deltaMs: Long?, positionMs: Long?) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = positionMs ?: (p.currentPosition + (deltaMs ?: 0))
        p.seekTo(target.coerceIn(0, duration))
        view.showController()
    }

    /** "e<n>" = one of our subtitle files, "i<n>" = a subtitle inside the stream, "off" = none. */
    private fun selectText(id: String) {
        val p = player ?: return
        if (id == "auto") return enableAuto()
        disableAuto()
        val builder = p.trackSelectionParameters.buildUpon()
        when {
            id.startsWith("e") -> {
                activeSub = id.drop(1).toIntOrNull()?.takeIf { it in subtitleFiles.indices } ?: return
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            id.startsWith("i") -> {
                val (group, index) = textTracks().getOrNull(id.drop(1).toIntOrNull() ?: -1) ?: return
                activeSub = -1
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
            else -> {
                activeSub = -1
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
        }
        p.trackSelectionParameters = builder.build()
        drawCaption()
    }

    /** The subtitle file shown by default: one in the chosen language, else the first one. */
    private fun defaultSubtitle(): Int {
        val language = subtitleLanguage(this)
        if (language == "off" || subtitleFiles.isEmpty()) return -1
        return subtitleFiles.indexOfFirst { it.language == language }.takeIf { it >= 0 } ?: 0
    }

    /** A subtitle loaded from the phone goes on right away, with no reload of the video. */
    private fun addSubtitle(file: File, label: String): Boolean {
        val captions = SubtitleParser.parse(file.readText())
        if (captions.isEmpty()) return false
        subtitleFiles += Sub(label, Subtitles.languageOf(label), captions)
        offsetMs = 0
        selectText("e${subtitleFiles.lastIndex}")
        return true
    }

    private fun shift(deltaMs: Long?, reset: Boolean) {
        offsetMs = if (reset) 0 else (offsetMs + (deltaMs ?: 0)).coerceIn(-600_000, 600_000)
        drawCaption()
    }

    // ---------- Keys ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { if (p.isPlaying) p.pause() else p.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekBack(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekForward(); return true }
        }
        // D-pad shows the controls first, then navigates them
        if (!view.isControllerFullyVisible && event.keyCode in DPAD_KEYS) {
            view.showController()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        engine?.close()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        /** What the last link turned out to be, shown in the phone's settings for diagnosis. */
        @Volatile
        var lastPlay: String? = null
            private set

        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_SUBTITLES = "subtitles"
        private const val EXTRA_SUBTITLE_NAMES = "subtitleNames"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_HEADERS = "headers"
        private const val INITIAL_BITRATE = 8_000_000L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9) ControleTV"
        private val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )

        /** Video players installed on the TV (other than ours), as package to label. */
        fun installedPlayers(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://x/v.mp4"), "video/*")
            return pm.queryIntentActivities(probe, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != context.packageName }
                .distinctBy { it.first }
        }

        /** Package of the player chosen in settings, or null for ours. */
        fun preferred(context: Context): String? =
            context.getSharedPreferences("player", Context.MODE_PRIVATE).getString("package", null)

        fun setPreferred(context: Context, pkg: String?) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("package", pkg).apply()
        }

        private var instance: WeakReference<PlayerActivity>? = null

        /** Runs on the player's thread and waits for the result; null when nothing is playing. */
        private fun <T> onPlayer(block: PlayerActivity.() -> T): T? {
            val activity = instance?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return null
            var result: T? = null
            val done = CountDownLatch(1)
            activity.runOnUiThread {
                try { result = activity.block() } finally { done.countDown() }
            }
            done.await(5, TimeUnit.SECONDS)
            return result
        }

        /** "en" (default), "pt", "es", "auto" (automatic subtitles) or "off". */
        fun subtitleLanguage(context: Context): String =
            context.getSharedPreferences("player", Context.MODE_PRIVATE).getString("subtitleLanguage", "en")!!

        fun setSubtitleLanguage(context: Context, language: String) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("subtitleLanguage", language).apply()
        }

        fun state(): JSONObject? = onPlayer { stateJson() }

        fun selectSubtitle(id: String) = onPlayer { selectText(id) } != null

        /** Null when nothing is playing, false when the file had no readable captions. */
        fun addSubtitle(file: File, label: String): Boolean? = onPlayer { addSubtitle(file, label) }

        fun shiftSubtitle(deltaMs: Long?, reset: Boolean) = onPlayer { shift(deltaMs, reset) } != null

        fun seekTo(deltaMs: Long?, positionMs: Long?) = onPlayer { seek(deltaMs, positionMs) } != null

        /** Saves the quality policy and applies it to the video playing now, if any. */
        fun changeQuality(context: Context, policy: String) {
            if (onPlayer { applyQuality(policy) } == null) setQuality(context, policy)
        }

        fun quality(context: Context): String =
            context.getSharedPreferences("player", Context.MODE_PRIVATE).getString("quality", "auto")!!

        private fun setQuality(context: Context, policy: String) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("quality", policy).apply()
        }

        fun open(
            context: Context, url: String, title: String?, type: String?,
            subtitles: List<String>, subtitleNames: List<String>, position: Long, headers: Map<String, String>,
        ) {
            preferred(context)?.let { pkg ->
                val other = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(url), "video/*")
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title", title)
                    .putExtra("position", position.toInt())
                // External players get one subtitle: the chosen language if we can tell, else the first
                val wanted = subtitleLanguage(context)
                subtitles.withIndex().filter { it.value.startsWith("http") }
                    .maxByOrNull { if (Subtitles.languageOf("${subtitleNames.getOrNull(it.index).orEmpty()} ${it.value}") == wanted) 1 else 0 }
                    ?.let { other.putExtra("subtitles_location", it.value) }
                if (headers.isNotEmpty()) other.putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
                try {
                    context.startActivity(other)
                    lastPlay = "Aberto no player escolhido ($pkg)"
                    return
                } catch (e: Exception) {
                    // Player was uninstalled: fall back to ours
                    setPreferred(context, null)
                }
            }
            val bundle = Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } }
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TYPE, type)
                    .putStringArrayListExtra(EXTRA_SUBTITLES, ArrayList(subtitles))
                    .putStringArrayListExtra(EXTRA_SUBTITLE_NAMES, ArrayList(subtitleNames))
                    .putExtra(EXTRA_POSITION, position)
                    .putExtra(EXTRA_HEADERS, bundle)
            )
        }
    }
}
