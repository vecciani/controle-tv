package com.controletv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A mouse pointer for the TV: an arrow drawn on top of every app, moved from the phone.
 * Clicks and scrolls are injected through ADB at the pointer's position, so they work in
 * apps made for touch screens (phone apps installed on the TV).
 */
object MouseCursor {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var x = 0f
    private var y = 0f
    private var width = 1920
    private var height = 1080
    private val hide = Runnable { view?.visibility = View.GONE }

    /** Moves by a fraction of the screen width/height (what the phone's pad reports). */
    fun move(context: Context, fx: Float, fy: Float) {
        onMain(context) {
            x = (x + fx * width).coerceIn(0f, width - 1f)
            y = (y + fy * height).coerceIn(0f, height - 1f)
            place()
        }
    }

    fun click(context: Context, long: Boolean) {
        val (px, py) = position(context)
        Adb.shell(if (long) "input swipe $px $py $px $py 800" else "input tap $px $py")
    }

    /** Drags the content under the pointer by [fy] of the screen height (positive = content moves down). */
    fun scroll(context: Context, fy: Float) {
        val (px, py) = position(context)
        val distance = (fy * height).toInt().coerceIn(-height / 2, height / 2)
        val endY = (py + distance).coerceIn(1, height - 1)
        Adb.shell("input swipe $px $py $px $endY 180")
    }

    private fun position(context: Context): Pair<Int, Int> {
        var result = 0 to 0
        onMain(context) { result = x.toInt() to y.toInt() }
        return result
    }

    /** Runs on the main thread (creating the overlay if needed), waits, and rethrows its error here. */
    private fun onMain(context: Context, block: () -> Unit) {
        val done = CountDownLatch(1)
        var error: Exception? = null
        main.post {
            try {
                ensureOverlay(context.applicationContext)
                block()
            } catch (e: Exception) {
                error = e
            } finally {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    private fun ensureOverlay(context: Context) {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            // Allowed through ADB (granted by the phone's connection); the next call will work
            Thread { runCatching { Adb.shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow") } }.start()
            throw IllegalStateException("Liberando a setinha na TV… tente de novo em 2 segundos")
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        x = width / 2f
        y = height / 2f

        val size = (height * 0.045f).toInt()
        val arrow = ArrowView(context)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(arrow, lp)
        view = arrow
        params = lp
    }

    private fun place() {
        val v = view ?: return
        val lp = params ?: return
        // The arrow's tip is the top-left corner of the view
        lp.x = x.toInt()
        lp.y = y.toInt()
        v.visibility = View.VISIBLE
        (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp)
        main.removeCallbacks(hide)
        main.postDelayed(hide, 6_000)
    }

    /** Classic arrow pointer: white with a dark outline, readable on any background. */
    private class ArrowView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            stroke.strokeWidth = s * 0.06f
            val path = Path().apply {
                moveTo(s * 0.06f, s * 0.04f)
                lineTo(s * 0.06f, s * 0.86f)
                lineTo(s * 0.28f, s * 0.66f)
                lineTo(s * 0.44f, s * 0.96f)
                lineTo(s * 0.58f, s * 0.89f)
                lineTo(s * 0.43f, s * 0.60f)
                lineTo(s * 0.72f, s * 0.60f)
                close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }
}
