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
    private lateinit var musicChip: Button
    private var musicKey = ""
    private var askedMusicPermission = false

    // Hero: the focused title's details over its backdrop
    private lateinit var backFront: ImageView
    private lateinit var backBack: ImageView
    private lateinit var heroLogo: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private lateinit var heroHint: TextView
    private var heroKey = ""
    private var heroPending: Runnable? = null
    private var backdropUrl: String? = null
    private val metaCache = ConcurrentHashMap<String, JSONObject>()

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
        // This is the home screen: Back goes to the top instead of leaving
        rowsScroll.smoothScrollTo(0, 0)
        firstCard()?.requestFocus()
    }

    // ---------- Layout ----------

    private fun buildScreen(): View {
        val root = FrameLayout(this).apply { background = NeonBackground() }

        // Backdrop of the focused title (two layers for a crossfade), darkened towards the
        // left and the bottom so the text and the rows stay readable
        backBack = backdropView()
        backFront = backdropView()
        root.addView(backBack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(backFront, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF207070C.toInt(), 0xB307070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.75f).toInt(), MATCH_PARENT))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF07070C.toInt(), 0xF207070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.62f).toInt(), Gravity.BOTTOM))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB307070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.18f).toInt(), Gravity.TOP))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(3.5f), u(1.8f), u(3.5f), 0)
            clipChildren = false
            clipToPadding = false
        }

        // Top bar: greeting on the left; search, music, status, clock and Wi-Fi on the right
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        greeting = text(1.35f, 0xDDF2F2F7.toInt(), bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        top.addView(greeting, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(pillButton("Pesquisar", R.drawable.ic_m_search) { SearchDialog().show() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.8f) })
        musicChip = pillButton("Música", R.drawable.ic_m_music) { musicChipClick() }.apply {
            maxWidth = u(22f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setOnLongClickListener {
                runCatching { MusicBridge.openSearch(this@MainActivity) }.onFailure { toast(it.message ?: "Sem app de música") }
                true
            }
        }
        top.addView(musicChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.2f) })
        phoneChip = chip()
        adbChip = chip()
        top.addView(phoneChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.6f) })
        top.addView(adbChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.2f) })
        clock = text(1.9f, TEXT, bold = true)
        top.addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.2f) })
        wifiButton = pillButton("Wi-Fi", null) {
            if (!SystemShortcuts.open(this, SystemShortcuts.SETTINGS.first { it.key == "set:net" })) toast("Esta TV não abriu a tela de rede")
        }
        top.addView(wifiButton)
        content.addView(top)

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
        hero.addView(heroLogo, LinearLayout.LayoutParams(u(30f), (screenH * 0.13f).toInt()))
        heroTitle = text(3.6f, TEXT, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = -0.02f
            setShadowLayer(u(0.8f).toFloat(), 0f, 0f, 0xAA000000.toInt())
        }
        hero.addView(heroTitle, LinearLayout.LayoutParams(u(48f), WRAP_CONTENT))
        heroMeta = text(1.3f, 0xE6F2F2F7.toInt(), bold = true).apply { compoundDrawablePadding = u(0.5f) }
        hero.addView(heroMeta, LinearLayout.LayoutParams(u(55f), WRAP_CONTENT).apply { topMargin = u(0.9f) })
        heroDesc = text(1.3f, 0xCCF2F2F7.toInt()).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        }
        hero.addView(heroDesc, LinearLayout.LayoutParams(u(46f), WRAP_CONTENT).apply { topMargin = u(0.8f) })
        heroHint = text(1.05f, CYAN)
        hero.addView(heroHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.9f) })
        content.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, (screenH * 0.40f).toInt()))

        // Rows: the focused one slides up under the hero (see bringRowToTop)
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, (screenH * 0.45f).toInt())
        }
        rowsScroll = RowsScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipChildren = false
            addView(rows)
        }
        content.addView(rowsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = u(0.8f) })
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
        var child: View = view
        var parent = child.parent
        while (parent is View && parent !== rows) {
            child = parent
            parent = child.parent
        }
        if (parent === rows) rowsScroll.smoothScrollTo(0, child.top)
    }

    // ---------- Hero ----------

    /** Shows a film or series: details at once, missing ones (synopsis, rating) fetched, backdrop after a short pause. */
    private fun heroFor(item: JSONObject, extra: String?) {
        val id = item.optString("id")
        heroKey = id
        applyHero(merge(item, metaCache[id]), extra)
        heroPending?.let { handler.removeCallbacks(it) }
        heroPending = Runnable {
            if (heroKey != id) return@Runnable
            val merged = merge(item, metaCache[id])
            setBackdrop(merged.optString("background").ifEmpty { "https://images.metahub.space/background/medium/$id/img" })
            if (!metaCache.containsKey(id) && (merged.optString("description").isEmpty() || merged.optString("imdbRating").isEmpty())) {
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
        heroMeta.setCompoundDrawables(null, null, null, null)
        heroMeta.text = info
        heroDesc.text = ""
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
        heroMeta.text = listOfNotNull(rating, year, runtime, kind, genres?.ifEmpty { null }, extra).joinToString("   ·   ")
        if (rating != null) {
            val star = getDrawable(R.drawable.ic_star)!!.mutate().apply {
                setColorFilter(0xFFFBBF24.toInt(), PorterDuff.Mode.SRC_IN)
                setBounds(0, 0, u(1.3f), u(1.3f))
            }
            heroMeta.setCompoundDrawables(star, null, null, null)
        } else {
            heroMeta.setCompoundDrawables(null, null, null, null)
        }
        heroDesc.text = item.optString("description")
        heroHint.text = "OK abre no Stremio"
    }

    private fun merge(item: JSONObject, meta: JSONObject?): JSONObject {
        if (meta == null) return item
        val out = JSONObject(item.toString())
        for (key in listOf("description", "imdbRating", "releaseInfo", "runtime", "genres", "genre", "background", "logo")) {
            if (out.optString(key).isEmpty() && meta.has(key)) out.put(key, meta.get(key))
        }
        return out
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
            // Keep the gradients and the content above the pictures
            (incoming.parent as FrameLayout).let { root ->
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i)
                    if (child !== backFront && child !== backBack) child.bringToFront()
                }
            }
        }
    }

    // ---------- Rows ----------

    private fun loadApps(force: Boolean) {
        loader.execute {
            val hidden = HomePrefs.hidden(this)
            val apps = Apps.list(this).filter { it.pkg !in hidden }
            val favorites = Apps.favorites(this).filter { pkg -> apps.any { it.pkg == pkg } }
            val folders = apps.associate { it.pkg to Folders.folderOf(this, it.pkg, it.label) }
            val rowOrder = HomePrefs.rowOrder(this)
            val sorted = rowOrder.associateWith { id -> HomePrefs.sortFolder(this, id, apps.filter { folders[it.pkg] == id }) }
            val signature = "s$stremioVersion|" + WatchHistory.list(this).joinToString { it.id + it.position / 60_000 } + WatchNext.entries().size +
                rowOrder.joinToString { id -> id + ":" + sorted[id]!!.joinToString(",") { it.pkg } } + "|" + favorites.joinToString()
            runOnUiThread {
                if (!force && signature == shownSignature) return@runOnUiThread
                shownSignature = signature
                val focused = currentFocus?.tag as? String
                rows.removeAllViews()
                val byPkg = apps.associateBy { it.pkg }

                // Stremio first: continue watching (landscape), then posters
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
                if (favorites.isNotEmpty()) {
                    rows.addView(row("Favoritos", favorites.mapNotNull { byPkg[it] }.map { appCard(it, true) }, R.drawable.ic_star))
                }
                for (id in rowOrder) {
                    val folder = Folders.ALL.first { it.id == id }
                    val inFolder = sorted[id].orEmpty()
                    if (inFolder.isNotEmpty()) rows.addView(row(folder.title, inFolder.map { appCard(it, it.pkg in favorites, folder.title) }))
                }
                val inputs = SystemShortcuts.inputs(this)
                if (inputs.isNotEmpty()) {
                    rows.addView(row("Entradas", inputs.map { input ->
                        card("in:${input.id}", input.label, "Entrada da TV", false, { it.setImageResource(R.drawable.ic_t_display) }, iconTile = true) {
                            if (!SystemShortcuts.openInput(this, input)) toast("Não deu para trocar para ${input.label}")
                        }
                    }))
                }
                rows.addView(row("Configurações", settingsTiles()))
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
        val w = u(11.5f)
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

    private fun settingsTiles(): List<View> {
        val tiles = mutableListOf<View>()
        // Each one tries several ways into Fire TV's settings app until one opens
        SystemShortcuts.SETTINGS.forEach { shortcut ->
            tiles += card(shortcut.key, shortcut.title, "Configurações da TV", false, { it.setImageResource(shortcut.icon) }, iconTile = true) {
                if (!SystemShortcuts.open(this, shortcut)) toast("Esta TV não abriu essa tela. Veja o diagnóstico nos Ajustes do celular.")
            }
        }
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
    private fun musicChipClick() {
        val controller = MediaListener.current(this)
        if (controller?.metadata == null) return openMusicApp()
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controller.transportControls.pause() else controller.transportControls.play()
        handler.postDelayed({ updateMusic() }, 300)
    }

    private fun updateMusic() {
        if (!MediaListener.enabled(this)) {
            // Turn music reading on by itself as soon as the phone's ADB connection allows it
            if (!askedMusicPermission && Adb.state == Adb.State.OK) {
                askedMusicPermission = true
                loader.execute { runCatching { MediaListener.enable() } }
            }
            showMusicChip("off", "Música", false)
            return
        }
        val controller = MediaListener.current(this)
        val meta = controller?.metadata
        if (meta == null) {
            showMusicChip("none", "Música", false)
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Música"
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        showMusicChip("$title|$artist|$playing", if (artist.isNotEmpty()) "$title · $artist" else title, playing)
    }

    private fun showMusicChip(key: String, label: String, playing: Boolean) {
        if (key == musicKey) return
        musicKey = key
        musicChip.text = label
        val icon = getDrawable(if (playing) R.drawable.ic_m_music else R.drawable.ic_m_music)!!.mutate().apply {
            setColorFilter(if (playing) CYAN else 0xFFA855F7.toInt(), PorterDuff.Mode.SRC_IN)
            setBounds(0, 0, u(1.4f), u(1.4f))
        }
        musicChip.setCompoundDrawables(icon, null, null, null)
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
        textSize = px(1.05f)
        setTextColor(TEXT)
        minHeight = 0
        minimumHeight = 0
        setPadding(u(1.2f), u(0.55f), u(1.2f), u(0.55f))
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
        val date = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(now)
        greeting.text = "$hello, ${HomePrefs.name(this)}   ·   $date"
    }

    private fun updateStatus() {
        val phoneActive = System.currentTimeMillis() - ControlServer.lastPhoneRequest < 15_000
        setChip(phoneChip, if (phoneActive) OK else MUTED, if (phoneActive) "Celular" else if (pairing.hasPhones()) "Pareado" else "Sem celular")
        val (color, label) = when (Adb.state) {
            Adb.State.OK -> OK to "Controle total"
            Adb.State.WAITING -> WARN to "Aceite a depuração"
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

    private fun chip() = text(0.95f, MUTED).apply {
        background = rounded(0x9912121D.toInt(), u(3f).toFloat(), LINE)
        setPadding(u(1f), u(0.5f), u(1f), u(0.5f))
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
