package com.controletv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shortcuts to the TV's own settings screens and HDMI inputs.
 *
 * Fire OS has its own settings app that often ignores Android's standard settings intents,
 * so each shortcut has a list of ways in: Fire OS's own page, the standard intent (aimed at the
 * settings app), the settings apps' screens whose names match, then the settings home. What
 * actually comes on screen is checked, and the way that worked is remembered (see [open]).
 */
object SystemShortcuts {
    data class Shortcut(val key: String, val title: String, val icon: Int, val actions: List<String>, val keywords: Regex)

    val SETTINGS = listOf(
        Shortcut("set:all", "Configurações", R.drawable.ic_t_settings, listOf(Settings.ACTION_SETTINGS), Regex("(?i)^$")),
        Shortcut("set:net", "Rede e Wi-Fi", R.drawable.ic_t_wifi, listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS), Regex("(?i)network|wifi|wi_fi|wireless|internet")),
        Shortcut("set:display", "Tela e som", R.drawable.ic_t_display, listOf(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SOUND_SETTINGS), Regex("(?i)display|sound|audio|screen|video")),
        Shortcut("set:bt", "Controles e Bluetooth", R.drawable.ic_t_bluetooth, listOf(Settings.ACTION_BLUETOOTH_SETTINGS, "com.google.android.intent.action.CONNECT_INPUT"), Regex("(?i)controller|bluetooth|remote|accessor|equipment")),
        Shortcut("set:apps", "Gerenciar apps", R.drawable.ic_t_apps, listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS), Regex("(?i)application|manageapp|apps|appinfo")),
        Shortcut("set:about", "Minha Fire TV", R.drawable.ic_t_settings, listOf(Settings.ACTION_DEVICE_INFO_SETTINGS), Regex("(?i)myfiretv|my_fire|device|about|system|deviceinfo")),
    )

    /** The package that answers "open settings" on this TV (com.amazon.tv.settings.v2 on Fire OS). */
    fun settingsPackage(context: Context): String? =
        context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName

    /** Fire OS's own pages (newer Fire OS guards some of them; then the next way in is tried). */
    private val FIRE_OS = mapOf(
        "set:net" to "com.amazon.tv.settings.v2/.tv.network.NetworkActivity",
        "set:display" to "com.amazon.tv.settings.v2/.tv.display_sounds.DisplayAndSoundsActivity",
        "set:bt" to "com.amazon.tv.settings.v2/.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity",
        "set:apps" to "com.amazon.tv.settings/.tv.AllApplicationsSettingsActivity",
    )

    /** Fire OS keeps its own settings pages in the launcher's settings app too. */
    private val EXTRA_PACKAGES = listOf("com.amazon.tv.settings.v2", "com.amazon.tv.settings", "com.amazon.tv.launcher")

    /** Exported screens of the settings app(s), by class name. */
    private fun settingsScreens(context: Context): List<ComponentName> {
        val pkgs = (listOfNotNull(settingsPackage(context)) + EXTRA_PACKAGES).distinct()
        return pkgs.flatMap { pkg ->
            @Suppress("DEPRECATION")
            val info = runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES) }.getOrNull()
            info?.activities.orEmpty().filter { it.exported && it.enabled && it.permission == null }.map { ComponentName(pkg, it.name) }
        }
    }

    /** Screens that match by name but aren't the settings page (pickers, dialogs, permission lists). */
    private val NOT_A_PAGE = Regex("(?i)picker|dialog|specialaccess|confirm|request")

    /** Every way in, in order: the one that worked before, standard intents, matching screens, the settings home. */
    private fun candidates(context: Context, shortcut: Shortcut): List<Intent> {
        val pm = context.packageManager
        val settingsPkg = settingsPackage(context)
        val out = mutableListOf<Intent>()
        remembered(context, shortcut)?.let { out += it }
        FIRE_OS[shortcut.key]?.let { ComponentName.unflattenFromString(it) }?.let { out += Intent().setComponent(it) }
        for (action in shortcut.actions) {
            settingsPkg?.let { out += Intent(action).setPackage(it) }
            out += Intent(action)
        }
        settingsScreens(context).filter { shortcut.keywords.containsMatchIn(it.className) && !NOT_A_PAGE.containsMatchIn(it.className) }
            .sortedBy { it.className.length }
            .forEach { out += Intent().setComponent(it) }
        out += Intent(Settings.ACTION_SETTINGS)
        return out.filter { it.resolveActivity(pm) != null }.distinctBy { it.toUri(0) }
    }

    /**
     * Opens a settings shortcut. Fire OS often answers the standard intents with its settings
     * home instead of the page asked for, so each way in is checked against what actually came
     * on screen (through the accessibility service): if it isn't the page, the next is tried, and
     * the one that worked is remembered. Blocking (it waits for each screen): call off the main thread.
     * Returns false if nothing opened.
     */
    fun open(context: Context, shortcut: Shortcut): Boolean {
        val all = candidates(context, shortcut)
        // The settings home itself, or no way to check what opened: the first one that starts wins
        val checkable = shortcut.key != "set:all" && HomeWatcher.running
        for ((i, intent) in all.withIndex()) {
            val before = HomeWatcher.lastWindowAt
            // After a wrong page, its task is cleared so Back doesn't land on it
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or (if (i > 0) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)
            val started = runCatching { context.startActivity(Intent(intent).addFlags(flags)) }.isSuccess
            if (!started) continue
            // The settings home as the last resort isn't remembered: next time the page is tried again
            if (!checkable || i == all.lastIndex) return true
            // Wait for the screen to come up, then see whether it's the page asked for
            val deadline = System.currentTimeMillis() + 2_500
            while (HomeWatcher.lastWindowAt == before && System.currentTimeMillis() < deadline) Thread.sleep(100)
            Thread.sleep(250)
            val shown = HomeWatcher.lastWindow.substringAfter('/')
            if (shortcut.keywords.containsMatchIn(shown)) { remember(context, shortcut, intent); return true }
        }
        return false
    }

    /** [open] off the main thread; [onFail] runs on the main thread if nothing opened. */
    fun openAsync(context: Context, shortcut: Shortcut, onFail: () -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread { if (!open(context, shortcut)) main.post(onFail) }.start()
    }

    private fun remember(context: Context, shortcut: Shortcut, intent: Intent) {
        prefs(context).edit().putString(shortcut.key, intent.toUri(0)).apply()
    }

    private fun remembered(context: Context, shortcut: Shortcut): Intent? =
        prefs(context).getString(shortcut.key, null)?.let { runCatching { Intent.parseUri(it, 0) }.getOrNull() }

    private fun prefs(context: Context) = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    // ---------- HDMI inputs ----------

    data class Input(val id: String, val label: String)

    /** The TV's inputs (HDMI and others) that can be switched to. Empty on devices without any. */
    fun inputs(context: Context): List<Input> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return runCatching {
            manager.tvInputList
                .filter { it.isPassthroughInput && !it.isHidden(context) }
                .map { info ->
                    val label = info.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() } ?: info.loadLabel(context).toString()
                    Input(info.id, label.ifBlank { if (info.type == TvInputInfo.TYPE_HDMI) "HDMI" else "Entrada" })
                }
        }.getOrDefault(emptyList())
    }

    fun openInput(context: Context, input: Input): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /** What the TV exposes, for the phone's diagnostics (to fix shortcuts that don't open). */
    fun report(context: Context): JSONObject {
        val pkg = settingsPackage(context)
        return JSONObject()
            .put("settingsPackage", pkg)
            .put("screens", JSONArray(settingsScreens(context).map { it.className.removePrefix("$pkg") }))
            .put("learned", JSONObject().apply { SETTINGS.forEach { sc -> remembered(context, sc)?.let { put(sc.key, it.component?.flattenToShortString() ?: it.action) } } })
            .put("inputs", JSONArray(inputs(context).map { "${it.label} (${it.id})" }))
            .put("standard", JSONArray(SETTINGS.flatMap { s -> s.actions.map { a -> "$a → " + (Intent(a).resolveActivity(context.packageManager)?.flattenToShortString() ?: "nada") } }))
    }
}
