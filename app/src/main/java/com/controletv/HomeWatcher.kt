package com.controletv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service with two jobs:
 *  - Home screen (optional, [redirectHome]): Fire OS doesn't let other apps replace its
 *    launcher, so when the Amazon home appears we open ours right away. Opening the Amazon
 *    screen on purpose (from our settings row) is respected until the user leaves it.
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
                MainActivity.open(this)
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
