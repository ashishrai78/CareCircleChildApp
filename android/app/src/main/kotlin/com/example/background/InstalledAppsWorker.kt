package com.example.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue

/**
 * 📱 InstalledAppsWorker — fallback safety net for installed apps sync
 *
 * Runs every 6 hours via WorkManager:
 *  - If ForegroundService is alive → just restart it (defensive)
 *  - If ForegroundService is dead → sync installed apps directly to Firestore
 *
 * Collection: installed_apps/{uid}
 */
class InstalledAppsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "InstalledAppsWorker"
        const val WORK_NAME = "carecircle_installed_apps_worker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "🔄 InstalledAppsWorker executing")

            // 1. Check if ForegroundService is alive
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
                    Log.d(TAG, "✅ ForegroundService restarted — let it handle apps sync")
                    return Result.success()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ ForegroundService restart failed: ${e.message}")
            }

            // 3. Fallback: sync installed apps directly
            Log.w(TAG, "🔄 Falling back to direct installed apps sync")
            syncInstalledAppsDirectly()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun syncInstalledAppsDirectly() {
        try {
            val uid = getUid() ?: run {
                Log.w(TAG, "⚠️ No UID available — skipping apps sync")
                return
            }

            FirestoreClient.init(applicationContext)
            FirestoreClient.setUserId(uid)

            val appsProvider = AppsProvider(applicationContext)
            val apps = appsProvider.getInstalledApps(withIcons = false, excludeSystem = true)

            val appsMap = mutableMapOf<String, Any?>()
            for (app in apps) {
                val pkg = app["packageName"] as? String ?: continue
                appsMap[pkg] = mapOf(
                    "name" to app["name"],
                    "versionName" to app["versionName"],
                    "versionCode" to app["versionCode"],
                    "category" to app["category"],
                    "systemApp" to app["systemApp"],
                    "installedAt" to app["installedAt"],
                    "updatedAt" to app["updatedAt"],
                    "enabled" to app["enabled"]
                )
            }

            val data = mapOf(
                "apps" to appsMap,
                "appCount" to appsMap.size,
                "updatedAt" to FieldValue.serverTimestamp(),
                "syncedBy" to "installed_apps_worker"
            )

            val success = FirestoreClient.writeInstalledApps(data)
            if (success) {
                Log.d(TAG, "✅ Installed apps synced directly (fallback) — ${appsMap.size} apps")
            } else {
                Log.w(TAG, "⚠️ Installed apps sync failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Direct apps sync exception: ${e.message}")
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