package com.example.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 🛡️ PRODUCTION RestartReceiver (v3 — Option B architecture)
 *
 * Restarts CareCircleForegroundService (was WatchdogService)
 * Uses WorkManager fallback for Android 12+ FGS restrictions
 */
class RestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RESTART_RECEIVER"
        private const val FLUTTER_SERVICE_CLASS =
            "id.flutter.flutter_background_service.BackgroundService"
        const val ACTION_RESTART_WATCHDOG = "com.example.background.RESTART_WATCHDOG"
        private const val RESTART_WORK_NAME = "carecircle_restart_one_time"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "📡 Received: ${intent.action}")

        when (intent.action) {
            ACTION_RESTART_WATCHDOG,
            Intent.ACTION_USER_PRESENT,
            "android.net.conn.CONNECTIVITY_CHANGE" -> {

                val pendingResult = goAsync()

                Thread {
                    try {
                        var started = false

                        // Try direct ForegroundService start
                        try {
                            CareCircleForegroundService.start(context)
                            started = true
                            Log.d(TAG, "✅ ForegroundService started directly")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Direct start failed: ${e.message}")
                        }

                        // Try direct Flutter service start
                        try {
                            val flutterIntent = Intent().apply {
                                setClassName(context, FLUTTER_SERVICE_CLASS)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    context.startForegroundService(flutterIntent)
                                } catch (e: Exception) {
                                    context.startService(flutterIntent)
                                }
                            } else {
                                context.startService(flutterIntent)
                            }
                            started = true
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Direct Flutter start failed: ${e.message}")
                        }

                        // If direct start failed (Android 12+), use WorkManager
                        if (!started) {
                            scheduleWorkManagerFallback(context)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Restart failed: ${e.message}")
                        scheduleWorkManagerFallback(context)
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }
        }
    }

    private fun scheduleWorkManagerFallback(context: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<WatchdogRestartWorker>()
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                RESTART_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "✅ WorkManager fallback scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ WorkManager schedule failed: ${e.message}")
        }
    }
}