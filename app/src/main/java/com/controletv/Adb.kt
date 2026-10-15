package com.controletv

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.ConnectException

/**
 * Connection to the Fire TV's own ADB daemon (port 5555). This is what gives the app
 * "remote control" powers: key presses, closing and uninstalling apps, installing updates.
 *
 * The first connection makes the TV show "Allow USB debugging?"; once accepted with
 * "always allow", our key is remembered and later connections are silent.
 */
object Adb {
    enum class State { OFF, WAITING, OK }

    class Unavailable(state: State) : Exception(
        when (state) {
            State.WAITING -> "Aceite \"Permitir depuração\" na TV"
            else -> "ADB indisponível. Veja os detalhes em Ajustes."
        }
    )

    /** The TV's own connection. Blocked by Amazon on recent Fire OS, so usually OFF. */
    @Volatile
    private var localState = State.OFF

    /** Whether ADB commands can run, either locally or through the phone app. */
    val state: State
        get() = when {
            localState == State.OK || PhoneRelay.ready -> State.OK
            PhoneRelay.connected && PhoneRelay.phoneState == State.WAITING -> State.WAITING
            else -> State.OFF
        }

    /** Who is running the commands, for the status screens. */
    val via: String?
        get() = when {
            localState == State.OK -> "tv"
            PhoneRelay.ready -> "celular"
            else -> null
        }

    @Volatile
    private var localError: String? = "Iniciando"

    /** Why ADB isn't available, shown in the phone's settings. */
    val lastError: String?
        get() = when {
            state == State.OK -> null
            PhoneRelay.connected -> "Celular: ${PhoneRelay.phoneError ?: "conectando"}"
            else -> "Abra o app Controle TV no celular. (Na TV: ${localError ?: "-"})"
        }

    private var dadb: Dadb? = null
    private lateinit var keys: AdbKeyPair
    private var connector: Thread? = null
    private val wake = Object()

    fun init(context: Context) {
        try {
            if (::keys.isInitialized) return
            val private = File(context.filesDir, "adbkey")
            val public = File(context.filesDir, "adbkey.pub")
            if (!private.exists()) AdbKeyPair.generate(private, public)
            keys = AdbKeyPair.read(private, public)
            connectInBackground()
        } catch (e: Throwable) {
            Log.e(TAG, "key setup failed", e)
            localError = "Falha ao criar a chave ADB: ${describe(e)}"
        }
    }

    /** Runs a shell command on the TV and returns its output. */
    fun shell(command: String): String {
        if (localState != State.OK && PhoneRelay.ready) return PhoneRelay.exec(command)
        return localShell(command)
    }

    @Synchronized
    private fun localShell(command: String): String {
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

    /** Skips the wait between attempts, for the "try again" button. */
    fun retryNow() {
        synchronized(wake) { wake.notifyAll() }
    }

    @Synchronized
    private fun drop() {
        runCatching { dadb?.close() }
        dadb = null
        localState = State.OFF
        connectInBackground()
    }

    /**
     * Keeps trying to connect. The authorization prompt blocks the socket until the
     * user answers it on the TV, so that wait happens here, never on a request thread.
     * Tries the loopback address first and the TV's own Wi-Fi address second, since
     * some Fire OS builds only accept one of them.
     */
    @Synchronized
    private fun connectInBackground() {
        if (connector?.isAlive == true) return
        connector = Thread {
            while (dadb == null) {
                val errors = mutableListOf<String>()
                for (host in listOfNotNull("127.0.0.1", Net.localIp()).distinct()) {
                    try {
                        // No read timeout: waits as long as the "allow debugging" prompt is open
                        Dadb.create(host, PORT, keys, CONNECT_TIMEOUT, 0).use { it.shell("echo ok") }
                        // Authorized: reopen with a read timeout so a stuck command can't hang requests
                        val ready = Dadb.create(host, PORT, keys, CONNECT_TIMEOUT, READ_TIMEOUT)
                        ready.shell("echo ok")
                        synchronized(this) {
                            dadb = ready
                            localState = State.OK
                            localError = null
                        }
                        break
                    } catch (e: ConnectException) {
                        errors += "$host:$PORT recusou a conexão (porta fechada)"
                    } catch (e: Throwable) {
                        Log.w(TAG, "connect to $host failed", e)
                        errors += "$host:$PORT ${describe(e)}"
                    }
                }
                if (dadb == null) {
                    localState = State.OFF
                    localError = errors.joinToString(" · ")
                    synchronized(wake) { wake.wait(RETRY_MS) }
                }
            }
        }.apply {
            name = "adb-connector"
            isDaemon = true
            start()
        }
    }

    private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: "sem detalhes"}"

    private const val TAG = "Adb"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT = 3_000
    private const val READ_TIMEOUT = 20_000
    private const val RETRY_MS = 60_000L
}
