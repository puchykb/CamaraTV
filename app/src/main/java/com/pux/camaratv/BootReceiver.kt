package com.pux.camaratv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts motion alerts when the TV box boots, without having to open the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && AlertSettings.mode(context) != AlertSettings.Mode.OFF) {
            MotionService.start(context)
        }
    }
}
