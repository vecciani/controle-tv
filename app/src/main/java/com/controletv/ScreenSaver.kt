package com.controletv

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.io.File

/**
 * Screensaver settings and the idle timer that starts it: after [minutes] without any remote
 * key or phone command, unless a film or channel is on screen.
 */
object ScreenSaver {
    /** Photos shown by the screensaver; the phone fills this folder (Ajustes → Tela de descanso). */
    const val FOLDER = "/sdcard/Pictures/DescansoTV"

    @Volatile private var lastActivity = System.currentTimeMillis()

    /** Package of the app on screen, as last seen by the accessibility service. */
    @Volatile var foreground: String? = null

    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun minutes(context: Context) = prefs(context).getInt("minutes", 2)
    /** How dark the photos get: 0.0 (none) to ~0.6. */
    fun dim(context: Context) = prefs(context).getFloat("dim", 0.35f)

    /** Photos pulse and change with the beat of the music that's playing. */
    fun followMusic(context: Context) = prefs(context).getBoolean("music", true)

    fun setFollowMusic(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("music", on).apply()
    }

    fun configure(context: Context, enabled: Boolean?, minutes: Int?, dim: Float?) {
        prefs(context).edit().apply {
            enabled?.let { putBoolean("enabled", it) }
            minutes?.let { putInt("minutes", it.coerceIn(1, 60)) }
            dim?.let { putFloat("dim", it.coerceIn(0f, 0.7f)) }
        }.apply()
        touch()
    }

    /** Photos sent from the phone (both ways), plus the shared album's. */
    fun photos(): List<File> =
        File(FOLDER).listFiles { f -> f.isFile && f.name.substringAfterLast('.').lowercase() in PHOTO_TYPES }
            ?.sortedBy { it.name }.orEmpty() + shared() + GooglePhotos.files()

    // Photos shared from any phone app ("Compartilhar → Descanso da TV"), kept in the app's own
    // storage so no storage permission or ADB is needed to save them
    @Volatile private var sharedDir: File? = null

    fun init(context: Context) {
        if (sharedDir == null) sharedDir = File(context.filesDir, "saver").apply { mkdirs() }
    }

    private fun shared(): List<File> = sharedDir?.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()

    /** Saves one photo sent by the phone (already JPEG at TV size); returns how many there are now. */
    fun saveShared(bytes: ByteArray): Int {
        val dir = sharedDir ?: throw IllegalStateException("Descanso ainda iniciando")
        if (bytes.size < 1_000 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) throw IllegalArgumentException("Foto inválida")
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(20)
        File(dir, "$name.jpg").writeBytes(bytes)
        return shared().size
    }

    fun clearShared() {
        sharedDir?.listFiles()?.forEach { it.delete() }
    }

    /** Called every few seconds by the background service. */
    fun check(context: Context) {
        if (!enabled(context) || ScreenSaverActivity.visible) return
        if (System.currentTimeMillis() - lastActivity < minutes(context) * 60_000L) return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) return
        if (watchingVideo(context)) { touch(); return }
        // The TV is on another HDMI input (console, cable box…): don't start, which could also
        // pull the TV back to the Fire TV. Checked again after another idle period.
        if (HdmiInput.fireTvOnScreen() == false) { touch(); return }
        start(context)
    }

    fun start(context: Context) {
        context.startActivity(Intent(context, ScreenSaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Films, live TV and our own player never get interrupted by the screensaver. */
    private fun watchingVideo(context: Context): Boolean {
        if (PlayerActivity.state() != null || PhotoShowActivity.visible) return true
        val pkg = foreground ?: return false
        if (pkg == context.packageName) return false
        return Folders.folderOf(context, pkg, Apps.label(context, pkg)) in setOf("filmes", "canais")
    }

    private fun prefs(context: Context) = context.getSharedPreferences("screensaver", Context.MODE_PRIVATE)

    private val PHOTO_TYPES = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
}
