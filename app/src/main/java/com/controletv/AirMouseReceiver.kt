package com.controletv

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Receives the phone's air-mouse movements over UDP (port 8766): a light, direct channel
 * with no HTTP or ADB in the way, so the pointer follows the hand without noticeable delay.
 *
 * Packet: "<token>|<dx>|<dy>" with dx/dy as fractions of the screen width/height, or
 * "<token>|0|<amount>|s" to scroll (amount as a fraction of the screen height).
 */
class AirMouseReceiver(private val context: Context, private val pairing: Pairing) {
    companion object {
        const val PORT = 8766
        private const val MIN_SCROLL = 0.04f

        /** Movements received / rejected, for the diagnostics in the phone's settings. */
        @Volatile var received = 0L
            private set
        @Volatile var rejected = 0L
            private set
    }

    @Volatile private var socket: DatagramSocket? = null

    // Scrolling goes through ADB (a few hundred ms per swipe), so amounts pile up here and a
    // single worker turns them into one swipe at a time: no backlog, no lag building up
    private val scrollLock = Object()
    private var scrollPending = 0f

    fun start() {
        Thread {
            try {
                val s = DatagramSocket(PORT)
                socket = s
                val buf = ByteArray(256)
                // Tokens are checked once per sender and remembered, to keep each packet cheap
                var trusted = ""
                while (!s.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val parts = String(packet.data, 0, packet.length).split('|')
                    if (parts.size != 3 && parts.size != 4) continue
                    if (parts[0] != trusted) {
                        if (!pairing.isValid(parts[0])) { rejected++; continue }
                        trusted = parts[0]
                    }
                    received++
                    val dx = parts[1].toFloatOrNull() ?: continue
                    val dy = parts[2].toFloatOrNull() ?: continue
                    if (parts.size == 4 && parts[3] == "s") {
                        synchronized(scrollLock) {
                            scrollPending = (scrollPending + dy).coerceIn(-0.5f, 0.5f)
                            scrollLock.notifyAll()
                        }
                    } else {
                        MouseCursor.moveNow(context, dx.coerceIn(-1f, 1f), dy.coerceIn(-1f, 1f))
                    }
                }
            } catch (e: Exception) {
                Log.w("AirMouse", "receiver stopped", e)
            }
        }.apply { name = "air-mouse"; isDaemon = true; start() }

        Thread {
            while (true) {
                val amount = synchronized(scrollLock) {
                    while (kotlin.math.abs(scrollPending) < MIN_SCROLL) scrollLock.wait()
                    scrollPending.also { scrollPending = 0f }
                }
                runCatching { MouseCursor.scroll(context, amount) }
            }
        }.apply { name = "air-scroll"; isDaemon = true; start() }
    }

    fun stop() {
        socket?.close()
    }

}
