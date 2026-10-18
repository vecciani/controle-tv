package com.controletv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.animation.TimeAnimator
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import kotlin.math.exp
import kotlin.math.hypot
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The screensaver: the user's photos full screen with a slow zoom and pan, a big clock and
 * date, what's playing (art, title, artist) and a dark veil so it's easy on the eyes.
 * With music on, everything flows with the song: the photo breathes with the loudness, a soft
 * light in the album's colour follows the melody (higher notes drift it right and up), and
 * photos change at the start of a bar with a transition that fits the moment (slow dissolve
 * when calm, a dive when it builds, a circular reveal on a chorus). Any key wakes it up.
 */
class ScreenSaverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var stage: FrameLayout
    private lateinit var veil: View
    private lateinit var glow: View
    private lateinit var light: View
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var music: LinearLayout
    private lateinit var musicArt: ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private var photos: List<File> = emptyList()
    private var index = 0
    private var musicKey = ""
    private var musicPlaying = false
    private var pulse: MusicPulse? = null
    private var lastPhotoAt = 0L
    private var beatsSincePhoto = 0
    private var beatMs: Long? = null
    // Effects glide from where they are towards these (see step)
    private var targetBreath = 0f
    private var targetLight = 0f
    private var targetPitch = 0.5f
    private var breath = 0f
    private var lightLevel = 0f
    private var pitch = 0.5f
    private var pop = 0f
    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    private val lightSize by lazy { (screenW * 0.75f).toInt() }

    private enum class Transition { DISSOLVE, ZOOM, REVEAL }
    private var glowColor = 0xFF22D3EE.toInt()

    private val unit by lazy { resources.displayMetrics.widthPixels / 100f }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(now).replaceFirstChar { it.uppercase() }
            updateMusic()
            handler.postDelayed(this, 1_000)
        }
    }

    private val nextPhoto = object : Runnable {
        override fun run() {
            showNext()
            handler.postDelayed(this, PHOTO_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // The two photos (for the crossfade) sit on a stage that pulses with the beat
        stage = FrameLayout(this)
        back = photoView()
        front = photoView()
        stage.addView(back, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stage.addView(front, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(stage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Dark veil over the photos ("lower the brightness"); it lifts a little on each beat
        veil = View(this).apply { setBackgroundColor(Color.argb((ScreenSaver.dim(this@ScreenSaverActivity) * 255).toInt(), 0, 0, 0)) }
        root.addView(veil, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // A soft light in the album's colour that follows the melody, and a glow from the bottom
        light = View(this).apply { alpha = 0f }
        root.addView(light, FrameLayout.LayoutParams(lightSize, lightSize, Gravity.CENTER))
        glow = View(this).apply { alpha = 0f }
        setGlowColor(glowColor)
        root.addView(glow, FrameLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6f).toInt(), Gravity.BOTTOM))
        // Stronger gradient behind the text
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45f).toInt(), Gravity.BOTTOM))

        val corner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(u(4f), 0, u(4f), u(3.5f))
        }
        val time = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text(7.5f, Color.WHITE, bold = true).apply { setShadowLayer(u(0.8f).toFloat(), 0f, 0f, 0x99000000.toInt()) }
        date = text(1.7f, 0xDDFFFFFF.toInt())
        time.addView(clock)
        time.addView(date)
        corner.addView(time, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        music = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = u(1.2f).toFloat() }
            setPadding(u(1f), u(1f), u(1.6f), u(1f))
            visibility = View.GONE
        }
        musicArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, u(0.7f).toFloat())
            }
            clipToOutline = true
        }
        music.addView(musicArt, LinearLayout.LayoutParams(u(5.5f), u(5.5f)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u(1.2f), 0, 0, 0) }
        musicTitle = text(1.4f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = u(24f) }
        musicArtist = text(1.15f, 0xCCFFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = u(24f) }
        info.addView(musicTitle)
        info.addView(musicArtist)
        music.addView(info)
        corner.addView(music)
        root.addView(corner, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
        loader.execute {
            val found = ScreenSaver.photos().shuffled()
            runOnUiThread {
                photos = found
                if (photos.isEmpty()) showPlaceholder() else handler.post(nextPhoto)
            }
        }
        // After a while, let the TV fall asleep on its own schedule
        handler.postDelayed({ window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }, KEEP_AWAKE_MS)
        if (ScreenSaver.followMusic(this)) startPulse()
    }

    // ---------- Following the music ----------

    private fun startPulse() {
        val p = MusicPulse { onMusicFrame(it) }
        if (p.start(this)) { pulse = p; motion.start(); return }
        // No permission yet: ask through the phone's ADB connection, then try once more
        if (Adb.state != Adb.State.OK) return
        loader.execute {
            runCatching { Adb.shell("pm grant $packageName android.permission.RECORD_AUDIO") }
            runOnUiThread { if (!isFinishing && p.start(this)) { pulse = p; motion.start() } }
        }
    }

    /** ~20 times a second: where the effects should head, and whether it's time for a new photo. */
    private fun onMusicFrame(f: MusicAnalyzer.Frame) {
        targetBreath = if (musicPlaying) f.loudness else 0f
        targetLight = if (musicPlaying) f.melody else 0f
        targetPitch = f.pitch
        if (!musicPlaying) return
        if (f.onset > 0f) pop = maxOf(pop, f.onset)
        f.beatMs?.let { beatMs = it }
        if (f.beat > 0f) beatsSincePhoto++
        if (photos.size < 2) return
        val shown = System.currentTimeMillis() - lastPhotoAt
        val transition = when {
            // Chorus or drop: change right away, revealed from the light
            f.section && shown > 4_000 -> Transition.REVEAL
            // Steady beat: change at the start of a bar (every 4 beats)
            f.beat > 0f && f.beatMs != null && beatsSincePhoto % 4 == 0 && shown > PHRASE_MS -> transitionFor(f.loudness)
            // No clear beat (calm songs): change on a new note
            f.beatMs == null && f.onset > 0f && shown > PHRASE_MS + 3_000 -> transitionFor(f.loudness)
            else -> null
        } ?: return
        handler.removeCallbacks(nextPhoto)
        showNext(transition)
        handler.postDelayed(nextPhoto, PHOTO_MS)
    }

    private fun transitionFor(loudness: Float) = when {
        loudness < 0.35f -> Transition.DISSOLVE
        loudness < 0.7f -> Transition.ZOOM
        else -> if (Random.nextBoolean()) Transition.ZOOM else Transition.REVEAL
    }

    /** Every screen frame: glide the effects towards the music, so nothing jumps. */
    private val motion = TimeAnimator().apply {
        setTimeListener { _, _, dt -> step(dt.coerceIn(1, 100).toFloat()) }
    }

    private fun step(dt: Float) {
        val quick = 1f - exp(-dt / 140f)
        val slow = 1f - exp(-dt / 450f)
        breath += (targetBreath - breath) * quick
        lightLevel += (targetLight - lightLevel) * quick
        pitch += (targetPitch - pitch) * slow
        pop *= exp(-dt / 380f)

        // The photo breathes with the loudness
        val s = 1f + 0.03f * breath
        stage.scaleX = s
        stage.scaleY = s
        // The veil lifts as the melody grows; the bottom glow follows the loudness
        veil.alpha = 1f - 0.3f * lightLevel
        glow.alpha = 0.45f * breath
        // A soft light follows the melody: right and up for high notes, left and down for low ones,
        // stronger as the melody grows and swelling a little on each new note
        light.alpha = (0.2f * lightLevel + 0.5f * lightLevel * lightLevel + 0.25f * pop).coerceIn(0f, 0.85f)
        light.translationX = (pitch - 0.5f) * screenW * 0.7f
        light.translationY = screenH * (0.15f - 0.3f * pitch)
        val ls = 0.85f + 0.3f * lightLevel + 0.15f * pop
        light.scaleX = ls
        light.scaleY = ls
        if (music.visibility == View.VISIBLE) {
            musicArt.scaleX = 1f + 0.05f * breath + 0.04f * pop
            musicArt.scaleY = musicArt.scaleX
        }
    }

    private fun setGlowColor(color: Int) {
        glowColor = color
        fun tint(alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        glow.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(tint(200), tint(70), 0))
        light.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = lightSize / 2f
            colors = intArrayOf(tint(170), tint(60), 0)
        }
    }

    /** The album art's average colour, made bright enough to glow. */
    private fun glowFrom(art: Bitmap): Int {
        val avg = Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(avg, hsv)
        hsv[1] = (hsv[1] * 1.4f).coerceIn(0.45f, 1f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        return Color.HSVToColor(hsv)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.post(tick)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(tick)
        super.onPause()
        // Leaving for any reason (key, another app) ends the screensaver
        finish()
    }

    override fun onDestroy() {
        motion.cancel()
        pulse?.stop()
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        ScreenSaver.touch()
        super.onDestroy()
    }

    // Any key or touch wakes up (and is not passed on, so it doesn't also click something)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        finish()
        return true
    }

    // ---------- Photos ----------

    private fun photoView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }

    private fun showNext(transition: Transition? = null) {
        if (photos.isEmpty()) return
        val file = photos[index % photos.size]
        index++
        lastPhotoAt = System.currentTimeMillis()
        beatsSincePhoto = 0
        // With music, transitions last a whole number of beats
        val beat = beatMs ?: 600L
        val duration = when (transition) {
            null -> FADE_MS
            Transition.DISSOLVE -> (beat * 4).coerceIn(1_800, 3_500)
            Transition.ZOOM -> (beat * 2).coerceIn(700, 1_400)
            Transition.REVEAL -> (beat * 2).coerceIn(600, 1_200)
        }
        loader.execute {
            val bitmap = runCatching { decode(file) }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                // Swap: the new photo fades in on top while the old one fades out
                val incoming = back
                back = front
                front = incoming
                incoming.setImageBitmap(bitmap)
                incoming.bringToFront()
                val outgoing = back
                // Delays and curves stick to a view's animator: reset them from the last transition
                for (v in listOf(incoming, outgoing)) v.animate().setStartDelay(0).setInterpolator(null)
                kenBurns(incoming)
                when (transition) {
                    Transition.ZOOM -> {
                        // Dive through: the old photo rushes towards you and fades as the new one appears
                        outgoing.animate().scaleX(outgoing.scaleX * 1.3f).scaleY(outgoing.scaleY * 1.3f).alpha(0f)
                            .setDuration(duration).setInterpolator(AccelerateInterpolator()).start()
                        incoming.animate().alpha(1f).setDuration(duration * 3 / 4).setStartDelay(duration / 4).start()
                    }
                    Transition.REVEAL -> {
                        // Opens in a circle from where the light is
                        incoming.alpha = 1f
                        val cx = (screenW / 2 + light.translationX).toInt().coerceIn(0, screenW)
                        val cy = (screenH / 2 + light.translationY).toInt().coerceIn(0, screenH)
                        val radius = hypot(screenW.toFloat(), screenH.toFloat())
                        runCatching {
                            ViewAnimationUtils.createCircularReveal(incoming, cx, cy, 0f, radius).apply {
                                this.duration = duration
                                interpolator = AccelerateDecelerateInterpolator()
                            }.start()
                        }
                        outgoing.animate().alpha(0f).setStartDelay(duration).setDuration(200).start()
                    }
                    else -> {
                        incoming.animate().alpha(1f).setDuration(duration).start()
                        outgoing.animate().alpha(0f).setDuration(duration).start()
                    }
                }
            }
        }
    }

    /** Slow zoom with a gentle drift in a random direction. */
    private fun kenBurns(view: ImageView) {
        val startScale = 1.0f + Random.nextFloat() * 0.05f
        val endScale = 1.10f + Random.nextFloat() * 0.06f
        val drift = u(2.5f).toFloat()
        view.scaleX = startScale
        view.scaleY = startScale
        view.translationX = (Random.nextFloat() - 0.5f) * drift
        view.translationY = (Random.nextFloat() - 0.5f) * drift
        view.animate()
            .scaleX(endScale).scaleY(endScale)
            .translationX((Random.nextFloat() - 0.5f) * drift * 2)
            .translationY((Random.nextFloat() - 0.5f) * drift * 2)
            .setDuration(PHOTO_MS + FADE_MS)
            .start()
    }

    /** Decodes at about screen size (big phone photos would waste memory) and fixes rotation. */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val target = resources.displayMetrics.widthPixels
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target * 9 / 16) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    }

    /** No photos yet: a calm neon gradient and how to add some. */
    private fun showPlaceholder() {
        front.alpha = 1f
        front.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B1A2A.toInt(), 0xFF1D0B2E.toInt(), 0xFF07070C.toInt())))
        (front.parent as FrameLayout).addView(text(1.4f, 0xCCFFFFFF.toInt()).apply {
            text = "Envie fotos pelo celular: Ajustes → Tela de descanso → Enviar fotos"
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = u(4f) })
    }

    // ---------- Music ----------

    private fun updateMusic() {
        val controller = MediaListener.current(this)
        val meta = controller?.metadata
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        musicPlaying = meta != null && playing
        if (meta == null || !playing) {
            music.visibility = View.GONE
            musicKey = ""
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val key = "$title|$artist|${art?.generationId}"
        if (key == musicKey) return
        musicKey = key
        musicTitle.text = title
        musicArtist.text = artist
        if (art != null) musicArt.setImageBitmap(art) else musicArt.setImageResource(R.drawable.ic_m_music)
        setGlowColor(art?.let { runCatching { glowFrom(it) }.getOrNull() } ?: 0xFF22D3EE.toInt())
        music.visibility = View.VISIBLE
    }

    // ---------- Helpers ----------

    private fun u(percent: Float) = (unit * percent).toInt()

    private fun text(sizePercent: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * sizePercent)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    companion object {
        @Volatile
        var visible = false
            private set

        private const val PHOTO_MS = 12_000L
        private const val FADE_MS = 1_500L
        /** With music on, a photo stays at least this long, then changes at the start of a bar. */
        private const val PHRASE_MS = 9_000L
        private const val KEEP_AWAKE_MS = 30 * 60_000L
    }
}
