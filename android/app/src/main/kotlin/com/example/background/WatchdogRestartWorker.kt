package com.example.background

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * 🛡️ WatchdogRestartWorker (v2 — Option B architecture)
 *
 * Restarts CareCircleForegroundService (was WatchdogService)
 * Used as fallback when direct service start fails on Android 12+
 */
class WatchdogRestartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "WatchdogRestartWorker"
    }

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "🔄 Worker executing — restarting CareCircleForegroundService")

            // Restart master foreground service
            CareCircleForegroundService.start(applicationContext)


            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker failed: ${e.message}")
            Result.retry()
        }
    }
}