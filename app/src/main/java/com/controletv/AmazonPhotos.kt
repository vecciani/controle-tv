package com.controletv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A shared Amazon Photos album ("Compartilhar → Copiar link", amazon.com/photos/share/…) for the
 * screensaver: read through the same public endpoints the shared page uses, no account needed.
 */
object AmazonPhotos {
    fun isLink(url: String) = Regex("(?i)amazon\\.[a-z.]+/photos/share/|amzn\\.(to|eu)/").containsMatchIn(url)

    /** The album's photos (id and a link to the picture at TV size). Blocking. */
    fun photos(link: String): List<GooglePhotos.Photo> {
        // Short links (amzn.eu/…) lead to the full one; the host tells the region (amazon.co.uk…)
        val full = resolve(link)
        val host = Regex("https://(www\\.amazon\\.[a-z.]+)/").find(full)?.groupValues?.get(1) ?: "www.amazon.com"
        val shareId = Regex("/photos/share/([\\w-]+)").find(full)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Não achei o álbum nesse link do Amazon Fotos")
        val share = json("https://$host/drive/v1/shares/$shareId?shareId=$shareId&resourceVersion=V2&ContentType=JSON")
        val nodeId = share.optJSONObject("nodeInfo")?.optString("id")?.ifEmpty { null }
            ?: throw IllegalStateException(share.optString("message").ifEmpty { "Álbum não encontrado: confira se o link está compartilhado" })
        val out = mutableListOf<GooglePhotos.Photo>()
        var offset = 0
        while (out.size < 300) {
            val page = json("https://$host/drive/v1/nodes/$nodeId/children?asset=ALL&limit=200&searchOnFamily=false&tempLink=true" +
                "&shareId=$shareId&offset=$offset&resourceVersion=V2&ContentType=JSON")
            val data = page.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val node = data.getJSONObject(i)
                val type = node.optJSONObject("contentProperties")?.optString("contentType").orEmpty()
                val link2 = node.optString("tempLink")
                if (!type.startsWith("image/") || link2.isEmpty()) continue
                val image = node.optJSONObject("contentProperties")?.optJSONObject("image")
                out += GooglePhotos.Photo(node.optString("id"), "$link2?viewBox=1920,1080", image?.optInt("width") ?: 1920, image?.optInt("height") ?: 1080)
            }
            if (data.length() < 200) break
            offset += 200
        }
        return out
    }

    private fun resolve(link: String): String {
        var current = URL(link.trim())
        repeat(5) {
            if (current.path.contains("/photos/share/")) return current.toString()
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            current = URL(current, location ?: return current.toString())
        }
        return current.toString()
    }

    private fun json(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val stream = (if (code < 400) conn.inputStream else conn.errorStream) ?: throw IllegalStateException("Amazon Fotos respondeu $code")
        val raw = stream.use { s -> if (conn.contentEncoding == "gzip") GZIPInputStream(s).readBytes() else s.readBytes() }
        conn.disconnect()
        return JSONObject(raw.decodeToString())
    }
}
