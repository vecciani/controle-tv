package com.controletv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Pulls new versions from the GitHub release named in UPDATE_URL.
 *
 * update.json says which app and page versions are published. New pages are unzipped
 * into filesDir/web and served right away; a new APK is installed through ADB, which
 * restarts the app (BootReceiver brings it back via MY_PACKAGE_REPLACED).
 */
object Updater {
    @Volatile
    var lastResult = "Nunca verificado"
        private set

    /** APK downloaded but waiting for the user to confirm it on the TV (no ADB). */
    @Volatile
    var pending: File? = null
        private set

    @Synchronized
    fun check(context: Context): String {
        if (BuildConfig.UPDATE_URL.isEmpty()) return "Atualização automática não configurada".also { lastResult = it }
        lastResult = try {
            val info = JSONObject(download(BuildConfig.UPDATE_URL).decodeToString())
            val messages = mutableListOf<String>()

            if (info.getInt("webVersion") > WebFiles.version(context)) {
                WebFiles.install(context, download(info.getString("web")), info.getInt("webVersion"))
                messages += "Telas atualizadas para v${info.getInt("webVersion")}"
            }

            if (info.getInt("apkVersion") > BuildConfig.VERSION_CODE) {
                val apk = File(context.cacheDir, "update.apk")
                apk.writeBytes(download(info.getString("apk")))
                if (Adb.via == "tv") {
                    messages += "App v${info.getInt("apkVersion")} instalando"
                    Adb.install(apk) // the process is replaced here when the install succeeds
                } else {
                    pending = apk
                    messages += "App v${info.getInt("apkVersion")} baixado. Confirme a instalação na TV"
                }
            }

            messages.joinToString(". ").ifEmpty { "Tudo atualizado" }
        } catch (e: Exception) {
            Log.w("Updater", "update failed", e)
            "Falha ao atualizar: ${e.message}"
        }
        return lastResult
    }

    /** Opens the system installer for a downloaded update. */
    fun installPending(context: Context) {
        val apk = pending ?: throw IllegalStateException("Nenhuma atualização baixada")
        Installer.prompt(context, apk)
    }

    fun startPeriodicChecks(context: Context) {
        Thread {
            Thread.sleep(30_000) // let the network come up after boot
            while (true) {
                check(context)
                Thread.sleep(6 * 60 * 60 * 1000L)
            }
        }.apply {
            name = "updater"
            isDaemon = true
            start()
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
            if (code != 200) throw IllegalStateException("HTTP $code em $current")
            return conn.inputStream.use { it.readBytes() }
        }
        throw IllegalStateException("Redirecionamentos demais")
    }
}

/** The phone and TV pages: downloaded ones in filesDir/web win over the ones bundled in the APK. */
object WebFiles {
    private fun dir(context: Context) = File(context.filesDir, "web")
    private fun versionFile(context: Context) = File(dir(context), ".version")

    private fun downloadedVersion(context: Context) =
        versionFile(context).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0

    fun version(context: Context) = maxOf(BuildConfig.WEB_VERSION, downloadedVersion(context))

    fun read(context: Context, name: String): ByteArray? {
        if (downloadedVersion(context) >= BuildConfig.WEB_VERSION) {
            val file = File(dir(context), name)
            if (file.canonicalPath.startsWith(dir(context).canonicalPath) && file.isFile) return file.readBytes()
        }
        return try {
            context.assets.open("web/$name").use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    fun install(context: Context, zip: ByteArray, version: Int) {
        val staging = File(context.filesDir, "web-new").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(staging, entry.name)
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        File(staging, ".version").writeText(version.toString())
        dir(context).deleteRecursively()
        staging.renameTo(dir(context))
    }
}
