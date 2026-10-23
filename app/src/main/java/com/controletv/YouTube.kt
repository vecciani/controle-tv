package com.controletv

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * YouTube without ads: the channels the user picks (on the phone) become rows of their newest
 * videos, and videos open in SmartTube, the ad-free YouTube app for TVs. Newest videos come from
 * each channel's public feed; searching uses YouTube's own web search. No account needed.
 */
object YouTube {
    data class Video(
        val id: String,
        val title: String,
        val channel: String,
        val channelId: String,
        /** ISO date from the feed, or null for search results (they have [publishedText]). */
        val published: String?,
        val publishedText: String?,
        val views: Long?,
        val viewsText: String?,
        val length: String?,
        val description: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("yt", true)
            .put("id", id)
            .put("name", title)
            .put("channel", channel)
            .put("channelId", channelId)
            .put("published", published)
            .put("when", publishedText ?: published?.let { ago(it, System.currentTimeMillis()) })
            .put("views", viewsText ?: views?.let { viewsLabel(it) })
            .put("length", length)
            .put("description", description)
            .put("thumb", "https://i.ytimg.com/vi/$id/hq720.jpg")
            .put("thumbSmall", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
            .put("backdrop", "https://i.ytimg.com/vi/$id/maxresdefault.jpg")
    }

    private val SMART_TUBE = listOf("com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")

    /** Goes up whenever the channel list changes, so the TV home reloads its rows. */
    @Volatile var changes = 0
        private set

    // ---------- Channels the user picked ----------

    fun channels(context: Context): List<JSONObject> {
        val raw = prefs(context).getString("channels", "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return List(array.length()) { array.getJSONObject(it) }
    }

    fun addChannel(context: Context, id: String, name: String, thumb: String?) {
        if (!Regex("UC[\\w-]{22}").matches(id)) throw IllegalArgumentException("Canal inválido")
        val list = channels(context).filter { it.optString("id") != id } +
            JSONObject().put("id", id).put("name", name.take(80)).put("thumb", thumb)
        save(context, list)
    }

    fun removeChannel(context: Context, id: String) = save(context, channels(context).filter { it.optString("id") != id })

    private fun save(context: Context, list: List<JSONObject>) {
        prefs(context).edit().putString("channels", JSONArray(list).toString()).apply()
        changes++
    }

    fun status(context: Context): JSONObject =
        JSONObject().put("channels", JSONArray(channels(context))).put("smartTube", smartTube(context) != null)

    // ---------- Videos ----------

    /** A channel's newest videos (Shorts left out: they don't fit a TV). */
    fun channelVideos(channelId: String): List<Video> {
        if (!Regex("UC[\\w-]{22}").matches(channelId)) throw IllegalArgumentException("Canal inválido")
        return parseFeed(get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"))
    }

    fun searchVideos(query: String): List<Video> {
        val rows = mutableListOf<JSONObject>()
        collect(search(query, VIDEOS_FILTER), "videoRenderer", rows)
        return rows.mapNotNull { v ->
            val id = v.optString("videoId").takeIf { it.length == 11 } ?: return@mapNotNull null
            val owner = v.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)
            Video(
                id = id,
                title = runs(v.optJSONObject("title")),
                channel = owner?.optString("text").orEmpty(),
                channelId = owner?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty(),
                published = null,
                publishedText = v.optJSONObject("publishedTimeText")?.optString("simpleText"),
                views = null,
                viewsText = v.optJSONObject("viewCountText")?.optString("simpleText"),
                length = v.optJSONObject("lengthText")?.optString("simpleText"),
                description = v.optJSONArray("detailedMetadataSnippets")?.optJSONObject(0)?.optJSONObject("snippetText")?.let { runs(it) }.orEmpty(),
            )
        }.distinctBy { it.id }.take(20)
    }

    /** Channels matching [query], for adding on the phone. */
    fun searchChannels(query: String): JSONArray {
        val rows = mutableListOf<JSONObject>()
        collect(search(query, CHANNELS_FILTER), "channelRenderer", rows)
        return JSONArray(rows.mapNotNull { c ->
            val id = c.optString("channelId").takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            val thumbs = c.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.let { if (it.startsWith("//")) "https:$it" else it }
            // YouTube puts the subscriber count in either of these, depending on the layout
            val counts = listOf("videoCountText", "subscriberCountText").mapNotNull { c.optJSONObject(it)?.optString("simpleText") }
            JSONObject().put("id", id).put("name", c.optJSONObject("title")?.optString("simpleText").orEmpty()).put("thumb", thumb)
                .put("subscribers", counts.firstOrNull { "inscrit" in it } ?: counts.firstOrNull { !it.startsWith("@") })
        }.distinctBy { it.optString("id") }.take(12))
    }

    /** Opens the video in SmartTube (no ads); without SmartTube, in whatever plays YouTube links. */
    fun open(context: Context, videoId: String, startSec: Long = 0) {
        if (!Regex("[\\w-]{11}").matches(videoId)) throw IllegalArgumentException("Vídeo inválido")
        val link = "https://www.youtube.com/watch?v=$videoId" + if (startSec > 0) "&t=${startSec}s" else ""
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        smartTube(context)?.let { intent.setPackage(it) }
        if (intent.resolveActivity(context.packageManager) == null) throw IllegalStateException("Instale o SmartTube na TV")
        context.startActivity(intent)
    }

    fun smartTube(context: Context): String? = SMART_TUBE.firstOrNull { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg) != null || context.packageManager.getLeanbackLaunchIntentForPackage(pkg) != null
    }

    // ---------- Parsing (plain Kotlin, tested) ----------

    /** Videos from a channel's Atom feed, newest first, without Shorts. */
    fun parseFeed(xml: String): List<Video> {
        val channelName = tag(xml.substringBefore("<entry>"), "title").orEmpty()
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val e = m.groupValues[1]
            val id = tag(e, "yt:videoId") ?: return@mapNotNull null
            val link = Regex("<link rel=\"alternate\" href=\"([^\"]+)\"").find(e)?.groupValues?.get(1).orEmpty()
            if ("/shorts/" in link) return@mapNotNull null
            Video(
                id = id,
                title = tag(e, "title").orEmpty(),
                channel = tag(e, "name") ?: channelName,
                channelId = tag(e, "yt:channelId").orEmpty(),
                published = tag(e, "published"),
                publishedText = null,
                views = Regex("<media:statistics views=\"(\\d+)\"").find(e)?.groupValues?.get(1)?.toLongOrNull(),
                viewsText = null,
                length = null,
                description = tag(e, "media:description").orEmpty(),
            )
        }.toList()
    }

