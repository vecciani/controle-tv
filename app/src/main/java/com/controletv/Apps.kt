package com.controletv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(val pkg: String, val label: String, val system: Boolean)

/** Lists the apps installed on the Fire TV, renders their artwork, opens them and keeps favorites. */
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
                found[pkg] = AppInfo(pkg, info.loadLabel(pm).toString(), Remote.isSystem(context, pkg))
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        pkg
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

    fun forget(pkg: String) {
        iconCache.remove(pkg)
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

    /** Favorites in the order the user arranged them. */
    fun favorites(context: Context): List<String> {
        val raw = prefs(context).getString("favorites", "[]")
        val array = JSONArray(raw)
        return List(array.length()) { array.getString(it) }
    }

    fun setFavorites(context: Context, pkgs: List<String>) {
        prefs(context).edit().putString("favorites", JSONArray(pkgs.distinct()).toString()).apply()
        HomePrefs.changed()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

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
