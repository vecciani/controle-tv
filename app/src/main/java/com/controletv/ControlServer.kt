package com.controletv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP server the phone talks to. Serves the controller page from assets/web
 * and a small JSON API under /api.
 */
class ControlServer(
    private val context: Context,
    private val pairing: Pairing,
) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message))
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri
        if (!path.startsWith("/api/")) return asset(path)

        if (path == "/api/pair" && session.method == Method.POST) {
            val pin = body(session).optString("pin")
            val token = pairing.pair(pin)
                ?: return json(Response.Status.FORBIDDEN, JSONObject().put("error", "PIN errado"))
            return json(Response.Status.OK, JSONObject().put("token", token))
        }

        // <img> tags can't send headers, so the token may also come in the query string
        val token = session.headers["x-token"] ?: session.parameters["token"]?.firstOrNull()
        if (!pairing.isValid(token)) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Não pareado"))
        }

        return when {
            path == "/api/apps" -> {
                val apps = JSONArray()
                Apps.list(context).forEach {
                    apps.put(JSONObject().put("pkg", it.pkg).put("label", it.label))
                }
                json(Response.Status.OK, JSONObject().put("apps", apps))
            }

            path == "/api/icon" -> {
                val pkg = session.parameters["pkg"]?.firstOrNull()
                val png = pkg?.let { Apps.icon(context, it) }
                    ?: return json(Response.Status.NOT_FOUND, JSONObject())
                newFixedLengthResponse(Response.Status.OK, "image/png", png.inputStream(), png.size.toLong())
                    .apply { addHeader("Cache-Control", "max-age=86400") }
            }

            path == "/api/launch" && session.method == Method.POST -> {
                val ok = Apps.launch(context, body(session).optString("pkg"))
                json(Response.Status.OK, JSONObject().put("ok", ok))
            }

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "Rota desconhecida"))
        }
    }

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"]?.let { JSONObject(it) } ?: JSONObject()
    }

    private fun asset(path: String): Response {
        val name = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
        if (".." in name) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "")
        val bytes = try {
            context.assets.open("web/$name").use { it.readBytes() }
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Não encontrado")
        }
        return newFixedLengthResponse(Response.Status.OK, mimeFor(name), bytes.inputStream(), bytes.size.toLong())
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun json(status: Response.Status, body: JSONObject) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())

    companion object {
        const val PORT = 8765
    }
}
