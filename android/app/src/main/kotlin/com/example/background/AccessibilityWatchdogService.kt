package com.example.background

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AccessibilityWatchdogService : AccessibilityService() {

    private val handler = Handler()

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                val intent = Intent(this@AccessibilityWatchdogService, WatchdogService::class.java)
                startService(intent)

                Log.d("ACCESS_WATCHDOG", "Watchdog ping")

            } catch (e: Exception) {
                Log.e("ACCESS_WATCHDOG", "Error: ${e.message}")
            }

            handler.postDelayed(this, 60000) // every 60 sec
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(watchdogRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 🔥 DO NOTHING (important)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(watchdogRunnable)
    }
}
