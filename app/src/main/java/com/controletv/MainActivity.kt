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
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
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
import android.text.Editable
import android.text.SpannableString
import android.text.TextUtils
import android.text.TextWatcher
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The TV home screen, Netflix style, drawn with plain Android views so remote-control
 * navigation is handled by the system itself (instant and smooth on Fire TV hardware).
 *
 * The focused film or series fills the background, with its logo, rating, year, genres and
 * synopsis on top. Below: Stremio's "continue watching", new releases, genres and the user's
 * library as rows of big posters (the focused row slides up under the details), then the apps,
 * folders, inputs and settings. OK opens; holding OK (or the menu button) opens app options.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val pairing by lazy { Pairing(this) }

    private lateinit var clock: TextView
    private lateinit var greeting: TextView
    private lateinit var phoneChip: TextView
    private lateinit var adbChip: TextView
    private lateinit var wifiButton: Button
    private lateinit var date: TextView
    private lateinit var musicArt: ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var musicControls: LinearLayout
    private lateinit var musicPlay: ImageView
    private lateinit var musicAction: Button
    private var musicKey = ""
    private var askedMusicPermission = false

    // Hero: the focused title's details over its backdrop
    private lateinit var backFront: ImageView
    private lateinit var backBack: ImageView
    private lateinit var heroLogo: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroImdb: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroAwards: TextView
    private lateinit var heroCredits: TextView
    private lateinit var heroDesc: TextView
    private lateinit var heroHint: TextView
    private var heroKey = ""
    private var heroPending: Runnable? = null
    private var backdropUrl: String? = null
    private val metaCache = ConcurrentHashMap<String, JSONObject>()

    // Tabs: each one swaps the rows below
    private var tab = "media"
    private val tabViews = mutableMapOf<String, LinearLayout>()
    private var rebuilding = false
    private var lastMedia: Pair<JSONObject, String?>? = null
    private var data: HomeData? = null
    private var focusedOnce = false

    private lateinit var rowsScroll: ScrollView
    private lateinit var rows: LinearLayout
    private lateinit var firstRun: FrameLayout
    private lateinit var firstRunQr: ImageView
    private lateinit var firstRunUrl: TextView
    private lateinit var updateButton: Button

    private var shownSignature = ""

    // Stremio content, refreshed in the background every few minutes
    private var stremioLoggedIn = false
    private var stremioWatching: List<JSONObject> = emptyList()
    private var stremioLibrary: List<JSONObject> = emptyList()
    private var newMovies: List<JSONObject> = emptyList()
    private var newSeries: List<JSONObject> = emptyList()
    private var genreRows: List<Pair<String, List<JSONObject>>> = emptyList()
    private var stremioFetchedAt = 0L
    private var stremioVersion = 0
    private var shownUrl: String? = null
    private var lastMinute = -1
    private var shownChanges = -1

    private val unit by lazy { resources.displayMetrics.widthPixels / 100f } // 1% of screen width
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    private val screenW by lazy { resources.displayMetrics.widthPixels }

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
        visible = true
        refreshStremio()
        loadApps(force = false)
        handler.post(tick)
    }

    override fun onPause() {
        visible = false
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
        // This is the home screen: Back goes up to the tabs, then to "Filmes e séries", instead of leaving
        val focus = currentFocus
        rowsScroll.smoothScrollTo(0, 0)
        when {
            focus != null && inRows(focus) -> tabViews[tab]?.requestFocus()
            tab != "media" -> tabViews["media"]?.requestFocus()
            else -> firstCard()?.requestFocus()
        }
    }

    /** Up from the first row lands on the current tab, down from a tab on the first card. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focus != null && inRows(focus) && rowOf(focus) === rows.getChildAt(0)) {
                tabViews[tab]?.requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (focus?.tag as? String)?.startsWith("tab:") == true) {
                firstCard()?.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Layout ----------

    private fun buildScreen(): View {
        val root = FrameLayout(this).apply { background = NeonBackground() }

        // Backdrop of the focused title, Netflix style: only the top right of the screen,
        // dimmed a little and faded into the background on its left and bottom edges, so the
        // text and the rows always sit on dark. Two layers for a crossfade.
        val backW = (screenW * 0.70f).toInt()
        val backH = (screenH * 0.70f).toInt()
        val backdrop = FrameLayout(this)
        backBack = backdropView()
        backFront = backdropView()
        backdrop.addView(backBack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        backdrop.addView(backFront, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(backdrop, FrameLayout.LayoutParams(backW, backH, Gravity.TOP or Gravity.END))
        root.addView(View(this).apply { setBackgroundColor(0x4007070C) },
            FrameLayout.LayoutParams(backW, backH, Gravity.TOP or Gravity.END))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF07070C.toInt(), 0xD907070C.toInt(), 0x6607070C, 0x0007070C))
        }, FrameLayout.LayoutParams((backW * 0.6f).toInt(), backH, Gravity.TOP or Gravity.START).apply { leftMargin = screenW - backW })
        val fadeH = (backH * 0.55f).toInt()
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF07070C.toInt(), 0xD907070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams(backW, fadeH, Gravity.TOP or Gravity.END).apply { topMargin = backH - fadeH })
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE607070C.toInt(), 0x9907070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.30f).toInt(), Gravity.TOP))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(3.5f), u(1.8f), u(3.5f), 0)
            clipChildren = false
            clipToPadding = false
        }

        // Top bar: search on the left; status, clock and the Wi-Fi button on the right
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchBar = Button(this).apply {
            text = "Pesquisar no Stremio, SmartTube e AuraMusic…"
            isAllCaps = false
            textSize = px(1.2f)
            setTextColor(MUTED)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = 0
            minimumHeight = 0
            setPadding(u(1.5f), u(0.75f), u(1.5f), u(0.75f))
            val icon = getDrawable(R.drawable.ic_m_search)!!.apply { setBounds(0, 0, u(1.5f), u(1.5f)) }
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = u(1f)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(3f).toFloat(), CYAN))
                addState(intArrayOf(), rounded(0xCC12121D.toInt(), u(3f).toFloat(), LINE))
            }
            setOnClickListener { SearchDialog().show() }
        }
        top.addView(searchBar, LinearLayout.LayoutParams(u(36f), WRAP_CONTENT))
        top.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        phoneChip = chip()
        adbChip = chip()
        top.addView(phoneChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.8f) })
        top.addView(adbChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.6f) })
        clock = text(2.2f, TEXT, bold = true)
        top.addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.6f) })
        wifiButton = pillButton("Wi-Fi", null) {
            if (!SystemShortcuts.open(this, SystemShortcuts.SETTINGS.first { it.key == "set:net" })) toast("Esta TV não abriu a tela de rede")
        }
        top.addView(wifiButton)
        content.addView(top)

        // Greeting and date on the left, music player on the right
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hello = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        greeting = text(2.7f, TEXT, bold = true).apply {
            letterSpacing = -0.02f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(u(1f).toFloat(), 0f, 0f, 0x5522D3EE)
        }
        date = text(1.35f, MUTED)
        hello.addView(greeting)
        hello.addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.5f) })
        header.addView(hello, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(buildMusicWidget(), LinearLayout.LayoutParams(u(34f), WRAP_CONTENT))
        content.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(1.2f) })

        // Tabs: moving onto one swaps the rows below (Filmes e séries · Apps · Configurações · Descanso)
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        TABS.forEach { (id, label, icon) ->
            val view = tabView(id, label, icon)
            tabViews[id] = view
            tabBar.addView(view, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.8f) })
        }
        content.addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = u(1.1f)
            leftMargin = -u(1.4f)
        })
        styleTabs()

        // Hero: logo (or title), rating · year · length · genres, synopsis, hint
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        heroLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            visibility = View.GONE
        }
        hero.addView(heroLogo, LinearLayout.LayoutParams(u(24f), (screenH * 0.085f).toInt()))
        heroTitle = text(3.6f, TEXT, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = -0.02f
            setShadowLayer(u(0.8f).toFloat(), 0f, 0f, 0xAA000000.toInt())
        }
        hero.addView(heroTitle, LinearLayout.LayoutParams(u(48f), WRAP_CONTENT))
        // IMDb rating badge, then year · length · kind · genres
        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroImdb = text(1.35f, TEXT, bold = true).apply {
            compoundDrawablePadding = u(0.5f)
            visibility = View.GONE
        }
        metaRow.addView(heroImdb, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.2f) })
        heroMeta = text(1.25f, 0xE6F2F2F7.toInt(), bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        metaRow.addView(heroMeta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        hero.addView(metaRow, LinearLayout.LayoutParams(u(58f), WRAP_CONTENT).apply { topMargin = u(0.9f) })
        heroAwards = text(1.1f, 0xFFFBBF24.toInt(), bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = u(0.5f)
            val icon = getDrawable(R.drawable.ic_award)!!.apply { setBounds(0, 0, u(1.25f), u(1.25f)) }
            setCompoundDrawables(icon, null, null, null)
            visibility = View.GONE
        }
        hero.addView(heroAwards, LinearLayout.LayoutParams(u(55f), WRAP_CONTENT).apply { topMargin = u(0.7f) })
        heroDesc = text(1.3f, 0xCCF2F2F7.toInt()).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        }
        hero.addView(heroDesc, LinearLayout.LayoutParams(u(52f), WRAP_CONTENT).apply { topMargin = u(0.8f) })
        heroCredits = text(1.0f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        hero.addView(heroCredits, LinearLayout.LayoutParams(u(50f), WRAP_CONTENT).apply { topMargin = u(0.7f) })
        heroHint = text(1.05f, CYAN)
        hero.addView(heroHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.9f) })
        content.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, (screenH * 0.30f).toInt()))

        // Rows: the focused one slides up under the hero (see bringRowToTop)
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            // Side padding here (not on the scroll view) so a focused card's zoom and ring reach the screen edge
            setPadding(u(3.5f), u(0.6f), u(3.5f), (screenH * 0.45f).toInt())
        }
        // Full screen width, so only its top edge cuts: rows scrolled up don't draw over the hero,
        // and the focused card's zoom isn't cut at the sides
        rowsScroll = RowsScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            addView(rows)
        }
        content.addView(rowsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = u(0.4f)
            leftMargin = -u(3.5f)
            rightMargin = -u(3.5f)
        })
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

    private fun backdropView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }

    /** Vertical scroll that only moves when we say (bringRowToTop), not on every focus change. */
    private class RowsScrollView(context: Context) : ScrollView(context) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?) = 0
    }

    /** Slides the row holding [view] up to the top of the rows area, Netflix style. */
    private fun bringRowToTop(view: View) {
        rowOf(view)?.let { rowsScroll.smoothScrollTo(0, it.top - rows.paddingTop) }
    }

    /** The row (direct child of [rows]) holding [view], or null when it's elsewhere. */
    private fun rowOf(view: View): View? {
        var child: View = view
        var parent = child.parent
        while (parent is View && parent !== rows) {
            child = parent
            parent = child.parent
        }
        return if (parent === rows) child else null
    }

    private fun inRows(view: View) = rowOf(view) != null

    // ---------- Tabs ----------

    private fun tabView(id: String, label: String, icon: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isFocusableInTouchMode = true
        tag = "tab:$id"
        setPadding(u(1.4f), u(0.6f), u(1.4f), u(0.45f))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(3f).toFloat(), CYAN))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, u(3f).toFloat(), Color.TRANSPARENT))
        }
        addView(text(1.45f, MUTED, bold = true).apply {
            text = label
            compoundDrawablePadding = u(0.6f)
            setCompoundDrawables(getDrawable(icon)!!.mutate().apply { setBounds(0, 0, u(1.5f), u(1.5f)) }, null, null, null)
        })
        // Underline of the open tab
        addView(View(this@MainActivity).apply { background = rounded(CYAN, u(0.2f).toFloat(), CYAN) },
            LinearLayout.LayoutParams(u(2.6f), maxOf(3, u(0.22f))).apply { topMargin = u(0.4f) })
        setOnFocusChangeListener { _, focused -> if (focused && !rebuilding) selectTab(id) }
        setOnClickListener { firstCard()?.requestFocus() }
    }

    private fun styleTabs() {
        tabViews.forEach { (id, box) ->
            val open = id == tab
            val title = box.getChildAt(0) as TextView
            title.setTextColor(if (open) TEXT else MUTED)
            title.compoundDrawables[0]?.setColorFilter(if (open) CYAN else MUTED, PorterDuff.Mode.SRC_IN)
            box.getChildAt(1).visibility = if (open) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Opens a tab: its rows slide in from the side it was moved to. */
    private fun selectTab(id: String) {
        if (id != tab) {
            val from = TABS.indexOfFirst { it.first == tab }
            val to = TABS.indexOfFirst { it.first == id }
            tab = id
            styleTabs()
            rowsScroll.scrollTo(0, 0)
            fillRows()
            rowsScroll.translationX = (if (to > from) 1 else -1) * u(7f).toFloat()
            rowsScroll.alpha = 0f
            rowsScroll.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        }
        heroForTab()
    }

    /** What the hero says while the cursor is on a tab. */
    private fun heroForTab() {
        when (tab) {
            "media" -> lastMedia?.let { (item, extra) -> heroFor(item, extra) }
                ?: heroText("Filmes e séries", "Do seu Stremio: o que você está vendo, lançamentos e gêneros", "Desça para escolher")
            "apps" -> heroText("Apps", "${data?.apps?.size ?: 0} apps na TV   ·   favoritos e pastas", "Desça para escolher   ·   segure OK num app para opções")
            "settings" -> heroText("Configurações", "Telas da Fire TV, entradas e a tela inicial", "Desça para escolher")
            else -> heroText("Descanso de tela", restSummary(), "Desça para mudar")
        }
    }

    private fun restSummary(): String {
        val photos = data?.photos?.size ?: 0
        val state = if (ScreenSaver.enabled(this)) "Liga sozinho depois de ${ScreenSaver.minutes(this)} min sem usar" else "Desligado"
        return "$state   ·   $photos ${if (photos == 1) "foto" else "fotos"}"
    }

    // ---------- Hero ----------

    /** Shows a film or series: details at once, missing ones (synopsis, rating) fetched, backdrop after a short pause. */
    private fun heroFor(item: JSONObject, extra: String?) {
        val id = item.optString("id")
        heroKey = id
        lastMedia = item to extra
        applyHero(merge(item, metaCache[id]), extra)
        heroPending?.let { handler.removeCallbacks(it) }
        heroPending = Runnable {
            if (heroKey != id) return@Runnable
            val merged = merge(item, metaCache[id])
            setBackdrop(merged.optString("background").ifEmpty { "https://images.metahub.space/background/medium/$id/img" })
            if (!metaCache.containsKey(id)) {
                loader.execute {
                    val meta = runCatching { Stremio.meta(item.optString("type"), id) }.getOrNull() ?: return@execute
                    metaCache[id] = meta
                    runOnUiThread { if (heroKey == id) applyHero(merge(item, meta), extra) }
                }
            }
        }.also { handler.postDelayed(it, 260) }
    }

    /** Shows an app, a shortcut or anything without a backdrop. */
    private fun heroText(title: String, info: String, hint: String) {
        heroKey = "text:$title"
        heroPending?.let { handler.removeCallbacks(it) }
        heroLogo.visibility = View.GONE
        heroTitle.visibility = View.VISIBLE
        heroTitle.text = title
        heroImdb.visibility = View.GONE
        heroMeta.text = info
        heroAwards.visibility = View.GONE
        heroDesc.text = ""
        heroCredits.text = ""
        heroHint.text = hint
        setBackdrop(null)
    }

    private fun applyHero(item: JSONObject, extra: String?) {
        val id = item.optString("id")
        val name = item.optString("name")
        heroTitle.text = name
        heroTitle.visibility = View.VISIBLE
        heroLogo.visibility = View.GONE
        // Show the title's logo instead of plain text when there is one
        RemoteImages.load(item.optString("logo").ifEmpty { "https://images.metahub.space/logo/medium/$id/img" }, heroLogo, u(30f)) { ok ->
            if (ok && heroKey == id) {
                heroLogo.visibility = View.VISIBLE
                heroTitle.visibility = View.GONE
            }
        }
        val rating = item.optString("imdbRating").ifEmpty { null }
        val year = item.optString("releaseInfo").ifEmpty { item.optString("year") }.ifEmpty { null }
        val runtime = item.optString("runtime").let { formatRuntime(it) }
        val genres = (item.optJSONArray("genres") ?: item.optJSONArray("genre"))?.let { g -> List(minOf(3, g.length())) { g.optString(it) } }?.joinToString(", ")
        val kind = if (item.optString("type") == "series") "Série" else "Filme"
        heroMeta.text = listOfNotNull(year, runtime, kind, genres?.ifEmpty { null }, extra).joinToString("   ·   ")
        if (rating != null) {
            heroImdb.text = imdbBadge(rating)
            heroImdb.visibility = View.VISIBLE
        } else {
            heroImdb.visibility = View.GONE
        }
        val awards = Awards.toPortuguese(item.optString("awards"))
        heroAwards.text = awards ?: ""
        heroAwards.visibility = if (awards != null) View.VISIBLE else View.GONE
        heroDesc.text = item.optString("description")
        fun names(key: String, max: Int) = item.optJSONArray(key)?.let { a -> List(minOf(max, a.length())) { a.optString(it) } }?.filter { it.isNotBlank() }.orEmpty()
        val cast = names("cast", 3)
        val director = names("director", 1)
        heroCredits.text = listOfNotNull(
            cast.takeIf { it.isNotEmpty() }?.let { "Elenco: " + it.joinToString(", ") },
            director.firstOrNull()?.let { "Direção: $it" },
        ).joinToString("   ·   ")
        heroHint.text = ""
    }

    private fun merge(item: JSONObject, meta: JSONObject?): JSONObject {
        if (meta == null) return item
        val out = JSONObject(item.toString())
        for (key in listOf("description", "imdbRating", "releaseInfo", "runtime", "genres", "genre", "background", "logo", "awards", "cast", "director")) {
            if (out.optString(key).isEmpty() && meta.has(key)) out.put(key, meta.get(key))
        }
        return out
    }

    /** "IMDb 7.8" with the IMDb yellow tag. */
    private fun imdbBadge(rating: String): CharSequence {
        val tag = "IMDb"
        return SpannableString("$tag  $rating").apply {
            setSpan(object : android.text.style.ReplacementSpan() {
                private val padX = u(0.45f).toFloat()
                override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) =
                    (paint.measureText(tag) * 0.85f + padX * 2).toInt()
                override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
                    val small = Paint(paint).apply { textSize = paint.textSize * 0.85f; typeface = Typeface.DEFAULT_BOLD }
                    val w = small.measureText(tag) + padX * 2
                    val h = paint.textSize * 1.15f
                    val top2 = y - paint.textSize * 0.95f
                    canvas.drawRoundRect(x, top2, x + w, top2 + h, u(0.25f).toFloat(), u(0.25f).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5C518.toInt() })
                    small.color = Color.BLACK
                    canvas.drawText(tag, x + padX, top2 + h / 2 - (small.descent() + small.ascent()) / 2, small)
                }
            }, 0, tag.length, 0)
        }
    }

    /** "166 min" → "2h46". */
    private fun formatRuntime(raw: String): String? {
        val minutes = Regex("(\\d+)").find(raw)?.value?.toIntOrNull() ?: return null
        return if (minutes >= 60) "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
    }

    /** Crossfades the backdrop to a new picture (null = back to the plain neon background). */
    private fun setBackdrop(url: String?) {
        if (url == backdropUrl) return
        backdropUrl = url
        if (url == null) {
            backFront.animate().alpha(0f).setDuration(350).start()
            backBack.animate().alpha(0f).setDuration(350).start()
            return
        }
        val incoming = backBack
        RemoteImages.load(url, incoming, resources.displayMetrics.widthPixels / 2) { ok ->
            if (!ok || backdropUrl != url) return@load
            backBack = backFront
            backFront = incoming
            incoming.bringToFront()
            incoming.animate().alpha(1f).setDuration(450).start()
            backBack.animate().alpha(0f).setDuration(450).start()
        }
    }

    // ---------- Rows ----------

    /** Everything the rows are built from, gathered in the background. */
    private class HomeData(
        val apps: List<AppInfo>,
        val favorites: List<String>,
        val rowOrder: List<String>,
        val sorted: Map<String, List<AppInfo>>,
        val photos: List<java.io.File>,
    )

    private fun loadApps(force: Boolean) {
        loader.execute {
            val hidden = HomePrefs.hidden(this)
            val apps = Apps.list(this).filter { it.pkg !in hidden }
            val favorites = Apps.favorites(this).filter { pkg -> apps.any { it.pkg == pkg } }
            val folders = apps.associate { it.pkg to Folders.folderOf(this, it.pkg, it.label) }
            val rowOrder = HomePrefs.rowOrder(this)
            val sorted = rowOrder.associateWith { id -> HomePrefs.sortFolder(this, id, apps.filter { folders[it.pkg] == id }) }
            val photos = ScreenSaver.photos()
            val signature = "s$stremioVersion|" + WatchHistory.list(this).joinToString { it.id + it.position / 60_000 } + WatchNext.entries().size +
                rowOrder.joinToString { id -> id + ":" + sorted[id]!!.joinToString(",") { it.pkg } } + "|" + favorites.joinToString() +
                "|" + photos.joinToString { it.name }
            runOnUiThread {
                if (!force && signature == shownSignature) return@runOnUiThread
                shownSignature = signature
                data = HomeData(apps, favorites, rowOrder, sorted, photos)
                fillRows()
            }
        }
    }

    /** Builds the rows of the open tab, keeping the focus where it was. */
    private fun fillRows() {
        val d = data ?: return
        val focus = currentFocus
        val focusInRows = focus != null && inRows(focus)
        val focusedTag = if (focusInRows) focus?.tag as? String else null
        rebuilding = true
        rows.removeAllViews()
        when (tab) {
            "media" -> mediaRows()
            "apps" -> appRows(d)
            "settings" -> settingsRows()
            else -> restRows(d)
        }
        rebuilding = false
        // Take the focus back only when it was in the rows (or on first show), never from the tabs or the top
        if (focusInRows || !focusedOnce || currentFocus == null) {
            focusedOnce = true
            (focusedTag?.let { rows.findViewWithTag<View>(it) } ?: firstCard())?.requestFocus()
        }
    }

    private fun mediaRows() {
        rows.addView(row("Continuar assistindo",
            if (stremioWatching.isNotEmpty()) stremioWatching.take(12).map { stremioWideCard(it) } else listOf(stremioHintCard())))
        if (newMovies.isNotEmpty()) rows.addView(row("Filmes novos", newMovies.take(MAX_PER_ROW).map { stremioPoster(it) }))
        if (newSeries.isNotEmpty()) rows.addView(row("Séries novas", newSeries.take(MAX_PER_ROW).map { stremioPoster(it) }))
        genreRows.forEach { (title, items) -> if (items.isNotEmpty()) rows.addView(row(title, items.take(MAX_PER_ROW).map { stremioPoster(it) })) }
        if (stremioLibrary.isNotEmpty()) rows.addView(row("Minha biblioteca", stremioLibrary.take(MAX_PER_ROW).map { stremioPoster(it) }))
        val history = WatchHistory.list(this)
        val watchNext = WatchNext.entries()
        if (history.isNotEmpty() || watchNext.isNotEmpty()) {
            rows.addView(row("Continuar no Tocar na TV", history.map { historyCard(it) } + watchNext.map { watchNextCard(it) }))
        }
    }

    private fun appRows(d: HomeData) {
        val byPkg = d.apps.associateBy { it.pkg }
        if (d.favorites.isNotEmpty()) {
            rows.addView(row("Favoritos", d.favorites.mapNotNull { byPkg[it] }.map { appCard(it, true) }, R.drawable.ic_star))
        }
        for (id in d.rowOrder) {
            val folder = Folders.ALL.first { it.id == id }
            val inFolder = d.sorted[id].orEmpty()
            if (inFolder.isNotEmpty()) rows.addView(row(folder.title, inFolder.map { appCard(it, it.pkg in d.favorites, folder.title) }))
        }
    }

    private fun settingsRows() {
        rows.addView(row("Telas da Fire TV", SystemShortcuts.SETTINGS.map { shortcut ->
            card(shortcut.key, shortcut.title, "Configurações da TV", false, { it.setImageResource(shortcut.icon) }, iconTile = true) {
                if (!SystemShortcuts.open(this, shortcut)) toast("Esta TV não abriu essa tela. Veja o diagnóstico nos Ajustes do celular.")
            }
        }))
        val inputs = SystemShortcuts.inputs(this)
        if (inputs.isNotEmpty()) {
            rows.addView(row("Entradas", inputs.map { input ->
                card("in:${input.id}", input.label, "Entrada da TV", false, { it.setImageResource(R.drawable.ic_t_display) }, iconTile = true) {
                    if (!SystemShortcuts.openInput(this, input)) toast("Não deu para trocar para ${input.label}")
                }
            }))
        }
        rows.addView(row("Tela inicial", homeTiles()))
    }

    /** Descanso: start it, turn it on/off, how long to wait, how dark, and the photos it shows. */
    private fun restRows(d: HomeData) {
        val on = ScreenSaver.enabled(this)
        val minutes = ScreenSaver.minutes(this)
        val dim = ScreenSaver.dim(this)
        fun changed() { fillRows() }
        rows.addView(row("Descanso de tela", listOf(
            card("rest:start", "Começar agora", "Mostra suas fotos agora", false, { it.setImageResource(R.drawable.ic_tab_moon) }, iconTile = true) {
                ScreenSaver.start(this)
            },
            card("rest:on", if (on) "Ligado" else "Desligado", "OK liga ou desliga o descanso", false, { it.setImageResource(R.drawable.ic_t_power) }, iconTile = true) {
                ScreenSaver.configure(this, !on, null, null); changed()
            },
            card("rest:min", "Depois de $minutes min", "OK muda o tempo sem usar até ele começar", false, { it.setImageResource(R.drawable.ic_t_timer) }, iconTile = true) {
                ScreenSaver.configure(this, null, REST_MINUTES.firstOrNull { it > minutes } ?: REST_MINUTES.first(), null); changed()
            },
            card("rest:music", if (ScreenSaver.followMusic(this)) "Segue a música" else "Não segue a música",
                "OK liga ou desliga: fotos pulsam e trocam na batida", false, { it.setImageResource(R.drawable.ic_m_music) }, iconTile = true) {
                ScreenSaver.setFollowMusic(this, !ScreenSaver.followMusic(this)); changed()
            },
            card("rest:dim", "Escurecer ${(dim * 100).roundToInt()}%", "OK muda o quanto as fotos escurecem", false, { it.setImageResource(R.drawable.ic_t_dim) }, iconTile = true) {
                ScreenSaver.configure(this, null, null, REST_DIM.firstOrNull { it > dim + 0.01f } ?: REST_DIM.first()); changed()
            },
        )))
        val photos = d.photos
        rows.addView(row(if (photos.isEmpty()) "Suas fotos" else "Suas fotos (${photos.size})",
            if (photos.isEmpty()) {
                listOf(card("rest:nophotos", "Sem fotos ainda", "Mande pelo celular: Ajustes → Tela de descanso", false, { it.setImageResource(R.drawable.ic_tab_moon) }, iconTile = true) {
                    toast("Mande fotos pelo celular: Ajustes → Tela de descanso")
                })
            } else {
                photos.take(MAX_PER_ROW * 2).map { file ->
                    card("rest:p:${file.name}", file.nameWithoutExtension, "OK começa o descanso", false, { loadPhoto(file, it) }) { ScreenSaver.start(this) }
                }
            }))
    }

    /** A photo from the TV's storage, shrunk to card size. */
    private fun loadPhoto(file: java.io.File, into: ImageView) {
        into.tag = file.path
        val width = u(13.5f)
        loader.execute {
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= width) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull() ?: return@execute
            runOnUiThread { if (into.tag == file.path) into.setImageBitmap(bitmap) }
        }
    }

    private fun firstCard(): View? {
        val firstRow = rows.getChildAt(0) as? LinearLayout ?: return null
        val strip = (firstRow.getChildAt(1) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout ?: return null
        return strip.getChildAt(0)
    }

    private fun row(title: String, cards: List<View>, icon: Int? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        row.addView(text(1.35f, 0xE6F2F2F7.toInt(), bold = true).apply {
            text = title
            icon?.let { res ->
                val d = getDrawable(res)!!.apply { setBounds(0, 0, u(1.5f), u(1.5f)) }
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = u(0.6f)
            }
        })
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(u(1.5f), u(1.2f), u(1.5f), u(1.6f))
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
            bottomMargin = u(0.8f)
        })
        return row
    }

    private fun appCard(app: AppInfo, favorite: Boolean, folderTitle: String = "Favoritos"): View =
        card(
            key = app.pkg, title = app.label, subtitle = folderTitle, favorite = favorite,
            bindImage = { loadIcon(app.pkg, it) },
            onOptions = { appOptions(app) },
        ) {
            toast("Abrindo ${app.label}")
            Apps.launch(this, app.pkg)
        }

    // ---------- Stremio ----------

    private fun refreshStremio() {
        if (System.currentTimeMillis() - stremioFetchedAt < 5 * 60_000) return
        stremioFetchedAt = System.currentTimeMillis()
        loader.execute {
            val loggedIn = Stremio.status(this).optBoolean("loggedIn")
            val library = if (loggedIn) runCatching { Stremio.library(this) }.getOrNull() else null
            val items = library?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            fun catalog(type: String) = runCatching { Stremio.newReleases(type, year) }.getOrDefault(emptyList())
            val movies = catalog("movie")
            val series = catalog("series")
            // Genre rows mix films and series, alternating
            val genres = GENRES.map { (title, genre) ->
                val m = runCatching { Stremio.genre("movie", genre) }.getOrDefault(emptyList())
                val t = runCatching { Stremio.genre("series", genre) }.getOrDefault(emptyList())
                title to (0 until maxOf(m.size, t.size)).flatMap { i -> listOfNotNull(m.getOrNull(i), t.getOrNull(i)) }
            }
            runOnUiThread {
                stremioLoggedIn = loggedIn
                stremioLibrary = items
                stremioWatching = items.filter {
                    val d = it.optLong("duration"); val p = it.optLong("position")
                    d > 0 && p > 0 && p.toFloat() / d < 0.95f
                }
                newMovies = movies
                newSeries = series
                genreRows = genres
                stremioVersion++
                loadApps(force = false)
            }
        }
    }

    private fun episodeLabel(videoId: String?): String? {
        val parts = videoId?.split(':') ?: return null
        return if (parts.size >= 3) "T${parts[1]} E${parts[2]}" else null
    }

    private fun openInStremio(item: JSONObject, videoId: String?) {
        runCatching { Stremio.openOnTv(this, item.optString("type"), item.optString("id"), videoId) }
            .onFailure { toast(it.message ?: "Instale o Stremio na TV") }
    }

    /** "Continue watching" card: backdrop with title, episode and time left, plus progress. */
    private fun stremioWideCard(item: JSONObject): View {
        val id = item.optString("id")
        val duration = item.optLong("duration")
        val left = ((duration - item.optLong("position")) / 60_000).coerceAtLeast(1)
        val episode = episodeLabel(item.optString("videoId").ifEmpty { null })
        val extra = listOfNotNull(episode, if (duration > 0) "faltam $left min" else null).joinToString(" · ").ifEmpty { null }
        val w = u(21f)
        return mediaTile(
            key = "sw:$id", item = item, extra = extra,
            imageUrl = "https://images.metahub.space/background/medium/$id/img", fallbackUrl = item.optString("poster"),
            width = w, height = w * 9 / 16, overlay = listOfNotNull(item.optString("name"), extra).joinToString("\n"),
            progress = if (duration > 0) item.optLong("position").toFloat() / duration else null,
        ) { openInStremio(item, item.optString("videoId").ifEmpty { null }) }
    }

    /** Shown when there's nothing to continue (or no Stremio account yet). */
    private fun stremioHintCard(): View {
        val w = u(21f)
        val message = if (stremioLoggedIn) "Nada em andamento no Stremio" else "Entre na sua conta do Stremio\npelo celular (aba Filmes)"
        return mediaTile(
            key = "sw:hint", item = null, extra = null, imageUrl = null, fallbackUrl = null,
            width = w, height = w * 9 / 16, overlay = message, progress = null,
            textTitle = "Continuar assistindo", textInfo = message.replace("\n", " "),
        ) { packageManager.getLaunchIntentForPackage("com.stremio.one")?.let { startActivity(it) } ?: toast("Instale o Stremio na TV") }
    }

    /** Big poster (2:3) for films and series. */
    private fun stremioPoster(item: JSONObject): View {
        val id = item.optString("id")
        val w = u(10.5f)
        return mediaTile(
            key = "sp:$id", item = item, extra = null,
            imageUrl = item.optString("poster").ifEmpty { "https://images.metahub.space/poster/medium/$id/img" }, fallbackUrl = null,
            width = w, height = w * 3 / 2, overlay = null, progress = null,
        ) { openInStremio(item, null) }
    }

    /** A focusable picture card; focusing it fills the hero with the title's details. */
    private fun mediaTile(
        key: String, item: JSONObject?, extra: String?, imageUrl: String?, fallbackUrl: String?,
        width: Int, height: Int, overlay: String?, progress: Float?,
        textTitle: String = "", textInfo: String = "", onClick: () -> Unit,
    ): View {
        val box = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            tag = key
            layoutParams = LinearLayout.LayoutParams(width, height).apply { rightMargin = u(1.2f) }
        }
        val art = FrameLayout(this).apply {
            background = rounded(SURFACE, u(0.8f).toFloat(), LINE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, u(0.8f).toFloat())
            }
            clipToOutline = true
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        art.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val primary = imageUrl ?: fallbackUrl
        RemoteImages.load(primary, image, width) { ok -> if (!ok && fallbackUrl != null && primary != fallbackUrl) RemoteImages.load(fallbackUrl, image, width) }
        if (overlay != null) {
            art.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
            }, FrameLayout.LayoutParams(MATCH_PARENT, height * 2 / 3, Gravity.BOTTOM))
            art.addView(text(1.15f, TEXT, bold = true).apply {
                text = overlay
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(u(1f), 0, u(1f), u(1f))
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        if (progress != null) {
            val track = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
            track.addView(View(this).apply { setBackgroundColor(CYAN) }, FrameLayout.LayoutParams((width * progress.coerceIn(0f, 1f)).toInt(), MATCH_PARENT))
            art.addView(track, FrameLayout.LayoutParams(MATCH_PARENT, maxOf(3, u(0.35f)), Gravity.BOTTOM))
        }
        val ring = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = u(0.8f).toFloat(); setStroke(u(0.22f), Color.WHITE) }
            visibility = View.INVISIBLE
        }
        art.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(art, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.setOnFocusChangeListener { _, focused ->
            ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            val scale = if (focused) 1.08f else 1f
            art.animate().scaleX(scale).scaleY(scale).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
            art.elevation = if (focused) u(1f).toFloat() else 0f
            if (focused) {
                bringRowToTop(box)
                if (item != null) heroFor(item, extra) else heroText(textTitle, textInfo, "OK abre o Stremio")
            }
        }
        box.setOnClickListener { onClick() }
        return box
    }

    // ---------- Played on the TV ("Tocar na TV") ----------

    private fun historyCard(item: WatchHistory.Item): View {
        val remaining = ((item.duration - item.position) / 60_000).coerceAtLeast(1)
        return card(
            key = "hist:${item.id}", title = item.title,
            subtitle = if (item.duration > 0) "Faltam $remaining min" else "Continuar",
            favorite = false, progress = item.progress,
            bindImage = { image ->
                val thumb = WatchHistory.thumb(this, item.id)
                if (thumb.exists()) loader.execute {
                    val bitmap = BitmapFactory.decodeFile(thumb.path)
                    runOnUiThread { image.setImageBitmap(bitmap) }
                } else image.setImageResource(R.drawable.ic_t_display)
            },
            onOptions = {
                val dialog = OptionsDialog(item.title)
                dialog.add("Continuar de onde parou") { resume(item) }
                dialog.add("Assistir do começo") { resume(item.copy(position = 0)) }
                dialog.add("Tirar desta lista") { WatchHistory.remove(this, item.id); loadApps(force = true) }
                dialog.show()
            },
        ) { resume(item) }
    }

    private fun resume(item: WatchHistory.Item) {
        toast("Continuando ${item.title}")
        PlayerActivity.open(this, item.url, item.title, item.type, item.subtitles, item.subtitleNames, item.position, item.headers)
    }

    private fun watchNextCard(entry: WatchNext.Entry): View = card(
        key = "wn:${entry.pkg}:${entry.title}", title = entry.title, subtitle = Apps.label(this, entry.pkg), favorite = false,
        bindImage = { loadIcon(entry.pkg, it) },
    ) {
        runCatching {
            startActivity(Intent.parseUri(entry.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Apps.launch(this, entry.pkg) }
    }

    // ---------- Search ----------

    /** Search in Stremio (titles listed right here), SmartTube and AuraMusic. */
    private inner class SearchDialog : Dialog(this@MainActivity) {
        private val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val smartTube = listOf("com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")
            .firstOrNull { pkg -> packageManager.getLaunchIntentForPackage(pkg) != null || packageManager.getLeanbackLaunchIntentForPackage(pkg) != null }
        private var pending: Runnable? = null
        private var query = ""

        init {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(SURFACE, u(1.4f).toFloat(), CYAN_DIM)
                setPadding(u(2f), u(1.8f), u(2f), u(1.8f))
            }
            val field = EditText(this@MainActivity).apply {
                hint = "Filmes e séries, vídeos ou músicas"
                setHintTextColor(MUTED)
                setTextColor(TEXT)
                textSize = px(1.6f)
                isSingleLine = true
                background = rounded(BG, u(1f).toFloat(), CYAN)
                setPadding(u(1.4f), u(1f), u(1.4f), u(1f))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) = schedule(s.toString().trim())
                })
            }
            box.addView(field, LinearLayout.LayoutParams(u(42f), WRAP_CONTENT).apply { bottomMargin = u(1.2f) })
            box.addView(ScrollView(this@MainActivity).apply { addView(results) }, LinearLayout.LayoutParams(u(42f), u(26f)))
            setContentView(box)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            render("", emptyList())
        }

        /** Waits for a pause in typing, then looks the titles up in Stremio's catalog. */
        private fun schedule(text: String) {
            query = text
            pending?.let { handler.removeCallbacks(it) }
            render(text, emptyList())
            if (text.length < 2) return
            pending = Runnable {
                loader.execute {
                    val titles = runCatching {
                        val movies = Stremio.catalog("movie", text)
                        val series = Stremio.catalog("series", text)
                        (List(minOf(4, movies.length())) { movies.getJSONObject(it) } + List(minOf(4, series.length())) { series.getJSONObject(it) })
                    }.getOrDefault(emptyList())
                    runOnUiThread { if (query == text && isShowing) render(text, titles) }
                }
            }.also { handler.postDelayed(it, 450) }
        }

        private fun render(text: String, titles: List<JSONObject>) {
            results.removeAllViews()
            if (text.isEmpty()) {
                results.addView(text(1.2f, MUTED).apply { this.text = "Busca filmes e séries no Stremio, vídeos no SmartTube e músicas no AuraMusic" })
                return
            }
            titles.forEach { t ->
                val kind = if (t.optString("type") == "series") "série" else "filme"
                val year = t.optString("releaseInfo")
                option("${t.optString("name")}  ($kind${if (year.isNotEmpty()) ", $year" else ""})  ·  abrir no Stremio") { openInStremio(t, null) }
            }
            if (smartTube != null) {
                option("Buscar \"$text\" no SmartTube") {
                    val search = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(text))).setPackage(smartTube)
                    runCatching { startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Apps.launch(this@MainActivity, smartTube) }
                }
            }
            if (MusicBridge.musicApp(this@MainActivity) != null) {
                option("Tocar \"$text\" no AuraMusic") {
                    // Searching goes over the network: off the main thread
                    Thread {
                        runCatching { MusicBridge.search(this@MainActivity, text, true) }
                            .onFailure { e -> runOnUiThread { toast(e.message ?: "Falhou") } }
                    }.start()
                }
            }
        }

        private fun option(label: String, action: () -> Unit) {
            results.addView(Button(this@MainActivity).apply {
                text = label
                isAllCaps = false
                textSize = px(1.35f)
                setTextColor(TEXT)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(u(1.4f), u(0.8f), u(1.4f), u(0.8f))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(0.8f).toFloat(), CYAN))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, u(0.8f).toFloat(), Color.TRANSPARENT))
                }
                setOnClickListener { dismiss(); action() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = u(0.3f) })
        }
    }

    // ---------- Settings row ----------

    private fun homeTiles(): List<View> {
        val tiles = mutableListOf<View>()
        packageManager.getLaunchIntentForPackage("com.amazon.venezia")?.let { store ->
            tiles += card("set:store", "Loja de apps", "Amazon Appstore", false, { it.setImageResource(R.drawable.ic_t_store) }, iconTile = true) {
                runCatching { startActivity(store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("A loja não abriu") }
            }
        }
        tiles += card("set:hidden", "Apps escondidos", "Apps tirados da tela inicial", false, { it.setImageResource(R.drawable.ic_t_apps) }, iconTile = true) {
            hiddenApps()
        }
        tiles += card("set:amazon", "Tela da Amazon", "Tela inicial original do Fire TV", false, { it.setImageResource(R.drawable.ic_t_amazon) }, iconTile = true) {
            HomeWatcher.openAmazonHome(this)
        }
        return tiles
    }

    /** App, shortcut and input cards (16:9 banners). */
    private fun card(
        key: String, title: String, subtitle: String, favorite: Boolean, bindImage: (ImageView) -> Unit,
        iconTile: Boolean = false, width: Int = u(13.5f), progress: Float? = null,
        onOptions: (() -> Unit)? = null, onClick: () -> Unit,
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
            background = rounded(SURFACE, u(0.8f).toFloat(), if (favorite) VIOLET_DIM else LINE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, u(0.8f).toFloat())
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
        if (progress != null) {
            val track = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
            track.addView(View(this).apply { setBackgroundColor(CYAN) }, FrameLayout.LayoutParams((w * progress.coerceIn(0f, 1f)).toInt(), MATCH_PARENT))
            art.addView(track, FrameLayout.LayoutParams(MATCH_PARENT, maxOf(3, u(0.35f)), Gravity.BOTTOM))
        }
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = u(0.8f).toFloat()
                setStroke(u(0.22f), Color.WHITE)
            }
            visibility = View.INVISIBLE
        }
        art.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(art, LinearLayout.LayoutParams(w, h))
        box.layoutParams = LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { rightMargin = u(1.2f) }

        bindImage(image)

        box.setOnFocusChangeListener { _, focused ->
            ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            val scale = if (focused) 1.1f else 1f
            AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(art, View.SCALE_X, scale), ObjectAnimator.ofFloat(art, View.SCALE_Y, scale))
                duration = 130
                interpolator = DecelerateInterpolator()
                start()
            }
            art.elevation = if (focused) u(1f).toFloat() else 0f
            if (focused) {
                bringRowToTop(box)
                heroText(title, subtitle, if (onOptions != null) "OK abre   ·   segure OK para opções" else "OK abre")
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
        dialog.add("Mover para pasta…") { folderPicker(app) }
        dialog.add("Esconder da tela inicial") {
            HomePrefs.setHidden(this, app.pkg, true)
            toast("${app.label} escondido. Para ver de novo: Configurações → Apps escondidos")
            loadApps(force = true)
        }
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

    private fun hiddenApps() {
        val hidden = HomePrefs.hidden(this)
        val apps = Apps.list(this).filter { it.pkg in hidden }
        if (apps.isEmpty()) return toast("Nenhum app escondido. Segure OK num app para esconder.")
        val dialog = OptionsDialog("Apps escondidos")
        apps.forEach { app ->
            dialog.add("${app.label}   ·   mostrar de novo") {
                HomePrefs.setHidden(this, app.pkg, false)
                toast("${app.label} voltou para a tela inicial")
                loadApps(force = true)
            }
        }
        dialog.show()
    }

    private fun folderPicker(app: AppInfo) {
        val dialog = OptionsDialog("Mover ${app.label} para…")
        val current = Folders.folderOf(this, app.pkg, app.label)
        Folders.ALL.forEach { folder ->
            dialog.add(folder.title + if (folder.id == current) "   (atual)" else "") {
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

    // ---------- Music chip (top bar) ----------

    /** OK on the chip: play/pause what's playing, or open the music app when nothing is. */
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
        box.addView(musicArt, LinearLayout.LayoutParams(u(5f), u(5f)))
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
            setPadding(0, u(0.4f), 0, 0)
        }
        musicControls.addView(mediaButton(R.drawable.ic_m_prev, false) { MediaListener.current(this)?.transportControls?.skipToPrevious() })
        musicPlay = mediaButton(R.drawable.ic_m_play, true) {
            val c = MediaListener.current(this) ?: return@mediaButton
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            handler.postDelayed({ updateMusic() }, 300)
        }
        musicControls.addView(musicPlay)
        musicControls.addView(mediaButton(R.drawable.ic_m_next, false) { MediaListener.current(this)?.transportControls?.skipToNext() })
        musicControls.addView(mediaButton(R.drawable.ic_m_search, false) {
            runCatching { MusicBridge.openSearch(this) }.onFailure { toast(it.message ?: "Sem app de música") }
        })
        info.addView(musicControls)
        musicAction = pillButton("Abrir AuraMusic", null) { openMusicApp() }.apply { visibility = View.GONE }
        info.addView(musicAction, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.6f) })
        box.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return box
    }

    private fun mediaButton(icon: Int, primary: Boolean, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        val size = u(if (primary) 3.0f else 2.5f)
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
            showMusic("music:none", "Nada tocando", "Pesquise pelo celular ou abra o AuraMusic", null, false, showAction = true)
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
        val music = apps.firstOrNull { it.pkg == MusicBridge.AURA }
            ?: apps.firstOrNull { it.pkg == "com.apple.android.music" }
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

    private fun pillButton(label: String, icon: Int?, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = px(1.1f)
        setTextColor(TEXT)
        minHeight = 0
        minimumHeight = 0
        setPadding(u(1.3f), u(0.6f), u(1.3f), u(0.6f))
        icon?.let { res ->
            setCompoundDrawables(getDrawable(res)!!.apply { setBounds(0, 0, u(1.4f), u(1.4f)) }, null, null, null)
            compoundDrawablePadding = u(0.6f)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(3f).toFloat(), CYAN))
            addState(intArrayOf(), rounded(0x9912121D.toInt(), u(3f).toFloat(), LINE))
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
        if (Adb.state == Adb.State.OK) loader.execute { WatchNext.refresh() }
        refreshStremio()

        // The layout may have been changed from the phone
        if (HomePrefs.changes != shownChanges) {
            shownChanges = HomePrefs.changes
            loadApps(force = false)
        }
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

        /** Fewer, bigger posters per row. */
        private const val MAX_PER_ROW = 12
        private val TABS = listOf(
            Triple("media", "Filmes e séries", R.drawable.ic_tab_film),
            Triple("apps", "Apps", R.drawable.ic_t_apps),
            Triple("settings", "Configurações", R.drawable.ic_t_settings),
            Triple("rest", "Descanso", R.drawable.ic_tab_moon),
        )
        private val REST_MINUTES = listOf(1, 2, 5, 10, 15, 30)
        private val REST_DIM = listOf(0f, 0.2f, 0.35f, 0.5f)

        /** Genre rows on the home (title shown, Stremio genre name). */
        private val GENRES = listOf("Comédia" to "Comedy", "Ficção científica" to "Sci-Fi", "Drama" to "Drama")

        /** Whether the home is on screen (music search brings it back afterwards). */
        @Volatile
        var visible = false
            private set

        /** App artwork, kept in memory so going back home never reloads it. */
        private val icons = ConcurrentHashMap<String, Drawable>()

        fun open(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
