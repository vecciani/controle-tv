package com.controletv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Loads pictures from the internet (posters, backdrops) into ImageViews, shrunk to the size
 * they're shown at. Kept in a small memory cache and also saved on the device (up to
 * [DISK_LIMIT]), so after a restart the home screen shows its pictures right away instead of
 * downloading them all again.
 */
object RemoteImages {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    // ~24 MB of decoded pictures
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private const val DISK_LIMIT = 200L * 1024 * 1024
    @Volatile private var dir: File? = null
    @Volatile private var savedSincePrune = 0

    /** Where pictures are saved; call once when the app starts. */
    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.cacheDir, "images").apply { mkdirs() }
        pool.execute { prune() }
    }

    /** [onDone] runs on the main thread with true once the picture is shown, false if it failed. */
    fun load(url: String?, into: ImageView, width: Int, onDone: ((Boolean) -> Unit)? = null) {
        if (url.isNullOrBlank()) { onDone?.invoke(false); return }
        into.tag = url
        val key = "$url@$width"
        cache.get(key)?.let { into.setImageBitmap(it); onDone?.invoke(true); return }
        pool.execute {
            val bitmap = runCatching { bytes(url)?.let { decode(it, width) } }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            // The view may have been reused for another picture meanwhile
            main.post {
                if (into.tag != url) return@post
                if (bitmap != null) into.setImageBitmap(bitmap)
                onDone?.invoke(bitmap != null)
            }
        }
    }

    /** The picture's file: from the device if saved before, otherwise downloaded and saved. */
    private fun bytes(url: String): ByteArray? {
        val file = dir?.let { File(it, sha1(url)) }
        if (file != null && file.exists()) {
            file.setLastModified(System.currentTimeMillis()) // recently used: kept when pruning
            return file.readBytes()
        }
        val data = download(url) ?: return null
        if (file != null) {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(data)
                tmp.renameTo(file)
            }
            if (++savedSincePrune >= 50) { savedSincePrune = 0; prune() }
        }
        return data
    }

    private fun decode(bytes: ByteArray, width: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun download(url: String): ByteArray? {
        var current = URL(url)
        repeat(4) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 ControleTV")
            if (conn.responseCode >= 400) { conn.disconnect(); return null }
            if (conn.responseCode in 300..399) {
                current = URL(current, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        }
        return null
    }

    /** Keeps the saved pictures under the limit, dropping the ones unused the longest. */
    private fun prune() {
        val files = dir?.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_LIMIT * 8 / 10) break
        }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
