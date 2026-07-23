package com.example.background

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 📡 PRODUCTION NotificationListenerService — captures ALL notifications in real-time
 *
 * Called by Android system whenever ANY notification is posted on the device.
 * Writes to Firestore immediately (no batching — true real-time).
 *
 * Collection: child_notifications/{childUid}/items/{autoId}
 *
 * Throttling:
 *  - Skip CareCircle's own notifications
 *  - Skip duplicate notifications (same pkg+title+text within 3 sec)
 *  - Skip foreground service notifications (ongoing calls, music players)
 *  - Skip system USB / battery / developer notifications
 *  - Max 100 notifications per app per day (spam protection)
 *
 * Privacy:
 *  - Captures full title + text content
 *  - App icon (compressed, optional)
 *  - User MUST manually grant Notification Access permission
 */
class CareCircleNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val COLLECTION_ROOT = "child_notifications"
        private const val SUB_COLLECTION = "items"

        // Spam protection: max notifications per app per day
        private const val MAX_PER_APP_PER_DAY = 100

        // Duplicate detection: skip if same notification within 3 seconds
        private const val DUPLICATE_WINDOW_MS = 3000L

        // SharedPreferences keys
        private const val PREFS_NAME = "carecircle_prefs"
        private const val KEY_LAST_NOTIF_PREFIX = "last_notif_"
        private const val KEY_DAILY_COUNT_PREFIX = "daily_count_"

        // 🔒 Apps to skip (privacy + system noise)
        private val BLACKLIST_PACKAGES = setOf(
            "android",                                    // System UI
            "com.android.systemui",                       // Status bar
            "com.android.settings",                       // Settings
            "com.android.providers.media",                // Media scanner
            "com.android.bluetooth",                      // Bluetooth
            "com.android.wifi",                           // WiFi
            "com.android.cellbroadcastreceiver",          // Emergency alerts
            "com.android.phone",                          // Missed call (kept elsewhere)
            "com.example.background",                     // CareCircle self
            "id.flutter.flutter_background_service",      // Flutter service
            "com.google.android.gms",                     // Google Play Services
            "com.google.android.gsf",                     // Google Services Framework
            "com.google.android.googlequicksearchbox",    // Google App
            "com.google.android.ext.services",            // Ext services
            "com.android.vending"                         // Play Store updates
        )

        // 🔒 Categories to skip
        private val BLACKLIST_CATEGORIES = setOf(
            "progress",     // Ongoing downloads/uploads
            "transport"     // Media playback (music players)
        )

        /**
         * Check if Notification Access is enabled (call from MainActivity)
         */
        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            val target = "${context.packageName}/${CareCircleNotificationListener::class.java.name}"
            return flat.split(":").any { it == target }
        }

        /**
         * Open Notification Access settings (call from MainActivity)
         */
        fun openSettings(context: Context) {
            try {
                val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open notification settings: ${e.message}")
            }
        }
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    /**
     * Called when a new notification is posted.
     * This runs in real-time — no delay, no batching.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        try {
            // Get child UID
            val uid = getChildUid() ?: run {
                Log.w(TAG, "⚠️ UID not available — skipping notification")
                return
            }

            val packageName = sbn.packageName ?: return

            // Skip blacklisted apps
            if (BLACKLIST_PACKAGES.contains(packageName)) return

            // Get notification object
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            // Skip ongoing (foreground service) notifications — calls, music, downloads
            val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            val isForeground = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            if (isOngoing || isForeground) return

            // Skip progress / transport categories
            val category = notification.category
            if (category != null && BLACKLIST_CATEGORIES.contains(category.lowercase())) return

            // Skip group summaries (notifications that bundle others)
            val isGroupSummary = extras.getBoolean("android.isGroupSummary", false)
            if (isGroupSummary) return

            // Extract content
            val title = extras.getString(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""

            // Combine text (prefer bigText if available — usually has more content)
            val fullText = when {
                bigText.isNotEmpty() -> bigText
                text.isNotEmpty() -> text
                else -> ""
            }

            // Skip if both title and text are empty
            if (title.isEmpty() && fullText.isEmpty()) return

            // Duplicate detection
            val dedupKey = "$packageName|$title|$fullText"
            val dedupHash = dedupKey.hashCode()
            val lastHash = prefs.getInt("${KEY_LAST_NOTIF_PREFIX}hash", 0)
            val lastTime = prefs.getLong("${KEY_LAST_NOTIF_PREFIX}time", 0L)
            val now = System.currentTimeMillis()

            if (dedupHash == lastHash && (now - lastTime) < DUPLICATE_WINDOW_MS) {
                Log.d(TAG, "🔄 Duplicate notification — skipping")
                return
            }

            // Save hash for next dedup check
            prefs.edit()
                .putInt("${KEY_LAST_NOTIF_PREFIX}hash", dedupHash)
                .putLong("${KEY_LAST_NOTIF_PREFIX}time", now)
                .apply()

            // Daily count check (spam protection)
            val todayKey = dateFormat.format(Date(now))
            val countKey = "${KEY_DAILY_COUNT_PREFIX}${packageName}_$todayKey"
            val todayCount = prefs.getInt(countKey, 0)
            if (todayCount >= MAX_PER_APP_PER_DAY) {
                Log.w(TAG, "⚠️ Daily limit ($MAX_PER_APP_PER_DAY) reached for $packageName — skipping")
                return
            }
            prefs.edit().putInt(countKey, todayCount + 1).apply()

            // Get app name
            val appName = getAppName(packageName)

            // Build notification document
            val notifData = mutableMapOf<String, Any?>(
                "packageName" to packageName,
                "appName" to appName,
                "title" to title,
                "text" to fullText,
                "subText" to subText.ifEmpty { null },
                "infoText" to infoText.ifEmpty { null },
                "category" to (category ?: "unknown"),
                "priority" to getPriorityLabel(notification),
                "postedAt" to now,           // device timestamp (ms)
                "timestamp" to FieldValue.serverTimestamp(),  // server timestamp
                "dateKey" to todayKey,       // for date filtering
                "cleared" to false,
                "capturedBy" to "native_listener"
            )

            // Write to Firestore immediately
            firestore.collection(COLLECTION_ROOT)
                .document(uid)
                .collection(SUB_COLLECTION)
                .add(notifData)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Notification saved: $appName - $title")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to save notification: ${e.message}")
                }

        } catch (e: Exception) {
            Log.e(TAG, "❌ onNotificationPosted error: ${e.message}")
        }
    }

    /**
     * Called when a notification is removed (dismissed by user or cleared by app)
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Optional: mark notification as cleared in Firestore
        // Skipped for now — keeps Firestore writes low
    }

    /**
     * Called when listener connects
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "✅ Notification listener connected")
    }

    /**
     * Called when listener disconnects (e.g., user revoked permission)
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "⚠️ Notification listener disconnected")
    }

    // ============ Private Helpers ============

    private fun getChildUid(): String? {
        // Try Firebase Auth
        try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user != null) return user.uid
        } catch (_: Exception) {}

        // Fallback: SharedPreferences
        return prefs.getString("currentUserId", null)
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun getPriorityLabel(notification: Notification): String {
        return when (notification.priority) {
            Notification.PRIORITY_MAX -> "max"
            Notification.PRIORITY_HIGH -> "high"
            Notification.PRIORITY_DEFAULT -> "default"
            Notification.PRIORITY_LOW -> "low"
            Notification.PRIORITY_MIN -> "min"
            else -> "default"
        }
    }
}
