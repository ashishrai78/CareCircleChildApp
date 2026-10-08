package com.example.background

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 🔄 OfflineSyncManager — Coordinates syncing locally cached offline notifications
 * and call logs to Cloud Firestore as soon as internet becomes available.
 */
object OfflineSyncManager {

    private const val TAG = "OfflineSyncManager"
    private const val PREFS_NAME = "carecircle_prefs"
    private const val MIN_SYNC_INTERVAL_MS = 4_000L // Debounce duplicate triggers

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isSyncing = AtomicBoolean(false)
    private var lastSyncTime = 0L

    /**
     * Non-blocking trigger to sync offline data in background
     */
    fun triggerSync(context: Context, reason: String = "manual") {
        scope.launch {
            syncAllPending(context.applicationContext, reason)
        }
    }

    /**
     * Synchronize all offline notifications and call logs to Firebase Firestore
     */
    suspend fun syncAllPending(context: Context, reason: String = "manual") = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastSyncTime < MIN_SYNC_INTERVAL_MS) {
            Log.d(TAG, "⏭️ Sync debounced (reason: $reason)")
            return@withContext
        }

        if (!isSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "⏭️ Sync already in progress, skipping duplicate trigger")
            return@withContext
        }

        try {
            if (!NetworkUtils.isNetworkAvailable(context)) {
                Log.d(TAG, "📵 Device still offline — skipping sync (reason: $reason)")
                return@withContext
            }

            val uid = getUserId(context)
            if (uid.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ No child UID found — cannot sync offline data")
                return@withContext
            }

            val db = OfflineSyncDatabase.getInstance(context)
            val firestore = FirebaseFirestore.getInstance()

            Log.d(TAG, "🚀 Starting offline sync (reason: $reason) for UID: $uid")

            // ==========================================
            // 1. SYNC OFFLINE NOTIFICATIONS
            // ==========================================
            val pendingNotifs = db.getPendingNotifications(uid, limit = 200)
            if (pendingNotifs.isNotEmpty()) {
                Log.d(TAG, "📤 Uploading ${pendingNotifs.size} offline notifications to Firebase...")
                val notifCollection = firestore.collection("child_notifications")
                    .document(uid)
                    .collection("items")

                val syncedNotifIds = mutableListOf<Long>()

                for (item in pendingNotifs) {
                    try {
                        val notifData = mutableMapOf<String, Any?>(
                            "packageName" to item.packageName,
                            "appName" to item.appName,
                            "title" to item.title,
                            "text" to item.text,
                            "subText" to item.subText,
                            "infoText" to item.infoText,
                            "category" to item.category,
                            "priority" to item.priority,
                            "postedAt" to item.postedAt,
                            "timestamp" to FieldValue.serverTimestamp(),
                            "dateKey" to item.dateKey,
                            "cleared" to false,
                            "capturedBy" to "offline_listener_synced"
                        )

                        notifCollection.add(notifData).await()
                        syncedNotifIds.add(item.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to upload notification id=${item.id}: ${e.message}")
                        // Stop if connection interrupted
                        if (!NetworkUtils.isNetworkAvailable(context)) break
                    }
                }

                if (syncedNotifIds.isNotEmpty()) {
                    db.deleteNotifications(syncedNotifIds)
                    Log.d(TAG, "✅ Synced and cleared ${syncedNotifIds.size} offline notifications")
                }
            }

            // ==========================================
            // 2. SYNC OFFLINE CALL LOGS
            // ==========================================
            val pendingCalls = db.getPendingCallLogs(uid, limit = 200)
            if (pendingCalls.isNotEmpty()) {
                Log.d(TAG, "📤 Uploading ${pendingCalls.size} offline call logs to Firebase...")
                val callCollection = firestore.collection("call_logs")
                    .document(uid)
                    .collection("items")

                val syncedCallIds = mutableListOf<Long>()

                for (call in pendingCalls) {
                    try {
                        val callData = mutableMapOf<String, Any?>(
                            "type" to call.type,
                            "phoneNumber" to call.phoneNumber,
                            "contactName" to call.contactName,
                            "duration" to call.duration,
                            "timestamp" to Timestamp(Date(call.deviceTime)),
                            "deviceTime" to call.deviceTime,
                            "detectedBy" to call.detectedBy,
                            "source" to call.source,
                            "syncedAt" to FieldValue.serverTimestamp()
                        )

                        callCollection.document(call.docId)
                            .set(callData, SetOptions.merge())
                            .await()

                        syncedCallIds.add(call.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to upload call log id=${call.id}: ${e.message}")
                        if (!NetworkUtils.isNetworkAvailable(context)) break
                    }
                }

                if (syncedCallIds.isNotEmpty()) {
                    db.deleteCallLogs(syncedCallIds)
                    Log.d(TAG, "✅ Synced and cleared ${syncedCallIds.size} offline call logs")
                }
            }

            // ==========================================
            // 3. SYSTEM CALL LOGS SYNC (Android CallLog.Calls past 7 days)
            // ==========================================
            try {
                val callLogsSyncHelper = CallLogsSyncHelper(context)
                val syncedCount = callLogsSyncHelper.syncCallLogs(days = 7)
                Log.d(TAG, "📞 CallLogsSyncHelper synced $syncedCount calls from system log")
            } catch (e: Exception) {
                Log.e(TAG, "CallLogsSyncHelper error during offline sync: ${e.message}")
            }

            // Clean up any stale items (> 7 days)
            db.cleanOldData(System.currentTimeMillis() - 7 * 24 * 3600 * 1000L)
            lastSyncTime = System.currentTimeMillis()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during offline sync: ${e.message}")
        } finally {
            isSyncing.set(false)
        }
    }

    private fun getUserId(context: Context): String? {
        try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) return user.uid
        } catch (_: Exception) {}

        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("currentUserId", null)
    }
}
