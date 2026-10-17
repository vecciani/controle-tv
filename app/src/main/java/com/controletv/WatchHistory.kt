package com.controletv

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Continuar assistindo": where each video played in our player stopped, with a frame
 * captured from it, so the TV home can resume it. Finished videos leave the list.
 */
object WatchHistory {
    private const val MAX_ITEMS = 12

    data class Item(
        val id: String, val title: String, val url: String, val type: String?,
        val subtitles: List<String>, val subtitleNames: List<String>, val headers: Map<String, String>,
        val position: Long, val duration: Long, val updatedAt: Long,
    ) {
        val progress get() = if (duration > 0) position.toFloat() / duration else 0f
    }

    fun idFor(url: String) = Integer.toHexString(url.substringBefore('?').hashCode())

    fun thumb(context: Context, id: String) = File(File(context.filesDir, "thumbs").apply { mkdirs() }, "$id.jpg")

    fun list(context: Context): List<Item> {
        val array = JSONArray(prefs(context).getString("items", "[]"))
        return List(array.length()) { parse(array.getJSONObject(it)) }.sortedByDescending { it.updatedAt }
    }

    /** Saves where a video is. Nearly finished (or barely started) videos aren't worth resuming. */
    fun save(context: Context, item: Item, frame: Bitmap?) {
        val done = item.duration > 0 && item.position > item.duration * 0.95
        val barelyStarted = item.position < 60_000
        val others = list(context).filter { it.id != item.id }
        val next = if (done || barelyStarted) others else (listOf(item) + others).take(MAX_ITEMS)
        write(context, next)
        if (frame != null && !done) runCatching {
            thumb(context, item.id).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        if (done) thumb(context, item.id).delete()
        HomePrefs.changed()
    }

    fun remove(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
        thumb(context, id).delete()
        HomePrefs.changed()
    }

    private fun write(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().put("id", item.id).put("title", item.title).put("url", item.url).put("type", item.type)
                    .put("subtitles", JSONArray(item.subtitles)).put("subtitleNames", JSONArray(item.subtitleNames))
                    .put("headers", JSONObject(item.headers)).put("position", item.position)
                    .put("duration", item.duration).put("updatedAt", item.updatedAt)
            )
        }
        prefs(context).edit().putString("items", array.toString()).apply()
    }

    private fun parse(o: JSONObject): Item {
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        val headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
        return Item(
            o.getString("id"), o.optString("title"), o.getString("url"), o.optString("type").ifEmpty { null },
            strings("subtitles"), strings("subtitleNames"), headers,
            o.optLong("position"), o.optLong("duration"), o.optLong("updatedAt"),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences("history", Context.MODE_PRIVATE)
}

/**
 * Android TV's standard "Watch Next" list, which apps publish their in-progress videos to.
 * Fire OS may keep it private; then this simply returns nothing.
 */
object WatchNext {
    data class Entry(val title: String, val pkg: String, val intentUri: String?, val posterUri: String?)

    @Volatile private var cache: List<Entry> = emptyList()
    @Volatile private var fetchedAt = 0L

    fun entries(): List<Entry> = cache

    /** Re-reads the list through ADB at most every 5 minutes (runs off the main thread). */
    fun refresh() {
        if (Adb.state != Adb.State.OK || System.currentTimeMillis() - fetchedAt < 5 * 60_000) return
        fetchedAt = System.currentTimeMillis()
        val out = runCatching {
            Adb.shell("content query --uri content://android.media.tv/watch_next_program --projection title:package_name:intent_uri:poster_art_uri 2>&1")
        }.getOrDefault("")
        // Rows look like: "Row: 0 title=…, package_name=…, intent_uri=…, poster_art_uri=…"
        val rows = out.lines().filter { it.startsWith("Row:") }.mapNotNull { row ->
            fun field(name: String) = Regex("$name=(.*?)(, [a-z_]+=|$)").find(row)?.groupValues?.get(1)?.takeUnless { it == "NULL" }
            val title = field("title") ?: return@mapNotNull null
            Entry(title, field("package_name") ?: "", field("intent_uri"), field("poster_art_uri"))
        }
        cache = rows
        if (rows.isNotEmpty()) HomePrefs.changed()
    }
}
