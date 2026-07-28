package com.example.background

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

/**
 * 🛡️ AccessibilityWatchdogService (v4 — APP BLOCKING ONLY)
 *
 * REFACTORED in Day 3 — Industry Standard Architecture:
 *
 * WHAT THIS SERVICE DOES:
 *  ✅ App Blocking — when child opens blocked app, force-close it
 *  ✅ Foreground app tracking (lightweight, for analytics)
 *
 * WHAT THIS SERVICE DOES NOT DO:
 *  ❌ NO polling (was causing battery drain)
 *  ❌ NO WakeLock (was triggering OEM "abnormal" detection)
 *  ❌ NO foreground notification (CareCircleForegroundService has it)
 *  ❌ NO service restart logic (WorkManager + RestartReceiver handles)
 *  ❌ NO auto-launch app on revoke (was causing infinite loop)
 *
 * WHY THIS IS REALME-FRIENDLY:
 *  - Real "accessibility use case" → Realme won't revoke
 *  - Minimal resource usage → not flagged as abnormal
 *  - Event-driven only → no suspicious background activity
 *
 * BLOCKED APPS DATA FLOW:
 *  1. Parent app writes to Firestore: blocked_apps/{uid}/apps
 *  2. CareCircleForegroundService listens + caches in SharedPreferences
 *  3. This service reads from SharedPreferences on every app launch
 */
class AccessibilityWatchdogService : AccessibilityService() {

    companion object {
        private const val TAG = "ACCESS_BLOCKER"

        // SharedPreferences keys (shared with ForegroundService)
        private const val PREFS_NAME = "carecircle_prefs"
        private const val KEY_BLOCKED_APPS = "blocked_apps_list"
        private const val KEY_BLOCKED_APPS_UPDATED = "blocked_apps_updated_at"

        // Block notification
        private const val BLOCK_NOTIF_CHANNEL_ID = "app_block_alert"
        private const val BLOCK_NOTIF_ID = 3001

        // Last shown block notification (throttle to avoid spam)
        private const val BLOCK_NOTIF_THROTTLE_MS = 30_000L  // 30 sec per app

        /**
         * Check if accessibility service is enabled (call from MainActivity / Flutter)
         */
        fun isEnabled(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                        as android.view.accessibility.AccessibilityManager
                val enabledServices = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_GENERIC
                )
                val serviceName = "${context.packageName}/.AccessibilityWatchdogService"
                enabledServices.any {
                    it.resolveInfo.serviceInfo.let { si ->
                        "${si.packageName}/${si.name}" == serviceName
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Update blocked apps list (called by ForegroundService when Firestore updates)
         * Stores as comma-separated string for fast access
         */
        fun updateBlockedApps(context: Context, apps: Set<String>) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putStringSet(KEY_BLOCKED_APPS, apps)
                    .putLong(KEY_BLOCKED_APPS_UPDATED, System.currentTimeMillis())
                    .apply()
                Log.d(TAG, "✅ Blocked apps updated: ${apps.size} apps")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update blocked apps: ${e.message}")
            }
        }

        /**
         * Get current blocked apps list
         */
        fun getBlockedApps(context: Context): Set<String> {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        }
    }

    // 🔥 In-memory cache of blocked apps (refreshed periodically)
    private var blockedAppsCache: Set<String> = emptySet()
    private var lastBlockedAppsRefresh = 0L
    private val BLOCKED_APPS_REFRESH_INTERVAL_MS = 60_000L  // 1 min

    // 🔥 Throttle per-app block notifications
    private val lastBlockNotifTime = mutableMapOf<String, Long>()

    // 🔥 Track current foreground app (avoid duplicate events)
    private var currentForegroundApp: String? = null
    private var eventCount = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ AccessibilityWatchdogService connected (v4 — App Blocking Only)")

        // 🔥 NO startForeground (AccessibilityService doesn't need it)
        // 🔥 NO WakeLock (was causing OEM "abnormal" detection)
        // 🔥 NO polling Handler (was causing battery drain)

