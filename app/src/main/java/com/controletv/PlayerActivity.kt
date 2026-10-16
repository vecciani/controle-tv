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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-screen player for video links sent from the phone ("Tocar na TV").
 *
 * Before playing, it peeks at the first bytes of the link to tell HLS, DASH, plain video
 * and web pages apart, since many links don't say what they are. If our player still
 * can't handle it, the link is handed to another player installed on the TV (VLC, MX…).
 * Media keys from the remote or the phone control it: play/pause, and rewind/forward seek 10 s.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    private class Request(
        val url: String, val title: String?, val type: String?, val subtitle: String?,
        val position: Long, val headers: Map<String, String>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this).apply { keepScreenOn = true }
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
            intent.getStringExtra(EXTRA_SUBTITLE), intent.getLongExtra(EXTRA_POSITION, 0), headers,
        )
        request.title?.let { toast(it) }
        Thread {
            val probe = sniff(request)
            lastPlay = probe.summary
            runOnUiThread {
                if (probe.kind == Kind.PAGE) {
                    toast("O link é uma página da web, não um vídeo. Tentando outro player…")
                    if (!openInOtherPlayer(request)) toast("Nenhum outro player na TV conseguiu abrir")
                } else {
                    start(request, probe.finalUrl, probe.kind.mime ?: mimeFromHints(request))
                }
            }
        }.start()
    }

    private fun start(request: Request, url: String, mime: String?, retriedAsHls: Boolean = false) {
        val headers = HashMap(request.headers)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers.remove("User-Agent") ?: headers.remove("user-agent") ?: USER_AGENT)
            .setDefaultRequestProperties(headers)

        val item = MediaItem.Builder()
            .setUri(url)
            .apply { mime?.let { setMimeType(it) } }
            .apply {
                request.subtitle?.let { sub ->
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                                .setMimeType(if (sub.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()
                        )
                    )
                }
            }
            .build()

        player?.release()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { p ->
                view.player = p
                p.setMediaItem(item, request.position)
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
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_HEADERS = "headers"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9) ControleTV"
        private val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )

        fun open(
            context: Context, url: String, title: String?, type: String?,
            subtitle: String?, position: Long, headers: Map<String, String>,
        ) {
            val bundle = Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } }
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TYPE, type)
                    .putExtra(EXTRA_SUBTITLE, subtitle)
                    .putExtra(EXTRA_POSITION, position)
                    .putExtra(EXTRA_HEADERS, bundle)
            )
        }
    }
}
