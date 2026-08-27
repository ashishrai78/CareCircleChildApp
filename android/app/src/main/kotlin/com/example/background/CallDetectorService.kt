package com.example.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 📞 CallDetectorService — Detects incoming/outgoing/missed calls in real-time
 *
 * ANDROID 10+ LIMITATION:
 *  - Cannot read past call logs (CallLog.Calls blocked for non-default dialer)
 *  - CAN detect real-time call state changes (RINGING, OFFHOOK, IDLE)
 *  - CAN log call type + duration + timestamp
 *  - CANNOT get phone number directly (needs Accessibility for that)
 *
 * Approach (same as AirDroid Kids):
 *  - PhoneStateListener detects call events
 *  - Logs to Firestore: timestamp, type, duration, phone number (if available)
 *
 * Phone number workaround:
 *  - On Android 10+, only "Unknown" is logged
 *  - For phone number, use Accessibility to read dialer screen (future)
 */
class CallDetectorService : Service() {

    companion object {
        private const val TAG = "CallDetector"
        private var telephonyManager: TelephonyManager? = null
        private var phoneStateListener: CallStateListener? = null
        private var isRunning = false

        fun start(context: Context) {
            // 🔥 Check: Did user enable call monitoring?
            val prefs = context.getSharedPreferences("carecircle_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("call_monitoring_enabled", false)

            if (!isEnabled) {
                Log.d(TAG, "Call monitoring disabled by user — not starting")
                return
            }

            if (isRunning) {
                Log.d(TAG, "CallDetectorService already running")
                return
            }
            val intent = Intent(context, CallDetectorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CallDetectorService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ CallDetectorService created")

        // 🔥 अत्यंत आवश्यक: 5 सेकंड के भीतर startForeground() कॉल करें, अन्यथा Android ऐप को किल कर देगा
        try {
            val notification = android.app.Notification.Builder(this, "call_detector_channel")
                .setContentTitle("CareCircle Protection")
                .setContentText("Monitoring active")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(android.app.Notification.PRIORITY_LOW)
                .build()

            // सुनिश्चित करें कि एंड्रॉइड 8+ के लिए चैनल मौजूद है
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "call_detector_channel",
                    "Call Detection",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background call monitoring"
                    setShowBadge(false)
                }
                val manager = getSystemService(android.app.NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }

            startForeground(1003, notification)
            Log.d(TAG, "✅ startForeground called")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground failed: ${e.message}")
        }

        startCallDetection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(TAG, "❌ CallDetectorService destroyed")
        stopCallDetection()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCallDetection() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = CallStateListener(serviceScope, this)

            telephonyManager?.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )
            isRunning = true
            Log.d(TAG, "✅ Phone state listener registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException — READ_PHONE_STATE missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start call detection: ${e.message}")
        }
    }

    private fun stopCallDetection() {
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            telephonyManager = null
            phoneStateListener = null
            isRunning = false
            Log.d(TAG, "Phone state listener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop: ${e.message}")
        }
    }
}

/**
 * 🔥 Custom PhoneStateListener — detects call states
 *
 * State transitions:
 *  IDLE → RINGING → OFFHOOK → IDLE  = Incoming call answered
 *  IDLE → RINGING → IDLE            = Missed call (or rejected)
 *  IDLE → OFFHOOK → IDLE            = Outgoing call
 */
class CallStateListener(
    private val scope: CoroutineScope,
    private val context: Context
) : PhoneStateListener() {

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var callStartTime: Long = 0L
    private var isIncoming: Boolean = false
    private var isRinging: Boolean = false

    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        super.onCallStateChanged(state, phoneNumber)

        if (lastState == state) return  // Ignore duplicate events

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Phone is ringing (incoming call)
                isIncoming = true
                isRinging = true
                callStartTime = System.currentTimeMillis()
                Log.d(TAG, "📞 INCOMING call ringing...")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call picked up (either incoming answered, or outgoing started)
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    // Incoming call answered
                    isIncoming = true
                    Log.d(TAG, "📞 Incoming call ANSWERED")
                } else {
                    // Outgoing call started
                    isIncoming = false
                    Log.d(TAG, "📞 OUTGOING call started")
                }
                isRinging = false
                callStartTime = System.currentTimeMillis()
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended (or phone went idle)
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    // Phone was ringing, but never picked up → MISSED CALL
                    Log.d(TAG, "📞 MISSED call")
                    logCall("missed", phoneNumber, 0L)
                } else if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    // Call was ongoing → ENDED
                    val duration = (System.currentTimeMillis() - callStartTime) / 1000
                    val type = if (isIncoming) "incoming" else "outgoing"
                    Log.d(TAG, "📞 $type call ENDED (duration: ${duration}s)")
                    logCall(type, phoneNumber, duration)
                }
                // Reset state
                isIncoming = false
                isRinging = false
                callStartTime = 0L
            }
        }

        lastState = state
    }

    /**
     * Log call to Firestore
     */
    private fun logCall(type: String, phoneNumber: String?, durationSec: Long) {
        scope.launch {
            try {
                val uid = getChildUid() ?: return@launch
                FirestoreClient.init(context)
                FirestoreClient.setUserId(uid)

                val callData = mutableMapOf<String, Any?>(
                    "type" to type,  // "incoming", "outgoing", "missed"
                    "phoneNumber" to (phoneNumber ?: "Unknown"),
                    "duration" to durationSec,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "deviceTime" to System.currentTimeMillis(),
                    "detectedBy" to "phone_state_listener"
                )

                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                db.collection("call_logs")
                    .document(uid)
                    .collection("items")
                    .add(callData)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Call logged: $type (duration: ${durationSec}s)")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to log call: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "logCall exception: ${e.message}")
            }
        }
    }

    private fun getChildUid(): String? {
        try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user != null) return user.uid
        } catch (_: Exception) {}

        return context.getSharedPreferences("carecircle_prefs", Context.MODE_PRIVATE)
            .getString("currentUserId", null)
    }

    companion object {
        private const val TAG = "CallStateListener"
    }
}