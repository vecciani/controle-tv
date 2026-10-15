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
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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

    private val prefs by lazy { getSharedPreferences("tv", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val host = prefs.getString("host", null)
        if (host == null) showDiscovery(null) else showController(host)
        Thread { PhoneUpdater.check(this) }.start()
    }

    // ---------- Controller ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun showController(host: String) {
        relay?.stop()
        relay = Relay(this, host).also { it.start() }

        val view = WebView(this).apply {
            setBackgroundColor(BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "ControleNative")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) main.post { showDiscovery("Não achei a TV em $host. Ela está ligada e no mesmo Wi-Fi?") }
                }
            }
            loadUrl("http://$host:8765/")
        }
        web = view
        setContentView(view)
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

    override fun onDestroy() {
        relay?.stop()
        web?.destroy()
        super.onDestroy()
    }

    // ---------- Discovery ----------

    private fun showDiscovery(message: String?) {
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
                    if (tvs.size == 1 && message == null) choose(tvs[0].first)
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
                try {
                    val conn = URL("http://$ip:8765/api/hello").openConnection() as HttpURLConnection
                    conn.connectTimeout = 600
                    conn.readTimeout = 1500
                    val body = conn.inputStream.use { it.readBytes().decodeToString() }
                    val json = JSONObject(body)
                    if (json.optString("app") == "controle-tv") results += ip to json.optString("name", "Fire TV")
                } catch (e: Exception) { /* nothing there */ }
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
        val BG = Color.parseColor("#07070C")
        val SURFACE = Color.parseColor("#12121D")
        val LINE = Color.parseColor("#2A2A3A")
        val TEXT = Color.parseColor("#F2F2F7")
        val MUTED = Color.parseColor("#8A8AA3")
        val CYAN = Color.parseColor("#22D3EE")
    }
}
