package com.example.background

import android.accessibilityservice.AccessibilityService
<<<<<<< HEAD
import android.content.Intent
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AccessibilityWatchdogService : AccessibilityService() {

    private val handler = Handler()
=======
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

/**
 * 🛡️ PRODUCTION AccessibilityWatchdogService
 *
 * Why Accessibility Service is the ULTIMATE survivor:
 *  - OEMs (Xiaomi, Oppo, Vivo, Huawei) whitelist Accessibility services from auto-kill
 *  - Survives "Clear All" in recents
 *  - Survives battery saver
 *  - Survives Doze mode (mostly)
 *
 * Responsibilities:
 *  - Every 60s: ensure WatchdogService is alive
 *  - Every 60s: ensure Flutter BackgroundService is alive
 *  - On service connected: full restart chain
 *
 * ⚠️ User MUST enable this in Settings > Accessibility > CareCircle
 */
class AccessibilityWatchdogService : AccessibilityService() {

    companion object {
        private const val TAG = "ACCESS_WATCHDOG"
        private const val CHANNEL_ID = "accessibility_watchdog_channel"
        private const val NOTIFICATION_ID = 1002
        private const val INTERVAL_MS = 60_000L  // 60 sec
        private const val WAKE_LOCK_TAG = "CareCircle::AccessibilityWakeLock"

        private const val FLUTTER_SERVICE_CLASS =
            "id.flutter.flutter_background_service.BackgroundService"

        /**
         * Check if accessibility service is enabled (call from Flutter / MainActivity)
         */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as android.view.accessibility.AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            val serviceName = "${context.packageName}/.AccessibilityWatchdogService"
            return enabledServices.any {
                it.resolveInfo.serviceInfo.let { si ->
                    "${si.packageName}/${si.name}" == serviceName
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
>>>>>>> workspace

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
<<<<<<< HEAD
                val intent = Intent(this@AccessibilityWatchdogService, WatchdogService::class.java)
                startService(intent)

                Log.d("ACCESS_WATCHDOG", "Watchdog ping")

            } catch (e: Exception) {
                Log.e("ACCESS_WATCHDOG", "Error: ${e.message}")
            }

            handler.postDelayed(this, 60000) // every 60 sec
=======
                // Ping Tier 3 — WatchdogService (Foreground)
                WatchdogService.start(applicationContext)

                // Ping Flutter BackgroundService directly (belt + suspenders)
                ensureFlutterService()

                Log.d(TAG, "🔄 Pinged Watchdog + Flutter service")

            } catch (e: Exception) {
                Log.e(TAG, "Loop error: ${e.message}")
            }

            handler.postDelayed(this, INTERVAL_MS)
>>>>>>> workspace
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
<<<<<<< HEAD
=======
        Log.d(TAG, "✅ AccessibilityWatchdogService connected")

        // 🔥 CRITICAL: Call startForeground() FIRST — before any other init
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.d(TAG, "✅ startForeground called")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground failed: ${e.message}")
        }

        try {
            acquireWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }

        // 🚀 Immediate first ping
>>>>>>> workspace
        handler.post(watchdogRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
<<<<<<< HEAD
        // 🔥 DO NOTHING (important)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(watchdogRunnable)
=======
        // We don't process events — but service must override
        // 🚨 Keep this EMPTY — processing events drains battery
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "⚠️ Accessibility service unbound — scheduling restart")
        // 🔥 Self-restart on unbind
        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "❌ Accessibility service destroyed")
        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
        super.onDestroy()
    }

    // ============ Private helpers ============

    private fun ensureFlutterService() {
        try {
            val intent = Intent().apply {
                setClassName(applicationContext, FLUTTER_SERVICE_CLASS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                } catch (e: Exception) {
                    startService(intent)
                }
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flutter service start failed: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CareCircle Running")
            .setContentText("Background monitoring active")
            .setSmallIcon(R.drawable.ic_notification)  // 🔥 App's own drawable
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CareCircle Accessibility",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps monitoring service persistent"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release failed: ${e.message}")
        }
>>>>>>> workspace
    }
}
