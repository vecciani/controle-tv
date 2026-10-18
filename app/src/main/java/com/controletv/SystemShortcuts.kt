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
 * so each shortcut has a list of ways in: the standard intent (aimed at the settings app),
 * then the settings app's own screens whose names match, then the settings home. The first
 * one that opens wins.
 */
object SystemShortcuts {
    data class Shortcut(val key: String, val title: String, val icon: Int, val actions: List<String>, val keywords: Regex)

    val SETTINGS = listOf(
        Shortcut("set:all", "Configurações", R.drawable.ic_t_settings, listOf(Settings.ACTION_SETTINGS), Regex("(?i)^$")),
        Shortcut("set:net", "Rede e Wi-Fi", R.drawable.ic_t_wifi, listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS), Regex("(?i)network|wifi|wi_fi|wireless")),
        Shortcut("set:display", "Tela e som", R.drawable.ic_t_display, listOf(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SOUND_SETTINGS), Regex("(?i)display|sound|audio|screen")),
        Shortcut("set:bt", "Controles e Bluetooth", R.drawable.ic_t_bluetooth, listOf(Settings.ACTION_BLUETOOTH_SETTINGS), Regex("(?i)controller|bluetooth|remote|accessor")),
        Shortcut("set:apps", "Gerenciar apps", R.drawable.ic_t_apps, listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS), Regex("(?i)application|manageapp|apps")),
        Shortcut("set:about", "Minha Fire TV", R.drawable.ic_t_settings, listOf(Settings.ACTION_DEVICE_INFO_SETTINGS), Regex("(?i)myfiretv|device|about|system")),
    )

    /** The package that answers "open settings" on this TV (com.amazon.tv.settings.v2 on Fire OS). */
    fun settingsPackage(context: Context): String? =
        context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName

    /** Exported screens of the settings app, by class name. */
    private fun settingsScreens(context: Context): List<ComponentName> {
        val pkg = settingsPackage(context) ?: return emptyList()
        @Suppress("DEPRECATION")
        val info = runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES) }.getOrNull() ?: return emptyList()
        return info.activities.orEmpty().filter { it.exported && it.enabled }.map { ComponentName(pkg, it.name) }
    }

    /** Opens a settings shortcut, trying each way in until one works. Returns false if none did. */
    fun open(context: Context, shortcut: Shortcut): Boolean {
        val pm = context.packageManager
        val settingsPkg = settingsPackage(context)
        val candidates = mutableListOf<Intent>()
        for (action in shortcut.actions) {
            // Aimed at the TV's own settings app first, then whoever answers
            settingsPkg?.let { candidates += Intent(action).setPackage(it) }
            candidates += Intent(action)
        }
        settingsScreens(context).filter { shortcut.keywords.containsMatchIn(it.className) }
            .sortedBy { it.className.length }
            .forEach { candidates += Intent().setComponent(it) }
        candidates += Intent(Settings.ACTION_SETTINGS)

        for (intent in candidates) {
            if (intent.resolveActivity(pm) == null) continue
            val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (started) return true
        }
        return false
    }

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
            .put("inputs", JSONArray(inputs(context).map { "${it.label} (${it.id})" }))
            .put("standard", JSONArray(SETTINGS.flatMap { s -> s.actions.map { a -> "$a → " + (Intent(a).resolveActivity(context.packageManager)?.flattenToShortString() ?: "nada") } }))
    }
}
