package com.controletv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The TV home screen, drawn with plain Android views so remote-control navigation is
 * handled by the system itself: instant and smooth even on modest Fire TV hardware.
 * Everything it shows comes from inside the app (no web page, no network, no ADB).
 *
 * OK opens an app; holding OK pins it to (or removes it from) the favorites row.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val pairing by lazy { Pairing(this) }

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greeting: TextView
    private lateinit var phoneChip: TextView
    private lateinit var adbChip: TextView
    private lateinit var connectCard: LinearLayout
    private lateinit var connectQr: ImageView
    private lateinit var connectTitle: TextView
    private lateinit var connectUrl: TextView
    private lateinit var firstRun: FrameLayout
    private lateinit var firstRunQr: ImageView
    private lateinit var firstRunUrl: TextView
    private lateinit var updateButton: Button
    private lateinit var rows: LinearLayout

    private var shownFavorites: List<String> = emptyList()
    private var shownApps: List<String> = emptyList()
    private var shownUrl: String? = null
    private var lastMinute = -1

    private val unit by lazy { resources.displayMetrics.widthPixels / 100f } // 1% of screen width

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps(force = true)
    }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            updateStatus()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ControlService.start(this)
        setContentView(buildScreen())
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onResume() {
        super.onResume()
        loadApps(force = false)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        loader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // This is the home screen: Back stays here
    }

    // ---------- Layout ----------

    private fun buildScreen(): View {
        val root = FrameLayout(this).apply { background = NeonBackground() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(4f), u(2.5f), u(4f), 0)
            clipChildren = false
            clipToPadding = false
        }

        // Top: clock on the left, status and "connect a phone" on the right
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text(9f, TEXT, bold = true).apply {
            letterSpacing = -0.04f
            setShadowLayer(u(1.2f).toFloat(), 0f, 0f, 0x7322D3EE)
        }
        date = text(1.7f, MUTED)
        greeting = text(1.8f, CYAN, bold = true)
        left.addView(clock)
        left.addView(date)
        left.addView(greeting)
        top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        phoneChip = chip()
        adbChip = chip()
        right.addView(phoneChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = u(0.8f) })
        right.addView(adbChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = u(1.2f) })
        connectCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, u(1.2f).toFloat(), CYAN_DIM)
            setPadding(u(1f), u(1f), u(1.4f), u(1f))
        }
        connectQr = ImageView(this)
        val connectText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u(1.2f), 0, 0, 0) }
        connectTitle = text(1.2f, TEXT, bold = true).apply { text = "Conectar outro celular" }
        connectUrl = text(1.0f, MUTED)
        connectText.addView(connectTitle)
        connectText.addView(connectUrl)
        connectCard.addView(connectQr, LinearLayout.LayoutParams(u(6.5f), u(6.5f)))
        connectCard.addView(connectText)
        right.addView(connectCard)
        top.addView(right)
        content.addView(top)

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        content.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = u(2.5f) })
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Update waiting for confirmation (when ADB can't install it silently)
        updateButton = Button(this).apply {
            text = "Nova versão do Controle TV pronta: aperte OK para instalar"
            isAllCaps = false
            textSize = px(1.4f)
            setTextColor(BG)
            background = rounded(CYAN, u(3f).toFloat(), CYAN)
            setPadding(u(2.4f), u(1f), u(2.4f), u(1f))
            visibility = View.GONE
            setOnClickListener {
                Thread {
                    val error = runCatching { Updater.installPending(this@MainActivity) }.exceptionOrNull()
                    if (error != null) runOnUiThread { toast(error.message ?: "Falhou") }
                }.start()
            }
        }
        root.addView(updateButton, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = u(2.5f) })

        // First run: big centered card until a phone pairs
        firstRun = FrameLayout(this).apply {
            setBackgroundColor(0xD907070C.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(SURFACE, u(1.6f).toFloat(), CYAN_DIM)
            setPadding(u(4f), u(3f), u(4f), u(3f))
        }
        firstRunQr = ImageView(this)
        card.addView(firstRunQr, LinearLayout.LayoutParams(u(20f), u(20f)).apply { bottomMargin = u(1.5f) })
        card.addView(text(2.4f, TEXT, bold = true).apply { text = "Abra o Controle TV no celular" })
        card.addView(text(1.4f, MUTED).apply { text = "ou aponte a câmera para o código" })
        firstRunUrl = text(1.3f, CYAN)
        card.addView(firstRunUrl)
        firstRun.addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(firstRun, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        return root
    }

    // ---------- Apps rows ----------

    private fun loadApps(force: Boolean) {
        loader.execute {
            val apps = Apps.list(this)
            val favorites = Apps.favorites(this).filter { pkg -> apps.any { it.pkg == pkg } }
            runOnUiThread {
                val pkgs = apps.map { it.pkg }
                if (!force && pkgs == shownApps && favorites == shownFavorites) return@runOnUiThread
                val focused = (currentFocus?.tag as? String)
                shownApps = pkgs
                shownFavorites = favorites
                rows.removeAllViews()
                val byPkg = apps.associateBy { it.pkg }
                if (favorites.isNotEmpty()) rows.addView(row("Favoritos", favorites.mapNotNull { byPkg[it] }, favorites))
                rows.addView(row("Todos os apps", apps, favorites))
                // Keep the focus where it was, or start on the first app
                val target = focused?.let { rows.findViewWithTag<View>(it) } ?: firstCard()
                target?.requestFocus()
            }
        }
    }

    private fun firstCard(): View? {
        val firstRow = rows.getChildAt(0) as? LinearLayout ?: return null
        val strip = (firstRow.getChildAt(1) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout ?: return null
        return strip.getChildAt(0)
    }

    private fun row(title: String, apps: List<AppInfo>, favorites: List<String>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        row.addView(text(1.4f, MUTED, bold = true).apply {
            text = title.uppercase()
            letterSpacing = 0.1f
        })
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            // Room for the focused card to grow without being cut
            setPadding(u(1.5f), u(1.6f), u(1.5f), u(1.6f))
        }
        apps.forEach { strip.addView(card(it, it.pkg in favorites)) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(strip)
        }
        row.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = -u(1.5f)
            bottomMargin = u(1f)
        })
        return row
    }

    private fun card(app: AppInfo, favorite: Boolean): View {
        val w = u(15f)
        val h = w * 9 / 16
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            tag = app.pkg
            clipChildren = false
        }
        val art = FrameLayout(this).apply {
            background = rounded(SURFACE, u(0.9f).toFloat(), if (favorite) VIOLET_DIM else LINE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, u(0.9f).toFloat())
            }
            clipToOutline = true
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        art.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Neon ring shown only on the focused card
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = u(0.9f).toFloat()
                setStroke(u(0.25f), CYAN)
            }
            visibility = View.INVISIBLE
        }
        art.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val label = text(1.1f, TEXT).apply {
            text = app.label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
            setPadding(0, u(0.8f), 0, 0)
        }
        box.addView(art, LinearLayout.LayoutParams(w, h))
        box.addView(label, LinearLayout.LayoutParams(w, WRAP_CONTENT))
        box.layoutParams = LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { rightMargin = u(1.4f) }

        loadIcon(app.pkg, image)

        box.setOnFocusChangeListener { _, focused ->
            ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            val scale = if (focused) 1.1f else 1f
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(art, View.SCALE_X, scale),
                    ObjectAnimator.ofFloat(art, View.SCALE_Y, scale),
                    ObjectAnimator.ofFloat(label, View.ALPHA, if (focused) 1f else 0f),
                )
                duration = 140
                interpolator = DecelerateInterpolator()
                start()
            }
            art.elevation = if (focused) u(1f).toFloat() else 0f
        }
        box.setOnClickListener {
            toast("Abrindo ${app.label}")
            Apps.launch(this, app.pkg)
        }
        box.setOnLongClickListener {
            val favorites = Apps.favorites(this)
            if (app.pkg in favorites) {
                Apps.setFavorites(this, favorites - app.pkg)
                toast("${app.label} saiu dos favoritos")
            } else {
                Apps.setFavorites(this, favorites + app.pkg)
                toast("${app.label} fixado nos favoritos")
            }
            loadApps(force = true)
            true
        }
        return box
    }

    private fun loadIcon(pkg: String, into: ImageView) {
        icons[pkg]?.let { into.setImageDrawable(it); return }
        loader.execute {
            val drawable = try {
                packageManager.getApplicationBanner(pkg) ?: packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            } ?: return@execute
            icons[pkg] = drawable
            runOnUiThread { if (into.isAttachedToWindow || !isFinishing) into.setImageDrawable(drawable) }
        }
    }

    // ---------- Clock and status ----------

    private fun updateClock() {
        val now = Date()
        val minute = (now.time / 60_000).toInt()
        if (minute == lastMinute) return
        lastMinute = minute
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val day = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(now)
        date.text = day.replaceFirstChar { it.uppercase() }
        val hour = SimpleDateFormat("H", Locale.getDefault()).format(now).toInt()
        greeting.text = when { hour < 5 -> "Boa noite"; hour < 12 -> "Bom dia"; hour < 18 -> "Boa tarde"; else -> "Boa noite" }
        greeting.paint.shader = LinearGradient(0f, 0f, greeting.paint.measureText(greeting.text.toString()), 0f, CYAN, VIOLET, Shader.TileMode.CLAMP)
        greeting.invalidate()
    }

    private fun updateStatus() {
        val phoneActive = System.currentTimeMillis() - ControlServer.lastPhoneRequest < 15_000
        setChip(phoneChip, if (phoneActive) OK else MUTED, if (phoneActive) "Celular conectado" else if (pairing.hasPhones()) "Celular pareado" else "Nenhum celular")
        val (color, label) = when (Adb.state) {
            Adb.State.OK -> OK to "Controle total"
            Adb.State.WAITING -> WARN to "Aceite \"Permitir depuração\""
            Adb.State.OFF -> MUTED to "Controle básico"
        }
        setChip(adbChip, color, label)

        val url = Net.url()
        if (url != shownUrl) {
            shownUrl = url
            connectUrl.text = url ?: "Sem rede"
            firstRunUrl.text = url ?: "Conecte o Fire TV ao Wi-Fi"
            if (url != null) loader.execute {
                val png = Qr.png(url, 480)
                val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                runOnUiThread {
                    connectQr.setImageBitmap(bitmap)
                    firstRunQr.setImageBitmap(bitmap)
                }
            }
        }
        firstRun.visibility = if (pairing.hasPhones()) View.GONE else View.VISIBLE
        connectCard.visibility = if (url == null) View.GONE else View.VISIBLE

        val pending = Updater.pending != null
        if (pending && updateButton.visibility != View.VISIBLE) {
            updateButton.visibility = View.VISIBLE
            updateButton.requestFocus()
        } else if (!pending) {
            updateButton.visibility = View.GONE
        }

        // Favorites may have been changed from the phone
        if (Apps.favorites(this) != shownFavorites) loadApps(force = false)
    }

    private fun setChip(chip: TextView, dot: Int, label: String) {
        val text = "●  $label"
        if (chip.text == text && chip.tag == dot) return
        chip.tag = dot
        chip.text = android.text.SpannableString(text).apply {
            setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0)
        }
    }

    // ---------- Small helpers ----------

    private fun u(percent: Float) = (unit * percent).toInt()
    private fun px(percent: Float) = unit * percent / resources.displayMetrics.scaledDensity

    private fun text(sizePercent: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = px(sizePercent)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private fun chip() = text(1.15f, MUTED).apply {
        background = rounded(0xCC12121D.toInt(), u(3f).toFloat(), LINE)
        setPadding(u(1.3f), u(0.7f), u(1.3f), u(0.7f))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        setStroke(maxOf(1, u(0.08f)), stroke)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Dark background with two soft neon glows, drawn once (no blur, no animation). */
    private class NeonBackground : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            canvas.drawColor(BG)
            paint.shader = RadialGradient(0f, 0f, w * 0.55f, 0x2622D3EE, 0x0022D3EE, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w, 0f, w * 0.55f, 0x2EA855F7, 0x00A855F7, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w * 0.5f, h * 1.15f, w * 0.6f, 0x1FA855F7, 0x00A855F7, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    companion object {
        private val BG = Color.parseColor("#07070C")
        private val SURFACE = Color.parseColor("#12121D")
        private val LINE = Color.parseColor("#22222F")
        private val TEXT = Color.parseColor("#F2F2F7")
        private val MUTED = Color.parseColor("#8A8AA3")
        private val CYAN = Color.parseColor("#22D3EE")
        private val CYAN_DIM = Color.parseColor("#5922D3EE")
        private val VIOLET = Color.parseColor("#A855F7")
        private val VIOLET_DIM = Color.parseColor("#66A855F7")
        private val OK = Color.parseColor("#34D399")
        private val WARN = Color.parseColor("#FBBF24")

        /** App artwork, kept in memory so going back home never reloads it. */
        private val icons = ConcurrentHashMap<String, Drawable>()

        fun open(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
