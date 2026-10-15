package com.controletv.phone

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Holds the ADB connection from the phone to the TV and runs the commands the TV app
 * asks for. Amazon blocks ADB from apps on the TV itself, but not from other devices,
 * so this is what gives the remote full control.
 */
class Relay(context: Context, private val host: String) {
    enum class State { OFF, WAITING, OK }

    @Volatile var state = State.OFF
        private set
    @Volatile var error: String? = "Conectando"
        private set
    @Volatile var token: String? = null

    private val keys: AdbKeyPair
    @Volatile private var dadb: Dadb? = null
    @Volatile private var running = true

    init {
        val private = File(context.filesDir, "adbkey")
        val public = File(context.filesDir, "adbkey.pub")
        if (!private.exists()) AdbKeyPair.generate(private, public)
        keys = AdbKeyPair.read(private, public)
    }

    fun start() {
        thread("adb-connect") { connectLoop() }
        thread("relay-poll") { pollLoop() }
    }

    fun stop() {
        running = false
        runCatching { dadb?.close() }
    }

    /** Runs a command directly, used for the phone's own volume buttons. */
    @Synchronized
    fun shell(command: String): String {
        val connection = dadb ?: throw IllegalStateException("ADB não conectado")
        return try {
            connection.shell(command).allOutput
        } catch (e: Exception) {
            drop(e)
            throw e
        }
    }

    private fun connectLoop() {
        while (running) {
            if (dadb != null) { Thread.sleep(2_000); continue }
            try {
                // No read timeout: the TV shows "Allow debugging?" and waits for the user
                state = State.WAITING
                error = "Aceite \"Permitir depuração\" na TV"
                Dadb.create(host, PORT, keys, 3_000, 0).use { it.shell("echo ok") }
                val ready = Dadb.create(host, PORT, keys, 3_000, 20_000)
                ready.shell("echo ok")
                dadb = ready
                state = State.OK
                error = null
            } catch (e: ConnectException) {
                state = State.OFF
                error = "A TV recusou a conexão: ative a Depuração ADB"
                Thread.sleep(5_000)
            } catch (e: Throwable) {
                Log.w(TAG, "connect failed", e)
                state = State.OFF
                error = "${e.javaClass.simpleName}: ${e.message ?: "sem detalhes"}"
                Thread.sleep(5_000)
            }
        }
    }

    private fun drop(e: Exception) {
        Log.w(TAG, "adb dropped", e)
        runCatching { dadb?.close() }
        dadb = null
        state = State.OFF
        error = "Conexão caiu, reconectando"
    }

    /** Long-polls the TV for commands and sends back their output. */
    private fun pollLoop() {
        while (running) {
            val token = token
            if (token == null) { Thread.sleep(1_000); continue }
            try {
                val query = "adb=${state.name.lowercase()}&error=" + URLEncoder.encode(error ?: "", "UTF-8")
                val job = JSONObject(http("GET", "/api/relay/next?$query", token, null))
                if (!job.has("id")) {
                    if (state != State.OK) Thread.sleep(2_000)
                    continue
                }
                val result = JSONObject().put("id", job.getLong("id"))
                try {
                    result.put("output", shell(job.getString("command")))
                } catch (e: Exception) {
                    result.put("error", "Falha no ADB do celular: ${e.message}")
                }
                http("POST", "/api/relay/result", token, result.toString())
            } catch (e: Exception) {
                Log.w(TAG, "poll failed", e)
                Thread.sleep(3_000)
            }
        }
    }

    private fun http(method: String, path: String, token: String, body: String?): String {
        val conn = URL("http://$host:8765$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("X-Token", token)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun thread(name: String, body: () -> Unit) = Thread {
        try { body() } catch (e: InterruptedException) { /* stopping */ }
    }.apply {
        this.name = name
        isDaemon = true
        start()
    }

    companion object {
        private const val TAG = "Relay"
        private const val PORT = 5555
    }
}
