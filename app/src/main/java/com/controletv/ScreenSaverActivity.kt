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
 * Any key wakes it up. After a while it lets the TV go to sleep normally.
 */
class ScreenSaverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
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

        back = photoView()
        front = photoView()
        root.addView(back, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(front, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Dark veil over the photos ("lower the brightness"), plus a stronger gradient behind the text
        root.addView(View(this).apply { setBackgroundColor(Color.argb((ScreenSaver.dim(this@ScreenSaverActivity) * 255).toInt(), 0, 0, 0)) },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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

    private fun showNext() {
        if (photos.isEmpty()) return
        val file = photos[index % photos.size]
        index++
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
                kenBurns(incoming)
                incoming.animate().alpha(1f).setDuration(FADE_MS).start()
                back.animate().alpha(0f).setDuration(FADE_MS).start()
                // Keep the text layers above the photos
                (incoming.parent as FrameLayout).let { parent ->
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child !== front && child !== back) child.bringToFront()
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
        private const val KEEP_AWAKE_MS = 30 * 60_000L
    }
}
