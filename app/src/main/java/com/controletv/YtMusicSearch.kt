package com.controletv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Song search on YouTube Music (the same service AuraMusic plays from), used to turn
 * "Coldplay Yellow" into a song AuraMusic can start right away.
 */
object YtMusicSearch {
    data class Song(val videoId: String, val title: String, val subtitle: String, val thumbnail: String?)

    private const val URL_SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    // The "Songs" and "Videos" filters of YouTube Music's search
    private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    private const val VIDEOS_FILTER = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"

    fun songs(query: String, limit: Int = 6): List<Song> = search(query, SONGS_FILTER, limit)

    // YouTube Music's own ("featured") playlists
    private const val PLAYLISTS_FILTER = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"

    data class Playlist(val id: String, val title: String, val subtitle: String, val thumbnail: String?)

    /** YouTube Music's official playlists for [query] ("músicas para relaxar"…). */
    fun playlists(query: String, limit: Int = 5): List<Playlist> {
        val rows = mutableListOf<JSONObject>()
        collect(request(query, PLAYLISTS_FILTER), rows)
        return rows.mapNotNull { row ->
            val browseId = row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?.takeIf { it.startsWith("VL") } ?: return@mapNotNull null
            val columns = row.optJSONArray("flexColumns") ?: return@mapNotNull null
            fun column(i: Int): String {
                val runs = columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
                return List(runs.length()) { runs.getJSONObject(it).optString("text") }.joinToString("")
            }
            val thumbs = row.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            Playlist(browseId.removePrefix("VL"), column(0), column(1), thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url"))
        }.distinctBy { it.id }.take(limit)
    }

    /** Music videos (subtitle: "Artist • views • length"), as AuraMusic's video mode looks them up. */
    fun videos(query: String, limit: Int = 10): List<Song> = search(query, VIDEOS_FILTER, limit)

    private fun search(query: String, filter: String, limit: Int): List<Song> {
        val rows = mutableListOf<JSONObject>()
        collect(request(query, filter), rows)
        return rows.mapNotNull { parse(it) }.distinctBy { it.videoId }.take(limit)
    }

    private fun request(query: String, filter: String): JSONObject {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", "1.20241023.01.00").put("hl", "pt").put("gl", "BR")))
            .put("query", query)
            .put("params", filter)
        val conn = URL(URL_SEARCH).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Origin", "https://music.youtube.com")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return JSONObject(conn.inputStream.use { it.readBytes().decodeToString() }).also { conn.disconnect() }
    }

    /** Finds every song row anywhere in the response. */
    private fun collect(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { out += it }
                node.keys().forEach { collect(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun parse(row: JSONObject): Song? {
        val videoId = row.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.length == 11 } ?: return null
        val columns = row.optJSONArray("flexColumns") ?: return null
        fun column(i: Int): String {
            val runs = columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
            return List(runs.length()) { runs.getJSONObject(it).optString("text") }.joinToString("")
        }
        val thumbs = row.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
        return Song(videoId, column(0), column(1), thumb)
    }
}
