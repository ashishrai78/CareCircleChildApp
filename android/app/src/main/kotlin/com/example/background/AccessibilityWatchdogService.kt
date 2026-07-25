package com.example.background

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 🛡️ PRODUCTION AccessibilityWatchdogService (v3 — REALME STABLE)
 */
class AccessibilityWatchdogService : AccessibilityService() {

    companion object {
        private const val TAG = "ACCESS_WATCHDOG"
        private const val CHANNEL_ID = "accessibility_watchdog_channel_v3"
        private const val NOTIFICATION_ID = 1002
        private const val INTERVAL_MS = 90_000L
        private const val WAKE_LOCK_TAG = "CareCircle::AccessibilityWakeLock"
        private const val WAKELOCK_RENEW_INTERVAL_MS = 4 * 60_000L

        private const val PREFS_NAME = "carecircle_prefs"
        private const val KEY_LAST_FLUTTER_START = "last_flutter_start_attempt"
        private const val FLUTTER_START_THROTTLE_MS = 5 * 60_000L

        private const val KEY_LAST_UNBIND_NOTIFY = "last_unbind_notify"
        private const val UNBIND_NOTIFY_THROTTLE_MS = 10 * 60_000L

        private const val FLUTTER_SERVICE_CLASS =
            "id.flutter.flutter_background_service.BackgroundService"

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastEventReceivedAt = 0L
    private var lastForegroundApp: String? = null
    private var lastWakeLockRenew = 0L
    private var eventCount = 0L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()

                if (now - lastWakeLockRenew >= WAKELOCK_RENEW_INTERVAL_MS) {
                    renewWakeLock()
                    lastWakeLockRenew = now
                }

                if (!isWatchdogServiceRunning()) {
                    WatchdogService.start(applicationContext)
                    Log.d(TAG, "🔄 WatchdogService restarted")
                }

                ensureFlutterService()

                if (lastEventReceivedAt > 0 && now - lastEventReceivedAt > 5 * 60_000L) {
                    Log.w(TAG, "⚠️ No accessibility events in 5 min — OEM may be throttling (events processed: $eventCount)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Loop error: ${e.message}")
            }

            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ AccessibilityWatchdogService connected (v3)")

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

        handler.post(watchdogRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        try {
            val eventType = event.eventType
            val packageName = event.packageName?.toString() ?: "unknown"
            val now = System.currentTimeMillis()

            lastEventReceivedAt = now
            eventCount++

            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                packageName != "android" &&
                !packageName.contains("systemui") &&
                packageName != this.packageName) {

                if (packageName != lastForegroundApp) {
                    lastForegroundApp = packageName
                    Log.d(TAG, "📋 Foreground app: $packageName (events: $eventCount)")
                }
            }
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "⚠️ Accessibility service unbound — showing notification (NO auto-launch)")

        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
        serviceScope.cancel()

        try {
            val broadcastIntent = Intent("com.example.background.ACCESSIBILITY_REVOKED")
            applicationContext.sendBroadcast(broadcastIntent)
            Log.d(TAG, "📡 Broadcasted ACCESSIBILITY_REVOKED")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
        }

        showAccessibilityUnbindNotification()

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "❌ Accessibility service destroyed")
        handler.removeCallbacks(watchdogRunnable)
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureFlutterService() {
        try {
            if (isFlutterServiceRunning()) return

            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastStart = prefs.getLong(KEY_LAST_FLUTTER_START, 0L)

            if (now - lastStart < FLUTTER_START_THROTTLE_MS) {
                Log.d(TAG, "Skipping Flutter service start (5-min throttle)")
                return
            }
            prefs.edit().putLong(KEY_LAST_FLUTTER_START, now).apply()

            val intent = Intent().apply {
                setClassName(applicationContext, FLUTTER_SERVICE_CLASS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                    Log.d(TAG, "🔄 Flutter service start requested")
                } catch (e: Exception) {
                    try { startService(intent) }
                    catch (e2: Exception) { Log.e(TAG, "Flutter start: ${e2.message}") }
                }
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flutter service start failed: ${e.message}")
        }
    }

    private fun showAccessibilityUnbindNotification() {
        try {
            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastNotify = prefs.getLong(KEY_LAST_UNBIND_NOTIFY, 0L)

            if (now - lastNotify < UNBIND_NOTIFY_THROTTLE_MS) {
                Log.d(TAG, "Skipping unbind notification (10-min throttle)")
                return
            }
            prefs.edit().putLong(KEY_LAST_UNBIND_NOTIFY, now).apply()

            val notifChannelId = "accessibility_unbind_alert"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    notifChannelId,
                    "Accessibility Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when accessibility is disabled"
                    enableVibration(true)
                    enableLights(true)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 2002, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, notifChannelId)
                .setContentTitle("⚠️ CareCircle Protection Paused")
                .setContentText("Accessibility disabled — tap to re-enable monitoring")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(2002, notification)

            Log.d(TAG, "🔔 Accessibility unbind notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification: ${e.message}")
        }
    }

    private fun isFlutterServiceRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Int.MAX_VALUE)
            services.any { it.service.className == FLUTTER_SERVICE_CLASS }
        } catch (e: Exception) {
            false
        }
    }

    private fun isWatchdogServiceRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Int.MAX_VALUE)
            services.any { it.service.className == "com.example.background.WatchdogService" }
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CareCircle Running")
            .setContentText("Background monitoring active")
            .setSmallIcon(R.drawable.ic_notification)
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
            wakeLock?.acquire()
            Log.d(TAG, "✅ WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "🔄 WakeLock re-acquired")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock renew failed: ${e.message}")
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
    }
}