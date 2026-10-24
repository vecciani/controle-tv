package com.controletv.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Descanso da TV" in the phone's share menu: photos from any app (Amazon Photos, the gallery,
 * Google Photos, WhatsApp…) are shrunk to TV size and saved in the TV's screensaver for good.
 */
class SaverShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tv", Context.MODE_PRIVATE)
        val host = prefs.getString("host", null)
        val token = prefs.getString("token", null)
        if (host == null || token == null) return done("Abra o Controle TV e conecte à TV primeiro")
        val uris = photos(intent)
        if (uris.isEmpty()) return done("Nenhuma foto para enviar")
        Toast.makeText(this, "Enviando ${uris.size} ${if (uris.size == 1) "foto" else "fotos"} para o descanso da TV…", Toast.LENGTH_SHORT).show()
        // Reading the shared photos needs this activity's permission: keep it alive until done
        Thread {
            var sent = 0
            var total = 0
            for (uri in uris) {
                runCatching {
                    total = post(host, token, shrink(uri))
                    sent++
                }
            }
            runOnUiThread {
                done(when {
                    sent == 0 -> "Não deu para enviar as fotos. A TV está ligada e na mesma rede?"
                    sent < uris.size -> "$sent de ${uris.size} fotos enviadas · $total no descanso"
                    else -> "${if (sent == 1) "Foto enviada" else "$sent fotos enviadas"} · $total no descanso da TV"
                })
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun photos(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }.filter { contentResolver.getType(it)?.startsWith("image/") != false }

    /** JPEG of at most 1920x1080-ish, turned the right way up. */
    private fun shrink(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 || bounds.outHeight / (sample * 2) >= 1920) sample *= 2
        var bitmap = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("Não é uma foto")
        val rotation = runCatching {
            contentResolver.openInputStream(uri)!!.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        if (rotation != 0f) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    /** Sends one photo; returns how many photos the screensaver has now. */
    private fun post(host: String, token: String, jpeg: ByteArray): Int {
        val conn = URL("http://$host:8765/api/screensaver/photo").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(jpeg.size)
        conn.setRequestProperty("Content-Type", "image/jpeg")
        conn.setRequestProperty("X-Token", token)
        conn.outputStream.use { it.write(jpeg) }
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        val reply = conn.inputStream.use { it.readBytes().decodeToString() }
        conn.disconnect()
        return Regex("\"photos\":(\\d+)").find(reply)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