    /** "há 3 dias", from an ISO date. */
    fun ago(iso: String, now: Long): String {
        val time = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)!!.time
        }.getOrNull() ?: return ""
        val minutes = ((now - time) / 60_000).coerceAtLeast(0)
        fun plural(n: Long, one: String, many: String) = "há $n ${if (n == 1L) one else many}"
        return when {
            minutes < 60 -> plural(maxOf(1, minutes), "minuto", "minutos")
            minutes < 60 * 24 -> plural(minutes / 60, "hora", "horas")
            minutes < 60 * 24 * 30 -> plural(minutes / (60 * 24), "dia", "dias")
            minutes < 60 * 24 * 365 -> plural(minutes / (60 * 24 * 30), "mês", "meses")
            else -> plural(minutes / (60 * 24 * 365), "ano", "anos")
        }
    }

    /** "1,2 mi de visualizações". */
    fun viewsLabel(n: Long): String {
        fun short(value: Double) = String.format(Locale("pt", "BR"), if (value < 10) "%.1f" else "%.0f", value).removeSuffix(",0")
        return when {
            n >= 1_000_000_000 -> "${short(n / 1e9)} bi de visualizações"
            n >= 1_000_000 -> "${short(n / 1e6)} mi de visualizações"
            n >= 1_000 -> "${short(n / 1e3)} mil visualizações"
            n == 1L -> "1 visualização"
            else -> "$n visualizações"
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let { unescape(it.trim()) }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    // ---------- Network ----------

    private const val VIDEOS_FILTER = "EgIQAQ=="
    private const val CHANNELS_FILTER = "EgIQAg=="

    private fun search(query: String, filter: String): JSONObject {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20250101.00.00").put("hl", "pt").put("gl", "BR")))
            .put("query", query.trim())
            .put("params", filter)
        return JSONObject(request("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString()))
    }

    private fun get(url: String) = request(url, null)

    private fun request(url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 ControleTV")
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        if (code >= 400) { conn.disconnect(); throw IllegalStateException("YouTube respondeu HTTP $code") }
        return conn.inputStream.use { it.readBytes().decodeToString() }.also { conn.disconnect() }
    }

    /** Every [key] object anywhere in the response (YouTube nests results differently over time). */
    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out += it }
                node.keys().forEach { collect(node.opt(it), key, out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun runs(text: JSONObject?): String {
        text ?: return ""
        text.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val r = text.optJSONArray("runs") ?: return ""
        return List(r.length()) { r.getJSONObject(it).optString("text") }.joinToString("")
    }

    private fun prefs(context: Context) = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
}
