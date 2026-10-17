package com.controletv

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Lets the home's music widget see and control what's playing (Apple Music, Spotify…).
 * Android only shares media sessions with an enabled notification listener; this one does
 * nothing else. It's enabled through ADB (see [enable]).
 */
class MediaListener : NotificationListenerService() {
    companion object {
        private const val COMPONENT = "com.controletv/com.controletv.MediaListener"

        fun enabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(COMPONENT) == true

        fun enable() {
            Adb.shell("cmd notification allow_listener $COMPONENT")
        }

        /** The session that's playing now, or the most recent one. */
        fun current(context: Context): MediaController? {
            if (!enabled(context)) return null
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = try {
                manager.getActiveSessions(ComponentName(context, MediaListener::class.java))
            } catch (e: SecurityException) {
                return null
            }
            return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        }
    }
}
