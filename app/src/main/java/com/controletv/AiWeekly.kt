package com.controletv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

/**
 * "Sugestões da semana": once a week a free AI (Google's Gemini, with the user's own free key)
 * gets the user's taste, in their words, plus what they already watched on Stremio, and answers
 * with titles off the obvious path, each with why it fits. Every title is checked against
 * Stremio's catalog so it has a poster and opens straight on the TV.
 */
object AiWeekly {
    private const val API = "https://generativelanguage.googleapis.com/v1beta/models"
    private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash")

    @Volatile var generating = false
        private set
    @Volatile private var lastAttempt = 0L
    /** Goes up when new suggestions are saved, so the TV home reloads its rows. */
    @Volatile var version = 0
        private set

    // ---------- Settings ----------

    fun status(context: Context): JSONObject {
        val p = prefs(context)
        val saved = runCatching { JSONObject(p.getString("week", "{}")!!) }.getOrDefault(JSONObject())
        return JSONObject()
            .put("hasKey", !p.getString("key", null).isNullOrBlank())
            .put("taste", p.getString("taste", ""))
            .put("generating", generating)
            .put("error", p.getString("error", null))
            .put("week", saved.optString("week"))
            .put("generatedAt", saved.optLong("at"))
            .put("items", saved.optJSONArray("items") ?: JSONArray())
    }

    fun configure(context: Context, key: String?, taste: String?) {
        prefs(context).edit().apply {
            key?.trim()?.let { putString("key", it) }
            taste?.trim()?.let { putString("taste", it.take(2000)) }
        }.apply()
    }

    /** The suggestions shown on the TV home (empty until the first week is generated). */
    fun items(context: Context): List<JSONObject> =
        status(context).optJSONArray("items")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()

    // ---------- Weekly run ----------

    /** Called every few seconds by the background service: generates once a week. */
    fun tick(context: Context) {
        val p = prefs(context)
        if (generating || p.getString("key", null).isNullOrBlank() || p.getString("taste", null).isNullOrBlank()) return
        val done = runCatching { JSONObject(p.getString("week", "{}")!!).optString("week") }.getOrDefault("")
        if (done == weekKey(Calendar.getInstance())) return
        // After a failure, wait a few hours before trying again
        if (System.currentTimeMillis() - lastAttempt < 6 * 3600_000L) return
        runCatching { generate(context) }
    }

