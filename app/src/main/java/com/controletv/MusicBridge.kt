package com.controletv

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
 * away in the chosen music app: AuraMusic (songs) or SmartTube (the official clip, then related videos).
 */
object MusicBridge {
    const val AURA = "com.auramusic.app"
    private val main = Handler(Looper.getMainLooper())

    /** "smarttube" or "aura": where music searches play (Ajustes do celular → Tela). */
    fun choice(context: Context): String = prefs(context).getString("app", "aura") ?: "aura"

    fun setChoice(context: Context, app: String) {
        prefs(context).edit().putString("app", if (app == "smarttube") "smarttube" else "aura").apply()
    }

    /** SmartTube is the music app when chosen and installed. */
    fun useSmartTube(context: Context) = choice(context) == "smarttube" && YouTube.smartTube(context) != null

    fun appName(context: Context) = if (useSmartTube(context)) "SmartTube" else "AuraMusic"

    private fun prefs(context: Context) = context.getSharedPreferences("music", Context.MODE_PRIVATE)

    fun state(context: Context): JSONObject {
        val json = JSONObject().put("enabled", MediaListener.enabled(context))
            // "aura" kept for older phone screens: a music app is there to search in
            .put("aura", musicApp(context) != null).put("appName", appName(context))
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
     * Plays the best match for [query] right away, or opens the app's search results so the
     * user can pick. AuraMusic doesn't act on "play from search", but it does play a song link,
     * so we find the song on YouTube Music first. Returns the songs found (for the phone's list).
     * Blocking (network): call off the main thread.
     */
    fun search(context: Context, query: String, play: Boolean): List<YtMusicSearch.Song> {
        if (useSmartTube(context)) return searchSmartTube(context, query, play)
        val app = musicApp(context) ?: throw IllegalStateException("Instale o AuraMusic na TV")
        if (!play) {
            open(context, app, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query)), returnHome = false)
            return emptyList()
        }
        val songs = YtMusicSearch.songs(query)
        val first = songs.firstOrNull() ?: throw IllegalStateException("Nada encontrado para \"$query\"")
        playSong(context, first.videoId)
        return songs
    }

    /**
     * SmartTube: the song's official clip (picked like AuraMusic's video mode does); SmartTube
     * goes on with related videos. The list returned is of clips, so tapping one on the phone plays that clip.
     */
    private fun searchSmartTube(context: Context, query: String, play: Boolean): List<YtMusicSearch.Song> {
        val smartTube = YouTube.smartTube(context) ?: throw IllegalStateException("Instale o SmartTube na TV")
        if (!play) {
            val results = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                .setPackage(smartTube).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(results) }.onFailure { Apps.launch(context, smartTube) }
            return emptyList()
        }
        val song = YtMusicSearch.songs(query, 1).firstOrNull()
        val official = song?.let { s -> MusicClip.auraMusicClips(s.title, s.subtitle.substringBefore(" • ")) { YtMusicSearch.videos(it) } }.orEmpty()
        val videos = YtMusicSearch.videos(query)
        val first = official.firstOrNull() ?: videos.firstOrNull()?.videoId ?: throw IllegalStateException("Nada encontrado para \"$query\"")
        YouTube.open(context, first)
        return (videos.filter { it.videoId == first } + videos.filter { it.videoId != first }).take(8)
    }

    /** Starts a song in AuraMusic (or the music app) by its YouTube Music id; in SmartTube, that clip. */
    fun playSong(context: Context, videoId: String) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) throw IllegalArgumentException("Música inválida")
        if (useSmartTube(context)) return YouTube.open(context, videoId)
        val app = musicApp(context) ?: throw IllegalStateException("Instale o AuraMusic na TV")
        open(context, app, Uri.parse("https://music.youtube.com/watch?v=$videoId"), returnHome = true)
    }

    private fun open(context: Context, app: String, uri: Uri, returnHome: Boolean) {
        val wasHome = MainActivity.visible
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // If the home was on screen, bring it back while the music keeps playing
        if (returnHome && wasHome) main.postDelayed({ MainActivity.open(context) }, 4_000)
    }

    /**
     * Plays a YouTube Music playlist: AuraMusic starts it as its queue right away; SmartTube shows
     * the playlist to pick from.
     */
    fun playPlaylist(context: Context, playlistId: String) {
        if (!Regex("[\\w-]{10,64}").matches(playlistId)) throw IllegalArgumentException("Playlist inválida")
        if (useSmartTube(context)) {
            val pkg = YouTube.smartTube(context) ?: throw IllegalStateException("Instale o SmartTube na TV")
            open(context, pkg, Uri.parse("https://www.youtube.com/playlist?list=$playlistId"), returnHome = false)
            return
        }
        val app = musicApp(context) ?: throw IllegalStateException("Instale o AuraMusic na TV")
        open(context, app, Uri.parse("https://music.youtube.com/watch?list=$playlistId"), returnHome = true)
    }

    /** Opens the music app's search screen (from the home widget). */
    fun openSearch(context: Context) {
        if (useSmartTube(context)) {
            YouTube.smartTube(context)?.let { Apps.launch(context, it) }
            return
        }
        val app = musicApp(context) ?: throw IllegalStateException("Instale o AuraMusic na TV")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search")).setPackage(app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Apps.launch(context, app) }
    }

    /** The chosen app (SmartTube or AuraMusic), else any app that accepts "play from search". */
    fun musicApp(context: Context): String? {
        if (useSmartTube(context)) return YouTube.smartTube(context)
        if (installed(context, AURA)) return AURA
        val probe = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        return context.packageManager.queryIntentActivities(probe, 0).firstOrNull()?.activityInfo?.packageName
    }

    private fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
}
