package com.example.background

import android.app.AlarmManager
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 🛡️ PRODUCTION WatchdogService (v4 — stable on OEM ROMs)
 *
 * Critical fixes vs v3:
 *  1. Uses `specialUse` FGS type — no 6-hour kill limit (Android 14+)
 *  2. Indefinite WakeLock with auto-renew (no 10-min expiry → Doze kill)
 *  3. `isServiceRunning()` check before pinging Flutter service (no repeated startForegroundService)
 *  4. Heavy sync operations on IO dispatcher (no main-thread ANR)
 *  5. `setAndAllowWhileIdle` for restart alarm (wakes in Doze)
 *  6. CoroutineScope cancelled cleanly in onDestroy
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WATCHDOG"
        private const val CHANNEL_ID = "watchdog_channel_v4"
        private const val NOTIFICATION_ID = 1001
        private const val FLUTTER_SERVICE_CLASS = "id.flutter.flutter_background_service.BackgroundService"

        private const val PING_INTERVAL_MS = 30_000L              // 30s — service ping
        private const val WAKELOCK_RENEW_INTERVAL_MS = 4 * 60_000L // 4 min — renew before 5-min safe limit
        private const val ACCESSIBILITY_PING_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L          // 60s
        private const val FULL_SYNC_INTERVAL_MS = 5 * 60_000L      // 5 min
        private const val APPS_SYNC_INTERVAL_MS = 6 * 60 * 60_000L // 6 hours
        private const val SYNC_REQUEST_CHECK_MS = 30_000L          // 30s

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

        /**
         * Check if WatchdogService is currently running (for MainActivity.onResume check)
         */
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Int.MAX_VALUE)
            return services.any { it.service.className == "com.example.background.WatchdogService" }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastFlutterPing = 0L
    private var lastAccessibilityPing = 0L
    private var lastHeartbeat = 0L
    private var lastFullSync = 0L
    private var lastAppsSync = 0L
    private var lastSyncRequestCheck = 0L
    private var lastWakeLockRenew = 0L

    private lateinit var dataCollector: NativeDataCollector

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ WatchdogService created")

        // 🔥 CRITICAL: startForeground() within 5 sec of startForegroundService()
        try {
            createNotificationChannel()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "✅ startForeground called (specialUse)")
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

        // 🔥 Indefinite WakeLock (no 10-min expiry)
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
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // 🔥 FIX: setAndAllowWhileIdle wakes device in Doze mode
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            } catch (se: SecurityException) {
                // Android 12+ may require SCHEDULE_EXACT_ALARM permission
                Log.w(TAG, "setAndAllowWhileIdle failed, using regular set: ${se.message}")
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restart schedule failed: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(TAG, "❌ WatchdogService destroyed")
        handler.removeCallbacks(watchdogRunnable)
        serviceScope.cancel()  // 🔥 Cancel all coroutines
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============ Watchdog loop ============
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()

                // 🔥 WakeLock renew (indefinite lock may get released by system)
                if (now - lastWakeLockRenew >= WAKELOCK_RENEW_INTERVAL_MS) {
                    renewWakeLock()
                    lastWakeLockRenew = now
                }

                // 1. Ping Flutter service (30s) — with running check
                if (now - lastFlutterPing >= PING_INTERVAL_MS) {
                    ensureFlutterServiceRunning()
                    lastFlutterPing = now
                }

                // 2. Check accessibility (60s)
                if (now - lastAccessibilityPing >= ACCESSIBILITY_PING_INTERVAL_MS) {
                    ensureAccessibilityServiceRunning()
                    lastAccessibilityPing = now
                }

                // 3. 📊 DATA COLLECTION — on IO dispatcher (no ANR)
                if (::dataCollector.isInitialized) {
                    if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                        serviceScope.launch {
                            try { dataCollector.sendHeartbeat() }
                            catch (e: Exception) { Log.e(TAG, "Heartbeat: ${e.message}") }
                        }
                        lastHeartbeat = now
                    }

                    if (now - lastSyncRequestCheck >= SYNC_REQUEST_CHECK_MS) {
                        serviceScope.launch {
                            try { checkSyncRequest() }
                            catch (e: Exception) { Log.e(TAG, "SyncReq: ${e.message}") }
                        }
                        lastSyncRequestCheck = now
                    }

                    if (now - lastFullSync >= FULL_SYNC_INTERVAL_MS) {
                        serviceScope.launch {
                            try { dataCollector.collectAndSyncAll() }
                            catch (e: Exception) { Log.e(TAG, "FullSync: ${e.message}") }
                        }
                        lastFullSync = now
                    }

                    if (now - lastAppsSync >= APPS_SYNC_INTERVAL_MS) {
                        serviceScope.launch {
                            try { dataCollector.syncInstalledApps() }
                            catch (e: Exception) { Log.e(TAG, "AppsSync: ${e.message}") }
                        }
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
    private suspend fun checkSyncRequest() {
        try {
            FirestoreClient.getChildControl()?.let { data ->
                val syncRequested = data["sync_request"] as? Boolean ?: false
                if (syncRequested) {
                    Log.d(TAG, "📡 Sync requested by parent — collecting all data")
                    dataCollector.collectAndSyncAll()
                    FirestoreClient.updateSyncComplete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkSyncRequest failed: ${e.message}")
        }
    }

    // ============ Private helpers ============

    /**
     * 🔥 FIX: Only start Flutter service if NOT already running
     * Repeated startForegroundService() calls trigger OEM battery optimization
     */
    private fun ensureFlutterServiceRunning() {
        try {
            if (isFlutterServiceRunning()) {
                // Already running — skip
                return
            }

            val intent = Intent().apply {
                setClassName(applicationContext, FLUTTER_SERVICE_CLASS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                    Log.d(TAG, "🔄 Flutter service started (was not running)")
                } catch (e: Exception) {
                    // Android 12+ may throw ForegroundServiceStartNotAllowedException
                    Log.w(TAG, "Foreground start failed: ${e.message}")
                    try {
                        startService(intent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "All Flutter start attempts failed: ${e2.message}")
                    }
                }
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Flutter service: ${e.message}")
        }
    }

    private fun isFlutterServiceRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Int.MAX_VALUE)
            services.any { it.service.className == FLUTTER_SERVICE_CLASS }
        } catch (e: Exception) {
            false  // On error, assume not running (will try to start)
        }
    }

    private fun ensureAccessibilityServiceRunning() {
        try {
            val enabledServices = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val serviceName = "$packageName/.AccessibilityWatchdogService"
            if (!enabledServices.contains(serviceName)) {
                Log.w(TAG, "⚠️ Accessibility service DISABLED — notifying app")

                // 🔥 Notify app to show re-enable prompt
                val broadcastIntent = Intent("com.example.background.ACCESSIBILITY_REVOKED")
                sendBroadcast(broadcastIntent)

                // Also try to bring app to foreground
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    launchIntent?.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    launchIntent?.putExtra("ACCESSIBILITY_REVOKED", true)
                    startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch app: ${e.message}")
                }
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

    // ============ WakeLock management ============

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()  // 🔥 Indefinite — no timeout
        Log.d(TAG, "✅ WakeLock acquired (indefinite)")
    }

    /**
     * 🔥 Renew WakeLock — defensive in case system released it
     */
    private fun renewWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (!wl.isHeld) {
                    wl.acquire()
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

// Import alias for coroutine launch (keep at file level after class)
//private fun CoroutineScope.launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
//    kotlinx.coroutines.launch(this, block = block)