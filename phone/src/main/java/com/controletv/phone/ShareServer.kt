package com.controletv.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import java.io.FileInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lets the TV play photos and videos that are on the phone ("Compartilhar → Tocar na TV" in the
 * gallery): a small web server on the phone hands each shared file to the TV, with byte ranges
 * so videos can be skipped through. Nothing is uploaded or copied. Runs as a foreground service
 * (with a "Parar" button) while sharing, and stops itself after a few hours.
 */
class ShareServer : Service() {
    private data class Shared(val uri: Uri, val mime: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // The files were registered in share(); the intent's clip carries the permission to keep
        // reading them while this service runs, and the service keeps the app alive meanwhile
        startForeground()
        stopTimer?.let { handler.removeCallbacks(it) }
        stopTimer = Runnable { stopSelf() }.also { handler.postDelayed(it, 4 * 3600_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        server = null
        files.clear()
        super.onDestroy()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stopTimer: Runnable? = null

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Mostrando na TV", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, ShareServer::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Mostrando fotos e vídeos na TV")
            .setContentText("Direto do celular. Toque em Parar quando terminar.")
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val PORT = 8791
        private const val CHANNEL = "share"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"

        private val files = ConcurrentHashMap<String, Shared>()
        @Volatile private var server: ServerSocket? = null
        @Volatile private var running = false
        private val pool = Executors.newCachedThreadPool()

        private fun startServer(resolver: android.content.ContentResolver) {
            if (server != null) return
            val socket = ServerSocket(PORT)
            server = socket
            running = true
            Thread {
                while (running) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { serve(resolver, client) }; runCatching { client.close() } }
                }
            }.apply { name = "share-server"; isDaemon = true; start() }
        }

        /** GET /f/<token>, with Range support (206 Partial Content) for videos. */
        private fun serve(resolver: android.content.ContentResolver, client: Socket) {
            client.soTimeout = 30_000
            val input = client.getInputStream().bufferedReader()
            val request = input.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            val out = client.getOutputStream()
            val parts = request.split(' ')
            val token = parts.getOrNull(1)?.removePrefix("/f/")?.substringBefore('/').orEmpty()
            val file = files[token] ?: return status(out, "404 Not Found")
            val pfd = resolver.openFileDescriptor(file.uri, "r") ?: return status(out, "404 Not Found")
            pfd.use {
                val size = it.statSize
                val (start, end) = parseRange(range, size)
                val length = end - start + 1
                val head = buildString {
                    append(if (range != null && size > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${file.mime}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (size > 0) append("Content-Length: $length\r\n")
                    if (range != null && size > 0) append("Content-Range: bytes $start-$end/$size\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                if (parts.getOrNull(0) == "HEAD") return
                FileInputStream(it.fileDescriptor).use { stream ->
                    stream.channel.position(start)
                    val buffer = ByteArray(256 * 1024)
                    var left = if (size > 0) length else Long.MAX_VALUE
                    while (left > 0) {
                        val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
                out.flush()
            }
        }

        private fun parseRange(range: String?, size: Long): Pair<Long, Long> {
            if (range == null || size <= 0) return 0L to maxOf(0, size - 1)
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range) ?: return 0L to size - 1
            val (a, b) = m.destructured
            return when {
                a.isEmpty() -> maxOf(0, size - b.toLong()) to size - 1          // last N bytes
                b.isEmpty() -> a.toLong() to size - 1
                else -> a.toLong() to minOf(b.toLong(), size - 1)
            }
        }

        private fun status(out: OutputStream, line: String) {
            out.write("HTTP/1.1 $line\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }

        /**
         * Starts sharing [uris] and returns the address the TV should use for each. [tvHost] picks
         * the phone's address on the TV's network (on a hotspot, the phone is the gateway).
         */
        fun share(context: Context, uris: List<Uri>, tvHost: String): List<String> {
            val ip = phoneAddressFor(tvHost) ?: throw IllegalStateException("O celular não está na mesma rede da TV")
            val tokens = uris.map { UUID.randomUUID().toString().replace("-", "").take(16) }
            // Registered and served right away, so the TV can't ask before the service is up
            uris.forEachIndexed { i, uri ->
                files[tokens[i]] = Shared(uri, context.contentResolver.getType(uri) ?: "application/octet-stream")
            }
            startServer(context.applicationContext.contentResolver)
            val intent = Intent(context, ShareServer::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri("", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            return tokens.map { "http://$ip:$PORT/f/$it" }
        }

        /** This phone's address on the same /24 network as [tvHost]. */
        private fun phoneAddressFor(tvHost: String): String? {
            val prefix = tvHost.substringBeforeLast('.')
            return NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress!! }
                .firstOrNull { it.substringBeforeLast('.') == prefix }
        }
    }
}
