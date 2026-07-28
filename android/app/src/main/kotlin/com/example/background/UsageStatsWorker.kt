package com.example.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue

/**
 * 📊 UsageStatsWorker — fallback safety net for usage stats sync
 *
 * Runs every 15 min via WorkManager:
 *  - If ForegroundService is alive → just restart it (defensive)
 *  - If ForegroundService is dead → sync usage stats directly to Firestore
 *
 * Collection: usage_data/{uid}/daily/{dateKey}
 */
class UsageStatsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UsageStatsWorker"
        const val WORK_NAME = "carecircle_usage_stats_worker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "🔄 UsageStatsWorker executing")

            // 1. Check if ForegroundService is alive — if yes, skip (it handles usage sync)
            val isServiceRunning = CareCircleForegroundService.isRunning(applicationContext)

            if (isServiceRunning) {
                Log.d(TAG, "✅ ForegroundService running — skipping direct sync")
                return Result.success()
            }

            // 2. Try to restart ForegroundService
            Log.w(TAG, "⚠️ ForegroundService NOT running — attempting restart")
            try {
                CareCircleForegroundService.start(applicationContext)
                kotlinx.coroutines.delay(5_000)

                if (CareCircleForegroundService.isRunning(applicationContext)) {
                    Log.d(TAG, "✅ ForegroundService restarted — let it handle usage sync")
                    return Result.success()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ ForegroundService restart failed: ${e.message}")
            }

            // 3. Fallback: sync usage stats directly
            Log.w(TAG, "🔄 Falling back to direct usage stats sync")
            syncUsageStatsDirectly()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun syncUsageStatsDirectly() {
        try {
            val uid = getUid() ?: run {
                Log.w(TAG, "⚠️ No UID available — skipping usage sync")
                return
            }

            FirestoreClient.init(applicationContext)
            FirestoreClient.setUserId(uid)

            val usageStats = UsageStatsProvider(applicationContext)
            val usage = usageStats.getTodayUsage()

            if (usage == null || usage.containsKey("error")) {
                Log.w(TAG, "⚠️ No usage data available")
                return
            }

            val dateKey = usage["dateKey"] as? String ?: ""
            if (dateKey.isEmpty()) {
                Log.w(TAG, "⚠️ Empty dateKey — skipping")
                return
            }

            val usageData = mapOf(
                "totalTime" to usage["totalTime"],
                "apps" to usage["apps"],
                "hourlyBreakdown" to usage["hourlyBreakdown"],
                "sessionCount" to usage["sessionCount"],
                "updatedAt" to FieldValue.serverTimestamp(),
                "syncedBy" to "usage_stats_worker"
            )

            val success = FirestoreClient.writeUsageData(dateKey, usageData)
            if (success) {
                Log.d(TAG, "✅ Usage stats synced directly (fallback) for $dateKey")
            } else {
                Log.w(TAG, "⚠️ Usage stats sync failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Direct usage sync exception: ${e.message}")
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