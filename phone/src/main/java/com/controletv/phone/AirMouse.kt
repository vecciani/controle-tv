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

/**
 * Air mouse: turning the phone moves the TV pointer, like an LG Magic Remote.
 * Reads the gyroscope as fast as the phone allows and sends the movement straight to the
 * TV over UDP, skipping HTTP and ADB, so the pointer keeps up with the hand.
 * Hold the phone like a remote (screen up, top pointing at the TV).
 */
class AirMouse(context: Context, private val host: String, private val token: () -> String?) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var lastEvent = 0L
    private var lastSent = 0L
    private var pendingX = 0f
    private var pendingY = 0f

    val available get() = gyro != null

    fun start() {
        if (gyro == null || thread != null) return
        val t = HandlerThread("air-mouse").apply { start() }
        thread = t
        Handler(t.looper).post {
            runCatching {
                socket = DatagramSocket()
                address = InetAddress.getByName(host)
            }
        }
        lastEvent = 0L
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
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
        if (lastEvent == 0L) { lastEvent = event.timestamp; return }
        val dt = (event.timestamp - lastEvent) / 1e9f
        lastEvent = event.timestamp
        if (dt <= 0f || dt > 0.1f) return

        // Turning left/right is rotation around the screen's normal (z); tilting is around x
        val yaw = event.values[2].let { if (abs(it) < DEADZONE) 0f else it }
        val pitch = event.values[0].let { if (abs(it) < DEADZONE) 0f else it }
        pendingX += -yaw * dt * GAIN
        pendingY += -pitch * dt * GAIN * TV_ASPECT

        // Send at most every 8 ms; tiny packets, so this is plenty and still immediate
        if (event.timestamp - lastSent < 8_000_000L || (pendingX == 0f && pendingY == 0f)) return
        val tok = token() ?: return
        val s = socket ?: return
        val a = address ?: return
        val data = "$tok|$pendingX|$pendingY".toByteArray()
        runCatching { s.send(DatagramPacket(data, data.size, a, PORT)) }
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
