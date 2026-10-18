package com.controletv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Loads pictures from the internet (posters, backdrops) into ImageViews, shrunk to the size
 * they're shown at and kept in a small memory cache, so the TV home stays light and quick.
 */
object RemoteImages {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    // ~24 MB of decoded pictures
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String?, into: ImageView, width: Int) {
        if (url.isNullOrBlank()) return
        into.tag = url
        cache.get(url)?.let { into.setImageBitmap(it); return }
        pool.execute {
            val bitmap = runCatching { fetch(url, width) }.getOrNull() ?: return@execute
            cache.put(url, bitmap)
            // The view may have been reused for another picture meanwhile
            main.post { if (into.tag == url) into.setImageBitmap(bitmap) }
        }
    }

    private fun fetch(url: String, width: Int): Bitmap? {
        var current = URL(url)
        repeat(4) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 ControleTV")
            if (conn.responseCode in 300..399) {
                current = URL(current, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        return null
    }
}
