package com.controletv

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.ConnectException

/**
 * Connection to the Fire TV's own ADB daemon (127.0.0.1:5555). This is what gives the
 * app "remote control" powers: key presses, closing and uninstalling apps, installing updates.
 *
 * The first connection makes the TV show "Allow USB debugging?"; once accepted with
 * "always allow", our key is remembered and later connections are silent.
 */
object Adb {
    enum class State { OFF, WAITING, OK }

    class Unavailable(state: State) : Exception(
        when (state) {
            State.WAITING -> "Aceite \"Permitir depuração\" na TV"
            else -> "Ative a Depuração ADB no Fire TV"
        }
    )

    @Volatile
    var state = State.OFF
        private set

    private var dadb: Dadb? = null
    private lateinit var keys: AdbKeyPair
    private var connector: Thread? = null

    fun init(context: Context) {
        if (::keys.isInitialized) return
        val private = File(context.filesDir, "adbkey")
        val public = File(context.filesDir, "adbkey.pub")
        if (!private.exists()) AdbKeyPair.generate(private, public)
        keys = AdbKeyPair.read(private, public)
        connectInBackground()
    }

    /** Runs a shell command on the TV and returns its output. */
    @Synchronized
    fun shell(command: String): String {
        val connection = dadb ?: throw Unavailable(state)
        return try {
            connection.shell(command).allOutput
        } catch (e: Exception) {
            Log.w(TAG, "shell failed, reconnecting", e)
            drop()
            throw Unavailable(state)
        }
    }

    @Synchronized
    fun install(apk: File) {
        val connection = dadb ?: throw Unavailable(state)
        connection.install(apk, "-r")
    }

    @Synchronized
    private fun drop() {
        runCatching { dadb?.close() }
        dadb = null
        state = State.OFF
        connectInBackground()
    }

    /**
     * Keeps trying to connect. The authorization prompt blocks the socket until the
     * user answers it on the TV, so that wait happens here, never on a request thread.
     */
    @Synchronized
    private fun connectInBackground() {
        if (connector?.isAlive == true) return
        connector = Thread {
            while (dadb == null) {
                try {
                    // No read timeout: waits as long as the "allow debugging" prompt is open
                    state = State.WAITING
                    Dadb.create(HOST, PORT, keys, CONNECT_TIMEOUT, 0).use { it.shell("echo ok") }
                    // Authorized: reopen with a read timeout so a stuck command can't hang requests
                    val ready = Dadb.create(HOST, PORT, keys, CONNECT_TIMEOUT, READ_TIMEOUT)
                    ready.shell("echo ok")
                    synchronized(this) {
                        dadb = ready
                        state = State.OK
                    }
                } catch (e: ConnectException) {
                    state = State.OFF
                    Thread.sleep(RETRY_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "connect failed", e)
                    state = State.OFF
                    Thread.sleep(RETRY_MS)
                }
            }
        }.apply {
            name = "adb-connector"
            isDaemon = true
            start()
        }
    }

    private const val TAG = "Adb"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT = 3_000
    private const val READ_TIMEOUT = 20_000
    private const val RETRY_MS = 10_000L
}
