package com.controletv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
    private lateinit var date: TextView
    // Welcome screen (search, status, greeting, music) above the tabs: fills the screen while the
    // cursor is up there, folds away while browsing the rows
    private lateinit var header: FrameLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var welcomeBack: ImageView
    private var welcomeUrl: String? = null
    private var headerShown = true
    private lateinit var backLayer: FrameLayout
    // Music video of the song playing, behind the welcome screen (up from the top: full screen)
    private var clip: MusicClip? = null
    private lateinit var clipHolder: FrameLayout
    private lateinit var clipCaption: TextView
    private lateinit var topRow: LinearLayout
    private lateinit var column: LinearLayout
    private var clipFull = false
    private var headerAnim: ValueAnimator? = null
    private lateinit var phoneChip: ImageView
    private lateinit var adbChip: ImageView
    private lateinit var wifiButton: ImageView
    private lateinit var musicBox: LinearLayout
    private lateinit var musicArt: ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var musicControls: LinearLayout
    private lateinit var musicPlay: ImageView
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
    private val tabViews = mutableMapOf<String, TextView>()
    private var rebuilding = false
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
    // YouTube: newest videos of each channel picked on the phone
    private var ytRows: List<Pair<String, List<JSONObject>>> = emptyList()
    private var ytFetchedAt = 0L
    private var ytChanges = -1
    private var ytLoading = false
    private var shownUrl: String? = null
    private var lastMinute = -1
    private var shownChanges = -1
    private var shownAiVersion = 0

    private val unit by lazy { resources.displayMetrics.widthPixels / 100f } // 1% of screen width
    /** Projector mode: no music or YouTube; brightness, Bluetooth and many more film rows instead. */
    private val isProjector by lazy { deviceKind(this) == "projetor" }
    private val tabs by lazy { if (isProjector) TABS.filter { it.first != "youtube" } else TABS }
    private var popularMovies: List<JSONObject> = emptyList()
    private var gems: List<JSONObject> = emptyList()
    private var popularSeries: List<JSONObject> = emptyList()
    private lateinit var brightnessBar: View
    private lateinit var brightnessLabel: TextView
    private lateinit var bluetoothLabel: TextView
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
        RemoteImages.init(this)
        ControlService.start(this)
        HomeWatcher.ensureRealHome(this, withAdb = false)
        setContentView(buildScreen())
        header.post { fitWelcome() }
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
        // Another app on screen: the clip pauses (it resumes on the next tick here)
        updateClip(MediaListener.current(this).takeIf { MediaListener.enabled(this) })
        if (clipFull) exitClipFull()
        super.onPause()
    }

    override fun onDestroy() {
        clip?.destroy()
        clipFullscreen = false
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
        // Full-screen music video: down/back return; OK pauses; left/right change the song
        if (clipFull) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val controls = MediaListener.current(this)?.transportControls
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP -> exitClipFull()
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> run {
                        val id = clip?.clipId
                        if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && id != null && YouTube.smartTube(this) != null) {
                            exitClipFull()
                            watchClipInSmartTube(id)
                            return@run
                        }
                        val c = MediaListener.current(this)
                        if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) controls?.pause() else controls?.play()
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> controls?.skipToPrevious()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> controls?.skipToNext()
                    else -> return super.dispatchKeyEvent(event)
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            // Up from the top row (search, Wi-Fi) while a clip plays: the clip alone, full screen
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focus?.parent === topRow && headerShown && !isProjector &&
                MediaListener.current(this)?.playbackState?.state == PlaybackState.STATE_PLAYING
            ) {
                // The clip with its sound in SmartTube, from where the song is; without SmartTube,
                // the silent clip full screen here, or AuraMusic's own player
                val id = clip?.clipId
                val playingIn = MediaListener.current(this)?.packageName
                when {
                    // Already playing in SmartTube (in the background): back to its video
                    playingIn != null && playingIn == YouTube.smartTube(this) -> Apps.launch(this, playingIn)
                    id != null && YouTube.smartTube(this) != null -> watchClipInSmartTube(id)
                    clip?.showing == true -> enterClipFull()
                    // Background clip switched off (or still searching): find the clip now
                    YouTube.smartTube(this) != null -> findClipAndWatch()
                    else -> openMusicApp()
                }
                return true
            }
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

        // A column of bands that never overlap: the welcome screen (search, status, greeting,
        // music), which fills the screen on the menu and folds away while browsing; the tabs;
        // the focused title's hero; the rows
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- Music video behind everything (see MusicClip); filled in when a song plays
        clipHolder = FrameLayout(this)
        root.addView(clipHolder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- Full-screen picture of the focused title, behind everything while browsing:
        // darker on the left for the details, at the bottom for the rows and at the top for the tabs
        backLayer = FrameLayout(this).apply { alpha = 0f }
        // The two pictures live in their own holder: the crossfade brings one to the front of
        // the holder only, never above the shading layers on top of it
        val backImages = FrameLayout(this)
        backBack = backdropView()
        backFront = backdropView()
        backImages.addView(backBack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        backImages.addView(backFront, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        backLayer.addView(backImages, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        backLayer.addView(View(this).apply { setBackgroundColor(0x5907070C) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        backLayer.addView(View(this).apply {
            // Stays dark across the whole text column, so light pictures never wash out the details
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF707070C.toInt(), 0xF007070C.toInt(), 0xD907070C.toInt(), 0x8C07070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams((screenW * 0.8f).toInt(), MATCH_PARENT, Gravity.START))
        backLayer.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFA07070C.toInt(), 0xE607070C.toInt(), 0x9907070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.58f).toInt(), Gravity.BOTTOM))
        backLayer.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE607070C.toInt(), 0x8007070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.2f).toInt(), Gravity.TOP))
        root.addView(backLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---- Welcome: a dim picture of something to watch behind the search bar, status and
        // clock; then the big greeting, the date and the music player
        header = FrameLayout(this)
        welcomeBack = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        header.addView(welcomeBack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        header.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF207070C.toInt(), 0x9907070C.toInt(), 0x4D07070C))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        header.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF07070C.toInt(), 0x0007070C))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.25f).toInt(), Gravity.BOTTOM))
        val welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(3.5f), (screenH * 0.03f).toInt(), u(3.5f), 0)
        }
        topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchBar = Button(this).apply {
            text = if (isProjector) "Pesquisar filmes e séries no Stremio…" else "Pesquisar no Stremio, SmartTube e AuraMusic…"
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
        topRow.addView(searchBar, LinearLayout.LayoutParams(u(36f), WRAP_CONTENT))
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        phoneChip = statusIcon(R.drawable.ic_s_phone)
        adbChip = statusIcon(R.drawable.ic_s_plug)
        topRow.addView(phoneChip, LinearLayout.LayoutParams(u(1.7f), u(1.7f)).apply { rightMargin = u(1f) })
        topRow.addView(adbChip, LinearLayout.LayoutParams(u(1.7f), u(1.7f)).apply { rightMargin = u(0.6f) })
        wifiButton = statusIcon(R.drawable.ic_s_wifi).apply {
            isFocusable = true
            setPadding(u(0.4f), u(0.4f), u(0.4f), u(0.4f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(2f).toFloat(), CYAN))
                addState(intArrayOf(), rounded(Color.TRANSPARENT, u(2f).toFloat(), Color.TRANSPARENT))
            }
            setOnClickListener {
                if (!SystemShortcuts.open(this@MainActivity, SystemShortcuts.SETTINGS.first { it.key == "set:net" })) toast("Esta TV não abriu a tela de rede")
            }
        }
        topRow.addView(wifiButton, LinearLayout.LayoutParams(u(2.5f), u(2.5f)).apply { rightMargin = u(1.4f) })
        clock = text(2.2f, TEXT, bold = true)
        topRow.addView(clock)
        welcome.addView(topRow)

        welcome.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        greeting = text(4.6f, TEXT, bold = true).apply {
            letterSpacing = -0.02f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(u(1f).toFloat(), 0f, u(0.15f).toFloat(), 0x99000000.toInt())
        }
        date = text(1.8f, 0xCCF2F2F7.toInt())
        welcome.addView(greeting)
        welcome.addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.7f) })
        // Widgets: music (or the projector's brightness) and the weather side by side, then playlists
        val widgets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The weather card (taller, with the next days) sets the row's height; the other matches it
        widgets.addView(if (isProjector) buildProjectorWidget() else buildMusicWidget(), LinearLayout.LayoutParams(u(42f), MATCH_PARENT))
        widgets.addView(buildWeatherWidget(), LinearLayout.LayoutParams(u(26f), WRAP_CONTENT).apply { leftMargin = u(1.4f) })
        welcome.addView(widgets, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(2.4f) })
        if (!isProjector) welcome.addView(buildPlaylists(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(2f) })
        welcome.addView(text(1.2f, TEXT).apply {
            typeface = interSemibold
            text = "Não sei o que ver"
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            compoundDrawablePadding = u(0.7f)
            setCompoundDrawables(getDrawable(R.drawable.ic_tab_sparkles)!!.mutate().apply {
                setColorFilter(0xFFFBBF24.toInt(), PorterDuff.Mode.SRC_IN)
                setBounds(0, 0, u(1.5f), u(1.5f))
            }, null, null, null)
            setPadding(u(1.5f), u(0.8f), u(1.7f), u(0.8f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(0x40FBBF24, u(3f).toFloat(), 0xFFFBBF24.toInt()))
                addState(intArrayOf(), rounded(0x1FFBBF24, u(3f).toFloat(), 0x40FBBF24))
            }
            setOnClickListener { DiscoverDialog().show() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(1.6f) })
        welcome.addView(View(this), LinearLayout.LayoutParams(0, 0, 1.3f))
        header.addView(welcome, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Starts filling the screen; adjusted to the exact space once laid out (see fitWelcome)
        column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, (screenH * 0.88f).toInt()))

        // ---- Tabs: always visible; moving onto one swaps the rows
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, u(1.0f), 0, u(0.6f))
        }
        // Centered in a frosted pill, Apple TV style
        val tabGlass = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(0.4f), u(0.4f), u(0.4f), u(0.4f))
            background = rounded(0xB3141420.toInt(), u(4f).toFloat(), 0x1FFFFFFF)
        }
        tabs.forEach { (id, label, icon) ->
            val view = tabView(id, label, icon)
            tabViews[id] = view
            tabGlass.addView(view, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = u(0.15f); rightMargin = u(0.15f) })
        }
        tabRow.addView(tabGlass, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        styleTabs()
        column.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- Hero: the focused title's details; its picture fills the whole screen behind
        // the tabs, the hero and the rows (see backLayer)
        val heroBox = FrameLayout(this)

        // Details: logo (or title), IMDb · year · length · genres, awards, synopsis, cast, hint
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(u(3.5f), u(1f), 0, u(1.2f))
        }
        heroLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            visibility = View.GONE
        }
        hero.addView(heroLogo, LinearLayout.LayoutParams(u(26f), (screenH * 0.11f).toInt()))
        heroTitle = text(3.4f, TEXT, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = -0.02f
            setShadowLayer(u(0.8f).toFloat(), 0f, 0f, 0xAA000000.toInt())
        }
        hero.addView(heroTitle, LinearLayout.LayoutParams(u(46f), WRAP_CONTENT))
        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroImdb = text(1.45f, TEXT, bold = true).apply { visibility = View.GONE }
        metaRow.addView(heroImdb, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.2f) })
        heroMeta = text(1.35f, 0xF2F4F4F8.toInt()).apply { typeface = interSemibold; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        metaRow.addView(heroMeta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        hero.addView(metaRow, LinearLayout.LayoutParams(u(52f), WRAP_CONTENT).apply { topMargin = u(0.9f) })
        heroAwards = text(1.2f, 0xFFFBBF24.toInt()).apply {
            typeface = interSemibold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = u(0.5f)
            val icon = getDrawable(R.drawable.ic_award)!!.apply { setBounds(0, 0, u(1.25f), u(1.25f)) }
            setCompoundDrawables(icon, null, null, null)
            visibility = View.GONE
        }
        hero.addView(heroAwards, LinearLayout.LayoutParams(u(48f), WRAP_CONTENT).apply { topMargin = u(0.7f) })
        // The synopsis is always shown whole (long ones get a slightly smaller font, see applyHero)
        heroDesc = text(1.3f, 0xF2F2F2F7.toInt()).apply { setLineSpacing(0f, 1.12f) }
        hero.addView(heroDesc, LinearLayout.LayoutParams(u(54f), WRAP_CONTENT).apply { topMargin = u(0.8f) })
        heroCredits = text(1.15f, 0xB3F4F4F8.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        hero.addView(heroCredits, LinearLayout.LayoutParams(u(44f), WRAP_CONTENT).apply { topMargin = u(0.7f) })
        heroHint = text(1.1f, 0x99F4F4F8.toInt()).apply { typeface = interMedium; visibility = View.GONE }
        hero.addView(heroHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.9f) })
        // A dark shadow under every line of the details, readable even over white
        listOf(heroTitle, heroImdb, heroMeta, heroAwards, heroDesc, heroCredits, heroHint).forEach {
            it.setShadowLayer(u(0.45f).toFloat(), 0f, u(0.08f).toFloat(), 0xF2000000.toInt())
        }
        heroBox.addView(hero, FrameLayout.LayoutParams(u(60f), MATCH_PARENT, Gravity.START))
        column.addView(heroBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- Rows: the focused one slides up to the top of its band (see bringRowToTop)
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            // Side padding here (not on the scroll view) so a focused card's zoom and ring reach the screen edge
            setPadding(u(3.5f), u(0.6f), u(3.5f), (screenH * 0.45f).toInt())
        }
        // Its own band, full width: rows scrolled up are cut at its top edge instead of covering
        // the hero, and the focused card's zoom isn't cut at the sides
        rowsScroll = RowsScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            addView(rows)
        }
        column.addView(rowsScroll, LinearLayout.LayoutParams(MATCH_PARENT, (screenH * 0.39f).toInt()).apply { topMargin = u(0.4f) })
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Song name over the full-screen clip, for a few seconds
        clipCaption = text(1.6f, TEXT, bold = true).apply {
            alpha = 0f
            setShadowLayer(u(0.6f).toFloat(), 0f, 0f, 0xEE000000.toInt())
        }
        root.addView(clipCaption, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = u(4f); bottomMargin = u(3.5f)
        })

        // The top folds away while browsing the rows and comes back on the menu
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now != null && !rebuilding) showHeader(!inRows(now))
        }

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

    /** A menu-bar item: icon (and label), cyan pill when focused. */
    private fun navButton(description: String, icon: Int, label: String?, onClick: () -> Unit) = text(1.3f, MUTED, bold = true).apply {
        text = label ?: ""
        contentDescription = description
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        val pad = if (label == null) u(0.9f) else u(1.3f)
        setPadding(pad, u(0.75f), pad, u(0.75f))
        compoundDrawablePadding = if (label == null) 0 else u(0.6f)
        setCompoundDrawables(getDrawable(icon)!!.mutate().apply { setBounds(0, 0, u(1.5f), u(1.5f)) }, null, null, null)
        setOnClickListener { onClick() }
        stylePill(this, open = false)
    }

    private fun tabView(id: String, label: String, icon: Int) = navButton(label, icon, label) { firstCard()?.requestFocus() }.apply {
        tag = "tab:$id"
        setOnFocusChangeListener { _, focused -> if (focused && !rebuilding) selectTab(id) }
    }

    /** Focused: cyan pill with dark text. Open tab: soft light pill. Others: just the text. */
    private fun stylePill(view: TextView, open: Boolean) {
        val radius = u(3f).toFloat()
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE, radius, Color.WHITE))
            addState(intArrayOf(), if (open) rounded(0x2EFFFFFF, radius, Color.TRANSPARENT) else rounded(Color.TRANSPARENT, radius, Color.TRANSPARENT))
        }
        val colors = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(BG, if (open) TEXT else 0xB3F4F4F8.toInt()),
        )
        view.setTextColor(colors)
        view.compoundDrawableTintList = colors
    }

    private fun styleTabs() {
        tabViews.forEach { (id, view) -> stylePill(view, open = id == tab) }
    }

    /** Folds the top (search, status, greeting, music) away or brings it back, smoothly. */
    private fun showHeader(show: Boolean) {
        if (show == headerShown) return
        headerShown = show
        val from = header.height
        val to = if (show) welcomeHeight() else 0
        headerAnim?.cancel()
        headerAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                header.layoutParams.height = it.animatedValue as Int
                header.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (headerShown) fitWelcome()
                }
            })
            start()
        }
        header.animate().alpha(if (show) 1f else 0f).setDuration(260).start()
        clipHolder.animate().alpha(if (show) 1f else 0f).setDuration(320).start()
        // The title's full-screen picture only shows while browsing (the welcome screen has its own)
        backLayer.animate().alpha(if (show) 0f else 1f).setDuration(320).start()
    }

    /** All the screen above the tabs. */
    private fun welcomeHeight(): Int {
        val column = header.parent as View
        val total = if (column.height > 0) column.height else screenH
        val tabs = if (tabRow.height > 0) tabRow.height else tabRow.measuredHeight
        return (total - tabs).coerceAtLeast(0)
    }

    private fun fitWelcome() {
        if (!headerShown) return
        val h = welcomeHeight()
        if (header.layoutParams.height != h) {
            header.layoutParams.height = h
            header.requestLayout()
        }
    }

    /** A dim picture of something to watch behind the welcome screen. */
    private fun updateWelcomeBack() {
        val item = stremioWatching.firstOrNull() ?: newMovies.firstOrNull() ?: newSeries.firstOrNull() ?: return
        val url = "https://images.metahub.space/background/medium/${item.optString("id")}/img"
        if (url == welcomeUrl) return
        welcomeUrl = url
        RemoteImages.load(url, welcomeBack, screenW / 2) { ok ->
            if (ok) welcomeBack.animate().alpha(0.55f).setDuration(600).start()
        }
    }

    private fun statusIcon(icon: Int) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(MUTED)
    }

    /** Opens a tab: its rows slide in from the side it was moved to. */
    private fun selectTab(id: String) {
        if (id != tab) {
            val from = tabs.indexOfFirst { it.first == tab }
            val to = tabs.indexOfFirst { it.first == id }
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

    /** While the cursor is on the menu: the greeting, the date and what the open tab has. */
    private fun heroForTab() {
        val about = when (tab) {
            "media" -> "Filmes e séries do seu Stremio"
            "week" -> if (data?.aiItems.isNullOrEmpty()) "Conte seu gosto pelo celular, na aba Semana" else "Sugestões da IA para o seu gosto, renovadas toda segunda"
            "youtube" -> if (ytRows.isEmpty()) "Escolha seus canais pelo celular, na aba YouTube" else "${ytRows.size} canais   ·   abre no SmartTube, sem anúncios"
            "apps" -> "${data?.apps?.size ?: 0} apps na TV"
            "settings" -> "Telas da Fire TV, entradas e tela inicial"
            else -> restSummary()
        }
        heroText(tabs.first { it.first == tab }.second, about, "Desça para escolher")
        heroKey = "tab"
    }

    private fun restSummary(): String {
        val photos = data?.photos?.size ?: 0
        val state = if (ScreenSaver.enabled(this)) "Liga sozinho depois de ${ScreenSaver.minutes(this)} min sem usar" else "Desligado"
        return "$state   ·   $photos ${if (photos == 1) "foto" else "fotos"}"
    }

    // ---------- Hero ----------

    /** Shows a film or series: details at once, missing ones (synopsis, rating) fetched, backdrop after a short pause. */
    private fun heroFor(item: JSONObject, extra: String?) {
        if (item.optBoolean("yt")) return heroForVideo(item)
        val id = item.optString("id")
        heroKey = id
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

    /** A YouTube video: title, channel · when · views · length, description, its picture behind. */
    private fun heroForVideo(video: JSONObject) {
        val id = video.optString("id")
        heroKey = "yt:$id"
        heroPending?.let { handler.removeCallbacks(it) }
        heroLogo.visibility = View.GONE
        heroTitle.visibility = View.VISIBLE
        heroTitle.text = video.optString("name")
        heroImdb.visibility = View.GONE
        heroMeta.text = listOf("channel", "when", "views", "length").map { video.optString(it) }
            .filter { it.isNotEmpty() && it != "null" }.joinToString("   ·   ")
        heroAwards.visibility = View.GONE
        // The first paragraph is what the video is about; the rest is usually links
        val about = video.optString("description").split(Regex("\\n\\s*\\n")).firstOrNull().orEmpty().take(420)
        heroDesc.textSize = px(1.4f)
        heroDesc.text = about
        heroCredits.text = ""
        heroHint.text = if (YouTube.smartTube(this) != null) "OK abre no SmartTube, sem anúncios" else "Instale o SmartTube na TV para ver sem anúncios"
        heroHint.visibility = View.VISIBLE
        heroPending = Runnable { if (heroKey == "yt:$id") setBackdrop(video.optString("backdrop"), video.optString("thumb")) }
            .also { handler.postDelayed(it, 260) }
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
        // "OK abre" alone says nothing new: only hints that add something are shown
        val useful = hint.takeUnless { it == "OK abre" }.orEmpty()
        heroHint.text = useful
        heroHint.visibility = if (useful.isEmpty()) View.GONE else View.VISIBLE
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
        val synopsis = item.optString("description")
        heroDesc.textSize = px(when {
            synopsis.length <= 320 -> 1.5f
            synopsis.length <= 480 -> 1.3f
            else -> 1.15f
        })
        heroDesc.text = synopsis
        fun names(key: String, max: Int) = item.optJSONArray(key)?.let { a -> List(minOf(max, a.length())) { a.optString(it) } }?.filter { it.isNotBlank() }.orEmpty()
        val cast = names("cast", 3)
        val director = names("director", 1)
        heroCredits.text = listOfNotNull(
            cast.takeIf { it.isNotEmpty() }?.let { "Elenco: " + it.joinToString(", ") },
            director.firstOrNull()?.let { "Direção: $it" },
        ).joinToString("   ·   ")
        heroHint.text = ""
        heroHint.visibility = View.GONE
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
    private fun setBackdrop(url: String?, fallback: String? = null) {
        if (url == backdropUrl) return
        backdropUrl = url
        if (url == null) {
            backFront.animate().alpha(0f).setDuration(350).start()
            backBack.animate().alpha(0f).setDuration(350).start()
            return
        }
        val incoming = backBack
        RemoteImages.load(url, incoming, resources.displayMetrics.widthPixels / 2) { ok ->
            if (backdropUrl != url) return@load
            if (!ok) {
                // e.g. YouTube has no full-size picture for some videos
                if (fallback != null) { backdropUrl = null; setBackdrop(fallback) }
                return@load
            }
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
        val aiItems: List<JSONObject>,
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
            val aiItems = AiWeekly.items(this)
            val signature = "s$stremioVersion|y${ytRows.hashCode()}|" + WatchHistory.list(this).joinToString { it.id + it.position / 60_000 } + WatchNext.entries().size +
                rowOrder.joinToString { id -> id + ":" + sorted[id]!!.joinToString(",") { it.pkg } } + "|" + favorites.joinToString() +
                "|" + photos.joinToString { it.name } + "|" + aiItems.joinToString { it.optString("id") }
            runOnUiThread {
                if (!force && signature == shownSignature) return@runOnUiThread
                shownSignature = signature
                data = HomeData(apps, favorites, rowOrder, sorted, photos, aiItems)
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
            "week" -> weekRows(d)
            "youtube" -> youtubeRows()
            "apps" -> appRows(d)
            "settings" -> settingsRows()
            else -> restRows(d)
        }
        rebuilding = false
        // First show starts on the tabs (top showing); later, take the focus back only when it was
        // in the rows, never from the tabs or the top
        if (!focusedOnce) {
            focusedOnce = true
            tabViews[tab]?.requestFocus()
        } else if (focusInRows || currentFocus == null) {
            (focusedTag?.let { rows.findViewWithTag<View>(it) } ?: firstCard())?.requestFocus()
        }
    }

    private fun mediaRows() {
        rows.addView(row("Continuar assistindo",
            if (stremioWatching.isNotEmpty()) stremioWatching.take(12).map { stremioWideCard(it) } else listOf(stremioHintCard())))
        if (gems.isNotEmpty()) rows.addView(row("Joias escondidas: bem avaliados que pouca gente viu", gems.take(MAX_PER_ROW).map { stremioPoster(it) }))
        if (popularMovies.isNotEmpty()) rows.addView(row("Filmes em alta", popularMovies.take(MAX_PER_ROW).map { stremioPoster(it) }))
        if (popularSeries.isNotEmpty()) rows.addView(row("Séries em alta", popularSeries.take(MAX_PER_ROW).map { stremioPoster(it) }))
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

    /** The AI's picks for this week: films and series, each with why it fits in the hero. */
    private fun weekRows(d: HomeData) {
        if (d.aiItems.isEmpty()) {
            val w = u(21f)
            val message = "Sugestões da IA toda semana:\nno celular, aba Semana → conte seu gosto"
            rows.addView(row("Sugestões da semana", listOf(mediaTile(
                key = "week:hint", item = null, extra = null, imageUrl = null, fallbackUrl = null,
                width = w, height = w * 9 / 16, overlay = message, progress = null,
                textTitle = "Sugestões da semana", textInfo = message.replace("\n", " "),
            ) { toast("Abra o app do celular, aba Semana") })))
            return
        }
        val movies = d.aiItems.filter { it.optString("type") != "series" }
        val series = d.aiItems.filter { it.optString("type") == "series" }
        if (movies.isNotEmpty()) rows.addView(row("Filmes da semana, escolhidos pela IA", movies.map { stremioPoster(it) }))
        if (series.isNotEmpty()) rows.addView(row("Séries da semana", series.map { stremioPoster(it) }))
    }

    private fun youtubeRows() {
        if (ytRows.isEmpty()) {
            val w = u(21f)
            val message = if (ytLoading) "Carregando os vídeos…" else "Escolha seus canais pelo celular:\naba YouTube → Canais"
            rows.addView(row("YouTube", listOf(mediaTile(
                key = "yt:hint", item = null, extra = null, imageUrl = null, fallbackUrl = null,
                width = w, height = w * 9 / 16, overlay = message, progress = null,
                textTitle = "YouTube sem anúncios", textInfo = message.replace("\n", " "),
            ) { YouTube.smartTube(this)?.let { Apps.launch(this, it) } ?: toast("Instale o SmartTube na TV") })))
            return
        }
        ytRows.forEach { (channel, videos) -> rows.addView(row(channel, videos.map { videoTile(it) })) }
    }

    /** 16:9 video picture with its title; OK opens it in SmartTube. */
    private fun videoTile(video: JSONObject): View {
        val w = u(21f)
        return mediaTile(
            key = "yt:${video.optString("id")}", item = video, extra = null,
            imageUrl = video.optString("thumb"), fallbackUrl = video.optString("thumbSmall"),
            width = w, height = w * 9 / 16, overlay = video.optString("name"), progress = null,
        ) {
            runCatching { YouTube.open(this, video.optString("id")) }.onFailure { toast(it.message ?: "Não abriu") }
        }
    }

    /** Newest videos of each picked channel: every 15 minutes, or right away when the list changes. */
    private fun refreshYouTube() {
        val changed = YouTube.changes != ytChanges
        if (!changed && System.currentTimeMillis() - ytFetchedAt < 15 * 60_000) return
        if (ytLoading) return
        ytChanges = YouTube.changes
        ytFetchedAt = System.currentTimeMillis()
        ytLoading = true
        loader.execute {
            val loaded = YouTube.channels(this).mapNotNull { channel ->
                val videos = runCatching { YouTube.channelVideos(channel.optString("id")) }.getOrNull() ?: return@mapNotNull null
                channel.optString("name") to videos.take(MAX_PER_ROW).map { it.toJson() }
            }
            runOnUiThread {
                ytLoading = false
                ytRows = loaded
                loadApps(force = false)
            }
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
        row.addView(text(1.5f, 0xF2F4F4F8.toInt()).apply {
            typeface = interSemibold
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
            fun popular(type: String) = runCatching { Stremio.catalog(type, null).let { a -> List(a.length()) { a.getJSONObject(it) } } }.getOrDefault(emptyList())
            val topMovies = if (isProjector) popular("movie") else emptyList()
            // A fresh draw of hidden gems (catalog pages are kept for hours, so this is light)
            val hidden = runCatching {
                Discover.suggest(this, Discover.Choice("qualquer", "qualquer", "sozinho"))
                    .let { a -> List(a.length()) { a.getJSONObject(it) } }
            }.getOrDefault(gems)
            val topSeries = if (isProjector) popular("series") else emptyList()
            // Genre rows mix films and series, alternating
            val genres = (if (isProjector) PROJECTOR_GENRES else GENRES).map { (title, genre) ->
                val m = runCatching { Stremio.genre("movie", genre) }.getOrDefault(emptyList())
                val t = runCatching { Stremio.genre("series", genre) }.getOrDefault(emptyList())
                title to (0 until maxOf(m.size, t.size)).flatMap { i -> listOfNotNull(m.getOrNull(i), t.getOrNull(i)) }
            }
            runOnUiThread {
                stremioLoggedIn = loggedIn
                stremioLibrary = items.filter { it.optBoolean("inLibrary") }
                stremioWatching = items.filter { it.optBoolean("watching") }
                newMovies = movies
                newSeries = series
                popularMovies = topMovies
                gems = hidden
                popularSeries = topSeries
                genreRows = genres
                stremioVersion++
                updateWelcomeBack()
                loadApps(force = false)
            }
        }
    }

    private fun episodeLabel(videoId: String?): String? {
        val parts = videoId?.split(':') ?: return null
        return if (parts.size >= 3) "T${parts[1]} E${parts[2]}" else null
    }

    private fun openInStremio(item: JSONObject, videoId: String?) {
        runCatching { Stremio.openOnTv(this, item.optString("type"), item.optString("id"), videoId, item.optString("name").ifEmpty { null }) }
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
            textTitle = if (stremioLoggedIn) "Nada em andamento" else "Conecte seu Stremio", textInfo = message.replace("\n", " "),
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

    /**
     * Apple TV-like focus: a soft light across the top of the picture and a faint edge, in place
     * of a hard white frame (see [liftOnFocus]).
     */
    private fun focusSheen() = View(this).apply {
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x38FFFFFF, 0x0AFFFFFF, 0x00FFFFFF)).apply { cornerRadius = u(0.9f).toFloat() },
            GradientDrawable().apply { cornerRadius = u(0.9f).toFloat(); setStroke(maxOf(2, u(0.1f)), 0x59FFFFFF) },
        ))
        alpha = 0f
    }

    /** The focused picture lifts: grows, casts a shadow and catches the light. */
    private fun liftOnFocus(art: View, sheen: View, focused: Boolean) {
        val scale = if (focused) 1.12f else 1f
        art.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) u(1.4f).toFloat() else 0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        sheen.animate().alpha(if (focused) 1f else 0f).setDuration(180).start()
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
        val ring = focusSheen()
        art.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(art, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.setOnFocusChangeListener { _, focused ->
            liftOnFocus(art, ring, focused)
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
    // ---------- "Não sei o que ver" on the TV (same choices as the phone's) ----------

    private val DISCOVER_GROUPS = listOf(
        Triple("type", "Tipo", listOf("movie" to "Filme", "series" to "Série")),
        Triple("mood", "Clima", listOf("qualquer" to "Tanto faz", "rir" to "Rir", "leve" to "Leve", "tensao" to "Tensão",
            "emocionar" to "Emocionar", "susto" to "Susto", "pensar" to "Pensar", "aventura" to "Aventura",
            "romance" to "Romance", "misterio" to "Mistério", "crime" to "Crime", "animacao" to "Animação", "documentario" to "Documentário")),
        Triple("time", "Tempo", listOf("curto" to "Até 1h40", "medio" to "Até 2h10", "qualquer" to "Tanto faz")),
        Triple("with", "Com quem", listOf("sozinho" to "Sozinho", "casal" to "Casal", "familia" to "Família")),
        Triple("era", "Época", listOf("qualquer" to "Tanto faz", "classico" to "Clássicos", "8090" to "Anos 80 e 90", "0015" to "2000 a 2015", "recente" to "Recentes")),
        Triple("region", "De onde", listOf("qualquer" to "Qualquer lugar", "fora" to "Fora dos EUA", "asia" to "Ásia", "europa" to "Europa", "latam" to "América Latina", "brasil" to "Brasil")),
    )

    private inner class DiscoverDialog : Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
        private val choice = mutableMapOf("type" to "movie", "mood" to "qualquer", "time" to "qualquer", "with" to "sozinho", "era" to "qualquer", "region" to "qualquer")
        private val chips = mutableMapOf<String, MutableList<Pair<String, TextView>>>()
        private val results = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(u(1f), u(1.4f), u(1f), u(1.4f))
        }
        private val status = text(1.2f, 0xB3F4F4F8.toInt()).apply { typeface = interMedium }

        init {
            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(u(4f), u(2.6f), u(4f), u(2f))
                setBackgroundColor(0xF507070C.toInt())
                clipChildren = false
                clipToPadding = false
            }
            root.addView(text(3f, TEXT).apply { typeface = interBold; text = "Não sei o que ver" })
            root.addView(text(1.25f, 0xB3F4F4F8.toInt()).apply {
                text = "Nada de óbvio: nota alta, pouca gente viu, e nada que você já tem"
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.5f); bottomMargin = u(1.2f) })
            DISCOVER_GROUPS.forEach { (key, label, options) ->
                val line = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                line.addView(text(1.1f, 0x99F4F4F8.toInt()).apply { typeface = interSemibold; text = label.uppercase(); letterSpacing = 0.08f },
                    LinearLayout.LayoutParams(u(10f), WRAP_CONTENT))
                options.forEach { (value, title) ->
                    val chip = chip(title) { select(key, value) }
                    chips.getOrPut(key) { mutableListOf() } += value to chip
                    line.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.6f) })
                }
                root.addView(HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    clipChildren = false
                    clipToPadding = false
                    addView(line)
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(0.5f) })
            }
            val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actions.addView(chip("Sortear", primary = true) { draw() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.8f) })
            actions.addView(chip("Surpreenda-me") { surprise() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(1.6f) })
            actions.addView(status)
            root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(1.4f) })
            root.addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(results)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setContentView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(root) })
            chips.keys.forEach { paint(it) }
        }

        /** A pill: white when chosen; a light ring when the cursor is on it. */
        private fun chip(title: String, primary: Boolean = false, onClick: () -> Unit) = text(1.2f, TEXT).apply {
            typeface = interMedium
            text = title
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(u(1.4f), u(0.7f), u(1.4f), u(0.7f))
            tag = primary
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, _ -> style(v as TextView, isSelected) }
            style(this, false)
        }

        private fun style(chip: TextView, chosen: Boolean) {
            val primary = chip.tag == true
            val focused = chip.isFocused
            val r = u(3f).toFloat()
            chip.background = when {
                chosen || primary -> rounded(if (primary) CYAN else Color.WHITE, r, if (focused) 0xFF7DE3F4.toInt() else Color.TRANSPARENT)
                focused -> rounded(0x40FFFFFF, r, Color.WHITE)
                else -> rounded(0x1CFFFFFF, r, 0x14FFFFFF)
            }
            if (focused && (chosen || primary)) (chip.background as GradientDrawable).setStroke(u(0.25f), 0xFF7DE3F4.toInt())
            chip.setTextColor(if (chosen || primary) BG else TEXT)
        }

        private fun select(key: String, value: String) {
            choice[key] = value
            paint(key)
        }

        private fun paint(key: String) {
            chips[key]?.forEach { (value, chip) ->
                chip.isSelected = choice[key] == value
                style(chip, chip.isSelected)
            }
        }

        private fun surprise() {
            DISCOVER_GROUPS.forEach { (key, _, options) ->
                if (key in setOf("mood", "era", "region")) choice[key] = options.map { it.first }.filter { it != "qualquer" || key != "mood" }.random()
            }
            chips.keys.forEach { paint(it) }
            draw()
        }

        private fun draw() {
            status.text = "Garimpando joias…"
            results.removeAllViews()
            val c = Discover.Choice(
                mood = choice["mood"]!!, time = choice["time"]!!, company = choice["with"]!!,
                region = choice["region"]!!, type = choice["type"]!!, era = choice["era"]!!,
            )
            loader.execute {
                val found = runCatching { Discover.suggest(this@MainActivity, c).let { a -> List(a.length()) { a.getJSONObject(it) } } }
                runOnUiThread {
                    if (!isShowing) return@runOnUiThread
                    found.onFailure { status.text = it.message ?: "Não deu para sortear" }.onSuccess { list ->
                        status.text = if (list.isEmpty()) "Nada com essas escolhas: afrouxe o tempo, a época ou o lugar" else "OK abre no Stremio"
                        list.take(10).forEach { results.addView(resultCard(it)) }
                        results.getChildAt(0)?.requestFocus()
                    }
                }
            }
        }

        /** Poster, title and why, the way the TV rows show titles. */
        private fun resultCard(item: JSONObject): View {
            val w = u(12f)
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                clipChildren = false
                clipToPadding = false
            }
            val art = FrameLayout(this@MainActivity).apply {
                background = rounded(SURFACE, u(0.9f).toFloat(), LINE)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, u(0.9f).toFloat())
                }
                clipToOutline = true
            }
            val image = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            art.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val sheen = focusSheen()
            art.addView(sheen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            RemoteImages.load(item.optString("poster").ifEmpty { "https://images.metahub.space/poster/medium/${item.optString("id")}/img" }, image, w)
            box.addView(art, LinearLayout.LayoutParams(w, w * 3 / 2))
            box.addView(text(1.15f, TEXT).apply {
                typeface = interSemibold
                text = item.optString("name")
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { topMargin = u(0.9f) })
            box.addView(text(0.95f, 0xFFFBBF24.toInt()).apply {
                text = item.optString("why")
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { topMargin = u(0.3f) })
            box.layoutParams = LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { rightMargin = u(1.6f) }
            box.setOnFocusChangeListener { _, focused -> liftOnFocus(art, sheen, focused) }
            box.setOnClickListener {
                dismiss()
                openInStremio(item, null)
            }
            return box
        }
    }

    private inner class SearchDialog : Dialog(this@MainActivity) {
        private val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val smartTube = YouTube.smartTube(this@MainActivity)
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
                results.addView(text(1.2f, MUTED).apply {
                    this.text = if (isProjector) "Busca filmes e séries no Stremio" else "Busca filmes e séries no Stremio, vídeos no SmartTube e músicas no AuraMusic"
                })
                return
            }
            titles.forEach { t ->
                val kind = if (t.optString("type") == "series") "série" else "filme"
                val year = t.optString("releaseInfo")
                option("${t.optString("name")}  ($kind${if (year.isNotEmpty()) ", $year" else ""})  ·  abrir no Stremio") { openInStremio(t, null) }
            }
            if (smartTube != null && !isProjector) {
                option("Buscar \"$text\" no SmartTube") {
                    val search = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(text))).setPackage(smartTube)
                    runCatching { startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Apps.launch(this@MainActivity, smartTube) }
                }
            }
            if (MusicBridge.musicApp(this@MainActivity) != null && !isProjector) {
                option("Tocar \"$text\" no ${MusicBridge.appName(this@MainActivity)}") {
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
        tiles += card("set:amazon", "Tela original", "Tela inicial de fábrica deste aparelho", false, { it.setImageResource(R.drawable.ic_t_amazon) }, iconTile = true) {
            HomeWatcher.openOriginalHome(this)
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
            // Shortcut tiles are frosted glass with white icons; app banners sit on a dark tile
            background = if (iconTile) rounded(0x1CFFFFFF, u(0.9f).toFloat(), 0x14FFFFFF) else rounded(SURFACE, u(0.9f).toFloat(), LINE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, u(0.9f).toFloat())
            }
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            scaleType = if (iconTile) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (iconTile) {
                setPadding(w * 2 / 5, h / 5, w * 2 / 5, h * 2 / 5)
                imageTintList = android.content.res.ColorStateList.valueOf(0xF2FFFFFF.toInt())
            }
        }
        art.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (iconTile) {
            art.addView(text(1.1f, TEXT).apply {
                typeface = interMedium
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
        val ring = focusSheen()
        art.addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        box.addView(art, LinearLayout.LayoutParams(w, h))
        box.layoutParams = LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { rightMargin = u(1.2f) }

        bindImage(image)

        box.setOnFocusChangeListener { _, focused ->
            liftOnFocus(art, ring, focused)
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
    /** Projector: brightness (− bar +) and Bluetooth (status; OK opens the Bluetooth screen). */
    private fun buildProjectorWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(1.4f), u(1.2f), u(1.4f), u(1.2f))
            background = rounded(0xCC12121D.toInt(), u(1.2f).toFloat(), VIOLET_DIM)
        }
        // Brightness
        val bright = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brightnessLabel = text(1.4f, TEXT, bold = true).apply { text = "Brilho" }
        bright.addView(brightnessLabel, LinearLayout.LayoutParams(u(11f), WRAP_CONTENT))
        bright.addView(mediaButton(R.drawable.ic_t_minus, false) { changeBrightness(-10) })
        val track = FrameLayout(this).apply { background = rounded(0x33FFFFFF, u(0.4f).toFloat(), 0x00000000) }
        brightnessBar = View(this).apply { background = rounded(CYAN, u(0.4f).toFloat(), CYAN) }
        track.addView(brightnessBar, FrameLayout.LayoutParams(0, MATCH_PARENT))
        bright.addView(track, LinearLayout.LayoutParams(0, u(0.8f), 1f).apply { leftMargin = u(0.8f); rightMargin = u(0.2f) })
        bright.addView(mediaButton(R.drawable.ic_t_plus, false) { changeBrightness(10) })
        box.addView(bright)
        // Bluetooth
        val bt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setPadding(u(0.6f), u(0.6f), u(0.6f), u(0.6f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(0x3322D3EE, u(1f).toFloat(), CYAN))
                addState(intArrayOf(), rounded(Color.TRANSPARENT, u(1f).toFloat(), Color.TRANSPARENT))
            }
            setOnClickListener { runCatching { Projector.openBluetoothSettings(this@MainActivity) }.onFailure { toast("Não abriu o Bluetooth") } }
        }
        bt.addView(ImageView(this).apply { setImageResource(R.drawable.ic_t_bluetooth) }, LinearLayout.LayoutParams(u(2.6f), u(2.6f)))
        bluetoothLabel = text(1.25f, TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        bt.addView(bluetoothLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = u(1f) })
        box.addView(bt, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(1.2f) })
        // Keeps the old music fields usable (they're never shown here)
        musicBox = LinearLayout(this)
        updateProjector()
        return box
    }

    private fun changeBrightness(delta: Int) {
        loader.execute {
            val result = runCatching { Projector.setBrightness(this, Projector.brightnessPercent(this) + delta) }
            runOnUiThread { result.onSuccess { showBrightness(it) }.onFailure { toast(it.message ?: "Não mudou o brilho") } }
        }
    }

    private fun showBrightness(percent: Int) {
        brightnessLabel.text = "Brilho $percent%"
        (brightnessBar.parent as View).post {
            val full = (brightnessBar.parent as View).width
            brightnessBar.layoutParams = (brightnessBar.layoutParams as FrameLayout.LayoutParams).apply { width = full * percent / 100 }
            brightnessBar.requestLayout()
        }
    }

    private fun updateProjector() {
        if (!::brightnessLabel.isInitialized) return
        showBrightness(Projector.brightnessPercent(this))
        val bt = Projector.bluetooth()
        val connected = bt.optJSONArray("devices")?.let { a -> List(a.length()) { a.getJSONObject(it) } }?.filter { it.optBoolean("connected") }.orEmpty()
        bluetoothLabel.text = when {
            !bt.optBoolean("available") -> "Sem Bluetooth"
            !bt.optBoolean("on") -> "Bluetooth desligado · OK para abrir"
            connected.isNotEmpty() -> "Bluetooth: ${connected.joinToString { it.optString("name") }}"
            else -> "Bluetooth ligado · OK para conectar caixa ou fone"
        }
    }

    // ---------- Weather and playlists (welcome screen) ----------

    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherText: TextView
    private lateinit var weatherRange: TextView
    private lateinit var weatherBox: View
    private lateinit var weatherDays: LinearLayout
    private var weatherAt = 0L

    /** Glass card: now (icon, temperature, condition, range, city) and the next days. Information only. */
    private fun buildWeatherWidget(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(1.4f), u(1.1f), u(1.4f), u(1.1f))
            background = rounded(0x1CFFFFFF, u(1.2f).toFloat(), 0x14FFFFFF)
            visibility = View.INVISIBLE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(box)
        weatherDays = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(weatherDays, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = u(0.9f) })
        weatherIcon = ImageView(this)
        box.addView(weatherIcon, LinearLayout.LayoutParams(u(4.2f), u(4.2f)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u(1.1f), 0, 0, 0) }
        weatherTemp = text(2.6f, TEXT).apply { typeface = interSemibold }
        weatherText = text(1.1f, 0xE6F4F4F8.toInt()).apply { typeface = interMedium; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        weatherRange = text(0.95f, 0x99F4F4F8.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(weatherTemp)
        info.addView(weatherText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.3f) })
        info.addView(weatherRange, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.2f) })
        box.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        weatherBox = card
        return card
    }

    /** One small column per next day: "seg", icon, "15° 7°". */
    private fun showWeatherDays(days: List<Weather.Day>) {
        weatherDays.removeAllViews()
        days.take(4).forEach { d ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(text(0.95f, 0xB3F4F4F8.toInt()).apply { typeface = interMedium; text = d.weekday })
            col.addView(ImageView(this).apply { setImageResource(Weather.icon(d.code, true)) },
                LinearLayout.LayoutParams(u(1.9f), u(1.9f)).apply { topMargin = u(0.3f); bottomMargin = u(0.3f) })
            col.addView(text(0.95f, TEXT).apply {
                typeface = interMedium
                text = SpannableString("${d.max}°  ${d.min}°").apply {
                    setSpan(ForegroundColorSpan(0x80F4F4F8.toInt()), "${d.max}°  ".length, length, 0)
                }
            })
            weatherDays.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    /** Every 15 minutes (the service itself keeps the answer for 20). */
    private fun updateWeather() {
        if (!::weatherBox.isInitialized || System.currentTimeMillis() - weatherAt < 15 * 60_000L) return
        weatherAt = System.currentTimeMillis()
        loader.execute {
            val now = Weather.now() ?: return@execute
            runOnUiThread {
                weatherIcon.setImageResource(now.icon)
                weatherTemp.text = "${now.temp}°"
                weatherText.text = now.label
                weatherRange.text = listOf("Máx ${now.max}°", "Mín ${now.min}°", now.city).filter { it.isNotBlank() }.joinToString("  ·  ")
                showWeatherDays(now.next)
                weatherBox.visibility = View.VISIBLE
            }
        }
    }

    /** "Playlists": moods that start one of YouTube Music's own playlists in the music app. */
    private fun buildPlaylists(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        column.addView(text(1.2f, 0xCCF4F4F8.toInt()).apply {
            typeface = interSemibold
            text = "Playlists"
            compoundDrawablePadding = u(0.5f)
            setCompoundDrawables(getDrawable(R.drawable.ic_m_list)!!.mutate().apply { setBounds(0, 0, u(1.4f), u(1.4f)) }, null, null, null)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(u(0.2f), u(0.9f), u(0.2f), u(0.4f))
        }
        playlistRow = row
        fillPlaylists()
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        })
        return column
    }

    private var playlistRow: LinearLayout? = null
    private var shownPlaylists = -1

    /** The pills, from the list chosen on the phone (Ajustes → Playlists da tela inicial). */
    private fun fillPlaylists() {
        val row = playlistRow ?: return
        shownPlaylists = MusicPlaylists.version
        row.removeAllViews()
        MusicPlaylists.saved(this).forEach { mood ->
            row.addView(text(1.15f, TEXT).apply {
                typeface = interMedium
                text = mood.title
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(u(1.5f), u(0.75f), u(1.5f), u(0.75f))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE, u(3f).toFloat(), Color.WHITE))
                    addState(intArrayOf(), rounded(0x1CFFFFFF, u(3f).toFloat(), 0x14FFFFFF))
                }
                setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(BG, TEXT),
                ))
                setOnClickListener { playMood(mood) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = u(0.8f) })
        }
    }

    private fun playMood(mood: MusicPlaylists.Entry) {
        toast("Abrindo ${mood.title}…")
        loader.execute {
            val result = runCatching {
                MusicBridge.playPlaylist(this, MusicPlaylists.playlistId(mood))
                mood.title
            }
            runOnUiThread {
                result.onSuccess { toast("Tocando: $it") }.onFailure { toast(it.message ?: "Não tocou") }
            }
        }
    }

    /** The welcome screen's music player: cover, title and artist, previous / play / next. */
    private fun buildMusicWidget(): View {
        musicBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(1.2f), u(1.2f), u(1.4f), u(1.2f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(0x33FFFFFF, u(1.2f).toFloat(), 0x99FFFFFF.toInt()))
                addState(intArrayOf(), rounded(0x1CFFFFFF, u(1.2f).toFloat(), 0x14FFFFFF))
            }
            // Focusable only while there are no buttons: then OK opens the music app
            setOnClickListener { openMusicApp() }
        }
        musicArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, u(0.6f).toFloat())
            }
            clipToOutline = true
            setImageResource(R.drawable.ic_m_music)
            // White like the other widgets' icons (album covers replace it when a song plays)
            imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
            setPadding(u(1.4f), u(1.4f), u(1.4f), u(1.4f))
        }
        musicBox.addView(musicArt, LinearLayout.LayoutParams(u(8f), u(8f)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(1.4f), 0, u(0.6f), 0)
        }
        musicTitle = text(1.9f, TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        musicArtist = text(1.4f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(musicTitle)
        info.addView(musicArtist, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = u(0.5f) })
        musicBox.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        musicControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        musicControls.addView(mediaButton(R.drawable.ic_m_prev, false) { MediaListener.current(this)?.transportControls?.skipToPrevious() })
        musicPlay = mediaButton(R.drawable.ic_m_play, true) {
            val c = MediaListener.current(this) ?: return@mediaButton
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            handler.postDelayed({ updateMusic() }, 300)
        }
        musicControls.addView(musicPlay)
        musicControls.addView(mediaButton(R.drawable.ic_m_next, false) { MediaListener.current(this)?.transportControls?.skipToNext() })
        musicBox.addView(musicControls)
        return musicBox
    }

    private fun mediaButton(icon: Int, primary: Boolean, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        val size = u(if (primary) 4.2f else 3.4f)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { leftMargin = u(0.6f) }
        setPadding(u(0.8f), u(0.8f), u(0.8f), u(0.8f))
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(if (primary) CYAN else 0x4422D3EE, size / 2f, CYAN))
            addState(intArrayOf(), rounded(if (primary) 0xCCF2F2F7.toInt() else Color.TRANSPARENT, size / 2f, if (primary) Color.TRANSPARENT else LINE))
        }
        setOnClickListener { onClick() }
    }

    /** Hands the song over to its clip: AuraMusic pauses, SmartTube plays the clip from the same point. */
    private fun watchClipInSmartTube(videoId: String) {
        val controller = MediaListener.current(this)
        val state = controller?.playbackState
        val position = if (state == null) 0L else state.position +
            if (state.state == PlaybackState.STATE_PLAYING) ((android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0
        controller?.transportControls?.pause()
        runCatching { YouTube.open(this, videoId, position / 1000) }
            .onSuccess { toast("Clipe no SmartTube") }
            .onFailure { controller?.transportControls?.play(); toast(it.message ?: "Não abriu o SmartTube") }
    }

    private fun findClipAndWatch() {
        val meta = MediaListener.current(this)?.metadata ?: return openMusicApp()
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        toast("Procurando o clipe…")
        loader.execute {
            val id = MusicClip.auraMusicClips(title, artist) { q -> YtMusicSearch.videos(q) }.firstOrNull()
            runOnUiThread { if (id != null) watchClipInSmartTube(id) else toast("Não achei o clipe dessa música") }
        }
    }

    private fun enterClipFull() {
        clipFull = true
        clipFullscreen = true
        column.animate().alpha(0f).setDuration(400).start()
        showClipCaption()
    }

    private fun exitClipFull() {
        clipFull = false
        clipFullscreen = false
        column.animate().alpha(1f).setDuration(300).start()
        clipCaption.animate().cancel()
        clipCaption.alpha = 0f
    }

    private fun showClipCaption() {
        val meta = MediaListener.current(this)?.metadata ?: return
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        clipCaption.text = listOf(title, artist).filter { it.isNotEmpty() }.joinToString("  ·  ")
        clipCaption.animate().cancel()
        clipCaption.alpha = 1f
        clipCaption.animate().alpha(0f).setStartDelay(5_000).setDuration(800).start()
    }

    /** The clip follows the song: what it is, where it is, whether it's playing and on screen. */
    private fun updateClip(controller: android.media.session.MediaController?) {
        val meta = controller?.metadata
        val state = controller?.playbackState
        if (meta == null || state == null || !MusicClip.enabled(this)) { clip?.stop(); return }
        val player = clip ?: MusicClip(this) { ready ->
            // The clip replaces the welcome picture; if it stops, the picture comes back
            welcomeBack.animate().alpha(if (ready || welcomeUrl == null) 0f else 0.55f).setDuration(600).start()
            if (!ready && clipFull) exitClipFull()
        }.also {
            clip = it
            // Small player scaled up to cover the screen (light stream, see MusicClip.PLAYER_W)
            val scale = maxOf(screenW.toFloat() / MusicClip.PLAYER_W, screenH.toFloat() / MusicClip.PLAYER_H)
            it.view.scaleX = scale
            it.view.scaleY = scale
            clipHolder.addView(it.view, FrameLayout.LayoutParams(MusicClip.PLAYER_W, MusicClip.PLAYER_H, Gravity.CENTER))
        }
        val playing = state.state == PlaybackState.STATE_PLAYING
        val position = state.position + if (playing) ((android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
        val key = "${meta.getString(MediaMetadata.METADATA_KEY_ARTIST)}|$title"
        if (clipFull && key != lastClipSong) showClipCaption()
        lastClipSong = key
        player.update(title, meta.getString(MediaMetadata.METADATA_KEY_ARTIST), playing, position,
            meta.getLong(MediaMetadata.METADATA_KEY_DURATION), visible = headerShown && visible)
    }

    private var lastClipSong = ""

    private fun updateMusic() {
        if (isProjector) return updateProjector()
        updateClip(if (MediaListener.enabled(this)) MediaListener.current(this) else null)
        if (!MediaListener.enabled(this)) {
            // Turn the widget on by itself as soon as the phone's ADB connection allows it
            if (!askedMusicPermission && Adb.state == Adb.State.OK) {
                askedMusicPermission = true
                loader.execute { runCatching { MediaListener.enable() } }
            }
            showMusic("music:off", "Música", "Conecte o celular", null, false)
            return
        }
        val controller = MediaListener.current(this)
        val meta = controller?.metadata
        if (controller == null || meta == null) {
            showMusic("music:none", "Nada tocando", "OK abre o ${MusicBridge.appName(this)}", null, false)
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Música"
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        showMusic("$title|$artist|$playing|${art?.generationId}", title, artist, art, playing)
    }

    private fun showMusic(key: String, title: String, artist: String, art: android.graphics.Bitmap?, playing: Boolean) {
        if (key == musicKey) return
        musicKey = key
        musicTitle.text = title
        musicArtist.text = artist
        if (art != null) {
            // The album cover, as it is
            musicArt.imageTintList = null
            musicArt.setPadding(0, 0, 0, 0)
            musicArt.setImageBitmap(art)
        } else {
            musicArt.imageTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
            musicArt.setPadding(u(1.4f), u(1.4f), u(1.4f), u(1.4f))
            musicArt.setImageResource(R.drawable.ic_m_music)
        }
        val controls = key.contains('|')
        musicControls.visibility = if (controls) View.VISIBLE else View.GONE
        musicBox.isFocusable = !controls
        musicPlay.setImageResource(if (playing) R.drawable.ic_m_pause else R.drawable.ic_m_play)
    }

    private fun openMusicApp() {
        val apps = Apps.list(this)
        val music = MusicBridge.musicApp(this)?.let { pkg -> apps.firstOrNull { it.pkg == pkg } }
            ?: apps.firstOrNull { it.pkg == MusicBridge.AURA }
            ?: apps.firstOrNull { it.pkg == "com.apple.android.music" }
            ?: apps.firstOrNull { Folders.folderOf(this, it.pkg, it.label) == "musica" }
        if (music == null) toast("Nenhum app de música instalado") else Apps.launch(this, music.pkg)
    }

    // ---------- Wi-Fi button ----------

    private fun updateWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = cm.getNetworkCapabilities(cm.activeNetwork) != null
        wifiButton.imageTintList = android.content.res.ColorStateList.valueOf(if (online) OK else WARN)
        wifiButton.contentDescription = if (online) "Rede conectada" else "Sem rede"
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
        updateWeather()
        if (MusicPlaylists.version != shownPlaylists && playlistRow != null) fillPlaylists()
        if (Adb.state == Adb.State.OK) loader.execute { WatchNext.refresh() }
        refreshStremio()
        refreshYouTube()

        // The layout may have been changed from the phone
        if (HomePrefs.changes != shownChanges || AiWeekly.version != shownAiVersion) {
            shownChanges = HomePrefs.changes
            shownAiVersion = AiWeekly.version
            loadApps(force = false)
        }
    }

    private fun setChip(icon: ImageView, color: Int, label: String) {
        if (icon.tag == color && icon.contentDescription == label) return
        icon.tag = color
        icon.contentDescription = label
        icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ---------- Small helpers ----------

    private fun u(percent: Float) = (unit * percent).toInt()
    @Suppress("DEPRECATION")
    private fun px(percent: Float) = unit * percent / resources.displayMetrics.scaledDensity

    private fun text(sizePercent: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = px(sizePercent)
        setTextColor(color)
        typeface = if (bold) interBold else interRegular
        includeFontPadding = false
    }

    // Inter (SIL Open Font License, see assets/fonts), close to Apple's own type
    private fun font(name: String) = runCatching { Typeface.createFromAsset(assets, "fonts/inter_$name.ttf") }.getOrNull()
    private val interRegular by lazy { font("regular") ?: Typeface.DEFAULT }
    private val interMedium by lazy { font("medium") ?: Typeface.DEFAULT }
    private val interSemibold by lazy { font("semibold") ?: Typeface.DEFAULT_BOLD }
    private val interBold by lazy { font("bold") ?: Typeface.DEFAULT_BOLD }
    private val interHeavy by lazy { font("extrabold") ?: Typeface.DEFAULT_BOLD }

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
            Triple("week", "Semana", R.drawable.ic_tab_sparkles),
            Triple("youtube", "YouTube", R.drawable.ic_tab_youtube),
            Triple("apps", "Apps", R.drawable.ic_t_apps),
            Triple("settings", "Configurações", R.drawable.ic_t_settings),
            Triple("rest", "Descanso", R.drawable.ic_tab_moon),
        )
        private val REST_MINUTES = listOf(1, 2, 5, 10, 15, 30)
        private val REST_DIM = listOf(0f, 0.2f, 0.35f, 0.5f)

        /** Genre rows on the home (title shown, Stremio genre name). */
        private val GENRES = listOf("Comédia" to "Comedy", "Ficção científica" to "Sci-Fi", "Drama" to "Drama")
        private val PROJECTOR_GENRES = listOf(
            "Ação" to "Action", "Aventura" to "Adventure", "Comédia" to "Comedy", "Drama" to "Drama",
            "Ficção científica" to "Sci-Fi", "Suspense" to "Thriller", "Terror" to "Horror", "Romance" to "Romance",
            "Animação" to "Animation", "Família" to "Family", "Fantasia" to "Fantasy", "Crime" to "Crime",
            "Mistério" to "Mystery", "Documentário" to "Documentary", "Guerra" to "War", "Faroeste" to "Western",
        )

        /** Whether the home is on screen (music search brings it back afterwards). */
        @Volatile
        var visible = false
            private set

        /** A music video full screen on the home: the screensaver must not start over it. */
        @Volatile
        var clipFullscreen = false
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
