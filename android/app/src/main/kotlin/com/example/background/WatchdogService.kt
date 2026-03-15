package com.example.background

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.util.Log

class WatchdogService : Service() {

    private val handler = Handler()
    private val interval: Long = 30000 // 30 sec

    private val runnable = object : Runnable {
        override fun run() {

            try {
                val intent = Intent().apply {
                    setClassName(
                        applicationContext,
                        "id.flutter.flutter_background_service.BackgroundService"
                    )
                }

                startService(intent)

                Log.d("WATCHDOG", "Ensured Flutter service running")

            } catch (e: Exception) {
                Log.e("WATCHDOG", "Error: ${e.message}")
            }

            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(runnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(runnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
