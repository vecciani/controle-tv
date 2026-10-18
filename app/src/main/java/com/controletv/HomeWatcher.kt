package com.controletv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service with two jobs:
 *  - Home screen (optional, [redirectHome]): Fire OS doesn't let other apps replace its
 *    launcher, so when the Home button brings up the Amazon home we open ours right away.
 *    Only the Home button counts: the Amazon app also hosts the store and search, and those
 *    must keep working.
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

    // Every remote key counts as activity for the screensaver (and is passed on untouched)
    override fun onKeyEvent(event: KeyEvent): Boolean {
        ScreenSaver.touch()
        if (event.keyCode == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_UP) homePressedAt = System.currentTimeMillis()
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.startsWith("com.android.systemui")) ScreenSaver.foreground = pkg
        when {
            pkg == packageName -> return
            pkg in AMAZON_HOME -> {
                if (amazonAllowed || !redirectHome(this)) return
                // The Amazon app is also the store and the search: only take over right after Home
                val afterHome = System.currentTimeMillis() - homePressedAt < 2_500
                val homeScreen = event.className?.toString()?.let { HOME_ACTIVITY.containsMatchIn(it) } == true
                if (afterHome || (homeScreen && homeKeySeen.not())) MainActivity.open(this)
            }
            // Some other app is on screen: the user left the Amazon home
            !pkg.startsWith("com.android.systemui") -> amazonAllowed = false
        }
    }

    override fun onInterrupt() {}

    companion object {
        private val AMAZON_HOME = setOf("com.amazon.tv.launcher", "com.amazon.firebat", "com.amazon.tv.leanbacklauncher")
        private const val COMPONENT = "com.controletv/com.controletv.HomeWatcher"

        @Volatile var running = false
            private set

        @Volatile private var homePressedAt = 0L
        // Once a Home press has been seen, rely on it alone (no guessing from screen names)
        private val homeKeySeen get() = homePressedAt != 0L
        private val HOME_ACTIVITY = Regex("(?i)home(?!.*(search|store|appstore|settings))")

        /** True after the user chose to open the Amazon home from our screen. */
        @Volatile private var amazonAllowed = false

        fun openAmazonHome(context: Context) {
            amazonAllowed = true
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val amazon = context.packageManager.queryIntentActivities(intent, 0)
                .firstOrNull { it.activityInfo.packageName in AMAZON_HOME }
            if (amazon != null) intent.setClassName(amazon.activityInfo.packageName, amazon.activityInfo.name)
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

        fun setRedirectHome(context: Context, on: Boolean) {
            context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("redirect", on).apply()
            // The service also serves the screensaver, so it stays on while either needs it
            if (on || ScreenSaver.enabled(context)) setEnabled(true)
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
