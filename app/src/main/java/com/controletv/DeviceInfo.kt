package com.controletv

import android.content.Context
import android.os.Build
import android.provider.Settings

/** A friendly name for this device in the phone's list ("Fire TV de Jonathan", "HY300"…). */
fun deviceName(context: Context): String {
    val cr = context.contentResolver
    val named = listOf(
        runCatching { Settings.Global.getString(cr, Settings.Global.DEVICE_NAME) }.getOrNull(),
        runCatching { Settings.Secure.getString(cr, "bluetooth_name") }.getOrNull(),
    ).firstOrNull { !it.isNullOrBlank() && it != Build.MODEL }
    return named ?: if (HomeWatcher.isFireTv(context)) "Fire TV" else Build.MODEL
}
