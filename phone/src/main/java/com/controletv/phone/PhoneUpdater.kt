package com.controletv.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks the GitHub release for a newer phone app and offers to install it. */
object PhoneUpdater {
    fun check(context: Context) {
        try {
            if (BuildConfig.UPDATE_URL.isEmpty()) return
            val info = JSONObject(download(BuildConfig.UPDATE_URL).decodeToString())
            if (info.optInt("phoneVersion") <= BuildConfig.VERSION_CODE) return
            val apk = File(context.cacheDir, "phone-update.apk")
            apk.writeBytes(download(info.getString("phone")))
            prompt(context, apk)
        } catch (e: Exception) {
            Log.w("PhoneUpdater", "update check failed", e)
        }
    }

    private fun prompt(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
        }
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

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
