package com.example.background

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 🛡️ CareCircleWorkScheduler — central scheduler for all periodic Workers
 *
 * Call from:
 *  - MainActivity.onCreate() (first app launch)
 *  - BootReceiver (after device boot)
 *
 * Schedules:
 *  - HeartbeatWorker       (every 15 min)
 *  - UsageStatsWorker      (every 15 min)
 *  - InstalledAppsWorker   (every 6 hours)
 *  - WatchdogRestartWorker (every 15 min) — existing
 *
 * WorkManager survives:
 *  - App kill
 *  - Device restart (with RECEIVE_BOOT_COMPLETED)
 *  - Doze mode (mostly)
 */
object CareCircleWorkScheduler {

    private const val TAG = "WorkScheduler"

    /**
     * Schedule ALL periodic workers — safe to call multiple times (uses KEEP policy)
     */
    fun scheduleAll(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)

            // 1. Heartbeat Worker — every 15 min
            val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, TimeUnit.MINUTES
            ).build()
            workManager.enqueueUniquePeriodicWork(
                HeartbeatWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                heartbeatRequest
            )

            // 2. Usage Stats Worker — every 15 min
            val usageStatsRequest = PeriodicWorkRequestBuilder<UsageStatsWorker>(
                15, TimeUnit.MINUTES
            ).build()
            workManager.enqueueUniquePeriodicWork(
                UsageStatsWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                usageStatsRequest
            )

            // 3. Installed Apps Worker — every 6 hours
            val installedAppsRequest = PeriodicWorkRequestBuilder<InstalledAppsWorker>(
                6, TimeUnit.HOURS
            ).build()
            workManager.enqueueUniquePeriodicWork(
                InstalledAppsWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                installedAppsRequest
            )

            // 4. Watchdog Restart Worker — every 15 min (existing)
            val watchdogRequest = PeriodicWorkRequestBuilder<WatchdogRestartWorker>(
                15, TimeUnit.MINUTES
            ).build()
            workManager.enqueueUniquePeriodicWork(
                "watchdog_fallback",
                ExistingPeriodicWorkPolicy.KEEP,
                watchdogRequest
            )

            Log.d(TAG, "✅ All workers scheduled (Heartbeat 15m, UsageStats 15m, Apps 6h, Watchdog 15m)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule workers: ${e.message}")
        }
    }

    /**
     * Cancel all workers (used during logout/account deletion)
     */
    fun cancelAll(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(HeartbeatWorker.WORK_NAME)
            workManager.cancelUniqueWork(UsageStatsWorker.WORK_NAME)
            workManager.cancelUniqueWork(InstalledAppsWorker.WORK_NAME)
            workManager.cancelUniqueWork("watchdog_fallback")
            Log.d(TAG, "✅ All workers cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cancel workers: ${e.message}")
        }
    }
}