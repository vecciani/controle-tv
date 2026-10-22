package com.controletv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

/**
 * "Não sei o que ver": hidden gems instead of the obvious picks. From Stremio's catalog it takes
 * well-rated titles (IMDb 7+) for the chosen mood, leaves out each genre's most popular ones
 * (what everyone recommends) and what the user already has or watched, then shuffles them.
 */
object Discover {
    data class Title(
        val id: String,
        val type: String,
        val name: String,
        val rating: Double,
        val runtime: Int?,
        val genres: List<String>,
        val country: String,
        val year: String,
        val json: JSONObject?,
    )

    data class Choice(
        val mood: String,      // rir, tensao, emocionar, susto, pensar, aventura, qualquer
        val time: String,      // curto (até 1h40), medio (até 2h10), qualquer
        val company: String,   // sozinho, casal, familia
        val far: Boolean,      // outside the US
        val type: String,      // movie, series
    )

    private val MOODS = mapOf(
        "rir" to listOf("Comedy"),
        "tensao" to listOf("Thriller", "Crime", "Mystery"),
        "emocionar" to listOf("Drama", "Romance"),
        "susto" to listOf("Horror"),
        "pensar" to listOf("Sci-Fi", "Mystery", "Documentary"),
        "aventura" to listOf("Adventure", "Action", "Fantasy"),
        "qualquer" to listOf("Drama", "Comedy", "Thriller", "Sci-Fi", "Adventure", "Crime"),
    )

    private const val BASE = "https://v3-cinemeta.strem.io/catalog"
    private val pages = ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()
    private val pool = Executors.newFixedThreadPool(8)

    /** Up to 30 shuffled suggestions for [choice], as Stremio metas with a "why" line. */
    fun suggest(context: Context, choice: Choice): JSONArray {
        val type = if (choice.type == "series") "series" else "movie"
        var genres = MOODS[choice.mood] ?: MOODS.getValue("qualquer")
        if (choice.company == "familia") genres = (genres + listOf("Family", "Animation")).distinct() - "Horror"
        // The obvious ones: each genre's 150 most popular; the pool: best rated, and deeper in popularity
        val obvious = mutableSetOf<String>()
        val poolTitles = mutableListOf<JSONObject>()
        val jobs = mutableListOf<Pair<Boolean, Future<List<JSONObject>>>>()
        for (g in genres) {
            for (skip in listOf(0, 50, 100)) jobs += true to pool.submit<List<JSONObject>> { page(type, "top", g, skip) }
            for (skip in listOf(0, 50, 100, 150, 200)) jobs += false to pool.submit<List<JSONObject>> { page(type, "imdbRating", g, skip) }
            for (skip in listOf(150, 200, 250, 300)) jobs += false to pool.submit<List<JSONObject>> { page(type, "top", g, skip) }
        }
        for ((isObvious, job) in jobs) {
            val metas = runCatching { job.get() }.getOrDefault(emptyList())
            if (isObvious) metas.forEach { obvious += it.optString("id") } else poolTitles += metas
        }
        val seen = runCatching {
            Stremio.library(context).let { a -> List(a.length()) { a.getJSONObject(it).optString("id") } }.toSet()
        }.getOrDefault(emptySet())
        val titles = poolTitles.map { parse(it, type) }.distinctBy { it.id }
        return JSONArray(pick(titles, choice, obvious + seen, Random.Default).map { t ->
            JSONObject(t.json.toString()).put("why", why(t))
        })
    }

