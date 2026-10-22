package com.controletv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's "second screen" for a film or series: cast with photos, what it's about (from the
 * Portuguese Wikipedia), and for each actor a short bio and their other films. Free, open
 * sources (Wikidata and Wikipedia), no account or key.
 */
object FilmInfo {
    private const val SPARQL = "https://query.wikidata.org/sparql"
    private const val AGENT = "ControleTV/1.0 (app pessoal de controle de TV)"
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    /**
     * Cast (photo, name, IMDb person id) and a Portuguese summary of the title [imdbId]. Wikidata
     * is often slow or rate-limited, so the cast also comes from Stremio's list, with photos from
     * Wikipedia.
     */
    fun title(imdbId: String, castOrder: List<String>): JSONObject = cached("t:$imdbId") {
        require(Regex("tt\\d+").matches(imdbId)) { "Título inválido" }
        val rows = runCatching { sparql("""
            SELECT ?nm ?actorLabel ?image ?article WHERE {
              ?film wdt:P345 "$imdbId".
              OPTIONAL { ?article schema:about ?film; schema:isPartOf <https://pt.wikipedia.org/>. }
              OPTIONAL {
                ?film wdt:P161 ?actor. ?actor wdt:P345 ?nm.
                OPTIONAL { ?actor wdt:P18 ?image }
              }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "pt,en". }
            } LIMIT 60
        """) }.getOrDefault(emptyList())
        val article = rows.firstNotNullOfOrNull { it.optString("article").ifEmpty { null } }
        val cast = rows.filter { it.optString("nm").startsWith("nm") }.distinctBy { it.optString("nm") }.map { r ->
            JSONObject().put("nm", r.optString("nm")).put("name", r.optString("actorLabel")).put("photo", thumb(r.optString("image")))
        }
        // The film's billing order (from Stremio) first, then everyone else
        val sorted = cast.sortedBy { c -> castOrder.indexOfFirst { it.equals(c.optString("name"), true) }.let { if (it < 0) 999 else it } }
        // Stremio's names missing from Wikidata (or all of them, when it didn't answer): photo from Wikipedia
        val missing = castOrder.take(12).filter { name -> sorted.none { it.optString("name").equals(name, true) } }
        val extra = missing.map { name ->
            val page = wiki(name)
            JSONObject().put("nm", "").put("name", name).put("photo", page?.optJSONObject("thumbnail")?.optString("source"))
        }
        JSONObject().put("cast", JSONArray((extra + sorted).sortedBy { c ->
            castOrder.indexOfFirst { it.equals(c.optString("name"), true) }.let { if (it < 0) 999 else it }
        }.take(24))).put("about", article?.let { summary(it) })
    }

    /** An actor by name only (when Wikidata didn't answer): photo and bio from Wikipedia. */
    fun personByName(name: String): JSONObject = cached("n:$name") {
        val page = wiki(name)
        JSONObject().put("name", name).put("photo", page?.optJSONObject("thumbnail")?.optString("source"))
            .put("bio", page?.optString("extract")?.ifEmpty { null }).put("films", JSONArray())
    }

    /** An actor: photo, Portuguese bio and other films and series (newest first). */
    fun person(nm: String): JSONObject = cached("p:$nm") {
        require(Regex("nm\\d+").matches(nm)) { "Pessoa inválida" }
        val rows = sparql("""
            SELECT ?actorLabel ?image ?article ?imdb ?filmLabel ?date ?series WHERE {
              ?actor wdt:P345 "$nm".
              OPTIONAL { ?actor wdt:P18 ?image }
              OPTIONAL { ?article schema:about ?actor; schema:isPartOf <https://pt.wikipedia.org/>. }
              OPTIONAL {
                ?film wdt:P161 ?actor; wdt:P345 ?imdb.
                OPTIONAL { ?film wdt:P577 ?date }
                OPTIONAL { ?film wdt:P31 wd:Q5398426. BIND(true AS ?series) }
              }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "pt,en". }
            } ORDER BY DESC(?date) LIMIT 120
        """)
        val first = rows.firstOrNull() ?: JSONObject()
        val films = rows.filter { it.optString("imdb").startsWith("tt") }.distinctBy { it.optString("imdb") }.take(30).map { r ->
            JSONObject()
                .put("id", r.optString("imdb"))
                .put("type", if (r.optString("series") == "true") "series" else "movie")
                .put("name", r.optString("filmLabel"))
                .put("year", r.optString("date").take(4))
                .put("poster", "https://images.metahub.space/poster/small/${r.optString("imdb")}/img")
        }
        JSONObject()
            .put("name", first.optString("actorLabel"))
            .put("photo", thumb(first.optString("image")))
            .put("bio", rows.firstNotNullOfOrNull { it.optString("article").ifEmpty { null } }?.let { summary(it) })
            .put("films", JSONArray(films))
    }

    /** A Wikimedia Commons picture, small. */
    private fun thumb(image: String): String? =
        image.ifEmpty { null }?.replace("http://", "https://")?.let { "$it?width=300" }

    /** A person's Wikipedia page summary by name: Portuguese first, then English. */
    private fun wiki(name: String): JSONObject? = listOf("pt", "en").firstNotNullOfOrNull { lang ->
        runCatching {
            val title = URLEncoder.encode(name.replace(' ', '_'), "UTF-8").replace("+", "%20")
            JSONObject(get("https://$lang.wikipedia.org/api/rest_v1/page/summary/$title", null))
                .takeIf { it.optString("type") != "disambiguation" && it.optString("extract").isNotEmpty() }
        }.getOrNull()
    }

    /** First paragraph of a Wikipedia article. */
    private fun summary(articleUrl: String): String? = runCatching {
        val title = URLDecoder.decode(articleUrl.substringAfterLast("/wiki/"), "UTF-8")
        val json = JSONObject(get("https://pt.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20"), null))
        json.optString("extract").ifEmpty { null }
    }.getOrNull()

    /** Rows of a Wikidata query, each as {variable: value}. */
    private fun sparql(query: String): List<JSONObject> {
        val json = JSONObject(get("$SPARQL?query=" + URLEncoder.encode(query.trimIndent(), "UTF-8"), "application/sparql-results+json"))
        val bindings = json.optJSONObject("results")?.optJSONArray("bindings") ?: return emptyList()
        return List(bindings.length()) { i ->
            val b = bindings.getJSONObject(i)
            JSONObject().apply { b.keys().forEach { k -> put(k, b.getJSONObject(k).optString("value")) } }
        }
    }

    private fun get(url: String, accept: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", AGENT)
        accept?.let { conn.setRequestProperty("Accept", it) }
        if (conn.responseCode >= 400) { conn.disconnect(); throw IllegalStateException("Sem resposta da Wikipédia (${conn.responseCode})") }
        return conn.inputStream.use { it.readBytes().decodeToString() }.also { conn.disconnect() }
    }

    /** Answers are kept for a day: actors and casts don't change during a film. */
    private fun cached(key: String, load: () -> JSONObject): JSONObject {
        cache[key]?.let { (at, value) -> if (System.currentTimeMillis() - at < 24 * 3600_000L) return value }
        return load().also { cache[key] = System.currentTimeMillis() to it }
    }
}
