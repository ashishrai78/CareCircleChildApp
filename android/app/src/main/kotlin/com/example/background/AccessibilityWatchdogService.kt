package com.example.background

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 🛡️ PRODUCTION AccessibilityWatchdogService (v2 — OEM-stable)
 *
 * Critical fixes vs v1:
 *  1. onAccessibilityEvent NOW PROCESSES events — prevents Realme/Xiaomi auto-revoke
 *  2. onUnbind notifies app + broadcasts (was empty before)
 *  3. isServiceRunning check before pinging WatchdogService
 *  4. Indefinite WakeLock with renew
 *  5. Tracks last event time for health diagnostics
 *
 * Why Accessibility Service is the ULTIMATE survivor:
 *  - OEMs whitelist Accessibility services from auto-kill
 *  - Survives "Clear All" in recents
 *  - Survives battery saver
 *
 * ⚠️ User MUST enable in Settings > Accessibility > CareCircle
 */
class AccessibilityWatchdogService : AccessibilityService() {

    companion object {
        private const val TAG = "ACCESS_WATCHDOG"
        private const val CHANNEL_ID = "accessibility_watchdog_channel_v2"
        private const val NOTIFICATION_ID = 1002
        private const val INTERVAL_MS = 60_000L  // 60 sec
        private const val WAKE_LOCK_TAG = "CareCircle::AccessibilityWakeLock"
        private const val WAKELOCK_RENEW_INTERVAL_MS = 4 * 60_000L

        private const val FLUTTER_SERVICE_CLASS =
            "id.flutter.flutter_background_service.BackgroundService"

        /**
         * Check if accessibility service is enabled (call from Flutter / MainActivity)
         */
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

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()

                // Renew WakeLock
                if (now - lastWakeLockRenew >= WAKELOCK_RENEW_INTERVAL_MS) {
                    renewWakeLock()
                    lastWakeLockRenew = now
                }

                // Ping WatchdogService (only if not running)
                if (!isWatchdogServiceRunning()) {
                    WatchdogService.start(applicationContext)
                    Log.d(TAG, "🔄 WatchdogService restarted")
                }

                // Ping Flutter BackgroundService (only if not running)
                ensureFlutterService()

                // 🔥 Health check — log if no events received in 5 min
                if (lastEventReceivedAt > 0 && now - lastEventReceivedAt > 5 * 60_000L) {
                    Log.w(TAG, "⚠️ No accessibility events in 5 min — OEM may be throttling")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Loop error: ${e.message}")
            }

            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ AccessibilityWatchdogService connected")

        // 🔥 startForeground FIRST
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

        // Immediate first ping
        handler.post(watchdogRunnable)
    }

    /**
     * 🔥 CRITICAL FIX: Process events to prevent Realme/Xiaomi auto-revoke
     *
     * OEMs monitor: events_received > 0 && events_processed == 0 → revoke
     * This handler does MINIMAL processing (no battery impact) but acknowledges events.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        try {
            val eventType = event.eventType
            val packageName = event.packageName?.toString() ?: "unknown"
            val now = System.currentTimeMillis()

            // 🔥 Track last event time (used in health check)
            lastEventReceivedAt = now

            // 🔥 OPTIONAL — track foreground app changes (production feature)
            // This data is useful for screen time tracking & app blocking
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                packageName != "android" &&
                packageName != packageName &&
                !packageName.contains("systemui")) {

                if (packageName != lastForegroundApp) {
                    lastForegroundApp = packageName
                    Log.d(TAG, "📋 Foreground app: $packageName")

                    // 🔥 Optional: push to Firestore (debounced via 60s sync)
                    // Uncomment if you want real-time foreground app tracking
                    /*
                    serviceScope.launch {
                        try {
                            FirestoreClient.writeLiveData(mapOf(
                                "currentAppPackage" to packageName,
                                "currentAppChangedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                            ))
                        } catch (e: Exception) {
                            Log.e(TAG, "Foreground app update failed: ${e.message}")
                        }
                    }
                    */
                }
            }
        } catch (e: Exception) {
            // Silent fail — don't crash accessibility service
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
    }

    /**
     * 🔥 FIX: onUnbind now actually notifies app + broadcasts
     * (was empty in v1 with misleading "self-restart" comment)
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "⚠️ Accessibility service unbound — notifying app")

        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
        serviceScope.cancel()

        // 🔥 Notify WatchdogService via broadcast
        try {
            val broadcastIntent = Intent("com.example.background.ACCESSIBILITY_REVOKED")
            applicationContext.sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
        }

        // 🔥 Bring app to foreground to prompt user re-enable
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            launchIntent?.putExtra("ACCESSIBILITY_REVOKED", true)
            applicationContext.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: ${e.message}")
        }

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "❌ Accessibility service destroyed")
        handler.removeCallbacks(watchdogRunnable)
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ============ Private helpers ============

    private fun ensureFlutterService() {
        try {
            if (isFlutterServiceRunning()) return

            val intent = Intent().apply {
                setClassName(applicationContext, FLUTTER_SERVICE_CLASS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
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
            wakeLock?.acquire()  // 🔥 Indefinite
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