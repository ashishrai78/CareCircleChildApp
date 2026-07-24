package com.example.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 🛡️ PRODUCTION BootReceiver (v2)
 *
 * Fixes vs v1:
 *  1. Removed LOCKED_BOOT_COMPLETED (was crashing — app not directBootAware)
 *  2. Uses WorkManager fallback for Android 12+ FGS start restrictions
 *  3. goAsync() for long-running operations (10 sec window)
 *  4. Proper ForegroundServiceStartNotAllowedException handling
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BOOT_RECEIVER"
        private const val FLUTTER_SERVICE_CLASS =
            "id.flutter.flutter_background_service.BackgroundService"
        private const val FALLBACK_WORK_NAME = "watchdog_fallback_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "📡 Boot/Restart broadcast received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 🔥 goAsync() — gives 10 sec window (default onReceive is 10ms)
                val pendingResult = goAsync()

                Thread {
                    try {
                        startAllServices(context)
                        scheduleFallbackWork(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Boot init failed: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }
        }
    }

    private fun startAllServices(context: Context) {
        // 1. Start native WatchdogService
        try {
            WatchdogService.start(context)
            Log.d(TAG, "✅ WatchdogService started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ WatchdogService start failed: ${e.message}")
        }

        // 2. Start Flutter BackgroundService (with Android 12+ exception handling)
        try {
            val flutterIntent = Intent().apply {
                setClassName(context, FLUTTER_SERVICE_CLASS)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(flutterIntent)
                    Log.d(TAG, "✅ Flutter service started (foreground)")
                } catch (e: Exception) {
                    // Android 12+ ForegroundServiceStartNotAllowedException
                    Log.w(TAG, "⚠️ Foreground start failed: ${e.message}")
                    try {
                        context.startService(flutterIntent)
                        Log.d(TAG, "✅ Flutter service started (regular)")
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ All Flutter service start attempts failed: ${e2.message}")
                        // Fallback to WorkManager — handled by scheduleFallbackWork
                    }
                }
            } else {
                context.startService(flutterIntent)
                Log.d(TAG, "✅ Flutter service started (legacy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Flutter service start failed: ${e.message}")
        }
    }

    /**
     * 🔥 NEW: Real WorkManager fallback (was empty log in v1)
     * Periodic check every 15 min — revives services if killed
     */
    private fun scheduleFallbackWork(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<WatchdogRestartWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                FALLBACK_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "✅ WorkManager fallback scheduled (15 min periodic)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ WorkManager schedule failed: ${e.message}")
        }
    }
}