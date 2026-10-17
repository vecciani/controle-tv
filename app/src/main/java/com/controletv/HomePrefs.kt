package com.controletv

import android.content.Context
import org.json.JSONArray

/**
 * What the TV home shows that the user can change: the greeting name, the 3 featured apps,
 * hidden apps, the order of the folder rows and the order of the apps inside each folder.
 */
object HomePrefs {
    private const val DEFAULT_NAME = "Família Vecciani"

    /** Bumped on every layout change (from the TV or the phone), so the home knows to redraw. */
    @Volatile var changes = 0
        private set

    fun changed() { changes++ }
    const val FEATURED_SLOTS = 3

    fun name(context: Context): String = prefs(context).getString("name", DEFAULT_NAME)!!

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString("name", name.trim().take(40)).apply()
    }

    /**
     * The 3 featured apps. Empty slots are filled with Netflix, then the favorites, then
     * film/series, live TV and music apps — never with system tools.
     */
    fun featured(context: Context, installed: List<String>): List<String> {
        val saved = JSONArray(prefs(context).getString("featured", "[]"))
        val chosen = List(FEATURED_SLOTS) { saved.optString(it).takeIf { pkg -> pkg in installed } }
        val byFolder = installed.groupBy { Folders.folderOf(context, it, Apps.label(context, it)) }
        val fillers = (listOf("com.netflix.ninja") + Apps.favorites(context) +
            byFolder["filmes"].orEmpty() + byFolder["canais"].orEmpty() + byFolder["musica"].orEmpty())
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
        changed()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun hidden(context: Context): Set<String> =
        prefs(context).getStringSet("hidden", emptySet())!!.toSet()

    fun setHidden(context: Context, pkg: String, hide: Boolean) {
        val next = if (hide) hidden(context) + pkg else hidden(context) - pkg
        prefs(context).edit().putStringSet("hidden", next).apply()
        changed()
    }

    /** Folder ids in the order the user arranged them (folders never arranged keep their default place). */
    fun rowOrder(context: Context): List<String> {
        val saved = JSONArray(prefs(context).getString("rows", "[]"))
        val ids = List(saved.length()) { saved.getString(it) }.filter { id -> Folders.ALL.any { it.id == id } }
        return ids + Folders.ALL.map { it.id }.filter { it !in ids }
    }

    /** Sorts a folder's apps in the user's order; apps never arranged go at the end, by name. */
    fun sortFolder(context: Context, folder: String, apps: List<AppInfo>): List<AppInfo> {
        val saved = JSONArray(prefs(context).getString("order:$folder", "[]"))
        val order = List(saved.length()) { saved.getString(it) }
        return apps.sortedWith(compareBy({ order.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() }))
    }

    /**
     * Applies a layout made in the phone's editor:
     * {"featured":[pkg…], "rows":[{"id":folder,"apps":[pkg…]}…], "hidden":[pkg…]}
     */
    fun apply(context: Context, layout: org.json.JSONObject) {
        val edit = prefs(context).edit()
        layout.optJSONArray("featured")?.let { f ->
            edit.putString("featured", JSONArray(List(FEATURED_SLOTS) { f.optString(it) }).toString())
        }
        layout.optJSONArray("rows")?.let { rows ->
            val ids = mutableListOf<String>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                if (Folders.ALL.none { it.id == id }) continue
                ids += id
                val apps = row.optJSONArray("apps") ?: JSONArray()
                edit.putString("order:$id", apps.toString())
                for (j in 0 until apps.length()) Folders.move(context, apps.getString(j), id)
            }
            edit.putString("rows", JSONArray(ids).toString())
        }
        layout.optJSONArray("hidden")?.let { h ->
            edit.putStringSet("hidden", List(h.length()) { h.getString(it) }.toSet())
        }
        edit.apply()
        changed()
    }

    /** The current layout, with labels, for the phone's editor. */
    fun describe(context: Context): org.json.JSONObject {
        val apps = Apps.list(context)
        val hidden = hidden(context)
        val byPkg = apps.associateBy { it.pkg }
        fun item(pkg: String) = org.json.JSONObject().put("pkg", pkg).put("label", byPkg[pkg]?.label ?: pkg)
        val rows = JSONArray()
        for (id in rowOrder(context)) {
            val folder = Folders.ALL.first { it.id == id }
            val inFolder = apps.filter { it.pkg !in hidden && Folders.folderOf(context, it.pkg, it.label) == id }
            rows.put(
                org.json.JSONObject().put("id", id).put("title", folder.title)
                    .put("apps", JSONArray(sortFolder(context, id, inFolder).map { item(it.pkg) }))
            )
        }
        return org.json.JSONObject()
            .put("featured", JSONArray(featured(context, apps.map { it.pkg }.filter { it !in hidden }).map { item(it) }))
            .put("rows", rows)
            .put("hidden", JSONArray(apps.filter { it.pkg in hidden }.map { item(it.pkg) }))
    }
}
