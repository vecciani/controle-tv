package com.controletv

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Receives the phone's air-mouse movements over UDP (port 8766): a light, direct channel
 * with no HTTP or ADB in the way, so the pointer follows the hand without noticeable delay.
 *
 * Packet: "<token>|<dx>|<dy>" with dx/dy as fractions of the screen width/height.
 */
class AirMouseReceiver(private val context: Context, private val pairing: Pairing) {
    @Volatile private var socket: DatagramSocket? = null

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
                    if (parts.size != 3) continue
                    if (parts[0] != trusted) {
                        if (!pairing.isValid(parts[0])) continue
                        trusted = parts[0]
                    }
                    val dx = parts[1].toFloatOrNull() ?: continue
                    val dy = parts[2].toFloatOrNull() ?: continue
                    MouseCursor.moveNow(context, dx.coerceIn(-1f, 1f), dy.coerceIn(-1f, 1f))
                }
            } catch (e: Exception) {
                Log.w("AirMouse", "receiver stopped", e)
            }
        }.apply { name = "air-mouse"; isDaemon = true; start() }
    }

    fun stop() {
        socket?.close()
    }

    companion object {
        const val PORT = 8766
    }
}
