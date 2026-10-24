package com.controletv.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The remote in the phone's notification shade: back, play/pause, volume down/up and home,
 * without opening the app. Each button sends the key straight to the TV (or projector) the
 * phone is connected to. Switched off and on in the app (Ajustes → Controle na notificação).
 */
object NotificationRemote {
    private const val CHANNEL = "remote"
    private const val ID = 11
    private const val ACTION = "com.controletv.phone.REMOTE_KEY"

    private val BUTTONS = listOf(
        Triple("back", "Voltar", R.drawable.ic_n_back),
        Triple("voldown", "Volume −", R.drawable.ic_n_minus),
        Triple("playpause", "Play/Pausa", R.drawable.ic_n_play),
        Triple("volup", "Volume +", R.drawable.ic_n_plus),
        Triple("home", "Início", R.drawable.ic_n_home),
    )

    fun enabled(context: Context) = context.getSharedPreferences("tv", Context.MODE_PRIVATE).getBoolean("notifRemote", true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().putBoolean("notifRemote", on).apply()
    }

    fun show(context: Context, deviceName: String) {
        if (!enabled(context)) return hide(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Controle na notificação", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, PhoneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_n_tv)
            .setContentTitle("Controle TV")
            .setContentText(deviceName)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(null)
        BUTTONS.forEachIndexed { i, (key, label, icon) ->
            val intent = Intent(context, KeyReceiver::class.java).setAction(ACTION).putExtra("key", key)
            val pending = PendingIntent.getBroadcast(context, 100 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, icon), label, pending).build())
        }
        // Media layout: all five buttons in a row, three of them visible when collapsed
        builder.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 2, 3))
        manager.notify(ID, builder.build())
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    /** Sends a key to the TV the phone last connected to. */
    class KeyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val key = intent.getStringExtra("key") ?: return
            val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
            val host = prefs.getString("host", null) ?: return
            val token = prefs.getString("token", null) ?: return
            val pending = goAsync()
            Thread {
                runCatching {
                    val conn = URL("http://$host:8765/api/key").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 5_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-Token", token)
                    conn.outputStream.use { it.write(JSONObject().put("key", key).toString().toByteArray()) }
                    conn.responseCode
                    conn.disconnect()
                }
                pending.finish()
            }.start()
        }
    }
}
