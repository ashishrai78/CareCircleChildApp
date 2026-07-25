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
 * 🔥 NATIVE Firestore Client (v3 — Realme-optimized)
 */
object FirestoreClient {

    private const val TAG = "FirestoreClient"
    private const val TIMEOUT_MS = 5_000L
    private const val MAX_RETRIES = 1
    private val MIN_TIME_BETWEEN_SYNCS_MS = 60_000L

    private var firestore: FirebaseFirestore? = null
    private var userId: String? = null

    @Volatile
    private var lastSyncAttemptTime = 0L

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

    private suspend fun <T> executeWithRetry(
        operationName: String,
        block: suspend () -> com.google.android.gms.tasks.Task<T>
    ): T? {
        val now = System.currentTimeMillis()
        if (now - lastSyncAttemptTime < MIN_TIME_BETWEEN_SYNCS_MS) {
            Log.d(TAG, "⏭️ $operationName skipped (60s rate limit)")
            return null
        }
        lastSyncAttemptTime = now

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

    suspend fun getChildControl(): Map<String, Any?>? {
        val uid = userId ?: return null
        val db = firestore ?: return null

        val result = executeWithRetry("getChildControl") {
            db.collection("child_control")
                .document(uid)
                .get()
        }

        return result?.let { doc ->
            if (doc.exists()) doc.data else null
        }
    }

    suspend fun updateSyncComplete(): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        val result = executeWithRetry("updateSyncComplete") {
            db.collection("child_control")
                .document(uid)
                .update(
                    mapOf(
                        "sync_request" to false,
                        "last_sync" to FieldValue.serverTimestamp()
                    )
                )
        }

        return result != null
    }
}