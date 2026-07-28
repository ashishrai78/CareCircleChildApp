package com.example.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth

/**
 * 🔥 HeartbeatWorker — fallback safety net for ForegroundService
 *
 * Runs every 15 min via WorkManager:
 *  - If ForegroundService is alive → just restart it (defensive)
 *  - If ForegroundService is dead → send heartbeat directly to Firestore
 *
 * This ensures parent app ALWAYS sees fresh heartbeat (max 15 min stale)
 * even if OEM kills ForegroundService.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        const val WORK_NAME = "carecircle_heartbeat_worker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "🔄 HeartbeatWorker executing")

            // 1. Check if ForegroundService is alive
            val isServiceRunning = CareCircleForegroundService.isRunning(applicationContext)

            if (isServiceRunning) {
                Log.d(TAG, "✅ ForegroundService already running — no fallback needed")
                return Result.success()
            }

            // 2. Try to restart ForegroundService
            Log.w(TAG, "⚠️ ForegroundService NOT running — attempting restart")
            try {
                CareCircleForegroundService.start(applicationContext)
                // Give it 5 sec to start
                kotlinx.coroutines.delay(5_000)

                if (CareCircleForegroundService.isRunning(applicationContext)) {
                    Log.d(TAG, "✅ ForegroundService restarted successfully")
                    return Result.success()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ ForegroundService restart failed: ${e.message}")
            }

            // 3. Fallback: send heartbeat directly (service can't be revived)
            Log.w(TAG, "🔄 Falling back to direct heartbeat")
            sendDirectHeartbeat()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker failed: ${e.message}")
            Result.retry()
        }
    }

    /**
     * Direct heartbeat to Firestore — bypasses ForegroundService
     */
    private suspend fun sendDirectHeartbeat() {
        try {
            // Get UID (from Firebase Auth or SharedPreferences)
            val uid = getUid() ?: run {
                Log.w(TAG, "⚠️ No UID available — skipping heartbeat")
                return
            }

            // Initialize Firestore if needed
            FirestoreClient.init(applicationContext)
            FirestoreClient.setUserId(uid)

            // Get battery level
            val deviceInfo = DeviceInfoProvider(applicationContext)
            val battery = deviceInfo.getBatteryInfo()
            val batteryLevel = (battery?.get("level") as? Int) ?: -1
            val isCharging = (battery?.get("isCharging") as? Boolean) ?: false

            // Send heartbeat
            val success = FirestoreClient.writeHeartbeat(batteryLevel, isCharging)
            if (success) {
                Log.d(TAG, "✅ Direct heartbeat sent (fallback)")
            } else {
                Log.w(TAG, "⚠️ Direct heartbeat failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Direct heartbeat exception: ${e.message}")
        }
    }

    private fun getUid(): String? {
        try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) return user.uid
        } catch (_: Exception) {}

        return applicationContext.getSharedPreferences("carecircle_prefs", Context.MODE_PRIVATE)
            .getString("currentUserId", null)
    }
}