    /** The filtering and shuffling, apart from the network (tested). */
    fun pick(titles: List<Title>, choice: Choice, exclude: Set<String>, random: Random): List<Title> {
        val minRating = if (choice.type == "series") 7.5 else 7.0
        val maxMinutes = when (choice.time) { "curto" -> 100; "medio" -> 130; else -> Int.MAX_VALUE }
        val candidates = titles.filter { t ->
            t.id !in exclude &&
                t.rating >= minRating &&
                (choice.type == "series" || (t.runtime ?: 0) in 60..maxMinutes) &&
                (!choice.far || (t.country.isNotBlank() && !t.country.contains("United States"))) &&
                (choice.company != "familia" || (t.genres.any { it == "Family" || it == "Animation" } && "Horror" !in t.genres)) &&
                (choice.mood != "rir" || "Horror" !in t.genres)
        }
        // Better rated first, with some chance, so every draw is different
        return candidates.sortedByDescending { it.rating + random.nextDouble() * 1.2 }.take(30).shuffled(random)
    }

    fun parse(meta: JSONObject, type: String): Title {
        val genres = (meta.optJSONArray("genres") ?: meta.optJSONArray("genre"))?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        return Title(
            id = meta.optString("id"),
            type = type,
            name = meta.optString("name"),
            rating = meta.optString("imdbRating").toDoubleOrNull() ?: 0.0,
            runtime = Regex("(\\d+)").find(meta.optString("runtime"))?.value?.toIntOrNull(),
            genres = genres,
            country = meta.optString("country"),
            year = meta.optString("releaseInfo").ifEmpty { meta.optString("year") },
            json = meta,
        )
    }

    /** "IMDb 7,8 · pouco conhecido · 1h54 · Coreia do Sul". */
    fun why(t: Title): String = listOfNotNull(
        "IMDb ${"%.1f".format(t.rating).replace('.', ',')}",
        "pouco conhecido",
        t.runtime?.let { if (it >= 60) "${it / 60}h${(it % 60).toString().padStart(2, '0')}" else "$it min" },
        country(t.country),
        t.year.ifEmpty { null },
    ).joinToString(" · ")

    private val COUNTRIES = mapOf(
        "United States" to "EUA", "United Kingdom" to "Reino Unido", "South Korea" to "Coreia do Sul", "Japan" to "Japão",
        "France" to "França", "Spain" to "Espanha", "Germany" to "Alemanha", "Italy" to "Itália", "Brazil" to "Brasil",
        "Mexico" to "México", "India" to "Índia", "Canada" to "Canadá", "Denmark" to "Dinamarca", "Sweden" to "Suécia",
        "Norway" to "Noruega", "Ireland" to "Irlanda", "Australia" to "Austrália", "Iran" to "Irã", "Belgium" to "Bélgica",
        "Netherlands" to "Holanda", "Poland" to "Polônia", "Argentina" to "Argentina", "China" to "China",
        "Hong Kong" to "Hong Kong", "Taiwan" to "Taiwan", "New Zealand" to "Nova Zelândia", "Finland" to "Finlândia",
        "Iceland" to "Islândia", "Austria" to "Áustria", "Switzerland" to "Suíça", "Chile" to "Chile", "Colombia" to "Colômbia",
        "Turkey" to "Turquia", "Thailand" to "Tailândia", "Indonesia" to "Indonésia", "Israel" to "Israel", "Portugal" to "Portugal",
    )

    /** The first country, in Portuguese. */
    fun country(raw: String): String? = raw.split(',').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { COUNTRIES[it] ?: it }

    /** One page of a Cinemeta catalog, kept for 6 hours so new draws are instant. */
    private fun page(type: String, catalog: String, genre: String, skip: Int): List<JSONObject> {
        val key = "$type/$catalog/$genre/$skip"
        pages[key]?.let { (at, list) -> if (System.currentTimeMillis() - at < 6 * 3600_000L) return list }
        val extra = "genre=${java.net.URLEncoder.encode(genre, "UTF-8")}" + if (skip > 0) "&skip=$skip" else ""
        val conn = URL("$BASE/$type/$catalog/$extra.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 ControleTV")
        val metas = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() }).optJSONArray("metas") ?: JSONArray()
        conn.disconnect()
        val list = List(metas.length()) { metas.getJSONObject(it) }
        pages[key] = System.currentTimeMillis() to list
        return list
    }
}
