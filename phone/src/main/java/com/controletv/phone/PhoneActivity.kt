package com.controletv.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone app: finds the TV on the network, shows the same controller page the TV
 * serves (in a full-screen WebView) and keeps the ADB relay running behind it.
 */
class PhoneActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var relay: Relay? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val tvFiles by lazy { TvFiles(this) { relay } }
    private var airMouse: AirMouse? = null
    private var lastUpdateCheck = 0L
    private var lastTvCheck = 0L

    // Once connected, make sure the TV runs the latest version (installed through our ADB)
    private val tvCheck = object : Runnable {
        override fun run() {
            val r = relay
            val host = prefs.getString("host", null)
            val token = r?.token
            if (r != null && host != null && token != null && r.state == Relay.State.OK &&
                System.currentTimeMillis() - lastTvCheck > 30 * 60 * 1000L
            ) {
                lastTvCheck = System.currentTimeMillis()
                Thread { TvUpdater.updateIfNeeded(this@PhoneActivity, host, token, r)?.let { jsToast(it) } }.start()
            }
            main.postDelayed(this, 10_000)
        }
    }
    private var pendingUpload: Pair<String, String>? = null // call id to target folder

    private val prefs by lazy { getSharedPreferences("tv", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val host = prefs.getString("host", null)
        if (host == null) showDiscovery(null) else showController(host)
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(tvCheck)
        main.postDelayed(tvCheck, 5_000)
        // The app usually stays alive in the background, so check here and not only on launch
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck > 20 * 60 * 1000L) {
            lastUpdateCheck = now
            Thread { PhoneUpdater.check(this) }.start()
        }
    }

    // ---------- Controller ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun showController(host: String) {
        relay?.stop()
        relay = null
        web?.destroy()
        web = null

        val status = text("Conectando à TV…\n$host", 16f, MUTED).apply { gravity = Gravity.CENTER }
        val jsError = text("", 12f, Color.parseColor("#F87171")).apply {
            setPadding(dp(16), dp(8), dp(16), dp(16))
            visibility = android.view.View.GONE
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(frame)

        // Check the TV answers before loading: an IP that changed would otherwise hang on a black screen
        Thread {
            val alive = hello(host) != null
            main.post {
                if (isFinishing) return@post
                if (!alive) showDiscovery("A TV não respondeu em $host. Procurando de novo…", failedHost = host)
                else loadController(host, frame, status, jsError)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadController(host: String, frame: FrameLayout, status: TextView, jsError: TextView) {
        relay = Relay(this, host).also { it.start() }
        var finished = false
        val view = WebView(this).apply {
            setBackgroundColor(BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "ControleNative")
            // Holding a finger down is a remote-control gesture, not "select text / copy"
            setOnLongClickListener { true }
            isLongClickable = false
            isHapticFeedbackEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    finished = true
                    status.visibility = android.view.View.GONE
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) main.post { showDiscovery("Erro ao abrir a TV em $host: ${error.description}", failedHost = host) }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) main.post { showDiscovery("A TV respondeu com erro ${response.statusCode}. Atualize o app da TV.", failedHost = host) }
                }
            }
            // Shows JavaScript errors on screen instead of leaving it blank
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    return try {
                        startActivityForResult(intent, PICK_FILE)
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        false
                    }
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        jsError.text = "Erro na tela: ${message.message()} (linha ${message.lineNumber()})"
                        jsError.visibility = android.view.View.VISIBLE
                    }
                    return true
                }
            }
            loadUrl("http://$host:8765/")
        }
        web = view
        frame.addView(view, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(jsError, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        main.postDelayed({
            if (!finished && web === view) showDiscovery("A tela da TV demorou demais para carregar.", failedHost = host)
        }, 12_000)
    }

    /** Asks a host whether it's our TV server; returns its name or null. */
    private fun hello(ip: String): String? = try {
        val conn = URL("http://$ip:8765/api/hello").openConnection() as HttpURLConnection
        conn.connectTimeout = 2_500
        conn.readTimeout = 2_500
        val json = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
        json.optString("name", "Fire TV").takeIf { json.optString("app") == "controle-tv" }
    } catch (e: Exception) {
        null
    }

    /** Called from the page's JavaScript. */
    inner class Bridge {
        @JavascriptInterface
        fun setToken(token: String?) {
            relay?.token = token
            // CastActivity ("Tocar na TV") needs it without the WebView
            prefs.edit().putString("token", token).apply()
        }

        @JavascriptInterface
        fun relayState(): String = relay?.state?.name?.lowercase() ?: "off"

        @JavascriptInterface
        fun relayError(): String = relay?.error ?: ""

        /** Opens the file picker; the result is reported through window.onNativeInstall. */
        @JavascriptInterface
        fun pickApk() {
            main.post {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                startActivityForResult(intent, PICK_APK)
            }
        }

        /** Apps the user installed on this phone, as JSON, so they can be copied to the TV. */
        @JavascriptInterface
        fun phoneApps(): String {
            val pm = packageManager
            val list = JSONArray()
            pm.getInstalledApplications(0)
                .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
                .map { it to it.loadLabel(pm).toString() }
                .sortedBy { it.second.lowercase() }
                .forEach { (info, label) ->
                    list.put(
                        JSONObject().put("pkg", info.packageName).put("label", label)
                            .put("split", !info.splitSourceDirs.isNullOrEmpty())
                            .put("icon", iconBase64(info))
                    )
                }
            return list.toString()
        }

        @JavascriptInterface
        fun sendPhoneApp(pkg: String) {
            val info = try { packageManager.getApplicationInfo(pkg, 0) } catch (e: Exception) { return }
            val files = listOf(File(info.sourceDir)) + info.splitSourceDirs.orEmpty().map { File(it) }
            installOnTv(files, info.loadLabel(packageManager).toString())
        }

        /**
         * Async calls from the page (file manager): runs [method] off the UI thread and answers
         * through window.nativeResult(id, ok, json), with window.nativeProgress for copies.
         */
        @JavascriptInterface
        fun call(id: String, method: String, argsJson: String) {
            val args = JSONObject(argsJson)
            if (method == "upload") {
                pendingUpload = id to args.getString("folder")
                main.post { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_UPLOAD) }
                return
            }
            Thread {
                try {
                    val result: Any = when (method) {
                        "list" -> tvFiles.list(args.getString("path"))
                        "delete" -> tvFiles.delete(args.getString("path"))
                        "rename" -> tvFiles.rename(args.getString("path"), args.getString("name"))
                        "mkdir" -> tvFiles.mkdir(args.getString("path"), args.getString("name"))
                        "install" -> tvFiles.installApk(args.getString("path"))
                        "open" -> tvFiles.open(args.getString("path"), args.getString("mime"))
                        "allowRead" -> tvFiles.allowPlayerToRead()
                        "download" -> tvFiles.download(args.getString("path"), args.optLong("size")) { done, total -> progress(id, done, total) }
                        else -> throw IllegalArgumentException("Função desconhecida: $method")
                    }
                    answer(id, true, if (result is JSONObject) result.toString() else JSONObject.quote(result.toString()))
                } catch (e: Exception) {
                    answer(id, false, JSONObject.quote(e.message ?: e.javaClass.simpleName))
                }
            }.start()
        }

        /** Air mouse: the pointer follows the phone's movement while [active]. Returns an error, or "". */
        @JavascriptInterface
        fun airMouse(active: Boolean): String {
            val host = prefs.getString("host", null) ?: return "TV não encontrada"
            return try {
                if (active) {
                    if (relay?.token == null) return "Ainda conectando à TV"
                    val mouse = airMouse ?: AirMouse(this@PhoneActivity, host) { relay?.token }.also { airMouse = it }
                    if (!mouse.available) return "Este celular não tem giroscópio"
                    mouse.start()
                } else {
                    airMouse?.stop()
                }
                ""
            } catch (e: Exception) {
                e.message ?: "Falha no mouse aéreo"
            }
        }

        @JavascriptInterface
        fun version(): Int = BuildConfig.VERSION_CODE

        /** "Atualizar a TV" button: installs the latest TV app through the phone's ADB. */
        @JavascriptInterface
        fun updateTv() {
            val r = relay ?: return jsToast("Ainda conectando à TV")
            val host = prefs.getString("host", null) ?: return
            val token = r.token ?: return jsToast("Ainda conectando à TV")
            jsToast("Verificando a versão da TV…")
            Thread {
                jsToast(TvUpdater.updateIfNeeded(this@PhoneActivity, host, token, r, force = false) ?: "A TV já está na versão mais nova")
            }.start()
        }

        /** Air mode as a switch: "move" (pointer follows the phone), "scroll" (tilt to scroll) or "off". */
        @JavascriptInterface
        fun airMode(mode: String): String {
            if (mode == "off") { airMouse?.stop(); return "" }
            val problem = airMouse(true)
            if (problem.isNotEmpty()) return problem
            airMouse?.scrollMode = mode == "scroll"
            return ""
        }

        @JavascriptInterface
        fun airMouseSent(): Long = airMouse?.sent ?: 0

        @JavascriptInterface
        fun hasGyroscope(): Boolean = (getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager)
            .getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null

        @JavascriptInterface
        fun forgetTv() {
            main.post {
                prefs.edit().remove("host").apply()
                showDiscovery(null)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_UPLOAD) {
            val (id, folder) = pendingUpload ?: return
            pendingUpload = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) return answer(id, false, JSONObject.quote("cancelado"))
            Thread {
                try {
                    val name = tvFiles.upload(uri, folder) { done, total -> progress(id, done, total) }
                    answer(id, true, JSONObject.quote(name))
                } catch (e: Exception) {
                    answer(id, false, JSONObject.quote(e.message ?: e.javaClass.simpleName))
                }
            }.start()
            return
        }
        if (requestCode == PICK_FILE) {
            fileCallback?.onReceiveValue(data?.data?.takeIf { resultCode == RESULT_OK }?.let { arrayOf(it) })
            fileCallback = null
            return
        }
        val uri = data?.data
        if (requestCode != PICK_APK || resultCode != RESULT_OK || uri == null) return
        report("working", "Copiando o arquivo…")
        Thread {
            try {
                val apk = File(cacheDir, "send.apk")
                contentResolver.openInputStream(uri)!!.use { input -> apk.outputStream().use { input.copyTo(it) } }
                installOnTv(listOf(apk), uri.lastPathSegment?.substringAfterLast('/') ?: "APK")
            } catch (e: Exception) {
                report("error", "Não deu para ler o arquivo: ${e.message}")
            }
        }.start()
    }

    private fun installOnTv(files: List<File>, label: String) {
        val relay = relay
        if (relay == null || relay.state != Relay.State.OK) {
            report("error", "O controle completo (ADB) precisa estar ativo")
            return
        }
        report("working", "Instalando $label na TV… pode levar um minuto")
        Thread {
            try {
                relay.install(files)
                report("done", "$label instalado na TV")
            } catch (e: Exception) {
                val reason = e.message.orEmpty()
                report(
                    "error",
                    when {
                        "NO_MATCHING_ABIS" in reason -> "$label não é compatível com o processador da TV (versão errada: procure a armeabi-v7a ou universal)"
                        "OLDER_SDK" in reason -> "$label precisa de um Android mais novo que o da TV"
                        "UPDATE_INCOMPATIBLE" in reason -> "Já existe outra versão de $label na TV com assinatura diferente. Desinstale ela antes"
                        else -> "Falhou: $reason"
                    }
                )
            }
        }.start()
    }

    private fun jsToast(message: String) {
        main.post { web?.evaluateJavascript("window.toast && toast(${JSONObject.quote(message)})", null) }
    }

    private fun answer(id: String, ok: Boolean, json: String) {
        main.post { web?.evaluateJavascript("window.nativeResult && window.nativeResult(${JSONObject.quote(id)}, $ok, $json)", null) }
    }

    private fun progress(id: String, done: Long, total: Long) {
        main.post { web?.evaluateJavascript("window.nativeProgress && window.nativeProgress(${JSONObject.quote(id)}, $done, $total)", null) }
    }

    private fun report(state: String, message: String) = main.post {
        web?.evaluateJavascript(
            "window.onNativeInstall && window.onNativeInstall(${JSONObject.quote(state)}, ${JSONObject.quote(message)})", null
        )
    }

    private fun iconBase64(info: ApplicationInfo): String {
        val size = dp(40)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        info.loadIcon(packageManager).apply { setBounds(0, 0, size, size); draw(Canvas(bitmap)) }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // Phone volume buttons control the TV volume while the app is open
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 24
            KeyEvent.KEYCODE_VOLUME_DOWN -> 25
            else -> return super.dispatchKeyEvent(event)
        }
        val relay = relay
        if (relay == null || relay.state != Relay.State.OK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            Thread { runCatching { relay.shell("input keyevent $code") } }.start()
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tvCheck)
        airMouse?.stop()
    }

    override fun onDestroy() {
        airMouse?.stop()
        relay?.stop()
        web?.destroy()
        super.onDestroy()
    }

    // ---------- Discovery ----------

    private fun showDiscovery(message: String?, failedHost: String? = null) {
        relay?.stop()
        relay = null
        web?.destroy()
        web = null

        val status = text(message ?: "Procurando a TV na rede…", 15f, MUTED)
        val found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val manual = EditText(this).apply {
            hint = "ou digite o IP da TV"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(SURFACE, LINE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val connect = button("Conectar", CYAN, BG) {
            val ip = manual.text.toString().trim()
            if (ip.isNotEmpty()) choose(ip)
        }
        val again = button("Procurar de novo", SURFACE, TEXT) { showDiscovery(null) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(72), dp(24), dp(24))
            addView(text("CONTROLE TV", 13f, CYAN, bold = true).apply { letterSpacing = 0.25f })
            addView(text("Encontrar a TV", 30f, TEXT, bold = true).apply { setPadding(0, dp(8), 0, dp(4)) })
            addView(status)
            addView(found, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
            addView(manual, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
            addView(connect, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(again, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        setContentView(root)

        Thread {
            val tvs = scan()
            main.post {
                if (web != null) return@post
                if (tvs.isEmpty()) {
                    status.text = (message?.let { "$it\n\n" } ?: "") +
                        "Nenhuma TV encontrada. Abra o Controle TV na TV e confira se o celular está no mesmo Wi-Fi (ou com o hotspot ligado)."
                } else {
                    status.text = message ?: "Toque na sua TV:"
                    tvs.forEach { (ip, name) ->
                        found.addView(
                            button("$name  ·  $ip", SURFACE, TEXT) { choose(ip) },
                            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
                        )
                    }
                    if (tvs.size == 1 && tvs[0].first != failedHost) choose(tvs[0].first)
                }
            }
        }.start()
    }

    private fun choose(ip: String) {
        prefs.edit().putString("host", ip).apply()
        showController(ip)
    }

    /** Looks for our TV server (port 8765) on every /24 network the phone is on. */
    private fun scan(): List<Pair<String, String>> {
        val prefixes = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .map { it.hostAddress!!.substringBeforeLast('.') }
            .distinct()
        val pool = Executors.newFixedThreadPool(48)
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        for (prefix in prefixes) for (i in 1..254) {
            val ip = "$prefix.$i"
            pool.execute {
                hello(ip)?.let { results += ip to it }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return results.sortedBy { it.first }
    }

    // ---------- Small view helpers ----------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(fg)
        background = rounded(bg, if (bg == SURFACE) LINE else bg)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), stroke)
    }

    private companion object {
        const val PICK_APK = 1
        const val PICK_FILE = 2
        const val PICK_UPLOAD = 3
        val BG = Color.parseColor("#07070C")
        val SURFACE = Color.parseColor("#12121D")
        val LINE = Color.parseColor("#2A2A3A")
        val TEXT = Color.parseColor("#F2F2F7")
        val MUTED = Color.parseColor("#8A8AA3")
        val CYAN = Color.parseColor("#22D3EE")
    }
}
