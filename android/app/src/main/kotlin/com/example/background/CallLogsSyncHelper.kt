package com.example.background

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 📞 CallLogsSyncHelper — Syncs call logs to Cloud Firestore with 7-Day retention
 *
 * Firestore structure:
 *  call_logs/{childUid} (Summary Document)
 *  call_logs/{childUid}/items/{docId} (Individual Call Entries)
 *
 * Retention: Keeps past 7 days, auto-deletes older entries (> 7 days).
 */
class CallLogsSyncHelper(private val context: Context) {

    companion object {
        private const val TAG = "CallLogsSync"
        private const val COLLECTION_ROOT = "call_logs"
        private const val SUB_COLLECTION = "items"
        private const val RETENTION_DAYS = 7L
        private const val PREFS_NAME = "carecircle_prefs"
        private const val KEY_LAST_CLEANUP = "last_call_log_cleanup_time"
        private const val CLEANUP_INTERVAL_MS = 6 * 3600 * 1000L // 6 hours
    }

    private val callLogProvider = CallLogProvider(context)
    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Sync call logs from device to Firestore for the past [days] days (default: 7)
     */
    suspend fun syncCallLogs(days: Int = 7): Int = withContext(Dispatchers.IO) {
        if (!callLogProvider.hasPermission()) {
            Log.w(TAG, "⚠️ READ_CALL_LOG permission not granted — skipping call log sync")
            return@withContext 0
        }

        val uid = getChildUid() ?: run {
            Log.w(TAG, "⚠️ No child UID found — skipping call log sync")
            return@withContext 0
        }

        try {
            val now = System.currentTimeMillis()
            val sinceTimeMs = now - (days * 24 * 3600 * 1000L)

            val calls = callLogProvider.getCallHistorySince(sinceTimeMs, maxResults = 500)
            if (calls.isEmpty()) {
                Log.d(TAG, "ℹ️ No calls found in the last $days days")
                checkAndCleanOldCallLogs(uid)
                return@withContext 0
            }

            val collectionRef = firestore.collection(COLLECTION_ROOT)
                .document(uid)
                .collection(SUB_COLLECTION)

            // Firestore batch write supports up to 500 operations per batch
            val chunks = calls.chunked(400)
            var totalSynced = 0

            for (chunk in chunks) {
                val batch = firestore.batch()

                for (call in chunk) {
                    val timestampMs = (call["timestamp"] as? Long) ?: continue
                    val type = (call["type"] as? String) ?: "unknown"
                    val rawPhone = (call["phoneNumber"] as? String) ?: "Unknown"
                    val cleanPhone = rawPhone.replace(Regex("[^0-9+]"), "").takeLast(10)

                    // Idempotent docId ensures no duplicates are created
                    val docId = "${timestampMs}_${type}_$cleanPhone"
                    val docRef = collectionRef.document(docId)

                    val callData = mutableMapOf<String, Any?>(
                        "type" to type,
                        "phoneNumber" to rawPhone,
                        "contactName" to call["contactName"],
                        "duration" to (call["duration"] as? Long ?: 0L),
                        "timestamp" to Timestamp(Date(timestampMs)),
                        "deviceTime" to timestampMs,
                        "detectedBy" to "call_log_provider",
                        "source" to "call_logs_sync",
                        "syncedAt" to FieldValue.serverTimestamp()
                    )

                    batch.set(docRef, callData, SetOptions.merge())
                }

                batch.commit().await()
                totalSynced += chunk.size
            }

            // Update summary document
            val summaryData = mapOf(
                "totalCallsLast7Days" to totalSynced,
                "lastSync" to FieldValue.serverTimestamp(),
                "hasPermission" to true
            )
            firestore.collection(COLLECTION_ROOT).document(uid)
                .set(summaryData, SetOptions.merge())
                .await()

            Log.d(TAG, "✅ Successfully synced $totalSynced call logs to Firestore")

            // Automatically clean up old call logs (> 7 days)
            checkAndCleanOldCallLogs(uid)

            totalSynced
        } catch (e: Exception) {
            Log.e(TAG, "❌ Call logs sync failed: ${e.message}")
            0
        }
    }

    /**
     * Delete call logs older than 7 days from Firestore (throttled every 6 hours)
     */
    fun checkAndCleanOldCallLogs(uid: String, force: Boolean = false) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastClean = prefs.getLong(KEY_LAST_CLEANUP, 0L)

            if (!force && (now - lastClean < CLEANUP_INTERVAL_MS)) {
                return
            }

            prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()

            val cutoffMs = now - (RETENTION_DAYS * 24 * 3600 * 1000L)

            firestore.collection(COLLECTION_ROOT)
                .document(uid)
                .collection(SUB_COLLECTION)
                .whereLessThan("deviceTime", cutoffMs)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot != null && !snapshot.isEmpty) {
                        val batch = firestore.batch()
                        for (doc in snapshot.documents) {
                            batch.delete(doc.reference)
                        }
                        batch.commit().addOnSuccessListener {
                            Log.d(TAG, "🗑️ Auto-deleted ${snapshot.size()} call logs older than $RETENTION_DAYS days")
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to clean old call logs: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndCleanOldCallLogs: ${e.message}")
        }
    }

    private fun getChildUid(): String? {
        try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user != null) return user.uid
        } catch (_: Exception) {}

        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("currentUserId", null)
    }
}
