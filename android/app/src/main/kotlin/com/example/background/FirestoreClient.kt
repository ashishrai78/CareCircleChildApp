package com.example.background

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * 🔥 NATIVE Firestore Client — used by WatchdogService
 *
 * Why native (not Flutter):
 *  - Flutter background service runs in separate isolate
 *  - MethodChannels registered in MainActivity are NOT available there
 *  - Native Firestore works ALWAYS — even when Flutter engine is dead
 *
 * Uses Firebase default instance (auto-initialized by google-services.json)
 */
object FirestoreClient {

    private const val TAG = "FirestoreClient"

    private var firestore: FirebaseFirestore? = null
    private var userId: String? = null

    /**
     * Initialize — call once from WatchdogService.onCreate()
     */
    fun init(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            firestore = FirebaseFirestore.getInstance()
            Log.d(TAG, "✅ Firestore initialized natively")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firestore init failed: ${e.message}")
        }
    }

    /**
     * Set current user ID (from GetStorage or Firebase Auth)
     */
    fun setUserId(uid: String?) {
        userId = uid
        Log.d(TAG, "User ID set: $uid")
    }

    fun getUserId(): String? = userId

    /**
     * Write child_live_data (location, battery, network, etc.)
     */
    fun writeLiveData(data: Map<String, Any?>): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        return try {
            val task = db.collection("child_live_data")
                .document(uid)
                .set(data, SetOptions.merge())

            // Wait synchronously (we're in background thread)
            com.google.android.gms.tasks.Tasks.await(task)
            Log.d(TAG, "✅ Live data written")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Live data write failed: ${e.message}")
            false
        }
    }

    /**
     * Write usage_data/{uid}/daily/{dateKey}
     */
    fun writeUsageData(dateKey: String, data: Map<String, Any?>): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        return try {
            val task = db.collection("usage_data")
                .document(uid)
                .collection("daily")
                .document(dateKey)
                .set(data, SetOptions.merge())

            com.google.android.gms.tasks.Tasks.await(task)
            Log.d(TAG, "✅ Usage data written for $dateKey")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Usage data write failed: ${e.message}")
            false
        }
    }

    /**
     * Write installed_apps
     */
    fun writeInstalledApps(data: Map<String, Any?>): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        return try {
            val task = db.collection("installed_apps")
                .document(uid)
                .set(data, SetOptions.merge())

            com.google.android.gms.tasks.Tasks.await(task)
            Log.d(TAG, "✅ Installed apps written")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Installed apps write failed: ${e.message}")
            false
        }
    }

    /**
     * Write heartbeat (every 60s)
     */
    fun writeHeartbeat(batteryLevel: Int, isCharging: Boolean): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        return try {
            val data = mapOf(
                "heartbeat" to FieldValue.serverTimestamp(),
                "battery" to batteryLevel,
                "isCharging" to isCharging,
                "serviceAlive" to true,
                "nativeService" to true
            )
            val task = db.collection("child_live_data")
                .document(uid)
                .set(data, SetOptions.merge())

            com.google.android.gms.tasks.Tasks.await(task)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Heartbeat failed: ${e.message}")
            false
        }
    }

    /**
     * Read child_control document (for sync_request)
     */
    fun getChildControl(onResult: (Map<String, Any?>?) -> Unit) {
        val uid = userId ?: run { onResult(null); return }
        val db = firestore ?: run { onResult(null); return }

        try {
            db.collection("child_control")
                .document(uid)
                .get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        onResult(doc.data)
                    } else {
                        onResult(null)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ getChildControl failed: ${e.message}")
                    onResult(null)
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ getChildControl exception: ${e.message}")
            onResult(null)
        }
    }

    /**
     * Update child_control (clear sync_request, set last_sync)
     */
    fun updateSyncComplete(): Boolean {
        val uid = userId ?: return false
        val db = firestore ?: return false

        return try {
            val task = db.collection("child_control")
                .document(uid)
                .update(
                    mapOf(
                        "sync_request" to false,
                        "last_sync" to FieldValue.serverTimestamp()
                    )
                )
            com.google.android.gms.tasks.Tasks.await(task)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateSyncComplete failed: ${e.message}")
            false
        }
    }
}
