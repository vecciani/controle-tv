package com.controletv.phone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Air mouse: turning the phone moves the TV pointer, like an LG Magic Remote.
 *
 * Works however the phone is held (lying like a remote or standing up): the gravity sensor
 * tells which way is up, so turning left/right is the rotation around the vertical, and
 * tilting is the rotation around the phone's side-to-side axis. Movement goes straight to the
 * TV over UDP, skipping HTTP and ADB, so the pointer keeps up with the hand.
 */
class AirMouse(context: Context, private val host: String, private val token: () -> String?) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var address: InetAddress? = null
    private var lastEvent = 0L
    private var lastSent = 0L
    private var pendingX = 0f
    private var pendingY = 0f
    // Unit "up" vector in phone coordinates; starts as "lying screen-up"
    private val up = floatArrayOf(0f, 0f, 1f)

    /** Packets sent since start, for the diagnostics in Settings. */
    @Volatile var sent = 0L
        private set

    val available get() = gyro != null

    fun start() {
        if (gyro == null || thread != null) return
        val t = HandlerThread("air-mouse").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        handler.post {
            runCatching {
                socket = DatagramSocket()
                address = InetAddress.getByName(host)
            }
        }
        lastEvent = 0L
        gravitySensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
    }

    fun stop() {
        sensors.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        socket?.close()
        socket = null
        pendingX = 0f
        pendingY = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) {
            // Gravity (or raw accelerometer, smoothed): which way is up for the phone right now
            val g = event.values
            val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).takeIf { it > 1f } ?: return
            val k = if (event.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.1f
            for (i in 0..2) up[i] += (g[i] / n - up[i]) * k
            return
        }
        if (lastEvent == 0L) { lastEvent = event.timestamp; return }
        val dt = (event.timestamp - lastEvent) / 1e9f
        lastEvent = event.timestamp
        if (dt <= 0f || dt > 0.1f) return

        val w = event.values
        // Turning left/right = rotation around the vertical; tilting = rotation around the phone's x axis
        val yawRate = (w[0] * up[0] + w[1] * up[1] + w[2] * up[2]).let { if (abs(it) < DEADZONE) 0f else it }
        val pitchRate = w[0].let { if (abs(it) < DEADZONE) 0f else it }
        pendingX += -yawRate * dt * GAIN
        pendingY += -pitchRate * dt * GAIN * TV_ASPECT

        // Send at most every 8 ms; tiny packets, so this is plenty and still immediate
        if (event.timestamp - lastSent < 8_000_000L || (pendingX == 0f && pendingY == 0f)) return
        val tok = token() ?: return
        val s = socket ?: return
        val a = address ?: return
        val data = "$tok|$pendingX|$pendingY".toByteArray()
        if (runCatching { s.send(DatagramPacket(data, data.size, a, PORT)) }.isSuccess) sent++
        pendingX = 0f
        pendingY = 0f
        lastSent = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val PORT = 8766
        // ~35 degrees of turn crosses the whole screen
        const val GAIN = 1.65f
        const val TV_ASPECT = 16f / 9f
        // Ignore hand tremor and sensor drift (rad/s)
        const val DEADZONE = 0.025f
    }
}
