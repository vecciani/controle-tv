package com.controletv

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Sorts apps into folders for the TV home: by the category the app declares and by its
 * name, with the user's own choices (made from the home's options menu) taking priority.
 */
object Folders {
    data class Folder(val id: String, val title: String)

    val ALL = listOf(
        Folder("filmes", "Filmes, séries e vídeos"),
        Folder("canais", "Canais e TV ao vivo"),
        Folder("musica", "Música e rádio"),
        Folder("jogos", "Jogos"),
        Folder("ferramentas", "Ferramentas"),
        Folder("outros", "Outros apps"),
    )

    private val RULES = listOf(
        "canais" to Regex("(?i)iptv|tivimate|smarters|xciptv|ott ?navigator|perfect ?player|pluto|tv ?ao ?vivo|canais|channels|live ?tv|sstv|tvexpress|ssiptv|m3u"),
        "musica" to Regex("(?i)spotify|deezer|tidal|soundcloud|music|m[uú]sica|r[aá]dio|radio|podcast|amazon ?mp3|com\\.amazon\\.mp3|palco"),
        "filmes" to Regex("(?i)netflix|prime ?video|avod|disney|hbo|\\bmax\\b|globoplay|paramount|star ?\\+|starplus|crunchyroll|plex|kodi|stremio|apple ?tv|mubi|youtube|twitch|vimeo|filmes|movies|cinema|series|s[eé]ries|anime|looke|telecine|claro ?tv|vix|tubi|rakuten|mx ?player|vlc|player"),
        "jogos" to Regex("(?i)game|jogo|retroarch|emulat|luna|xbox|playstation|steam ?link|moonlight"),
        "ferramentas" to Regex("(?i)downloader|send ?files|arquivo|files?\\b|explorer|x-plore|browser|navegador|silk|firefox|chrome|vpn|speed ?test|analiti|aptoide|cleaner|easy ?fire|bugjaeger|adb|wifi|teclado|keyboard|remote|controle"),
    )

    fun folderOf(context: Context, pkg: String, label: String): String {
        overrides(context)[pkg]?.let { return it }
        val text = "$label $pkg"
        RULES.firstOrNull { it.second.containsMatchIn(text) }?.let { return it.first }
        val declared = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        if (declared != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (declared.category) {
                ApplicationInfo.CATEGORY_VIDEO -> return "filmes"
                ApplicationInfo.CATEGORY_AUDIO -> return "musica"
                ApplicationInfo.CATEGORY_GAME -> return "jogos"
                ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_IMAGE,
                ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_MAPS, ApplicationInfo.CATEGORY_SOCIAL -> return "ferramentas"
            }
        }
        return "outros"
    }

    fun move(context: Context, pkg: String, folder: String) {
        prefs(context).edit().putString(pkg, folder).apply()
        HomePrefs.changed()
    }

    private fun overrides(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private fun prefs(context: Context) = context.getSharedPreferences("folders", Context.MODE_PRIVATE)
}
