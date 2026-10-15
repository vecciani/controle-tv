package com.controletv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the controller and opens our screen as soon as the Fire TV boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ControlService.start(context)
        MainActivity.open(context)
    }
}
