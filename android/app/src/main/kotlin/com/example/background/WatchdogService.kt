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
 * 🛡️ PRODUCTION WatchdogService (v5 — REALME STABLE)
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WATCHDOG"
        private const val CHANNEL_ID = "watchdog_channel_v5"
        private const val NOTIFICATION_ID = 1001
        private const val FLUTTER_SERVICE_CLASS = "id.flutter.flutter_background_service.BackgroundService"

        private const val PING_INTERVAL_MS = 60_000L
        private const val WAKELOCK_RENEW_INTERVAL_MS = 4 * 60_000L
        private const val ACCESSIBILITY_PING_INTERVAL_MS = 90_000L
        private const val HEARTBEAT_INTERVAL_MS = 3 * 60_000L
        private const val FULL_SYNC_INTERVAL_MS = 10 * 60_000L
        private const val APPS_SYNC_INTERVAL_MS = 6 * 60 * 60_000L
        private const val SYNC_REQUEST_CHECK_MS = 2 * 60_000L

        private const val WAKE_LOCK_TAG = "CareCircle::WatchdogWakeLock"

        private const val ACCESSIBILITY_CONFIRMATION_CHECKS = 3
        private const val ACCESSIBILITY_RECHECK_INTERVAL_MS = 30_000L

        private const val PREFS_NAME = "carecircle_prefs"
        private const val KEY_LAST_ACCESSIBILITY_NOTIFY = "last_accessibility_notify"
        private const val ACCESSIBILITY_NOTIFY_THROTTLE_MS = 5 * 60_000L

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

    private var accessibilityDisabledCheckCount = 0

    private lateinit var dataCollector: NativeDataCollector

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ WatchdogService created (v5)")

        try {
            createNotificationChannel()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "✅ startForeground called (specialUse)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground FAILED in onCreate: ${e.message}")
        }

        try {
            val isAdminEnabled = CareCircleDeviceAdminReceiver.isEnabled(this)
            Log.d(TAG, "🔒 Device Admin status: ${if (isAdminEnabled) "ENABLED" else "DISABLED"}")
        } catch (e: Exception) {
            Log.e(TAG, "Device Admin check failed: ${e.message}")
        }

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
                this, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            } catch (se: SecurityException) {
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
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()

                if (now - lastWakeLockRenew >= WAKELOCK_RENEW_INTERVAL_MS) {
                    renewWakeLock()
                    lastWakeLockRenew = now
                }

                if (now - lastFlutterPing >= PING_INTERVAL_MS) {
                    ensureFlutterServiceRunning()
                    lastFlutterPing = now
                }

                if (now - lastAccessibilityPing >= ACCESSIBILITY_PING_INTERVAL_MS) {
                    ensureAccessibilityServiceRunning()
                    lastAccessibilityPing = now
                }

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

    private fun ensureFlutterServiceRunning() {
        try {
            if (isFlutterServiceRunning()) return

            val intent = Intent().apply {
                setClassName(applicationContext, FLUTTER_SERVICE_CLASS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                    Log.d(TAG, "🔄 Flutter service started (was not running)")
                } catch (e: Exception) {
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
            false
        }
    }

    private fun ensureAccessibilityServiceRunning() {
        try {
            val isActuallyEnabled = checkAccessibilityEnabledReliably()

            if (isActuallyEnabled) {
                if (accessibilityDisabledCheckCount > 0) {
                    Log.d(TAG, "✅ Accessibility re-enabled (false alarm cleared)")
                }
                accessibilityDisabledCheckCount = 0
                return
            }

            accessibilityDisabledCheckCount++
            Log.w(TAG, "⚠️ Accessibility appears disabled (check ${accessibilityDisabledCheckCount}/$ACCESSIBILITY_CONFIRMATION_CHECKS)")

            if (accessibilityDisabledCheckCount < ACCESSIBILITY_CONFIRMATION_CHECKS) {
                handler.postDelayed({
                    ensureAccessibilityServiceRunning()
                }, ACCESSIBILITY_RECHECK_INTERVAL_MS)
                return
            }

            accessibilityDisabledCheckCount = 0
            handleAccessibilityDisabledConfirmed()

        } catch (e: Exception) {
            Log.e(TAG, "Accessibility check failed: ${e.message}")
        }
    }

    private fun checkAccessibilityEnabledReliably(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager

            if (am.isEnabled) {
                val enabledServices = am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
                )
                val serviceName = "$packageName/.AccessibilityWatchdogService"

                val isActuallyEnabled = enabledServices.any {
                    it.resolveInfo.serviceInfo.let { si ->
                        "${si.packageName}/${si.name}" == serviceName
                    }
                }

                if (isActuallyEnabled) return true
            }

            try {
                val enabledServicesStr = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val serviceName = "$packageName/.AccessibilityWatchdogService"
                enabledServicesStr.contains(serviceName)
            } catch (e: Exception) {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reliable check failed: ${e.message}")
            true
        }
    }

    private fun handleAccessibilityDisabledConfirmed() {
        Log.w(TAG, "🚨 Accessibility CONFIRMED disabled — showing notification")

        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotifyTime = prefs.getLong(KEY_LAST_ACCESSIBILITY_NOTIFY, 0L)

        if (now - lastNotifyTime < ACCESSIBILITY_NOTIFY_THROTTLE_MS) {
            Log.d(TAG, "Skipping notification (5-min throttle active)")
            return
        }

        prefs.edit().putLong(KEY_LAST_ACCESSIBILITY_NOTIFY, now).apply()

        try {
            val broadcastIntent = Intent("com.example.background.ACCESSIBILITY_REVOKED")
            sendBroadcast(broadcastIntent)
            Log.d(TAG, "📡 Broadcasted ACCESSIBILITY_REVOKED to Flutter")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
        }

        showAccessibilityDisabledNotification()
    }

    private fun showAccessibilityDisabledNotification() {
        try {
            val notifChannelId = "accessibility_disabled_alert"

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
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, notifChannelId)
                .setContentTitle("⚠️ CareCircle Protection Paused")
                .setContentText("Tap to re-enable monitoring")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(2001, notification)

            Log.d(TAG, "🔔 Accessibility disabled notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification: ${e.message}")
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

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
        Log.d(TAG, "✅ WakeLock acquired (indefinite)")
    }

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