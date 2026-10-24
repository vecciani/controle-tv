package com.controletv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.Executors

/**
 * The music video of the song AuraMusic is playing, as a silent background on the TV home: the
 * same clip AuraMusic's video mode picks (see [auraMusicClips]), played muted in YouTube's own
 * embedded player and kept in time with the song. The sound keeps coming from AuraMusic.
 */
@SuppressLint("SetJavaScriptEnabled")
class MusicClip(private val context: Context, private val onReady: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val search = Executors.newSingleThreadExecutor()
    private var web: WebView? = null
    private var songKey = ""
    private var candidates: List<String> = emptyList()
    private var tried = 0
    private var playing = false
    private var lastSync = 0L
    private var stoppedAt = 0L

    /** The song's official clip, found even when it can't be shown here (to open it in SmartTube). */
    val clipId: String?
        get() = if (showing) candidates.getOrNull(tried) else candidates.firstOrNull()

    /** True while a clip is on screen (loaded and playing). */
    var showing = false
        private set

    /** The view to put behind the home screen (created on first use). */
    val view: WebView
        get() = web ?: WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            alpha = 0f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Clip")
        }.also { web = it }

    /**
     * Called every second with what the music app is doing. [positionMs] is the song's current
     * position; null title means nothing is playing.
     */
    fun update(title: String?, artist: String?, isPlaying: Boolean, positionMs: Long, durationMs: Long, visible: Boolean) {
        if (title.isNullOrBlank()) return stop()
        val key = "$artist|$title"
        if (key != songKey) {
            songKey = key
            load(title, artist.orEmpty(), durationMs, positionMs)
            return
        }
        // Not on screen (browsing the rows, another app): paused to save the TV's power
        val run = isPlaying && visible
        if (run != playing) {
            playing = run
            js(if (run) "play()" else "pause()")
            if (run) lastSync = 0L // catch up with the song right away
        }
        // Every 15 s, pull the video back in time with the song if it drifted
        if (isPlaying && visible && System.currentTimeMillis() - lastSync > 15_000) {
            lastSync = System.currentTimeMillis()
            js("play()")
            web?.evaluateJavascript("time()") { value ->
                val videoSec = value?.toDoubleOrNull() ?: return@evaluateJavascript
                if (videoSec >= 0 && kotlin.math.abs(videoSec - positionMs / 1000.0) > 4) js("seek(${positionMs / 1000})")
            }
        }
    }

    fun stop() {
        if (songKey.isEmpty() && !showing) return
        songKey = ""
        candidates = emptyList()
        hide()
        js("pause()")
        stoppedAt = System.currentTimeMillis()
        // Free the player a minute after the music stopped
        main.postDelayed({
            if (songKey.isEmpty() && System.currentTimeMillis() - stoppedAt >= 60_000) {
                web?.loadUrl("about:blank")
            }
        }, 61_000)
    }

    fun destroy() {
        web?.destroy()
        web = null
        search.shutdownNow()
    }

    private fun load(title: String, artist: String, durationMs: Long, positionMs: Long) {
        hide()
        val key = songKey
        search.execute {
            // The same clip AuraMusic's video mode shows for this song
            val ids = auraMusicClips(title, artist) { q -> YtMusicSearch.videos(q) }
            main.post {
                if (key != songKey) return@post
                candidates = ids
                tried = 0
                playCandidate(positionMs)
            }
        }
    }

    /** Next video in the list (the previous one couldn't be embedded). */
    private fun playCandidate(positionMs: Long) {
        val id = candidates.getOrNull(tried) ?: return hide()
        playing = true
        lastSync = System.currentTimeMillis()
        view.loadDataWithBaseURL("https://controletv.app/", page(id, positionMs / 1000), "text/html", "utf-8", null)
    }

    private fun hide() {
        if (!showing) return
        showing = false
        web?.animate()?.alpha(0f)?.setDuration(500)?.start()
        onReady(false)
    }

    private fun js(code: String) {
        web?.evaluateJavascript("try{$code}catch(e){}", null)
    }

    private inner class Bridge {
        /** YouTube player states: 1 = playing. */
        @JavascriptInterface
        fun state(value: Int) = main.post {
            // Not playing (ended, paused, buffering at the start): YouTube draws its title, buttons
            // or end screen over the picture, so the clip hides until it plays again
            if (value != 1) {
                if (showing && value != 3) {
                    showing = false
                    web?.animate()?.alpha(0f)?.setDuration(300)?.start()
                    onReady(false)
                }
                return@post
            }
            if (showing || songKey.isEmpty()) return@post
            // YouTube shows its title and buttons for the first seconds: appear after they've gone
            val key = songKey
            main.postDelayed({
                if (showing || songKey != key) return@postDelayed
                showing = true
                web?.animate()?.alpha(1f)?.setDuration(900)?.start()
                onReady(true)
            }, 7_000)
        }

        /** The video can't be played here (embedding off, removed…): try the next one. */
        @JavascriptInterface
        fun error(code: Int) = main.post {
            tried++
            playCandidate(0)
        }
    }

    companion object {
        /**
         * Played in a small 640x360 player blown up to the screen: YouTube then sends a light
         * low-resolution stream (the clip sits behind dark gradients anyway), so the TV stays smooth.
         */
        const val PLAYER_W = 640
        const val PLAYER_H = 360

        /** The "clipe no fundo" switch (Ajustes do celular → Tela). */
        fun enabled(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("musicClip", true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("musicClip", on).apply()
        }

        /**
         * The clip AuraMusic's video mode would pick for this song, by the same rules: YouTube
         * Music's video search ("title artist official music video", then looser), lyric/live/
         * cover/remix/visualizer versions out, title and artist both matching, official channels
         * first. Best candidates first (the next one is used when a clip can't be embedded).
         */
        fun auraMusicClips(title: String, artist: String, search: (String) -> List<YtMusicSearch.Song>): List<String> {
            val queries = listOf("$title $artist official music video", "$title $artist music video", "$title $artist")
            val scored = mutableMapOf<String, Int>()
            for (q in queries) {
                val results = runCatching { search(q) }.getOrDefault(emptyList())
                for (r in results) scored.merge(r.videoId, score(r, title, artist), ::maxOf)
                if (scored.values.any { it >= 80 }) break
            }
            // Above 30: lyric, live or cover versions (−50) never make it, even as a fallback
            return scored.filter { it.value > 30 }.entries.sortedByDescending { it.value }.map { it.key }.take(4)
        }

        private val EXCLUDE = listOf(
            "lyrics", "lyric video", "lyrical", "live", "concert", "performance", "reaction", "react", "cover",
            "remix", "instrumental", "visualizer", "audio", "karaoke", "sped up", "slowed", "nightcore",
            "behind the scenes", "making of", "fan made", "shorts", "teaser", "trailer",
            "letra", "ao vivo", "legendado", "tradução", "subtitulad",
            // Compilations aren't the song's clip
            "playlist", "best of", "top songs", "greatest hits", "full album", "mix", "||", "1 hour",
        )

        /** AuraMusic's score for one search result. */
        fun score(r: YtMusicSearch.Song, songTitle: String, artistName: String): Int {
            val title = r.title.lowercase()
            val channel = r.subtitle.substringBefore(" • ").lowercase()
            val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
            val cleanSong = songTitle.lowercase().replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
            val artist = artistName.lowercase().trim()
            val tokens = artist.split(" ", ",", "&").map { it.trim() }
                .filter { it.length > 2 && it !in listOf("the", "and", "feat", "ft", "with", "de", "la") }
            var score = 0
            if (EXCLUDE.any { it in title }) score -= 50
            val titleMatch = cleanSong.isNotEmpty() && cleanSong in cleanTitle
            val artistMatch = artist.isNotEmpty() && (artist in title || artist in channel ||
                (tokens.isNotEmpty() && tokens.all { it in title || it in channel }))
            if (titleMatch) score += 40
            if (artistMatch) score += 35
            score -= when {
                !titleMatch && !artistMatch -> 60
                !titleMatch -> 25
                !artistMatch -> 30
                else -> 0
            }
            if ("vevo" in channel || channel.endsWith("official")) score += 25
            if ("official" in title || "oficial" in title) score += 10
            if ("music video" in title || " mv " in title || title.endsWith(" mv") || "clipe" in title) score += 10
            return score
        }

        /** YouTube's player filling the screen like a background (cropped, no controls, muted). */
        fun page(videoId: String, startSec: Long) = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            #p{position:absolute;top:50%;left:50%;width:100vw;height:56.25vw;min-height:100vh;min-width:177.78vh;transform:translate(-50%,-50%);pointer-events:none;border:0}</style>
            </head><body><div id="p"></div>
            <script>
            var player;
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('p',{videoId:'$videoId',host:'https://www.youtube-nocookie.com',
                playerVars:{autoplay:1,mute:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,rel:0,playsinline:1,cc_load_policy:0,start:$startSec,loop:1,playlist:'$videoId'},
                events:{onReady:function(e){e.target.mute();try{e.target.unloadModule('captions');e.target.unloadModule('cc')}catch(x){}e.target.playVideo();},
                  onStateChange:function(e){if(e.data==1){try{e.target.setOption('captions','track',{});e.target.unloadModule('captions')}catch(x){}}Clip.state(e.data)},onError:function(e){Clip.error(e.data)}}});
            }
            function seek(s){if(player&&player.seekTo)player.seekTo(s,true)}
            function play(){if(player&&player.playVideo)player.playVideo()}
            function pause(){if(player&&player.pauseVideo)player.pauseVideo()}
            function time(){return player&&player.getCurrentTime?player.getCurrentTime():-1}
            </script><script src="https://www.youtube.com/iframe_api"></script></body></html>
        """.trimIndent()
    }
}