    /** Asks the AI for this week's suggestions and saves them. */
    fun generate(context: Context) {
        if (generating) throw IllegalStateException("Já estou gerando as sugestões")
        val p = prefs(context)
        val key = p.getString("key", null)?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Cole a chave gratuita do Gemini primeiro")
        val taste = p.getString("taste", null)?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Conte do que você gosta primeiro")
        generating = true
        lastAttempt = System.currentTimeMillis()
        try {
            val seen = runCatching {
                Stremio.library(context).let { a -> List(a.length()) { a.getJSONObject(it).optString("name") } }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            val previous = items(context).map { it.optString("name") }
            val answer = ask(key, prompt(taste, seen.take(60), previous))
            val items = parse(answer).mapNotNull { s -> resolve(s) }.distinctBy { it.optString("id") }.take(12)
            if (items.isEmpty()) throw IllegalStateException("A IA respondeu, mas não achei os títulos no catálogo. Tente de novo.")
            p.edit()
                .putString("week", JSONObject().put("week", weekKey(Calendar.getInstance())).put("at", System.currentTimeMillis())
                    .put("items", JSONArray(items)).toString())
                .remove("error")
                .apply()
            version++
        } catch (e: Exception) {
            p.edit().putString("error", e.message ?: "Falhou").apply()
            throw e
        } finally {
            generating = false
        }
    }

    // ---------- Prompt and answer (plain logic, tested) ----------

    data class Suggestion(val title: String, val year: String, val type: String, val why: String, val imdbId: String?)

    fun prompt(taste: String, seen: List<String>, previous: List<String>): String = buildString {
        appendLine("Você é um curador de cinema e séries com gosto apurado, que foge do óbvio.")
        appendLine("Sugira 10 títulos para a semana desta pessoa: 7 filmes e 3 séries.")
        appendLine()
        appendLine("O gosto dela, nas palavras dela:")
        appendLine("\"\"\"$taste\"\"\"")
        appendLine()
        if (seen.isNotEmpty()) appendLine("Ela já viu ou já tem (não sugira estes): ${seen.joinToString("; ")}")
        if (previous.isNotEmpty()) appendLine("Já sugeridos na semana passada (não repita): ${previous.joinToString("; ")}")
        appendLine()
        appendLine("Regras:")
        appendLine("- Nada de blockbusters e títulos que todo mundo indica; prefira joias menos conhecidas, mas bem avaliadas.")
        appendLine("- Varie países, décadas e estilos dentro do gosto dela.")
        appendLine("- Só títulos que existem de verdade, com o ano correto.")
        appendLine("- \"why\": 1 ou 2 frases em português do Brasil dizendo por que combina com ela, sem spoilers.")
        appendLine("- \"title\": o título original (ou em inglês); \"imdbId\": o código tt do IMDb se você souber com certeza, senão vazio.")
        appendLine()
        append("Responda só com um array JSON de objetos {\"title\", \"year\", \"type\" (\"movie\" ou \"series\"), \"why\", \"imdbId\"}.")
    }

    /** The AI's answer: a JSON array, sometimes wrapped in ```json fences. */
    fun parse(text: String): List<Suggestion> {
        val json = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = json.indexOf('[')
        val end = json.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = JSONArray(json.substring(start, end + 1))
        return List(array.length()) { array.optJSONObject(it) }.filterNotNull().mapNotNull { o ->
            val title = o.optString("title").trim().ifEmpty { return@mapNotNull null }
            Suggestion(
                title = title,
                year = o.optString("year").filter { it.isDigit() }.take(4),
                // "series", "Série", "TV show"…: the AI may answer in either language
                type = if (Regex("(?i)s[eé]r|show|tv").containsMatchIn(o.optString("type"))) "series" else "movie",
                why = o.optString("why").trim(),
                imdbId = o.optString("imdbId").trim().takeIf { Regex("tt\\d{5,}").matches(it) },
            )
        }
    }

    /** ISO week, e.g. "2026-W43": a new one starts every Monday. */
    fun weekKey(now: Calendar): String {
        val c = (now.clone() as Calendar).apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4 }
        return String.format(Locale.US, "%d-W%02d", c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR))
    }

    // ---------- Network ----------

    /** Finds the title in Stremio's catalog (by IMDb code, or by name and year). */
    private fun resolve(s: Suggestion): JSONObject? {
        val meta = s.imdbId?.let { id -> runCatching { Stremio.meta(s.type, id) }.getOrNull()?.takeIf { it.optString("name").isNotEmpty() } }
            ?: runCatching {
                val found = Stremio.catalog(s.type, s.title)
                val list = List(found.length()) { found.getJSONObject(it) }
                list.firstOrNull { s.year.isNotEmpty() && it.optString("releaseInfo").startsWith(s.year) } ?: list.firstOrNull()
            }.getOrNull()
            ?: return null
        val id = meta.optString("id").ifEmpty { return null }
        return JSONObject()
            .put("id", id)
            .put("type", s.type)
            .put("name", meta.optString("name"))
            .put("poster", meta.optString("poster").ifEmpty { "https://images.metahub.space/poster/medium/$id/img" })
            .put("releaseInfo", meta.optString("releaseInfo").ifEmpty { s.year })
            .put("imdbRating", meta.optString("imdbRating"))
            .put("why", s.why)
            .put("description", if (s.why.isNotEmpty()) "Por que você vai gostar: ${s.why}" else meta.optString("description"))
    }

    /** Sends the prompt to Gemini (trying newer model names first) and returns its text. */
    private fun ask(key: String, prompt: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 1.0))
            .toString()
        var lastError = "sem resposta"
        for (model in MODELS) {
            val conn = URL("$API/$model:generateContent?key=${URLEncoder.encode(key, "UTF-8")}").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            conn.disconnect()
            when {
                code == 404 -> { lastError = "modelo $model indisponível"; continue }
                code == 400 && text.contains("API key", ignoreCase = true) -> throw IllegalStateException("A chave do Gemini não é válida")
                code == 429 -> throw IllegalStateException("O limite gratuito do Gemini acabou por hoje. Tente amanhã.")
                code >= 400 -> throw IllegalStateException("O Gemini respondeu com erro $code")
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("O Gemini não sugeriu nada desta vez")
            return List(parts.length()) { parts.getJSONObject(it).optString("text") }.joinToString("")
        }
        throw IllegalStateException("Não achei um modelo do Gemini disponível ($lastError)")
    }

    private fun prefs(context: Context) = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
}
