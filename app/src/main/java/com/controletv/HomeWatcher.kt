package com.controletv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes Controle TV the TV's home screen. Fire OS doesn't let other apps replace its
 * launcher, so this accessibility service watches for the Amazon home screen and opens
 * ours right away — the remote's Home button effectively leads here.
 *
 * Opening the Amazon screen on purpose (from our "Sistema" row) is respected until the
 * user leaves it.
 */
class HomeWatcher : AccessibilityService() {

    override fun onServiceConnected() {
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg == packageName -> return
            pkg in AMAZON_HOME -> {
                if (amazonAllowed) return
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
