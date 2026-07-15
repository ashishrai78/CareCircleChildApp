package com.example.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * RestartReceiver — catches system events to ensure services are alive
 *
 * Triggers on:
 *  - USER_PRESENT (phone unlocked)
 *  - CONNECTIVITY_CHANGE (network restored)
 *  - Custom RESTART_WATCHDOG broadcast (from WatchdogService.onDestroy)
 */
class RestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RESTART_RECEIVER"
        private const val FLUTTER_SERVICE_CLASS =
            "id.flutter.flutter_background_service.BackgroundService"
        const val ACTION_RESTART_WATCHDOG = "com.example.background.RESTART_WATCHDOG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "📡 Received: ${intent.action}")

        when (intent.action) {
            ACTION_RESTART_WATCHDOG,
            Intent.ACTION_USER_PRESENT,
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                WatchdogService.start(context)
                try {
                    val flutterIntent = Intent().apply {
                        setClassName(context, FLUTTER_SERVICE_CLASS)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        try {
                            context.startForegroundService(flutterIntent)
                        } catch (e: Exception) {
                            context.startService(flutterIntent)
                        }
                    } else {
                        context.startService(flutterIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Flutter restart failed: ${e.message}")
                }
            }
        }
    }
}
