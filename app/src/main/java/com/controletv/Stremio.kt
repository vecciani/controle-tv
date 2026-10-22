package com.controletv

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Stremio for the phone's "Filmes" tab: the user's library and "continue watching" (from their
 * Stremio account) plus Stremio's public catalog (Cinemeta: posters, synopsis, ratings).
 * Titles open in the Stremio app, which does the playing with whatever add-ons the user has.
 * Only the session key is stored, never the password.
 */
object Stremio {
    private const val API = "https://api.strem.io/api"
    private const val CATALOG = "https://v3-cinemeta.strem.io"

    fun status(context: Context): JSONObject {
        val p = prefs(context)
        return JSONObject()
            .put("loggedIn", p.getString("authKey", null) != null)
            .put("email", p.getString("email", null))
            .put("installed", Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///")).resolveActivity(context.packageManager) != null)
    }

    fun login(context: Context, email: String, password: String) {
        val reply = post("$API/login", JSONObject().put("type", "Login").put("email", email).put("password", password).put("facebook", false))
        reply.optJSONObject("error")?.let { throw IllegalArgumentException(if (it.optBoolean("wrongEmail")) "E-mail não encontrado no Stremio" else "E-mail ou senha incorretos") }
        val key = reply.getJSONObject("result").getString("authKey")
        prefs(context).edit().putString("authKey", key).putString("email", email).apply()
    }

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * The user's library and what they're watching, most recently watched first, each with where
     * they stopped. Like Stremio itself: "inLibrary" items are the ones added to the library;
     * "watching" ones are in "continue watching", which includes titles never added (Stremio keeps
     * those as temporary items) and leaves out finished ones (their position goes back to 0).
     */
    fun library(context: Context): JSONArray {
        val key = prefs(context).getString("authKey", null) ?: throw IllegalStateException("Entre com sua conta do Stremio")
        val reply = post("$API/datastoreGet", JSONObject().put("authKey", key).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true))
        reply.optJSONObject("error")?.let {
            logout(context)
            throw IllegalStateException("A sessão do Stremio expirou: entre de novo")
        }
        val items = reply.optJSONArray("result") ?: JSONArray()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val removed = item.optBoolean("removed")
            val temp = item.optBoolean("temp")
            if (item.optString("type") == "other") continue
            val state = item.optJSONObject("state") ?: JSONObject()
            val inLibrary = !removed && !temp
            val watching = (!removed || temp) && state.optLong("timeOffset") > 0
            if (!inLibrary && !watching) continue
            out += JSONObject()
                .put("id", item.optString("_id"))
                .put("type", item.optString("type"))
                .put("name", item.optString("name"))
                .put("poster", item.optString("poster"))
                .put("position", state.optLong("timeOffset"))
                .put("duration", state.optLong("duration"))
                .put("videoId", state.optString("video_id").ifEmpty { null })
                .put("lastWatched", state.optString("lastWatched"))
                .put("inLibrary", inLibrary)
                .put("watching", watching)
        }
        return JSONArray(out.sortedByDescending { it.optString("lastWatched") })
    }

    /** Popular titles, or search results when [query] is given. */
    fun catalog(type: String, query: String?): JSONArray {
        val safeType = if (type == "series") "series" else "movie"
        val path = if (query.isNullOrBlank()) "top" else "top/search=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return get("$CATALOG/catalog/$safeType/$path.json").optJSONArray("metas") ?: JSONArray()
    }

    /** Popular titles of a genre (Stremio's genre names are in English: Comedy, Sci-Fi, Drama…). */
    fun genre(type: String, genre: String): List<JSONObject> {
        val safeType = if (type == "series") "series" else "movie"
        val metas = get("$CATALOG/catalog/$safeType/top/genre=${URLEncoder.encode(genre, "UTF-8")}.json").optJSONArray("metas") ?: JSONArray()
        return List(metas.length()) { metas.getJSONObject(it) }
    }

    /** This year's releases ("Filmes novos", "Séries novas"), as plain objects. */
    fun newReleases(type: String, year: Int): List<JSONObject> {
        val safeType = if (type == "series") "series" else "movie"
        val metas = get("$CATALOG/catalog/$safeType/year/genre=$year.json").optJSONArray("metas") ?: JSONArray()
        return List(metas.length()) { metas.getJSONObject(it) }
    }

    fun meta(type: String, id: String): JSONObject {
        if (!Regex("tt\\d+|[a-z0-9:_-]+").matches(id)) throw IllegalArgumentException("Título inválido")
        val safeType = if (type == "series") "series" else "movie"
        return get("$CATALOG/meta/$safeType/$id.json").optJSONObject("meta") ?: JSONObject()
    }

    /** The last title opened in Stremio through Controle TV, so the phone knows what's on screen. */
    @Volatile var lastOpened: JSONObject? = null
        private set

    /** Opens the title (or an episode) in the Stremio app on the TV. */
    fun openOnTv(context: Context, type: String, id: String, videoId: String?, name: String? = null) {
        val uri = buildString {
            append("stremio:///detail/").append(if (type == "series") "series" else "movie").append('/').append(Uri.encode(id))
            if (!videoId.isNullOrBlank()) append('/').append(Uri.encode(videoId))
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) throw IllegalStateException("Instale o Stremio na TV")
        context.startActivity(intent)
        lastOpened = JSONObject().put("id", id).put("type", if (type == "series") "series" else "movie")
            .put("name", name).put("at", System.currentTimeMillis())
    }

    private fun get(url: String): JSONObject = JSONObject(request(url, null))

    private fun post(url: String, body: JSONObject): JSONObject = JSONObject(request(url, body.toString()))

    private fun request(url: String, body: String?): String {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 ControleTV")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                current = URL(current, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            return (stream ?: throw IllegalStateException("Stremio respondeu HTTP $code")).use { it.readBytes().decodeToString() }
        }
        throw IllegalStateException("Redirecionamentos demais")
    }

    private fun prefs(context: Context) = context.getSharedPreferences("stremio", Context.MODE_PRIVATE)
}
