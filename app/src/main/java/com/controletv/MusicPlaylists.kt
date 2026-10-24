package com.controletv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The home's playlist shortcuts, chosen on the phone: each one is either a fixed playlist (from
 * a search or a pasted link) or a mood that's turned into one of YouTube Music's own playlists
 * (looked up once a week, so it stays fresh). Played in the chosen music app.
 */
object MusicPlaylists {
    /** [id]: a YouTube playlist id; or [query]: a mood looked up on YouTube Music. */
    data class Entry(val title: String, val id: String?, val query: String?) {
        fun toJson(): JSONObject = JSONObject().put("title", title).put("id", id).put("query", query)
    }

    private val DEFAULTS = listOf(
        Entry("Relaxar", null, "músicas para relaxar"),
        Entry("Animada", null, "músicas animadas"),
        Entry("Foco", null, "lo-fi para estudar e focar"),
        Entry("MPB", null, "MPB clássicos"),
        Entry("Sertanejo", null, "sertanejo sucessos"),
        Entry("Pop", null, "pop hits"),
        Entry("Rock", null, "rock clássico"),
        Entry("Anos 80", null, "hits dos anos 80"),
        Entry("Jantar", null, "jazz para jantar"),
    )

    /** Goes up when the list changes, so the TV home rebuilds the row. */
    @Volatile var version = 0
        private set

    fun saved(context: Context): List<Entry> {
        val raw = prefs(context).getString("list", null) ?: return DEFAULTS
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Entry(o.getString("title"), o.optString("id").ifEmpty { null }?.takeIf { it != "null" }, o.optString("query").ifEmpty { null }?.takeIf { it != "null" })
            }
        }.getOrDefault(DEFAULTS)
    }

    fun save(context: Context, list: JSONArray) {
        val clean = JSONArray()
        for (i in 0 until minOf(list.length(), 20)) {
            val o = list.getJSONObject(i)
            val title = o.optString("title").trim().take(40)
            val id = o.optString("id").trim().takeIf { Regex("[\\w-]{10,64}").matches(it) }
            val query = o.optString("query").trim().take(80).ifEmpty { null }
            if (title.isEmpty() || (id == null && query == null)) continue
            clean.put(Entry(title, id, query).toJson())
        }
        prefs(context).edit().putString("list", clean.toString()).apply()
        version++
    }

    fun json(context: Context): JSONArray = JSONArray(saved(context).map { it.toJson() })

    /** The playlist id of an entry (a mood is looked up, then kept for a week). Blocking. */
    fun playlistId(entry: Entry): String {
        entry.id?.let { return it }
        val query = entry.query ?: throw IllegalStateException("Playlist sem endereço")
        found[query]?.let { (at, id) -> if (System.currentTimeMillis() - at < 7 * 24 * 3600_000L) return id }
        val id = YtMusicSearch.playlists(query, 1).firstOrNull()?.id ?: throw IllegalStateException("Não achei uma playlist de ${entry.title}")
        found[query] = System.currentTimeMillis() to id
        return id
    }

    /** Playlists for the phone's search: YouTube Music's own first, then the community's. */
    fun search(query: String): JSONArray {
        val own = runCatching { YtMusicSearch.playlists(query, 6) }.getOrDefault(emptyList())
        val community = runCatching { YtMusicSearch.playlists(query, 6, community = true) }.getOrDefault(emptyList())
        return JSONArray((own + community).distinctBy { it.id }.take(12).map {
            JSONObject().put("id", it.id).put("title", it.title).put("subtitle", it.subtitle).put("thumb", it.thumbnail)
        })
    }

    /** A public playlist's name (YouTube's oEmbed), or null. Blocking. */
    fun title(id: String): String? = runCatching {
        val url = java.net.URL("https://www.youtube.com/oembed?format=json&url=" +
            java.net.URLEncoder.encode("https://www.youtube.com/playlist?list=$id", "UTF-8"))
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        JSONObject(conn.inputStream.use { it.readBytes().decodeToString() }).also { conn.disconnect() }.optString("title").ifEmpty { null }
    }.getOrNull()

    /** The playlist id inside a YouTube or YouTube Music link ("…?list=PL…"), or null. */
    fun idFromLink(link: String): String? =
        Regex("[?&]list=([\\w-]{10,64})").find(link)?.groupValues?.get(1)

    private val found = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun prefs(context: Context) = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
}
