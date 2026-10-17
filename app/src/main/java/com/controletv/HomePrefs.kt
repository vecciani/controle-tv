package com.controletv

import android.content.Context
import org.json.JSONArray

/** What the TV home shows that the user can change: the greeting name and the 3 featured apps. */
object HomePrefs {
    private const val DEFAULT_NAME = "Família Vecciani"
    const val FEATURED_SLOTS = 3

    fun name(context: Context): String = prefs(context).getString("name", DEFAULT_NAME)!!

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString("name", name.trim().take(40)).apply()
    }

    /** The 3 featured apps; empty slots are filled with Netflix and the favorites. */
    fun featured(context: Context, installed: List<String>): List<String> {
        val saved = JSONArray(prefs(context).getString("featured", "[]"))
        val chosen = List(FEATURED_SLOTS) { saved.optString(it).takeIf { pkg -> pkg in installed } }
        val fillers = (listOf("com.netflix.ninja") + Apps.favorites(context) + installed)
            .filter { it in installed && it !in chosen }
            .distinct()
            .iterator()
        return chosen.map { it ?: if (fillers.hasNext()) fillers.next() else null }.filterNotNull()
    }

    fun setFeatured(context: Context, slot: Int, pkg: String) {
        val saved = JSONArray(prefs(context).getString("featured", "[]"))
        val list = MutableList(FEATURED_SLOTS) { saved.optString(it) }
        // An app appears in one slot only
        for (i in list.indices) if (list[i] == pkg) list[i] = ""
        list[slot] = pkg
        prefs(context).edit().putString("featured", JSONArray(list).toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)
}
