package com.controletv.phone

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Fire-and-forget UDP to the TV (port 8766): the fast channel for pointer and scroll moves. */
class UdpLink(private val host: String) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null

    @Synchronized
    fun send(message: String): Boolean {
        return try {
            val s = socket ?: DatagramSocket().also { socket = it }
            val a = address ?: InetAddress.getByName(host).also { address = it }
            val data = message.toByteArray()
            s.send(DatagramPacket(data, data.size, a, PORT))
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun close() {
        socket?.close()
        socket = null
    }

    private companion object {
        const val PORT = 8766
    }
}
