package com.controletv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD

/**
 * Keeps the server running in the background, even while another app is on screen.
 * Also brings our screen back whenever the Fire TV wakes up.
 */
class ControlService : Service() {
    private var server: ControlServer? = null
    private var airMouse: AirMouseReceiver? = null

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            MainActivity.open(context)
            Updater.checkSoon(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        server = ControlServer(this, Pairing(this)).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        airMouse = AirMouseReceiver(this, Pairing(this)).also { it.start() }
        // Generating the ADB key takes a moment the first time: keep it off the main thread
        Thread { Adb.init(this) }.start()
        Updater.startPeriodicChecks(this)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenOn)
        server?.stop()
        airMouse?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Controle TV", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Controle TV ativo")
            .setSmallIcon(R.drawable.ic_app)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "controle"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, ControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
