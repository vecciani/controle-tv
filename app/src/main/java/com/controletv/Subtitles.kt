package com.controletv

import android.content.Context
import androidx.media3.common.MimeTypes
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtitle files for the player: downloads them, fixes their text encoding (many Brazilian
 * .srt files are Windows-1252, which shows up as "NÃ£o" if read as UTF-8) and tells formats apart.
 */
object Subtitles {
    private const val MAX_BYTES = 5 * 1024 * 1024

    /** Saves subtitle bytes (from the phone or a download) as a clean UTF-8 file. */
    fun save(context: Context, name: String, bytes: ByteArray): File {
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("Legenda grande demais")
        val dir = File(context.cacheDir, "subs").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60).ifEmpty { "legenda" }
        return File(dir, "${System.currentTimeMillis()}-$safe").apply { writeText(toUtf8(bytes)) }
    }

    fun download(context: Context, url: String, headers: Map<String, String>): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        return save(context, url.substringAfterLast('/').substringBefore('?'), bytes)
    }

    /** Best guess of a subtitle's language from its name or link ("pt-br", "Português", "eng"...). */
    fun languageOf(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("pt[-_ ]?br|portugu|brazil|brasil|(^|[^a-z])(pt|por|pob|ptbr)([^a-z]|$)").containsMatchIn(t) -> "pt"
            Regex("english|ingl|(^|[^a-z])(en|eng)([^a-z]|$)").containsMatchIn(t) -> "en"
            Regex("espa|spanish|(^|[^a-z])(es|spa)([^a-z]|$)").containsMatchIn(t) -> "es"
            else -> null
        }
    }

    /** Language codes the player matches against track languages. */
    fun codesFor(language: String): Array<String> = when (language) {
        "pt" -> arrayOf("pt", "pt-BR", "por")
        "es" -> arrayOf("es", "spa")
        "off" -> emptyArray()
        else -> arrayOf("en", "eng")
    }

    fun labelFor(language: String?, index: Int) = when (language) {
        "pt" -> "Português"
        "en" -> "Inglês"
        "es" -> "Espanhol"
        else -> "Legenda ${index + 1}"
    }

    fun mimeOf(file: File): String {
        val head = file.bufferedReader().use { r -> CharArray(512).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
        return when {
            head.trimStart().startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
            "[Script Info]" in head -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun toUtf8(bytes: ByteArray): String {
        // Byte order marks first
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).trimStart('﻿')
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).trimStart('﻿')
        // Valid UTF-8 stays as is; anything else is almost always Windows-1252 (Latin-1 superset)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
