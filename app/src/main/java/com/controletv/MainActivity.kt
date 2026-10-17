package com.controletv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
 *
 * Layout: a greeting and a music widget (controls whatever is playing), three big featured apps
 * the user picks, then folder rows (films, live channels, music…) and a settings row with the
 * same shortcuts as the Fire TV home. Everything scrolls with the remote.
 * OK opens; holding OK (or the menu button) opens the app's options.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val pairing by lazy { Pairing(this) }

    private lateinit var clock: TextView
    private lateinit var phoneChip: TextView
    private lateinit var adbChip: TextView
    private lateinit var wifiButton: Button
    private lateinit var greeting: TextView
    private lateinit var date: TextView
    private lateinit var heroSub: TextView
    private lateinit var featuredRow: LinearLayout
    private lateinit var musicArt: ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var musicControls: LinearLayout
    private lateinit var musicPlay: ImageView
    private lateinit var musicAction: Button
    private var musicKey = ""
    private var askedMusicPermission = false
    private lateinit var rowsScroll: ScrollView
    private lateinit var rows: LinearLayout
    private lateinit var firstRun: FrameLayout
    private lateinit var firstRunQr: ImageView
    private lateinit var firstRunUrl: TextView
    private lateinit var updateButton: Button

    private var shownSignature = ""
    private var shownFavorites = ""
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
        // This is the home screen: Back goes to the top instead of leaving
        rowsScroll.smoothScrollTo(0, 0)
        firstCard()?.requestFocus()
    }

    // ---------- Layout ----------

    private fun buildScreen(): View {
        val root = FrameLayout(this).apply { background = NeonBackground() }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(3.5f), u(2f), u(3.5f), 0)
            // Let the focused card's ring and zoom spill into the margins instead of being cut
            clipChildren = false
            clipToPadding = false
        }

        // Top bar: status, clock and the Wi-Fi button on the right
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        phoneChip = chip()
        adbChip = chip()
        top.addView(phoneChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.8f) })
        top.addView(adbChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.6f) })
        clock = text(2.2f, TEXT, bold = true)
        top.addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.6f) })
        wifiButton = pillButton("Wi-Fi") {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        top.addView(wifiButton)
        content.addView(top)

        // Everything below scrolls with the remote
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, u(8f))
        }

        // Greeting on the left, music widget on the right
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hello = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        greeting = text(3.4f, TEXT, bold = true).apply {
            letterSpacing = -0.02f
            setShadowLayer(u(1f).toFloat(), 0f, 0f, 0x5522D3EE)
        }
        date = text(1.4f, MUTED)
        hello.addView(greeting)
        hello.addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.5f) })
        header.addView(hello, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(buildMusicWidget(), LinearLayout.LayoutParams(u(34f), WRAP_CONTENT))
        page.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(1.6f) })

        // Three big featured apps
        featuredRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(u(1.5f), u(1.6f), u(1.5f), u(1.2f))
        }
        page.addView(featuredRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = u(1.2f)
            leftMargin = -u(1.5f)
        })
        heroSub = text(1.3f, MUTED)
        page.addView(heroSub, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = u(1.6f) })

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        page.addView(rows)
        rowsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipChildren = false
            addView(page)
        }
        content.addView(rowsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = u(0.6f) })
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

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

        firstRun = FrameLayout(this).apply {
            setBackgroundColor(0xE607070C.toInt())
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

    // ---------- Rows ----------

    private fun loadApps(force: Boolean) {
        loader.execute {
            val apps = Apps.list(this)
            val favorites = Apps.favorites(this).filter { pkg -> apps.any { it.pkg == pkg } }
            val folders = apps.associate { it.pkg to Folders.folderOf(this, it.pkg, it.label) }
            val signature = apps.joinToString { it.pkg + "=" + folders[it.pkg] } + "|" + favorites.joinToString()
            runOnUiThread {
                if (!force && signature == shownSignature) return@runOnUiThread
                shownSignature = signature
                shownFavorites = Apps.favorites(this).joinToString()
                val focused = currentFocus?.tag as? String
                rows.removeAllViews()
                val byPkg = apps.associateBy { it.pkg }
                featuredRow.removeAllViews()
                val featuredWidth = (resources.displayMetrics.widthPixels - u(7f) - u(2.6f) * 2) / HomePrefs.FEATURED_SLOTS
                HomePrefs.featured(this, apps.map { it.pkg }).mapNotNull { byPkg[it] }.forEach { app ->
                    featuredRow.addView(appCard(app, app.pkg in favorites, Folders.ALL.firstOrNull { it.id == folders[app.pkg] }?.title ?: "Destaque", featuredWidth, "feat:"))
                }
                if (favorites.isNotEmpty()) {
                    rows.addView(row("★  Favoritos", favorites.mapNotNull { byPkg[it] }.map { appCard(it, true) }))
                }
                for (folder in Folders.ALL) {
                    val inFolder = apps.filter { folders[it.pkg] == folder.id }
                    if (inFolder.isNotEmpty()) rows.addView(row(folder.title, inFolder.map { appCard(it, it.pkg in favorites, folder.title) }))
                }
                rows.addView(row("Configurações", settingsTiles()))
                val target = focused?.let { rowsScroll.findViewWithTag<View>(it) } ?: featuredRow.getChildAt(0) ?: firstCard()
                target?.requestFocus()
            }
        }
    }

    private fun firstCard(): View? {
        val firstRow = rows.getChildAt(0) as? LinearLayout ?: return null
        val strip = (firstRow.getChildAt(1) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout ?: return null
        return strip.getChildAt(0)
    }

    private fun row(title: String, cards: List<View>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        row.addView(text(1.35f, MUTED, bold = true).apply {
            text = title.uppercase()
            letterSpacing = 0.1f
        })
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(u(1.5f), u(1.4f), u(1.5f), u(1.4f))
        }
        cards.forEach { strip.addView(it) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(strip)
        }
        row.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = -u(1.5f)
            bottomMargin = u(0.6f)
        })
        return row
    }

    private fun appCard(
        app: AppInfo, favorite: Boolean, folderTitle: String = "Favoritos", width: Int = u(13.5f), keyPrefix: String = "",
    ): View =
        card(
            key = keyPrefix + app.pkg, title = app.label, subtitle = folderTitle, favorite = favorite, width = width,
            bindImage = { loadIcon(app.pkg, it) },
            onOptions = { appOptions(app) },
        ) {
            toast("Abrindo ${app.label}")
            Apps.launch(this, app.pkg)
        }

    /** Fire TV's own screens, as on the Amazon home. Shortcuts the TV doesn't have are left out. */
    private fun settingsTiles(): List<View> {
        val tiles = mutableListOf<View>()
        fun add(key: String, title: String, icon: Int, intent: Intent) {
            if (intent.resolveActivity(packageManager) == null) return
            tiles += card(key, title, "Configurações da TV", false, { it.setImageResource(icon) }, iconTile = true) {
                runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { toast("Esta TV não abriu essa tela") }
            }
        }
        add("set:all", "Configurações", R.drawable.ic_t_settings, Intent(Settings.ACTION_SETTINGS))
        add("set:net", "Rede e Wi-Fi", R.drawable.ic_t_wifi, Intent(Settings.ACTION_WIFI_SETTINGS))
        add("set:display", "Tela e som", R.drawable.ic_t_display, Intent(Settings.ACTION_DISPLAY_SETTINGS))
        add("set:bt", "Controles e Bluetooth", R.drawable.ic_t_bluetooth, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        add("set:apps", "Gerenciar apps", R.drawable.ic_t_apps, Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        packageManager.getLaunchIntentForPackage("com.amazon.venezia")?.let { add("set:store", "Loja de apps", R.drawable.ic_t_store, it) }
        tiles += card("set:amazon", "Tela da Amazon", "Tela inicial original do Fire TV", false, { it.setImageResource(R.drawable.ic_t_amazon) }, iconTile = true) {
            HomeWatcher.openAmazonHome(this)
        }
        return tiles
    }

    private fun card(
        key: String, title: String, subtitle: String, favorite: Boolean, bindImage: (ImageView) -> Unit,
        iconTile: Boolean = false, width: Int = u(13.5f), onOptions: (() -> Unit)? = null, onClick: () -> Unit,
    ): View {
        val w = width
        val h = w * 9 / 16
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            tag = key
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
        val image = ImageView(this).apply {
            scaleType = if (iconTile) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (iconTile) setPadding(w / 3, h / 5, w / 3, h * 2 / 5)
        }
        art.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (iconTile) {
            art.addView(text(1.05f, TEXT, bold = true).apply {
                text = title
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(u(0.5f), 0, u(0.5f), h / 8)
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = u(0.9f).toFloat()
                setStroke(u(0.25f), CYAN)
            }
            visibility = View.INVISIBLE
        }
        art.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(art, LinearLayout.LayoutParams(w, h))
        box.layoutParams = LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { rightMargin = u(1.3f) }

        bindImage(image)

        box.setOnFocusChangeListener { _, focused ->
            ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            // Big featured tiles grow less, so they don't spill over their neighbours
            val scale = if (focused) (if (w > u(20f)) 1.05f else 1.12f) else 1f
            AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(art, View.SCALE_X, scale), ObjectAnimator.ofFloat(art, View.SCALE_Y, scale))
                duration = 130
                interpolator = DecelerateInterpolator()
                start()
            }
            art.elevation = if (focused) u(1f).toFloat() else 0f
            if (focused) {
                heroSub.text = "$title   ·   $subtitle" + if (onOptions != null) "   ·   OK abre   ·   segure OK para opções" else "   ·   OK abre"
            }
        }
        box.setOnClickListener { onClick() }
        if (onOptions != null) {
            box.setOnLongClickListener { onOptions(); true }
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { onOptions(); true } else false
            }
        }
        return box
    }

    // ---------- App options (hold OK / menu button) ----------

    private fun appOptions(app: AppInfo) {
        val favorites = Apps.favorites(this)
        val favorite = app.pkg in favorites
        val dialog = OptionsDialog(app.label)
        dialog.add(if (favorite) "Tirar dos favoritos" else "Fixar nos favoritos") {
            Apps.setFavorites(this, if (favorite) favorites - app.pkg else favorites + app.pkg)
            toast(if (favorite) "${app.label} saiu dos favoritos" else "${app.label} fixado nos favoritos")
            loadApps(force = true)
        }
        dialog.add("Destacar no topo…") { featurePicker(app) }
        dialog.add("Mover para pasta…") { folderPicker(app) }
        dialog.add("Informações do app") {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { toast("Esta TV não abriu essa tela") }
        }
        if (!app.system) {
            dialog.add("Desinstalar") {
                @Suppress("DEPRECATION")
                startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        dialog.show()
    }

    private fun featurePicker(app: AppInfo) {
        val dialog = OptionsDialog("Destacar ${app.label} na posição…")
        val current = HomePrefs.featured(this, Apps.list(this).map { it.pkg })
        for (slot in 0 until HomePrefs.FEATURED_SLOTS) {
            val now = current.getOrNull(slot)?.let { Apps.label(this, it) } ?: "vazio"
            dialog.add("${slot + 1}  (hoje: $now)") {
                HomePrefs.setFeatured(this, slot, app.pkg)
                toast("${app.label} em destaque na posição ${slot + 1}")
                loadApps(force = true)
            }
        }
        dialog.show()
    }

    private fun folderPicker(app: AppInfo) {
        val dialog = OptionsDialog("Mover ${app.label} para…")
        val current = Folders.folderOf(this, app.pkg, app.label)
        Folders.ALL.forEach { folder ->
            dialog.add((if (folder.id == current) "✓  " else "") + folder.title) {
                Folders.move(this, app.pkg, folder.id)
                toast("${app.label} agora está em ${folder.title}")
                loadApps(force = true)
            }
        }
        dialog.show()
    }

    /** A remote-friendly menu: a title and focusable buttons. */
    private inner class OptionsDialog(title: String) : Dialog(this@MainActivity) {
        private val list = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, u(1.4f).toFloat(), CYAN_DIM)
            setPadding(u(2f), u(1.8f), u(2f), u(1.8f))
            addView(text(1.8f, TEXT, bold = true).apply { text = title; setPadding(0, 0, 0, u(1.2f)) })
        }

        init {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(list)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        fun add(label: String, action: () -> Unit) {
            list.addView(Button(this@MainActivity).apply {
                text = label
                isAllCaps = false
                textSize = px(1.4f)
                setTextColor(TEXT)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(u(1.4f), u(0.9f), u(1.4f), u(0.9f))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(0.8f).toFloat(), CYAN))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, u(0.8f).toFloat(), Color.TRANSPARENT))
                }
                setOnClickListener { dismiss(); action() }
            }, LinearLayout.LayoutParams(u(30f), WRAP_CONTENT).apply { bottomMargin = u(0.4f) })
        }
    }

    // ---------- Music widget ----------

    private fun buildMusicWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, u(1.2f).toFloat(), VIOLET_DIM)
            setPadding(u(1f), u(1f), u(1f), u(1f))
        }
        musicArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, u(0.7f).toFloat())
            }
            clipToOutline = true
            setImageResource(R.drawable.ic_m_music)
        }
        box.addView(musicArt, LinearLayout.LayoutParams(u(6f), u(6f)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(1f), 0, u(0.6f), 0)
        }
        musicTitle = text(1.3f, TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        musicArtist = text(1.05f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(musicTitle)
        info.addView(musicArtist, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.3f) })
        musicControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, u(0.6f), 0, 0)
        }
        musicControls.addView(mediaButton(R.drawable.ic_m_prev, false) { MediaListener.current(this)?.transportControls?.skipToPrevious() })
        musicPlay = mediaButton(R.drawable.ic_m_play, true) {
            val c = MediaListener.current(this) ?: return@mediaButton
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            handler.postDelayed({ updateMusic() }, 300)
        }
        musicControls.addView(musicPlay)
        musicControls.addView(mediaButton(R.drawable.ic_m_next, false) { MediaListener.current(this)?.transportControls?.skipToNext() })
        info.addView(musicControls)
        musicAction = pillButton("Abrir música") { openMusicApp() }.apply { visibility = View.GONE }
        info.addView(musicAction, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.6f) })
        box.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return box
    }

    private fun mediaButton(icon: Int, primary: Boolean, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        val size = u(if (primary) 3.4f else 2.8f)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = u(0.8f) }
        setPadding(u(0.6f), u(0.6f), u(0.6f), u(0.6f))
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(if (primary) CYAN else 0x4422D3EE, size / 2f, CYAN))
            addState(intArrayOf(), rounded(if (primary) 0xCCF2F2F7.toInt() else Color.TRANSPARENT, size / 2f, if (primary) Color.TRANSPARENT else LINE))
        }
        setOnClickListener { onClick() }
    }

    private fun updateMusic() {
        if (!MediaListener.enabled(this)) {
            // Turn the widget on by itself as soon as the phone's ADB connection allows it
            if (!askedMusicPermission && Adb.state == Adb.State.OK) {
                askedMusicPermission = true
                loader.execute { runCatching { MediaListener.enable() } }
            }
            showMusic("music:off", "Controles de música", "Conecte o celular para ativar", null, false, showAction = false)
            return
        }
        val controller = MediaListener.current(this)
        val meta = controller?.metadata
        if (controller == null || meta == null) {
            showMusic("music:none", "Nada tocando", "Apple Music, Spotify, Amazon Music…", null, false, showAction = true)
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Música"
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        showMusic("$title|$artist|$playing|${art?.generationId}", title, artist, art, playing, showAction = false)
    }

    private fun showMusic(key: String, title: String, artist: String, art: android.graphics.Bitmap?, playing: Boolean, showAction: Boolean) {
        if (key == musicKey) return
        musicKey = key
        musicTitle.text = title
        musicArtist.text = artist
        if (art != null) musicArt.setImageBitmap(art) else musicArt.setImageResource(R.drawable.ic_m_music)
        val controls = key.contains('|')
        musicControls.visibility = if (controls) View.VISIBLE else View.GONE
        musicAction.visibility = if (showAction) View.VISIBLE else View.GONE
        musicPlay.setImageResource(if (playing) R.drawable.ic_m_pause else R.drawable.ic_m_play)
    }

    private fun openMusicApp() {
        val apps = Apps.list(this)
        val music = apps.firstOrNull { it.pkg == "com.apple.android.music" }
            ?: apps.firstOrNull { Folders.folderOf(this, it.pkg, it.label) == "musica" }
        if (music == null) toast("Nenhum app de música instalado") else Apps.launch(this, music.pkg)
    }

    // ---------- Wi-Fi button ----------

    private fun updateWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val label = when {
            caps == null -> "●  Sem rede"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "●  Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "●  Cabo"
            else -> "●  Rede"
        }
        if (wifiButton.text.toString() == label) return
        wifiButton.text = SpannableString(label).apply { setSpan(ForegroundColorSpan(if (caps == null) WARN else OK), 0, 1, 0) }
    }

    private fun pillButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = px(1.1f)
        setTextColor(TEXT)
        minHeight = 0
        minimumHeight = 0
        setPadding(u(1.3f), u(0.6f), u(1.3f), u(0.6f))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(3f).toFloat(), CYAN))
            addState(intArrayOf(), rounded(0xCC12121D.toInt(), u(3f).toFloat(), LINE))
        }
        setOnClickListener { onClick() }
    }

    // ---------- Icons ----------

    private fun loadIcon(pkg: String, into: ImageView) {
        icons[pkg]?.let { into.setImageDrawable(it); return }
        loader.execute {
            val drawable = try {
                packageManager.getApplicationBanner(pkg) ?: packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            } ?: return@execute
            icons[pkg] = drawable
            runOnUiThread { into.setImageDrawable(drawable) }
        }
    }

    // ---------- Clock and status ----------

    private fun updateClock() {
        val now = Date()
        val minute = (now.time / 60_000).toInt()
        if (minute == lastMinute) return
        lastMinute = minute
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val hour = SimpleDateFormat("H", Locale.getDefault()).format(now).toInt()
        val hello = when { hour < 5 -> "Boa noite"; hour < 12 -> "Bom dia"; hour < 18 -> "Boa tarde"; else -> "Boa noite" }
        greeting.text = "$hello, ${HomePrefs.name(this)}"
        date.text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(now).replaceFirstChar { it.uppercase() }
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
            firstRunUrl.text = url ?: "Conecte o Fire TV ao Wi-Fi"
            if (url != null) loader.execute {
                val png = Qr.png(url, 480)
                val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                runOnUiThread { firstRunQr.setImageBitmap(bitmap) }
            }
        }
        firstRun.visibility = if (pairing.hasPhones()) View.GONE else View.VISIBLE

        val pending = Updater.pending != null
        if (pending && updateButton.visibility != View.VISIBLE) {
            updateButton.visibility = View.VISIBLE
            updateButton.requestFocus()
        } else if (!pending) {
            updateButton.visibility = View.GONE
        }

        updateWifi()
        updateMusic()

        // Favorites may have been changed from the phone
        if (Apps.favorites(this).joinToString() != shownFavorites) loadApps(force = false)
    }

    private fun setChip(chip: TextView, dot: Int, label: String) {
        val text = "●  $label"
        if (chip.text.toString() == text && chip.tag == dot) return
        chip.tag = dot
        chip.text = SpannableString(text).apply { setSpan(ForegroundColorSpan(dot), 0, 1, 0) }
    }

    // ---------- Small helpers ----------

    private fun u(percent: Float) = (unit * percent).toInt()
    @Suppress("DEPRECATION")
    private fun px(percent: Float) = unit * percent / resources.displayMetrics.scaledDensity

    private fun text(sizePercent: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = px(sizePercent)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private fun chip() = text(1.05f, MUTED).apply {
        background = rounded(0xCC12121D.toInt(), u(3f).toFloat(), LINE)
        setPadding(u(1.2f), u(0.6f), u(1.2f), u(0.6f))
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
