package com.example.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 🛡️ PRODUCTION WatchdogService (v3 — crash-fixed)
 *
 * Critical fixes:
 *  1. startForeground() called IMMEDIATELY in onCreate() — before any other init
 *  2. Uses app's own R.drawable.ic_notification (not system drawable)
 *  3. Removed specialUse FGS type (only valid on Android 14+)
 *  4. All init wrapped in try/catch to prevent onCreate from failing
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WATCHDOG"
        private const val CHANNEL_ID = "watchdog_channel_v2"
        private const val NOTIFICATION_ID = 1001
        private const val FLUTTER_SERVICE_CLASS = "id.flutter.flutter_background_service.BackgroundService"
        private const val PING_INTERVAL_MS = 30_000L
        private const val ACCESSIBILITY_PING_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L           // 60s
        private const val FULL_SYNC_INTERVAL_MS = 5 * 60_000L       // 5 min
        private const val APPS_SYNC_INTERVAL_MS = 6 * 60 * 60_000L  // 6 hours
        private const val SYNC_REQUEST_CHECK_MS = 30_000L           // 30s
        private const val WAKE_LOCK_TAG = "CareCircle::WatchdogWakeLock"

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "WatchdogService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WatchdogService: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastFlutterPing = 0L
    private var lastAccessibilityPing = 0L
    private var lastHeartbeat = 0L
    private var lastFullSync = 0L
    private var lastAppsSync = 0L
    private var lastSyncRequestCheck = 0L

    private lateinit var dataCollector: NativeDataCollector

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ WatchdogService created")

        // 🔥 CRITICAL: Call startForeground() IMMEDIATELY — within 5 sec of startForegroundService()
        try {
            createNotificationChannel()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "✅ startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground FAILED in onCreate: ${e.message}")
        }

        // Initialize native Firebase + data collector
        try {
            FirestoreClient.init(this)
            dataCollector = NativeDataCollector(this)
            Log.d(TAG, "✅ Data collector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Data collector init failed: ${e.message}")
        }

        try {
            acquireWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }

        // Start watchdog loop
        handler.post(watchdogRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchdogService onStartCommand")
        // Already called startForeground in onCreate, no need to repeat
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "⚠️ Task removed — scheduling restart")
        try {
            val restartIntent = Intent(applicationContext, WatchdogService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restart schedule failed: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(TAG, "❌ WatchdogService destroyed")
        handler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============ Watchdog loop ============
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()

                // 1. Ping Flutter service (30s)
                if (now - lastFlutterPing >= PING_INTERVAL_MS) {
                    ensureFlutterServiceRunning()
                    lastFlutterPing = now
                }

                // 2. Check accessibility (60s)
                if (now - lastAccessibilityPing >= ACCESSIBILITY_PING_INTERVAL_MS) {
                    ensureAccessibilityServiceRunning()
                    lastAccessibilityPing = now
                }

                // 3. 📊 DATA COLLECTION — heartbeats + sync (NEW!)
                if (::dataCollector.isInitialized) {
                    // Heartbeat every 60s
                    if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                        dataCollector.sendHeartbeat()
                        lastHeartbeat = now
                    }

                    // Check sync_request every 30s
                    if (now - lastSyncRequestCheck >= SYNC_REQUEST_CHECK_MS) {
                        checkSyncRequest()
                        lastSyncRequestCheck = now
                    }

                    // Full sync every 5 min
                    if (now - lastFullSync >= FULL_SYNC_INTERVAL_MS) {
                        dataCollector.collectAndSyncAll()
                        lastFullSync = now
                    }

                    // Installed apps every 6 hours
                    if (now - lastAppsSync >= APPS_SYNC_INTERVAL_MS) {
                        dataCollector.syncInstalledApps()
                        lastAppsSync = now
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Watchdog loop error: ${e.message}")
            }

            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    /**
     * Check if parent requested sync (sync_request = true in child_control)
     */
    private fun checkSyncRequest() {
        try {
            FirestoreClient.getChildControl { data ->
                if (data != null) {
                    val syncRequested = data["sync_request"] as? Boolean ?: false
                    if (syncRequested) {
                        Log.d(TAG, "📡 Sync requested by parent — collecting all data")
                        dataCollector.collectAndSyncAll()
                        FirestoreClient.updateSyncComplete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkSyncRequest failed: ${e.message}")
        }
    }

    // ============ Private helpers ============

    private fun ensureFlutterServiceRunning() {
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
            Log.d(TAG, "🔄 Flutter service pinged")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Flutter service: ${e.message}")
        }
    }

    private fun ensureAccessibilityServiceRunning() {
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val enabledServices = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val serviceName = "$packageName/.AccessibilityWatchdogService"
            if (!enabledServices.contains(serviceName)) {
                Log.w(TAG, "⚠️ Accessibility service not enabled — user must enable manually")
            } else {
                Log.d(TAG, "✅ Accessibility service enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Accessibility check failed: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CareCircle Protection Active")
            .setContentText("Monitoring is running in background")
            .setSmallIcon(R.drawable.ic_notification)  // 🔥 App's own drawable — guaranteed to exist
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CareCircle Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps monitoring service alive"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(10 * 60 * 1000L)
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
