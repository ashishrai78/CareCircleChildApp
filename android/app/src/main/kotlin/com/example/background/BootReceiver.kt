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
 * 🛡️ PRODUCTION BootReceiver (v3 — Option B architecture)
 *
 * Fixes vs v2:
 *  1. Starts CareCircleForegroundService (was WatchdogService)
 *  2. Schedules all workers via CareCircleWorkScheduler
 *  3. Removed LOCKED_BOOT_COMPLETED (was crashing)
 *  4. Uses goAsync() for long operations
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BOOT_RECEIVER"
        private const val FLUTTER_SERVICE_CLASS =
            "id.flutter.flutter_background_service.BackgroundService"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "📡 Boot/Restart broadcast received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pendingResult = goAsync()

                Thread {
                    try {
                        startAllServices(context)
                        CareCircleWorkScheduler.scheduleAll(context)
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
        // 1. Start CareCircleForegroundService (master service)
        try {
            CareCircleForegroundService.start(context)
            Log.d(TAG, "✅ CareCircleForegroundService started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ForegroundService start failed: ${e.message}")
        }

        // 2. Start Flutter BackgroundService (for WebRTC mic streaming)
        try {
            val flutterIntent = Intent().apply {
                setClassName(context, FLUTTER_SERVICE_CLASS)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(flutterIntent)
                    Log.d(TAG, "✅ Flutter service started (foreground)")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Foreground start failed: ${e.message}")
                    try {
                        context.startService(flutterIntent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ All Flutter service start attempts failed: ${e2.message}")
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
}