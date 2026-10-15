package com.controletv.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Tocar na TV": shows up when a video app offers "open with external player" or "share".
 * Takes the video link (plus headers, title and subtitles the app passes along, using the
 * same extras as MX Player and VLC) and sends it to the TV's player.
 */
class CastActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tv", Context.MODE_PRIVATE)
        val host = prefs.getString("host", null)
        val token = prefs.getString("token", null)
        if (host == null || token == null) return done("Abra o Controle TV e conecte à TV primeiro")

        val request = try {
            build(intent)
        } catch (e: IllegalArgumentException) {
            return done(e.message ?: "Link não suportado")
        } ?: return done("Não encontrei um link de vídeo")

        Thread {
            val message = try {
                post(host, token, request.first, request.second)
                if (request.first == "/api/play") "Tocando na TV" else "Abrindo na TV"
            } catch (e: Exception) {
                "Não deu para falar com a TV: ${e.message}"
            }
            runOnUiThread { done(message) }
        }.start()
    }

    /** Returns the API path and body for this intent. */
    private fun build(intent: Intent): Pair<String, JSONObject>? {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = intent.data?.toString()
            ?: sharedText?.let { Regex("https?://\\S+").find(it)?.value }
            ?: return null
        if (url.startsWith("content:") || url.startsWith("file:")) {
            throw IllegalArgumentException("Arquivos guardados no celular ainda não podem tocar na TV")
        }

        // Shared web pages (not video files) open in the matching TV app or browser instead
        val isShare = intent.action == Intent.ACTION_SEND
        if (isShare && !looksLikeVideo(url)) return "/api/open-url" to JSONObject().put("url", url)

        val body = JSONObject().put("url", url)
        intent.type?.let { body.put("type", it) }
        title(intent)?.let { body.put("title", it) }
        subtitle(intent)?.let { body.put("subtitle", it) }
        val position = intent.getIntExtra("position", 0).toLong()
        if (position > 0) body.put("position", position)
        val headers = headers(intent)
        if (headers.length() > 0) body.put("headers", headers)
        return "/api/play" to body
    }

    private fun looksLikeVideo(url: String) =
        Regex("\\.(m3u8?|mp4|mkv|webm|mpd|ts|avi|mov)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(Uri.parse(url).path ?: url)

    private fun title(intent: Intent): String? =
        intent.getStringExtra("title")
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: intent.getStringExtra("android.intent.extra.TITLE")

    private fun subtitle(intent: Intent): String? {
        @Suppress("DEPRECATION")
        val subs = intent.getParcelableArrayExtra("subs")
        val first = (subs?.firstOrNull() as? Uri)?.toString() ?: intent.getStringExtra("subtitle")
        return first?.takeIf { it.startsWith("http") }
    }

    /** MX Player style ("headers" as [key, value, key, value…]) or a Bundle (browser style). */
    private fun headers(intent: Intent): JSONObject {
        val result = JSONObject()
        intent.getStringArrayExtra("headers")?.let { pairs ->
            for (i in 0 until pairs.size - 1 step 2) result.put(pairs[i], pairs[i + 1])
        }
        for (key in listOf("headers", "com.android.browser.headers")) {
            val bundle = runCatching { intent.getBundleExtra(key) }.getOrNull() ?: continue
            bundle.keySet().forEach { k -> bundle.getString(k)?.let { result.put(k, it) } }
        }
        return result
    }

    private fun post(host: String, token: String, path: String, body: JSONObject) {
        val conn = URL("http://$host:8765$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Token", token)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code != 200) {
            val error = conn.errorStream?.use { it.readBytes().decodeToString() }
            throw IllegalStateException(runCatching { JSONObject(error!!).getString("error") }.getOrDefault("HTTP $code"))
        }
        conn.disconnect()
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
