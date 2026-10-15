package com.controletv

import android.content.Context
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Step-by-step ADB handshake that reports what the TV answered at each stage.
 * Only for diagnosing connection problems; the real connection is in [Adb].
 */
object AdbProbe {
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    // ASN.1 DigestInfo prefix for SHA-1, which adbd expects in front of the 20-byte token
    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    private class Message(val command: Int, val arg0: Int, val data: ByteArray)

    fun run(context: Context): String {
        val hosts = listOfNotNull("127.0.0.1", Net.localIp()).distinct()
        return hosts.joinToString("\n\n") { host -> "[$host]\n" + probe(context, host) }
    }

    private fun probe(context: Context, host: String): String {
        val log = mutableListOf<String>()
        val start = System.currentTimeMillis()
        fun step(text: String) = log.add("${(System.currentTimeMillis() - start) / 1000.0}s $text")

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, 5555), 3000)
                socket.soTimeout = 10_000
                step("conectou na porta 5555")
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
                    val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
                        .putInt(data.sumOf { it.toInt() and 0xff }).putInt(command.inv())
                    output.write(header.array())
                    output.write(data)
                    output.flush()
                }

                fun read(): Message {
                    val header = ByteArray(24).also { input.readFully(it) }
                    val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val command = buf.int
                    val arg0 = buf.int
                    buf.int
                    val length = buf.int
                    return Message(command, arg0, ByteArray(length).also { input.readFully(it) })
                }

                fun describe(m: Message) = when (m.command) {
                    CNXN -> "CNXN (autorizado: ${String(m.data).trim(Char(0))})"
                    AUTH -> "AUTH tipo ${m.arg0}"
                    else -> "comando 0x${Integer.toHexString(m.command)}"
                }

                send(CNXN, 0x01000000, 256 * 1024, "host::\u0000".toByteArray())
                step("enviou CNXN")
                var reply = read()
                step("TV respondeu ${describe(reply)}")
                if (reply.command == CNXN) return log.joinToString("\n")

                send(AUTH, AUTH_SIGNATURE, 0, sign(context, reply.data))
                step("enviou assinatura da nossa chave")
                reply = read()
                step("TV respondeu ${describe(reply)}")
                if (reply.command == CNXN) return log.joinToString("\n")

                val public = File(context.filesDir, "adbkey.pub").readBytes() + 0
                send(AUTH, AUTH_RSAPUBLICKEY, 0, public)
                step("enviou chave pública: a TV deveria mostrar \"Permitir depuração?\"")
                socket.soTimeout = 60_000
                reply = read()
                step("TV respondeu ${describe(reply)}")
            }
        } catch (e: EOFException) {
            step("a TV FECHOU a conexão aqui")
        } catch (e: SocketTimeoutException) {
            step("sem resposta (tempo esgotado)")
        } catch (e: Throwable) {
            step("erro: ${e.javaClass.simpleName}: ${e.message}")
        }
        return log.joinToString("\n")
    }

    private fun sign(context: Context, token: ByteArray): ByteArray {
        val pem = File(context.filesDir, "adbkey").readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(SHA1_PREFIX + token)
    }
}
