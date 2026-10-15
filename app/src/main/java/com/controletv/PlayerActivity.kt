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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Full-screen player for video links sent from the phone ("Tocar na TV").
 * Media keys from the remote or the phone control it: play/pause, and rewind/forward seek 10 s.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this).apply { keepScreenOn = true }
        setContentView(view)
        play(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        play(intent)
    }

    private fun play(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val headers = HashMap<String, String>()
        intent.getBundleExtra(EXTRA_HEADERS)?.let { bundle ->
            bundle.keySet().forEach { key -> bundle.getString(key)?.let { headers[key] = it } }
        }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers.remove("User-Agent") ?: headers.remove("user-agent") ?: USER_AGENT)
            .setDefaultRequestProperties(headers)

        val item = MediaItem.Builder()
            .setUri(url)
            .apply { mimeFor(url, intent.getStringExtra(EXTRA_TYPE))?.let { setMimeType(it) } }
            .apply {
                intent.getStringExtra(EXTRA_SUBTITLE)?.let { sub ->
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                                .setMimeType(if (sub.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
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
                p.setMediaItem(item, intent.getLongExtra(EXTRA_POSITION, 0))
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@PlayerActivity, "Não deu para tocar: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                    }
                })
                p.prepare()
                p.play()
            }
        intent.getStringExtra(EXTRA_TITLE)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

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

    private fun mimeFor(url: String, type: String?): String? {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            type != null && ("mpegurl" in type.lowercase()) -> MimeTypes.APPLICATION_M3U8
            type != null && ("dash" in type.lowercase()) -> MimeTypes.APPLICATION_MPD
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    companion object {
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
