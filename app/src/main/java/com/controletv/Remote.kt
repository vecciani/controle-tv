package com.controletv

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.text.Normalizer

/** Everything the phone can do to the TV beyond opening apps. All of it goes through ADB. */
object Remote {
    private val KEYS = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
        "back" to 4, "home" to 3, "menu" to 82, "search" to 84, "settings" to 176,
        "playpause" to 85, "rewind" to 89, "forward" to 90, "next" to 87, "previous" to 88,
        "volup" to 24, "voldown" to 25, "mute" to 164,
        "sleep" to 223, "wakeup" to 224,
    )

    private val PACKAGE = Regex("^[A-Za-z0-9_.]+$")

    fun key(name: String, times: Int = 1) {
        val code = KEYS[name] ?: throw IllegalArgumentException("Tecla desconhecida: $name")
        val count = times.coerceIn(1, 20)
        Adb.shell("input keyevent " + List(count) { code }.joinToString(" "))
    }

    /**
     * Types into whatever has focus on the TV. Our own keyboard handles accents when it is the
     * active input method; apps with built-in keyboards (YouTube, Netflix) only take key events,
     * so for those we fall back to `input text`, which only understands plain ASCII.
     */
    fun type(backspaces: Int, text: String, enter: Boolean) {
        if (KeyboardService.type(backspaces, text, enter)) return
        if (backspaces > 0) Adb.shell("input keyevent " + List(backspaces.coerceAtMost(200)) { 67 }.joinToString(" "))
        if (text.isNotEmpty()) Adb.shell("input text " + quote(asciiOnly(text).replace(" ", "%s")))
        if (enter) Adb.shell("input keyevent 66")
    }

    /** Makes our keyboard the active one while the phone's keyboard tab is open. */
    fun useOurKeyboard(context: Context, active: Boolean) {
        val ours = "${context.packageName}/.KeyboardService"
        val prefs = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
        if (active) {
            val current = Adb.shell("settings get secure default_input_method").trim()
            if (current == ours) return
            prefs.edit().putString("previous", current).apply()
            Adb.shell("ime enable $ours; ime set $ours")
        } else {
            val previous = prefs.getString("previous", null) ?: return
            if (PACKAGE_COMPONENT.matches(previous)) Adb.shell("ime set $previous")
        }
    }

    private val PACKAGE_COMPONENT = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")

    fun stop(pkg: String) {
        Adb.shell("am force-stop ${checked(pkg)}")
    }

    fun uninstall(context: Context, pkg: String): String {
        checked(pkg)
        if (pkg == context.packageName) throw IllegalArgumentException("Não dá para desinstalar o próprio Controle TV por aqui")
        if (isSystem(context, pkg)) throw IllegalArgumentException("App do sistema, não pode ser removido")
        val result = Adb.shell("pm uninstall $pkg").trim()
        if (!result.contains("Success")) throw IllegalStateException("Falhou: $result")
        Apps.forget(pkg)
        return result
    }

    /** Apps with a task in recents and a live process, i.e. open in the background. */
    fun running(context: Context): List<String> {
        val recents = Adb.shell("dumpsys activity recents")
        val processes = Adb.shell("ps -A").lines()
            .mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull()?.substringBefore(':') }
            .toSet()
        val hidden = homePackages(context) + context.packageName
        return Regex("""realActivity=\{?([A-Za-z0-9_.]+)/""").findAll(recents)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it in processes && it !in hidden }
            .toList()
    }

    /** The app currently on screen, or null when it's the home screen. */
    fun foreground(context: Context): String? {
        val out = Adb.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
        val pkg = Regex("""\s([A-Za-z0-9_.]+)/""").find(out)?.groupValues?.get(1) ?: return null
        return pkg.takeUnless { it in homePackages(context) }
    }

    /**
     * Opens Fire TV's screen mirroring mode. Its activity name changes between Fire OS
     * versions, so we look for it and remember what worked.
     */
    fun mirror(context: Context): String {
        val prefs = context.getSharedPreferences("mirror", Context.MODE_PRIVATE)
        prefs.getString("component", null)?.let { if (start(it)) return it }
        for (candidate in mirrorCandidates()) {
            if (start(candidate)) {
                prefs.edit().putString("component", candidate).apply()
                return candidate
            }
        }
        for (action in listOf("android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS")) {
            if (!failed(Adb.shell("am start -a $action"))) return action
        }
        throw IllegalStateException("Não achei o modo de espelhamento neste Fire TV")
    }

    /** Activities whose names look like mirroring, for the phone's diagnostics screen. */
    fun mirrorCandidates(): List<String> {
        val out = Adb.shell(
            "dumpsys package | grep -ioE '[a-z0-9_.]+/[a-z0-9_.$]*(mirror|miracast|wfd|wifidisplay|screencast|cast)[a-z0-9_.$]*'"
        )
        return out.lines().map { it.trim() }
            .filter { PACKAGE_COMPONENT.matches(it) }
            .filterNot { Regex("(?i)(receiver|service|provider|broadcast)").containsMatchIn(it.substringAfter('/')) }
            .distinct()
    }

    /** Tries one candidate and returns what `am start` said, remembering it if it worked. */
    fun tryMirror(context: Context, component: String): String {
        if (!PACKAGE_COMPONENT.matches(component)) throw IllegalArgumentException("Componente inválido")
        val out = Adb.shell("am start -n $component").trim()
        if (!failed(out)) {
            context.getSharedPreferences("mirror", Context.MODE_PRIVATE).edit().putString("component", component).apply()
        }
        return out
    }

    private fun start(component: String): Boolean =
        PACKAGE_COMPONENT.matches(component) && !failed(Adb.shell("am start -n $component"))

    private fun failed(amOutput: String) =
        amOutput.contains("Error") || amOutput.contains("Exception") || amOutput.contains("does not exist")

    private fun homePackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    fun isSystem(context: Context, pkg: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(pkg, 0).flags
        flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (e: Exception) {
        false
    }

    private fun checked(pkg: String): String {
        if (!PACKAGE.matches(pkg)) throw IllegalArgumentException("Pacote inválido")
        return pkg
    }

    private fun asciiOnly(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("[^\\x20-\\x7E]"), "")

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
}
