package com.controletv

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A shared album as the screensaver's photos, from Google Photos (album → Compartilhar → Criar
 * link) or Amazon Photos (see [AmazonPhotos]): its photos are saved on the TV at screen size and
 * kept in step with the album every few hours (new photos come in, removed ones go). No account.
 */
object GooglePhotos {
    private const val MAX_PHOTOS = 300
    private const val AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    @Volatile private var dir: File? = null
    @Volatile var syncing = false
        private set

    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "gphotos").apply { mkdirs() }
    }

    /** The album's photos saved on the TV. */
    fun files(): List<File> = dir?.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()

    fun albumUrl(context: Context): String? = prefs(context).getString("url", null)

    fun status(context: Context) = org.json.JSONObject()
        .put("url", albumUrl(context))
        .put("photos", files().size)
        .put("syncing", syncing)
        .put("error", prefs(context).getString("error", null))
        .put("syncedAt", prefs(context).getLong("syncedAt", 0))

    /** Saves the album link (null or empty removes the album and its photos) and syncs it. */
    fun setAlbum(context: Context, url: String?) {
        init(context)
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) {
            prefs(context).edit().clear().apply()
            files().forEach { it.delete() }
            return
        }
        if (!Regex("^https://(photos\\.app\\.goo\\.gl|photos\\.google\\.com|goo\\.gl)/\\S+").matches(clean) && !AmazonPhotos.isLink(clean)) {
            throw IllegalArgumentException("Cole o link de um álbum compartilhado do Google Fotos ou do Amazon Fotos")
        }
        prefs(context).edit().putString("url", clean).putLong("syncedAt", 0).remove("error").apply()
        Thread { sync(context) }.start()
    }

    /** Every 6 hours (called often by the background service). */
    fun tick(context: Context) {
        init(context)
        if (albumUrl(context) == null || syncing) return
        if (System.currentTimeMillis() - prefs(context).getLong("syncedAt", 0) < 6 * 3600_000L) return
        sync(context)
    }

    @Synchronized
    fun sync(context: Context) {
        val url = albumUrl(context) ?: return
        val folder = dir ?: return
        syncing = true
        try {
            val photos = (if (AmazonPhotos.isLink(url)) AmazonPhotos.photos(url) else parseAlbum(fetch(url).decodeToString())).take(MAX_PHOTOS)
            if (photos.isEmpty()) throw IllegalStateException("Não achei fotos nesse link. Confira se o álbum está compartilhado por link.")
            val wanted = photos.associateBy { name(it.id) }
            for ((file, photo) in wanted) {
                val target = File(folder, file)
                if (target.exists()) continue
                // Screen size: Google resizes it for us (fits inside 1920x1080)
                runCatching {
                    val part = File(folder, "$file.part")
                    // Google resizes with "=w1920-h1080"; Amazon's link already asks for TV size
                    part.writeBytes(fetch(if (photo.url.contains("googleusercontent")) photo.url + "=w1920-h1080" else photo.url))
                    part.renameTo(target)
                }
            }
            folder.listFiles()?.filter { it.name !in wanted.keys }?.forEach { it.delete() }
            prefs(context).edit().putLong("syncedAt", System.currentTimeMillis()).remove("error").apply()
        } catch (e: Exception) {
            // Tried again in an hour, not every few seconds
            prefs(context).edit().putString("error", e.message ?: "Falhou").putLong("syncedAt", System.currentTimeMillis() - 5 * 3600_000L).apply()
        } finally {
            syncing = false
        }
    }

    data class Photo(val id: String, val url: String, val width: Int, val height: Int)

    /**
     * Photos in a shared album page: each comes as ["AF1Q…id",["https://lh3…/pw/…",width,height,…].
     * The owner's and viewers' pictures (small, other paths) are left out.
     */
    fun parseAlbum(html: String): List<Photo> =
        Regex("\\[\"(AF1Q[\\w-]+)\",\\[\"(https://lh3\\.googleusercontent\\.com/pw/[^\"]+)\",(\\d+),(\\d+)")
            .findAll(html)
            .map { m -> Photo(m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].toInt()) }
            .filter { it.width >= 400 && it.height >= 400 }
            .distinctBy { it.id }
            .toList()

    private fun name(id: String): String =
        MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".jpg"

    private fun fetch(url: String): ByteArray {
        var current = URL(url)
        repeat(6) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", AGENT)
            conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
            val code = conn.responseCode
            if (code in 300..399) {
                current = URL(current, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            if (code >= 400) { conn.disconnect(); throw IllegalStateException("O Google Fotos respondeu com erro $code") }
            return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        }
        throw IllegalStateException("Redirecionamentos demais")
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gphotos", Context.MODE_PRIVATE)
}
