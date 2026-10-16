package com.controletv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
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
 * Subtitles: every one the video app sent is loaded (fixed to UTF-8), Portuguese is turned on
 * automatically, and the phone can switch tracks or load an .srt while the video plays.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    private class Request(
        val url: String, val title: String?, val type: String?, val subtitles: List<String>,
        val subtitleNames: List<String>, val position: Long, val headers: Map<String, String>,
    )

    private class Sub(val file: File, val label: String, val language: String?, val manual: Boolean = false)

    // What is playing now, so a subtitle added from the phone can restart it in place
    private var current: Request? = null
    private var currentUrl: String? = null
    private var currentMime: String? = null
    private val subtitleFiles = mutableListOf<Sub>()

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
        setContentView(view)
        handle(intent)
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
                    start(request, probe.finalUrl, probe.kind.mime ?: mimeFromHints(request))
                }
            }
        }.start()
    }

    /** Downloads (or reads) every subtitle the video app sent, as clean UTF-8 files. */
    private fun loadSubtitles(request: Request): List<Sub> =
        request.subtitles.mapIndexedNotNull { i, sub ->
            try {
                val file = if (sub.startsWith("http")) Subtitles.download(this, sub, request.headers) else File(Uri.parse(sub).path!!)
                val name = request.subtitleNames.getOrNull(i).orEmpty()
                val language = Subtitles.languageOf("$name $sub")
                Sub(file, name.ifEmpty { Subtitles.labelFor(language, i) }, language)
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
            .setSubtitleConfigurations(
                subtitleFiles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub.file))
                        .setMimeType(Subtitles.mimeOf(sub.file))
                        .setLabel(sub.label)
                        .setLanguage(sub.language)
                        .setSelectionFlags(if (sub === defaultSubtitle()) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }
            )
            .build()

        player?.release()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { p ->
                view.player = p
                // Subtitles turn on by themselves in the chosen language, including ones inside the stream
                val language = subtitleLanguage(this)
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, language == "off")
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
        val textOff = p?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        textTracks().forEachIndexed { i, (group, index) ->
            val format = group.getTrackFormat(index)
            tracks.put(
                JSONObject().put("id", i)
                    .put("label", format.label ?: format.language?.let { "Legenda ($it)" } ?: "Legenda ${i + 1}")
                    .put("selected", !textOff && group.isTrackSelected(index))
            )
        }
        return JSONObject()
            .put("title", current?.title)
            .put("playing", p?.isPlaying == true)
            .put("position", p?.currentPosition ?: 0)
            .put("duration", p?.duration?.takeIf { it > 0 } ?: 0)
            .put("subtitles", tracks)
    }

    private fun selectText(id: Int) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (id < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val (group, index) = textTracks().getOrNull(id) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        }
        p.trackSelectionParameters = builder.build()
    }

    /** The external subtitle shown by default: one in the chosen language, else the first one. */
    private fun defaultSubtitle(): Sub? {
        val language = subtitleLanguage(this)
        if (language == "off") return null
        return subtitleFiles.lastOrNull { it.manual } ?: subtitleFiles.firstOrNull { it.language == language } ?: subtitleFiles.firstOrNull()
    }

    private fun addSubtitle(file: File, label: String) {
        val request = current ?: return
        // A subtitle loaded by hand is the one the user wants: make it the default
        subtitleFiles += Sub(file, label, Subtitles.languageOf(label), manual = true)
        start(request, currentUrl ?: request.url, currentMime, retriedAsHls = true, position = player?.currentPosition ?: 0)
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

        /** "en" (default), "pt", "es" or "off". */
        fun subtitleLanguage(context: Context): String =
            context.getSharedPreferences("player", Context.MODE_PRIVATE).getString("subtitleLanguage", "en")!!

        fun setSubtitleLanguage(context: Context, language: String) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("subtitleLanguage", language).apply()
        }

        fun state(): JSONObject? = onPlayer { stateJson() }

        fun selectSubtitle(id: Int) = onPlayer { selectText(id) } != null

        fun addSubtitle(file: File, label: String) = onPlayer { addSubtitle(file, label) } != null

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