        // Load initial blocked apps cache
        refreshBlockedAppsCache()
    }

    /**
     * 🔥 MAIN FUNCTION — Process accessibility events for app blocking
     *
     * This is the REAL USE CASE that prevents Realme from revoking accessibility.
     * Realme's algorithm checks: is this service actually doing accessibility work?
     * App blocking = legitimate accessibility use → no revoke.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        try {
            val eventType = event.eventType
            val packageName = event.packageName?.toString() ?: return
            eventCount++

            // Only process window state changes (app launch / switch)
            if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

            // Skip system UI and own app
            if (packageName == "android" ||
                packageName == "com.android.systemui" ||
                packageName == this.packageName ||
                packageName.contains("systemui")) {
                return
            }

            // Skip duplicate events for same app
            if (packageName == currentForegroundApp) return
            currentForegroundApp = packageName

            Log.d(TAG, "📋 Foreground app changed: $packageName (event #$eventCount)")

            // 🔥 Refresh blocked apps cache if stale (every 1 min)
            refreshBlockedAppsCacheIfNeeded()

            // 🔥 CHECK IF APP IS BLOCKED
            if (isAppBlocked(packageName)) {
                Log.w(TAG, "🚫 Blocked app launched: $packageName — force closing")
                blockApp(packageName)
            }

        } catch (e: Exception) {
            // Silent fail — don't crash accessibility service
            Log.e(TAG, "Event processing error: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    /**
     * 🔥 Service unbound (user disabled accessibility or OEM revoked)
     *
     * NEW BEHAVIOR: Just notify, NO auto-launch app (prevents infinite loop)
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "⚠️ Accessibility service unbound — notifying app (NO auto-launch)")

        // 🔥 Broadcast to Flutter (if app is open, it can show dialog)
        try {
            val broadcastIntent = Intent("com.example.background.ACCESSIBILITY_REVOKED")
            applicationContext.sendBroadcast(broadcastIntent)
            Log.d(TAG, "📡 Broadcasted ACCESSIBILITY_REVOKED")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
        }

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "❌ Accessibility service destroyed")
        super.onDestroy()
    }

    // ============ App Blocking Logic ============

    private fun isAppBlocked(packageName: String): Boolean {
        return blockedAppsCache.contains(packageName)
    }

    /**
     * Force-close blocked app + show notification
     */
    private fun blockApp(packageName: String) {
        try {
            // 🔥 Method 1: Go to home screen (works on all devices)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // 🔥 Method 2: Try to use Back action (sometimes more effective)
            // performGlobalAction(GLOBAL_ACTION_BACK)

            // 🔥 Show notification (throttled per app)
            showBlockNotification(packageName)

            Log.d(TAG, "✅ Blocked app force-closed: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block app: ${e.message}")
        }
    }

    /**
     * Show notification when app is blocked (throttled per app to avoid spam)
     */
    private fun showBlockNotification(packageName: String) {
        try {
            val now = System.currentTimeMillis()
            val lastShown = lastBlockNotifTime[packageName] ?: 0L

            // Throttle: max 1 notification per app per 30 sec
            if (now - lastShown < BLOCK_NOTIF_THROTTLE_MS) {
                Log.d(TAG, "Skipping block notification (30s throttle) for $packageName")
                return
            }
            lastBlockNotifTime[packageName] = now

            // Get app name
            val appName = getAppName(packageName)

            // Create notification channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    BLOCK_NOTIF_CHANNEL_ID,
                    "App Block Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when blocked apps are opened"
                    enableVibration(true)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }

            // Build notification
            val notification = NotificationCompat.Builder(this, BLOCK_NOTIF_CHANNEL_ID)
                .setContentTitle("🚫 $appName is Blocked")
                .setContentText("Your parent has restricted access to this app")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(BLOCK_NOTIF_ID, notification)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show block notification: ${e.message}")
        }
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

    // ============ Blocked Apps Cache Management ============

    private fun refreshBlockedAppsCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastBlockedAppsRefresh < BLOCKED_APPS_REFRESH_INTERVAL_MS) return
        refreshBlockedAppsCache()
    }

    private fun refreshBlockedAppsCache() {
        try {
            blockedAppsCache = getBlockedApps(this)
            lastBlockedAppsRefresh = System.currentTimeMillis()
            Log.d(TAG, "🔄 Blocked apps cache refreshed: ${blockedAppsCache.size} apps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh blocked apps: ${e.message}")
        }
    }
}