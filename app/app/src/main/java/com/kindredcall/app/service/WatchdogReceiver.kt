package com.kindredcall.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Watchdog triggered by action: $action")
        
        // Simply attempt to start the service. 
        // If it's already running and correctly implemented, it will just stay alive.
        SignalingService.start(context)
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
        const val ACTION_WATCHDOG_TIMER = "com.kindredcall.app.ACTION_WATCHDOG_TIMER"
    }
}
