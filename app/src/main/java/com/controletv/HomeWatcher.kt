package com.controletv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service with two jobs:
 *  - Home screen (optional, [redirectHome]): Fire OS doesn't let other apps replace its
 *    launcher, so when the Home button brings up the original home we open ours right away.
 *    Only the Home button counts: the Amazon app also hosts the store and search, and those
 *    must keep working. Other devices (projectors, TV boxes) also get ours set as the real
 *    home screen ([setRedirectHome]); the redirect stays as a backup there.
 *  - Screensaver: notices remote key presses and which app is on screen, so it starts only
 *    when the TV is really idle and never over a film.
 */
class HomeWatcher : AccessibilityService() {

    override fun onServiceConnected() {
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    // Every remote key counts as activity for the screensaver. The Home button is taken over
    // before the system sees it (when ours is the home screen): a press opens ours straight away,
    // so the Amazon home never flashes; holding it opens the original home (a way out).
    override fun onKeyEvent(event: KeyEvent): Boolean {
        ScreenSaver.touch()
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (!redirectHome(this)) {
            if (event.action == KeyEvent.ACTION_UP) homePressedAt = System.currentTimeMillis()
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) homeDownAt = event.eventTime
            KeyEvent.ACTION_UP -> {
                val held = event.eventTime - homeDownAt
                homePressedAt = System.currentTimeMillis()
                if (held >= LONG_PRESS_MS) openOriginalHome(this) else {
                    amazonAllowed = false
                    MainActivity.open(this)
                }
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.startsWith("com.android.systemui")) ScreenSaver.foreground = pkg
        lastWindow = pkg + "/" + (event.className ?: "")
        lastWindowAt = System.currentTimeMillis()
        when {
            pkg == packageName -> return
            isOriginalHome(this, pkg) -> {
                if (amazonAllowed || !redirectHome(this)) return
                // The Amazon app is also the store and the search: take over right after Home, and
                // whenever its home screen itself comes up (back out of an app, after booting…)
                val afterHome = System.currentTimeMillis() - homePressedAt < 2_500
                val homeScreen = event.className?.toString()?.let { HOME_ACTIVITY.containsMatchIn(it) } == true
                if (afterHome || homeScreen) MainActivity.open(this)
            }
            // Some other app is on screen: the user left the original home
            !pkg.startsWith("com.android.systemui") -> amazonAllowed = false
        }
    }

    override fun onInterrupt() {}

    companion object {
        private val AMAZON_HOME = setOf("com.amazon.tv.launcher", "com.amazon.firebat", "com.amazon.tv.leanbacklauncher")
        private const val COMPONENT = "com.controletv/com.controletv.HomeWatcher"
        private const val HOME_ALIAS = "com.controletv.HomeScreen"

        /** Fire TV: the launcher can't be replaced, only redirected. */
        fun isFireTv(context: Context) = context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            AMAZON_HOME.any { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }

        @Volatile private var homePackages: Set<String>? = null

        /** The device's own home screen(s): Amazon's on Fire TV, the maker's launcher elsewhere. */
        private fun isOriginalHome(context: Context, pkg: String): Boolean {
            if (pkg in AMAZON_HOME) return true
            val homes = homePackages ?: context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
                // Settings apps answer HOME on some boxes ("fallback home"): not a launcher
                .filter { it != context.packageName && !it.contains("settings", ignoreCase = true) }
                .toSet().also { homePackages = it }
            return pkg in homes
        }

        @Volatile var running = false
            private set

        @Volatile private var homePressedAt = 0L
        @Volatile private var homeDownAt = 0L
        private const val LONG_PRESS_MS = 700L

        /** The window on screen ("package/Class") and when it came up, to check what a shortcut opened. */
        @Volatile var lastWindow = ""
            private set
        @Volatile var lastWindowAt = 0L
            private set
        private val HOME_ACTIVITY = Regex("(?i)home(?!.*(search|store|appstore|settings))")

        /** True after the user chose to open the original home from our screen. */
        @Volatile private var amazonAllowed = false

        /** Opens the device's own home screen (Amazon's, or the maker's launcher). */
        fun openOriginalHome(context: Context) {
            amazonAllowed = true
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val original = context.packageManager.queryIntentActivities(intent, 0)
                .firstOrNull { isOriginalHome(context, it.activityInfo.packageName) }
            if (original != null) intent.setClassName(original.activityInfo.packageName, original.activityInfo.name)
            context.startActivity(intent)
        }

        /**
         * Whether the Amazon home is replaced by ours (the "tela inicial" switch on the phone).
         * Before the screensaver existed the service was only on for this, hence the default.
         */
        fun redirectHome(context: Context) =
            context.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("redirect", true)

        /** Turns the service on for the screensaver only, without taking over the Home button. */
        fun enableForScreensaver(context: Context) {
            val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
            if (!running && !prefs.contains("redirect")) prefs.edit().putBoolean("redirect", false).apply()
            setEnabled(true)
        }

        /**
         * Projectors and TV boxes: ours is the home screen unless the user turned it off. The
         * home entry is switched on right away; making it the default needs ADB ([withAdb]).
         */
        fun ensureRealHome(context: Context, withAdb: Boolean) {
            if (isFireTv(context) || !redirectHome(context)) return
            val alias = ComponentName(context, HOME_ALIAS)
            if (context.packageManager.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                context.packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                homePackages = null
            }
            if (withAdb) runCatching { Adb.shell("cmd package set-home-activity ${context.packageName}/$HOME_ALIAS") }
        }

        fun setRedirectHome(context: Context, on: Boolean) {
            context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("redirect", on).apply()
            if (!isFireTv(context)) setRealHome(context, on)
            // The service also serves the screensaver, so it stays on while either needs it
            if (on || ScreenSaver.enabled(context)) setEnabled(true)
        }

        /**
         * Outside Fire OS: makes ours the device's actual home screen (or gives it back to the
         * original launcher), so Home lands here without the redirect.
         */
        private fun setRealHome(context: Context, on: Boolean) {
            val alias = ComponentName(context, HOME_ALIAS)
            context.packageManager.setComponentEnabledSetting(
                alias,
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            homePackages = null
            runCatching {
                if (on) {
                    Adb.shell("cmd package set-home-activity ${context.packageName}/$HOME_ALIAS")
                } else {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    context.packageManager.queryIntentActivities(intent, 0)
                        .firstOrNull { isOriginalHome(context, it.activityInfo.packageName) }
                        ?.let { Adb.shell("cmd package set-home-activity ${it.activityInfo.packageName}/${it.activityInfo.name}") }
                }
            }
        }

        /** "Bloqueio total" (Fire TV): Amazon's home is switched off and ours is the only one. */
        fun fullLock(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE).getString("lockedPkg", null) != null

        /**
         * Switches Amazon's home app off (pm disable-user, undone with pm enable) so the TV has no
         * home but ours: Home, boot and every app's exit land here, with nothing to redirect. Ours
         * is made the home entry first, so the TV is never left without one. Needs ADB; newer
         * Fire OS may refuse to switch its launcher off, which is reported. Returns a message.
         */
        fun setFullLock(context: Context, on: Boolean): String {
            val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
            val alias = ComponentName(context, HOME_ALIAS)
            if (!on) {
                val pkg = prefs.getString("lockedPkg", null) ?: return "O bloqueio total já está desligado"
                val out = Adb.shell("pm enable $pkg")
                if (!out.contains("enabled", ignoreCase = true)) return "A TV não religou a tela da Amazon: $out"
                prefs.edit().remove("lockedPkg").apply()
                homePackages = null
                if (isFireTv(context)) {
                    context.packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
                return "Bloqueio desligado: a tela da Amazon voltou (o Home segue abrindo o Controle TV se a tela inicial estiver ligada)"
            }
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val original = context.packageManager.queryIntentActivities(homeIntent, 0)
                .map { it.activityInfo.packageName }.firstOrNull { isOriginalHome(context, it) }
                ?: return "Não achei a tela inicial da Amazon nesta TV"
            // Ours becomes a home first: the TV must never be without one
            context.packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            runCatching { Adb.shell("cmd package set-home-activity ${context.packageName}/$HOME_ALIAS") }
            val out = Adb.shell("pm disable-user --user 0 $original")
            if (!out.contains("disabled", ignoreCase = true)) {
                return "A Fire TV não deixou desligar a tela da Amazon (${out.trim().take(120)}). O Home continua abrindo o Controle TV pelo modo normal."
            }
            prefs.edit().putString("lockedPkg", original).putBoolean("redirect", true).apply()
            homePackages = null
            if (!running) setEnabled(true)
            return "Bloqueio total ligado: a tela da Amazon foi desligada. Para desfazer, use o mesmo botão."
        }

        /** Turns the service on or off through ADB (accessibility settings are "secure" settings). */
        fun setEnabled(enabled: Boolean) {
            val current = Adb.shell("settings get secure enabled_accessibility_services").trim()
                .takeUnless { it == "null" }.orEmpty()
            val others = current.split(':').filter { it.isNotBlank() && it != COMPONENT }
            val next = if (enabled) others + COMPONENT else others
            Adb.shell("settings put secure enabled_accessibility_services '${next.joinToString(":")}'")
            if (enabled) Adb.shell("settings put secure accessibility_enabled 1")
        }
    }
}
