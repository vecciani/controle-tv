package com.controletv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Photos shared from the phone's gallery ("Compartilhar → Tocar na TV"), full screen: they come
 * straight from the phone over the network (nothing is saved on the TV), crossfade with a slow
 * zoom and advance on their own. Left / right move, OK pauses, Back leaves.
 */
class PhotoShowActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var counter: TextView
    private lateinit var paused: TextView
    private var urls: List<String> = emptyList()
    private var index = 0
    private var playing = true
    private var shownToken = 0

    private val next = object : Runnable {
        override fun run() {
            show(index + 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        back = photoView()
        front = photoView()
        root.addView(back, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(front, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(MATCH_PARENT, resources.displayMetrics.heightPixels / 5, Gravity.BOTTOM))
        counter = text(1.3f).apply { setShadowLayer(8f, 0f, 0f, Color.BLACK) }
        root.addView(counter, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, u(3f), u(2f))
        })
        paused = text(1.6f).apply {
            text = "Pausado · OK continua"
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = u(1f).toFloat() }
            setPadding(u(1.4f), u(0.7f), u(1.4f), u(0.7f))
            visibility = View.GONE
        }
        root.addView(paused, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = u(2f) })
        setContentView(root)
        visible = true
        if (urls.isEmpty()) finish() else show(0)
    }

    override fun onDestroy() {
        visible = false
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        ScreenSaver.touch()
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> show(index + 1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> show(index - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> togglePause()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun togglePause() {
        playing = !playing
        paused.visibility = if (playing) View.GONE else View.VISIBLE
        handler.removeCallbacks(next)
        if (playing) handler.postDelayed(next, SHOW_MS)
    }

    private fun show(i: Int) {
        handler.removeCallbacks(next)
        index = ((i % urls.size) + urls.size) % urls.size
        counter.text = if (urls.size > 1) "${index + 1} / ${urls.size}" else ""
        val token = ++shownToken
        val url = urls[index]
        loader.execute {
            val bitmap = runCatching { load(url) }.getOrNull()
            runOnUiThread {
                if (isFinishing || token != shownToken) return@runOnUiThread
                if (bitmap != null) crossfade(bitmap)
                if (playing && urls.size > 1) handler.postDelayed(next, SHOW_MS)
            }
        }
    }

    private fun crossfade(bitmap: Bitmap) {
        val incoming = back
        back = front
        front = incoming
        incoming.setImageBitmap(bitmap)
        incoming.bringToFront()
        counter.bringToFront()
        paused.bringToFront()
        incoming.alpha = 0f
        val start = 1.0f + Random.nextFloat() * 0.03f
        incoming.scaleX = start
        incoming.scaleY = start
        incoming.animate().alpha(1f).setDuration(700).start()
        incoming.animate().scaleX(start + 0.06f).scaleY(start + 0.06f).setDuration(SHOW_MS + 700).start()
        back.animate().alpha(0f).setDuration(700).start()
    }

    /** Downloads one photo from the phone, at about screen size, upright. */
    private fun load(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 30_000
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        val target = resources.displayMetrics.widthPixels
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target * 9 / 16) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private fun photoView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        alpha = 0f
    }

    private fun u(percent: Float) = (resources.displayMetrics.widthPixels / 100f * percent).toInt()

    private fun text(sizePercent: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.displayMetrics.widthPixels / 100f * sizePercent)
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val SHOW_MS = 7_000L

        @Volatile var visible = false
            private set

        fun open(context: Context, urls: List<String>) {
            context.startActivity(
                Intent(context, PhotoShowActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
