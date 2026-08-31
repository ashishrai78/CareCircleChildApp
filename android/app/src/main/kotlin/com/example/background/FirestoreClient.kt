package com.example.background

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 🔥 NATIVE Firestore Client (v4 — Sync-Friendly)
 *
 * Fixes vs v3:
 *  1. ❌ REMOVED global 60s rate limit (was blocking sync_request flow)
 *  2. ✅ Per-operation throttle (each operation has its own 30s throttle)
 *  3. ✅ updateSyncComplete() BYPASSES throttle (parent needs immediate feedback)
 *  4. ✅ Timeout 5s, 1 retry (kept from v3)
 */
object FirestoreClient {

    private const val TAG = "FirestoreClient"
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_RETRIES = 2

    // 🔥 Per-operation throttle (30s per operation type)
    private val PER_OP_THROTTLE_MS = 30_000L
    private val lastOpTime = mutableMapOf<String, Long>()

    private var firestore: FirebaseFirestore? = null
    private var userId: String? = null

    fun init(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }

            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()

            firestore = FirebaseFirestore.getInstance()
            firestore?.firestoreSettings = settings

            Log.d(TAG, "✅ Firestore initialized natively with offline cache")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firestore init failed: ${e.message}")
        }
    }

    fun setUserId(uid: String?) {
        userId = uid
        Log.d(TAG, "User ID set: $uid")
    }

    fun getUserId(): String? = userId

    /**
     * 🔥 Per-operation throttle — each operation type has its own 30s window
     * updateSyncComplete() bypasses this (uses executeWithoutThrottle)
     */
    private suspend fun <T> executeWithRetry(
        operationName: String,
        block: suspend () -> com.google.android.gms.tasks.Task<T>
    ): T? {
        // 🔥 Check per-operation throttle
        val now = System.currentTimeMillis()
        val lastTime = lastOpTime[operationName] ?: 0L
        if (now - lastTime < PER_OP_THROTTLE_MS) {
            Log.d(TAG, "⏭️ $operationName skipped (30s per-op throttle)")
            return null
        }
        lastOpTime[operationName] = now

        return executeWithoutThrottle(operationName, block)
    }

    /**
     * 🔥 Execute without throttle check — used by updateSyncComplete()
     * Parent needs immediate feedback that sync is done
     */
    private suspend fun <T> executeWithoutThrottle(
        operationName: String,
        block: suspend () -> com.google.android.gms.tasks.Task<T>
    ): T? {
        var attempt = 0
        var lastError: Exception? = null

        while (attempt < MAX_RETRIES) {
            try {
                val task = block()
                val result = withTimeoutOrNull(TIMEOUT_MS) { task.await() }

                if (result != null) {
                    return result
                } else {
                    Log.w(TAG, "⚠️ $operationName timed out")
                    lastError = Exception("Timeout")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ $operationName failed: ${e.message}")
                lastError = e
            }

            attempt++
            if (attempt < MAX_RETRIES) {
                kotlinx.coroutines.delay(2_000L)
            }
        }

        Log.e(TAG, "💥 $operationName failed after $attempt attempts")
        return null
    }

    suspend fun writeLiveData(data: Map<String, Any?>): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        val result = executeWithRetry("writeLiveData") {
            db.collection("child_live_data")
                .document(uid)
                .set(data, SetOptions.merge())
        }

        if (result != null) {
            Log.d(TAG, "✅ Live data written")
            return true
        }
        return false
    }

    suspend fun writeUsageData(dateKey: String, data: Map<String, Any?>): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        val result = executeWithRetry("writeUsageData") {
            db.collection("usage_data")
                .document(uid)
                .collection("daily")
                .document(dateKey)
                .set(data, SetOptions.merge())
        }

        if (result != null) {
            Log.d(TAG, "✅ Usage data written for $dateKey")
            return true
        }
        return false
    }

    suspend fun writeInstalledApps(data: Map<String, Any?>): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        val result = executeWithRetry("writeInstalledApps") {
            db.collection("installed_apps")
                .document(uid)
                .set(data, SetOptions.merge())
        }

        if (result != null) {
            Log.d(TAG, "✅ Installed apps written")
            return true
        }
        return false
    }

    suspend fun clearContactsSyncRequest(): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false  // ✅ Use 'firestore' directly+
        val result = executeWithoutThrottle("clearContactsSyncRequest") {
            db.collection("child_control")
                .document(uid)
                .update(mapOf("contacts_sync_request" to false))
        }
        return result != null
    }

    suspend fun writeHeartbeat(batteryLevel: Int, isCharging: Boolean): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        val data = mapOf(
            "heartbeat" to FieldValue.serverTimestamp(),
            "battery" to batteryLevel,
            "isCharging" to isCharging,
            "serviceAlive" to true,
            "nativeService" to true
        )

        val result = executeWithRetry("writeHeartbeat") {
            db.collection("child_live_data")
                .document(uid)
                .set(data, SetOptions.merge())
        }

        return result != null
    }

    /**
     * 🔥 Read child_control — bypass throttle (read is cheap)
     */
    suspend fun getChildControl(): Map<String, Any?>? {
        val uid = userId ?: return null
        val db = firestore ?: return null

        val result = executeWithoutThrottle("getChildControl") {
            db.collection("child_control")
                .document(uid)
                .get()
        }

        return result?.let { doc ->
            if (doc.exists()) doc.data else null
        }
    }

    /**
     * 🔥 CRITICAL: updateSyncComplete bypasses throttle
     * Parent needs immediate feedback that sync is done
     * Otherwise parent keeps sending sync_request = true
     */
    suspend fun updateSyncComplete(): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        val result = executeWithoutThrottle("updateSyncComplete") {
            db.collection("child_control")
                .document(uid)
                .update(
                    mapOf(
                        "sync_request" to false,
                        "last_sync" to FieldValue.serverTimestamp()
                    )
                )
        }

        if (result != null) {
            Log.d(TAG, "✅ Sync complete — parent flag cleared")
            return true
        }
        return false
    }
}