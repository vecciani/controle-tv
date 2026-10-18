package com.controletv

import android.content.Context
import android.net.Uri
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP server the phone (and the TV's own screen) talks to. Serves the pages
 * and a small JSON API under /api.
 */
class ControlServer(
    private val context: Context,
    private val pairing: Pairing,
) : NanoHTTPD(PORT) {

    @Volatile
    private var foregroundCache: Pair<Long, String?> = 0L to null

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Adb.Unavailable) {
        error(Response.Status.CONFLICT, e.message)
    } catch (e: IllegalStateException) {
        error(Response.Status.CONFLICT, e.message)
    } catch (e: IllegalArgumentException) {
        error(Response.Status.BAD_REQUEST, e.message)
    } catch (e: Exception) {
        error(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri
        if (!path.startsWith("/api/")) return page(path)

        // Lets the phone app find the TV on the network
        if (path == "/api/hello") return ok(JSONObject().put("app", "controle-tv").put("name", android.os.Build.MODEL))

        if (path == "/api/pair" && session.method == Method.POST) {
            val token = pairing.pair(body(session).optString("pin"))
                ?: return error(Response.Status.FORBIDDEN, "PIN errado")
            return ok(JSONObject().put("token", token))
        }

        // <img> tags can't send headers, so the token may also come in the query string
        val token = session.headers["x-token"] ?: session.parameters["token"]?.firstOrNull()
        if (!pairing.isValid(token)) return error(Response.Status.UNAUTHORIZED, "Não pareado")
        val local = token == pairing.localKey
        if (!local) lastPhoneRequest = System.currentTimeMillis()

        val post = session.method == Method.POST
        // Commands from the phone count as using the TV (status polling doesn't)
        if (post) ScreenSaver.touch()
        return when {
            path == "/api/status" -> ok(status(local))

            path == "/api/settings" && post -> {
                val body = body(session)
                if (body.has("requirePin")) pairing.requirePin = body.getBoolean("requirePin")
                if (body.has("homeLauncher")) HomeWatcher.setRedirectHome(context, body.getBoolean("homeLauncher"))
                if (body.has("screensaver") || body.has("screensaverMinutes") || body.has("screensaverDim")) {
                    ScreenSaver.configure(
                        context,
                        if (body.has("screensaver")) body.getBoolean("screensaver") else null,
                        if (body.has("screensaverMinutes")) body.getInt("screensaverMinutes") else null,
                        if (body.has("screensaverDim")) body.getDouble("screensaverDim").toFloat() else null,
                    )
                    if (ScreenSaver.enabled(context)) HomeWatcher.enableForScreensaver(context)
                }
                if (body.has("homeName")) HomePrefs.setName(context, body.getString("homeName"))
                if (body.has("player")) PlayerActivity.setPreferred(context, body.optString("player").ifEmpty { null })
                if (body.has("subtitleLanguage")) {
                    val language = body.getString("subtitleLanguage")
                    PlayerActivity.setSubtitleLanguage(context, language)
                    // Get the speech model ready ahead of the next video
                    if (language == "auto") AutoCaptions.recognizerOrPrepare(context) {}
                }
                ok()
            }

            path == "/api/apps" -> {
                val favorites = Apps.favorites(context)
                val running = if (Adb.state == Adb.State.OK) runCatching { Remote.running(context) }.getOrDefault(emptyList()) else emptyList()
                val apps = JSONArray()
                Apps.list(context).forEach {
                    apps.put(
                        JSONObject().put("pkg", it.pkg).put("label", it.label)
                            .put("system", it.system).put("favorite", it.pkg in favorites)
                    )
                }
                ok(
                    JSONObject().put("apps", apps)
                        .put("favorites", JSONArray(favorites))
                        .put("running", JSONArray(running))
                )
            }

            path == "/api/icon" -> {
                val png = session.parameters["pkg"]?.firstOrNull()?.let { Apps.icon(context, it) }
                    ?: return error(Response.Status.NOT_FOUND, "Sem ícone")
                newFixedLengthResponse(Response.Status.OK, "image/png", png.inputStream(), png.size.toLong())
                    .apply { addHeader("Cache-Control", "max-age=86400") }
            }

            path == "/api/launch" && post -> {
                ok(JSONObject().put("ok", Apps.launch(context, body(session).optString("pkg"))))
            }

            path == "/api/key" && post -> {
                val body = body(session)
                Remote.key(body.optString("key"), body.optInt("times", 1))
                ok()
            }

            path == "/api/mouse/move" && post -> {
                val body = body(session)
                MouseCursor.move(context, body.optDouble("x").toFloat(), body.optDouble("y").toFloat())
                ok()
            }

            path == "/api/mouse/click" && post -> {
                MouseCursor.click(context, body(session).optBoolean("long"))
                ok()
            }

            path == "/api/mouse/scroll" && post -> {
                val body = body(session)
                MouseCursor.scroll(context, body.optDouble("x", 0.0).toFloat(), body.optDouble("y", 0.0).toFloat())
                ok()
            }

            path == "/api/text" && post -> {
                val body = body(session)
                Remote.type(body.optInt("backspaces"), body.optString("text"), body.optBoolean("enter"))
                ok()
            }

            path == "/api/keyboard" && post -> {
                Remote.useOurKeyboard(context, body(session).optBoolean("active"))
                ok()
            }

            path == "/api/stop" && post -> {
                Remote.stop(body(session).optString("pkg"))
                ok()
            }

            path == "/api/uninstall" && post -> {
                Remote.uninstall(context, body(session).optString("pkg"))
                ok()
            }

            path == "/api/favorites" && post -> {
                val array = body(session).optJSONArray("pkgs") ?: JSONArray()
                Apps.setFavorites(context, List(array.length()) { array.getString(it) })
                ok()
            }

            path == "/api/play" && post -> {
                val body = body(session)
                val url = body.getString("url")
                val local = url.startsWith("file:///sdcard/") || url.startsWith("file:///storage/")
                if (!local && !url.startsWith("http://") && !url.startsWith("https://")) throw IllegalArgumentException("Só links http(s) ou arquivos da TV podem tocar")
                val headers = body.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap()
                // Subtitle links, plus subtitle files the phone read from its own storage
                val subtitles = mutableListOf<String>()
                val names = mutableListOf<String>()
                body.optJSONArray("subtitles")?.let { list ->
                    for (i in 0 until list.length()) {
                        val sub = list.getJSONObject(i)
                        val url = sub.optString("url")
                        val data = sub.optString("data")
                        val name = sub.optString("name")
                        when {
                            data.isNotEmpty() -> subtitles += Uri.fromFile(Subtitles.save(context, name.ifEmpty { "legenda.srt" }, Base64.decode(data, Base64.DEFAULT))).toString()
                            url.startsWith("http") -> subtitles += url
                            else -> continue
                        }
                        names += name
                    }
                }
                body.optString("subtitle").takeIf { it.startsWith("http") }?.let { subtitles += it; names += "" }
                PlayerActivity.open(
                    context, url, body.optString("title").ifEmpty { null }, body.optString("type").ifEmpty { null },
                    subtitles, names, body.optLong("position"), headers,
                )
                PlayerActivity.noteFields(body.optString("fields"))
                ok()
            }

            path == "/api/player" -> ok(PlayerActivity.state() ?: JSONObject().put("closed", true))

            path == "/api/player/subtitle" && post -> {
                if (!PlayerActivity.selectSubtitle(body(session).getString("id"))) throw IllegalStateException("Nenhum vídeo tocando")
                ok()
            }

            path == "/api/player/seek" && post -> {
                val body = body(session)
                val delta = if (body.has("deltaMs")) body.getLong("deltaMs") else null
                val position = if (body.has("positionMs")) body.getLong("positionMs") else null
                if (!PlayerActivity.seekTo(delta, position)) throw IllegalStateException("Nenhum vídeo tocando")
                ok()
            }

            path == "/api/player/quality" && post -> {
                val policy = body(session).getString("quality")
                if (!Regex("auto|max|h\\d{3,4}").matches(policy)) throw IllegalArgumentException("Qualidade inválida")
                PlayerActivity.changeQuality(context, policy)
                ok()
            }

            path == "/api/player/sync" && post -> {
                val body = body(session)
                val delta = if (body.has("deltaMs")) body.getLong("deltaMs") else null
                if (!PlayerActivity.shiftSubtitle(delta, body.optBoolean("reset"))) throw IllegalStateException("Nenhum vídeo tocando")
                ok()
            }

            path == "/api/player/subtitle-file" && post -> {
                val body = body(session)
                val name = body.optString("name").ifEmpty { "legenda.srt" }
                val file = Subtitles.save(context, name, Base64.decode(body.getString("data"), Base64.DEFAULT))
                when (PlayerActivity.addSubtitle(file, name.substringBeforeLast('.'))) {
                    null -> throw IllegalStateException("Nenhum vídeo tocando")
                    false -> throw IllegalArgumentException("Não consegui ler esse arquivo de legenda")
                    true -> {}
                }
                ok()
            }

            path == "/api/screensaver/start" && post -> {
                ScreenSaver.start(context)
                ok()
            }

            path == "/api/stremio" -> ok(Stremio.status(context))

            path == "/api/stremio/login" && post -> {
                val body = body(session)
                Stremio.login(context, body.getString("email").trim(), body.getString("password"))
                ok(Stremio.status(context))
            }

            path == "/api/stremio/logout" && post -> {
                Stremio.logout(context)
                ok()
            }

            path == "/api/stremio/library" -> ok(JSONObject().put("items", Stremio.library(context)))

            path == "/api/stremio/catalog" -> {
                val type = session.parameters["type"]?.firstOrNull() ?: "movie"
                ok(JSONObject().put("metas", Stremio.catalog(type, session.parameters["q"]?.firstOrNull())))
            }

            path == "/api/stremio/meta" -> {
                val type = session.parameters["type"]?.firstOrNull() ?: "movie"
                ok(JSONObject().put("meta", Stremio.meta(type, session.parameters["id"]?.firstOrNull().orEmpty())))
            }

            path == "/api/stremio/open" && post -> {
                val body = body(session)
                Stremio.openOnTv(context, body.optString("type"), body.getString("id"), body.optString("videoId").ifEmpty { null })
                ok()
            }

            path == "/api/media" -> ok(MusicBridge.state(context))

            path == "/api/media/art" -> {
                val jpg = MusicBridge.artPng(context) ?: return error(Response.Status.NOT_FOUND, "Sem capa")
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpg.inputStream(), jpg.size.toLong())
            }

            path == "/api/media/control" && post -> {
                val body = body(session)
                MusicBridge.control(context, body.getString("action"), body.optLong("value"))
                ok()
            }

            path == "/api/media/search" && post -> {
                val body = body(session)
                val query = body.optString("query").trim()
                if (query.isEmpty()) throw IllegalArgumentException("Digite o que quer ouvir")
                val songs = MusicBridge.search(context, query, body.optBoolean("play", true))
                val list = JSONArray()
                songs.forEach { list.put(JSONObject().put("id", it.videoId).put("title", it.title).put("subtitle", it.subtitle).put("thumb", it.thumbnail)) }
                ok(JSONObject().put("songs", list))
            }

            path == "/api/media/song" && post -> {
                MusicBridge.playSong(context, body(session).getString("id"))
                ok()
            }

            path == "/api/home/layout" && !post -> ok(HomePrefs.describe(context))

            path == "/api/home/layout" && post -> {
                HomePrefs.apply(context, body(session))
                ok()
            }

            path == "/api/players" -> {
                val list = JSONArray()
                PlayerActivity.installedPlayers(context).forEach { (pkg, label) ->
                    list.put(JSONObject().put("pkg", pkg).put("label", label))
                }
                ok(JSONObject().put("players", list).put("preferred", PlayerActivity.preferred(context) ?: ""))
            }

            path == "/api/open-url" && post -> {
                val url = body(session).getString("url")
                if (!url.startsWith("http://") && !url.startsWith("https://")) throw IllegalArgumentException("Link inválido")
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ok()
            }

            path == "/api/mirror" && post -> ok(JSONObject().put("opened", Remote.mirror(context)))

            path == "/api/mirror/candidates" -> ok(JSONObject().put("candidates", JSONArray(Remote.mirrorCandidates())))

            path == "/api/mirror/try" && post -> {
                ok(JSONObject().put("output", Remote.tryMirror(context, body(session).optString("component"))))
            }

            path == "/api/abi" -> ok(JSONObject().put("abis", Adb.shell("getprop ro.product.cpu.abilist").trim()))

            path == "/api/update" && post -> ok(JSONObject().put("result", Updater.check(context)))

            path == "/api/update/install" && post -> {
                Updater.installPending(context)
                ok()
            }

            path == "/api/relay/next" -> {
                val state = runCatching { Adb.State.valueOf(session.parameters["adb"]?.firstOrNull()?.uppercase() ?: "OFF") }
                    .getOrDefault(Adb.State.OFF)
                val job = PhoneRelay.next(state, session.parameters["error"]?.firstOrNull())
                ok(if (job == null) JSONObject() else JSONObject().put("id", job.first).put("command", job.second))
            }

            path == "/api/relay/result" && post -> {
                val body = body(session)
                PhoneRelay.result(body.getLong("id"), body.optString("output", null), body.optString("error", null))
                ok()
            }

            path == "/api/adb/probe" && post -> ok(JSONObject().put("report", AdbProbe.run(context)))

            path == "/api/adb/retry" && post -> {
                Adb.retryNow()
                ok()
            }

            else -> error(Response.Status.NOT_FOUND, "Rota desconhecida")
        }
    }

    private fun status(local: Boolean): JSONObject {
        val json = JSONObject()
            .put("adb", Adb.state.name.lowercase())
            .put("via", Adb.via)
            .put("version", BuildConfig.VERSION_CODE)
            .put("webVersion", WebFiles.version(context))
            .put("update", Updater.lastResult)
            .put("updatePending", Updater.pending != null)
            .put("adbError", Adb.lastError)
            .put("lastPlay", PlayerActivity.lastPlay)
            .put("lastFields", PlayerActivity.lastFields)
            .put("homeLauncher", HomeWatcher.running && HomeWatcher.redirectHome(context))
            .put("screensaver", JSONObject()
                .put("enabled", ScreenSaver.enabled(context))
                .put("minutes", ScreenSaver.minutes(context))
                .put("dim", ScreenSaver.dim(context).toDouble())
                .put("photos", runCatching { ScreenSaver.photos().size }.getOrDefault(0))
                .put("folder", ScreenSaver.FOLDER))
            .put("homeName", HomePrefs.name(context))
            .put("airReceived", AirMouseReceiver.received)
            .put("airRejected", AirMouseReceiver.rejected)
            .put("pointerProblem", MouseCursor.problem)
            .put("requirePin", pairing.requirePin)
            .put("subtitleLanguage", PlayerActivity.subtitleLanguage(context))
            .put("autoCc", AutoCaptions.json())
            .put("device", "${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        foreground()?.let { pkg ->
            json.put("foreground", JSONObject().put("pkg", pkg).put("label", Apps.label(context, pkg)))
        }
        if (local) {
            json.put("url", Net.url())
                .put("pin", pairing.pin)
                .put("paired", pairing.hasPhones())
                .put("phoneActive", System.currentTimeMillis() - lastPhoneRequest < PHONE_ACTIVE_MS)
        }
        return json
    }

    /** Cached briefly because every open phone asks for it every few seconds. */
    private fun foreground(): String? {
        if (Adb.state != Adb.State.OK) return null
        val (at, pkg) = foregroundCache
        if (System.currentTimeMillis() - at < 1500) return pkg
        val fresh = runCatching { Remote.foreground(context) }.getOrNull()
        foregroundCache = System.currentTimeMillis() to fresh
        return fresh
    }

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"]?.let { JSONObject(it) } ?: JSONObject()
    }

    private fun page(path: String): Response {
        val name = when (path) {
            "/", "" -> "index.html"
            else -> path.trimStart('/')
        }
        if (".." in name) return error(Response.Status.FORBIDDEN, "")
        val bytes = WebFiles.read(context, name)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Não encontrado")
        return newFixedLengthResponse(Response.Status.OK, mimeFor(name), bytes.inputStream(), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "no-cache") }
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "webmanifest" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun ok(body: JSONObject = JSONObject().put("ok", true)) =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString())

    private fun error(status: Response.Status, message: String?) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", JSONObject().put("error", message ?: "Erro").toString())

    companion object {
        const val PORT = 8765

        /** When a phone last talked to us, so the TV home can show "phone connected". */
        @Volatile
        var lastPhoneRequest = 0L
            private set
        private const val PHONE_ACTIVE_MS = 15_000L
    }
}
