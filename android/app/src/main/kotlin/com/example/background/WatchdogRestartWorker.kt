package com.example.background

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * 🛡️ WatchdogRestartWorker — native WorkManager fallback
 *
 * Used by:
 *  - BootReceiver — fallback if direct service start fails on Android 12+
 *  - RestartReceiver — fallback when FGS start throws ForegroundServiceStartNotAllowedException
 *
 * WorkManager survives Doze, app kills, and reboots (with RECEIVE_BOOT_COMPLETED).
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
            Log.d(TAG, "🔄 Worker executing — restarting WatchdogService")

            // Restart native watchdog
            WatchdogService.start(applicationContext)

            // Restart Flutter BackgroundService
            try {
                val flutterIntent = android.content.Intent().apply {
                    setClassName(
                        applicationContext,
                        "id.flutter.flutter_background_service.BackgroundService"
                    )
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        applicationContext.startForegroundService(flutterIntent)
                    } catch (e: Exception) {
                        applicationContext.startService(flutterIntent)
                    }
                } else {
                    applicationContext.startService(flutterIntent)
                }
                Log.d(TAG, "✅ Flutter service restarted")
            } catch (e: Exception) {
                Log.w(TAG, "Flutter restart failed (non-fatal): ${e.message}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker failed: ${e.message}")
            // Retry with exponential backoff (10s, 20s, 40s)
            Result.retry()
        }
    }
}