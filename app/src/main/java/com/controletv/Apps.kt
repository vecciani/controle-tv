package com.controletv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(val pkg: String, val label: String)

/** Lists the apps installed on the Fire TV, renders their artwork and opens them. */
object Apps {
    private val iconCache = ConcurrentHashMap<String, ByteArray>()

    fun list(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, AppInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || pkg in found) continue
                found[pkg] = AppInfo(pkg, info.loadLabel(pm).toString())
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** PNG of the app's TV banner, falling back to its regular icon. */
    fun icon(context: Context, pkg: String): ByteArray? = iconCache[pkg] ?: run {
        val pm = context.packageManager
        val drawable = try {
            pm.getApplicationBanner(pkg) ?: pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            return null
        }
        val out = ByteArrayOutputStream()
        toBitmap(drawable).compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray().also { iconCache[pkg] = it }
    }

    fun launch(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun toBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 192
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 192
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }
}
