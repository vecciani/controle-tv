package com.controletv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Music on the TV, for the phone's "Música na TV" panel and the home widget: what's playing
 * (any app, through its media session), transport controls, and search that plays straight
 * away in AuraMusic (or another music app that supports "play from search").
 */
object MusicBridge {
    const val AURA = "com.auramusic.app"
    private val main = Handler(Looper.getMainLooper())

    fun state(context: Context): JSONObject {
        val json = JSONObject().put("enabled", MediaListener.enabled(context)).put("aura", installed(context, AURA))
        val controller = MediaListener.current(context) ?: return json
        val meta = controller.metadata
        val playback = controller.playbackState
        // Position moves on while playing: extrapolate from the last update
        val position = playback?.let {
            if (it.state == PlaybackState.STATE_PLAYING) it.position + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
            else it.position
        } ?: 0
        return json
            .put("app", controller.packageName)
            .put("appLabel", Apps.label(context, controller.packageName))
            .put("title", meta?.getString(MediaMetadata.METADATA_KEY_TITLE))
            .put("artist", meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM))
            .put("duration", meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
            .put("position", position.coerceAtLeast(0))
            .put("playing", playback?.state == PlaybackState.STATE_PLAYING)
            .put("artKey", art(context)?.generationId ?: 0)
    }

    fun art(context: Context): Bitmap? {
        val meta = MediaListener.current(context)?.metadata ?: return null
        return meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }

    fun artPng(context: Context): ByteArray? {
        val bitmap = art(context) ?: return null
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    fun control(context: Context, action: String, value: Long) {
        val c = MediaListener.current(context)?.transportControls ?: throw IllegalStateException("Nada tocando na TV")
        when (action) {
            "prev" -> c.skipToPrevious()
            "next" -> c.skipToNext()
            "play" -> c.play()
            "pause" -> c.pause()
            "playpause" -> {
                val playing = MediaListener.current(context)?.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) c.pause() else c.play()
            }
            "seek" -> c.seekTo(value)
            else -> throw IllegalArgumentException("Ação desconhecida")
        }
    }

    /**
     * Plays the best match for [query] right away ("play from search"), or opens the app's
     * search results so the user can pick. If the TV home was on screen, it comes back after
     * a few seconds while the music keeps playing.
     */
    fun search(context: Context, query: String, play: Boolean) {
        val app = musicApp(context) ?: throw IllegalStateException("Instale o AuraMusic na TV")
        val intent = if (play) {
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query)))
        }.setPackage(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val wasHome = MainActivity.visible
        context.startActivity(intent)
        if (play && wasHome) main.postDelayed({ MainActivity.open(context) }, 4_000)
    }

    /** Opens the music app's search screen (from the home widget). */
    fun openSearch(context: Context) {
        val app = musicApp(context) ?: throw IllegalStateException("Instale o AuraMusic na TV")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search")).setPackage(app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Apps.launch(context, app) }
    }

    /** AuraMusic if installed, else any app that accepts "play from search". */
    fun musicApp(context: Context): String? {
        if (installed(context, AURA)) return AURA
        val probe = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        return context.packageManager.queryIntentActivities(probe, 0).firstOrNull()?.activityInfo?.packageName
    }

    private fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
}
