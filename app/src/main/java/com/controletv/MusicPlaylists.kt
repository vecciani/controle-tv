package com.controletv

import java.util.concurrent.ConcurrentHashMap

/**
 * The home's playlist shortcuts: a few moods, each turned into one of YouTube Music's own
 * playlists (looked up once a week, so it stays fresh) and played in the chosen music app.
 */
object MusicPlaylists {
    data class Mood(val id: String, val title: String, val query: String)

    val MOODS = listOf(
        Mood("relax", "Relaxar", "músicas para relaxar"),
        Mood("animada", "Animada", "músicas animadas"),
        Mood("foco", "Foco", "lo-fi para estudar e focar"),
        Mood("mpb", "MPB", "MPB clássicos"),
        Mood("sertanejo", "Sertanejo", "sertanejo sucessos"),
        Mood("pop", "Pop", "pop hits"),
        Mood("rock", "Rock", "rock clássico"),
        Mood("80s", "Anos 80", "hits dos anos 80"),
        Mood("jantar", "Jantar", "jazz para jantar"),
    )

    private val found = ConcurrentHashMap<String, Pair<Long, YtMusicSearch.Playlist>>()

    /** The playlist for a mood (network on the first time each week). Blocking. */
    fun playlist(mood: Mood): YtMusicSearch.Playlist {
        found[mood.id]?.let { (at, p) -> if (System.currentTimeMillis() - at < 7 * 24 * 3600_000L) return p }
        val p = YtMusicSearch.playlists(mood.query, 1).firstOrNull() ?: throw IllegalStateException("Não achei uma playlist de ${mood.title}")
        found[mood.id] = System.currentTimeMillis() to p
        return p
    }
}
