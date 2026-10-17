package com.controletv.phone

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the TV app up to date from the phone: compares the TV's version with the latest
 * release and, if it's older, installs the new one through the phone's ADB connection.
 * No confirmation needed on the TV, so updates can't get stuck waiting for an OK.
 */
object TvUpdater {
    @Volatile var running = false
        private set

    /** Returns a message to show, or null when there was nothing to do. */
    fun updateIfNeeded(context: Context, host: String, token: String, relay: Relay, force: Boolean = false): String? {
        if (running) return null
        running = true
        try {
            val info = JSONObject(download(BuildConfig.UPDATE_URL).decodeToString())
            val latest = info.getInt("apkVersion")
            val status = JSONObject(get("http://$host:8765/api/status", token))
            val installed = status.optInt("version")
            if (installed >= latest && !force) return null
            if (relay.state != Relay.State.OK) return "A TV precisa de atualização (v$installed → v$latest), mas o controle completo (ADB) não está ativo"
            val apk = File(context.cacheDir, "tv-update.apk")
            apk.writeBytes(download(info.getString("apk")))
            relay.install(listOf(apk))
            apk.delete()
            return "TV atualizada para a versão $latest"
        } catch (e: Exception) {
            Log.w("TvUpdater", "update failed", e)
            return if (force) "Não deu para atualizar a TV: ${e.message}" else null
        } finally {
            running = false
        }
    }

    private fun get(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("X-Token", token)
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }

    private fun download(url: String): ByteArray {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                current = URL(current, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        }
        throw IllegalStateException("Redirecionamentos demais")
    }
}
