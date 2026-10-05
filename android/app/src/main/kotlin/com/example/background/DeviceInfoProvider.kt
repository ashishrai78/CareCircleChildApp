package com.example.background

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * 📱 OPTIMIZED DeviceInfoProvider — Lean & Battery-Friendly
 *
 * Optimized for performance and reduced Firestore payload:
 *  - Removed heavy & unnecessary telemetry: storage checks (StatFs), RAM checks (ActivityManager),
 *    root checks (filesystem traversal), wifi SSID / IP / carrier (WifiManager / TelephonyManager),
 *    battery temperature, voltage, and power source.
 *  - Added valuable real-world parent indicators:
 *    * isScreenOn: whether child's device screen is actively ON or locked/sleeping
 *    * isPowerSaveMode: whether device has battery saver mode enabled (causes GPS throttling)
 *    * ringerMode: Silent, Vibrate, or Normal (explains why child isn't answering calls)
 */
class DeviceInfoProvider(private val context: Context) {

    companion object {
        private const val TAG = "DeviceInfoProvider"
    }

    fun getAll(): Map<String, Any?> {
        return mapOf(
            "device" to getDeviceInfo(),
            "battery" to getBatteryInfo(),
            "network" to getNetworkInfo(),
            "timestamp" to System.currentTimeMillis()
        )
    }

    /**
     * 📱 Lean device info + real-time status (Screen, Power Saver, Ringer mode)
     */
    fun getDeviceInfo(): Map<String, Any?> {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            val ringerMode = when (am?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "Normal"
                else -> "Normal"
            }

            val isInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                pm?.isInteractive ?: false
            } else {
                @Suppress("DEPRECATION")
                pm?.isScreenOn ?: false
            }

            val isPowerSaveMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                pm?.isPowerSaveMode ?: false
            } else {
                false
            }

            mapOf(
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
                "osVersion" to Build.VERSION.RELEASE,
                "isScreenOn" to isInteractive,
                "isPowerSaveMode" to isPowerSaveMode,
                "ringerMode" to ringerMode
            )
        } catch (e: Exception) {
            Log.e(TAG, "DeviceInfo error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 🔋 Battery status — level & charging state only (fast & lightweight)
     */
    @SuppressLint("BroadcastReceiverRegistration")
    fun getBatteryInfo(): Map<String, Any?> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, filter) ?: return emptyMap()

            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            mapOf(
                "level" to percent,
                "isCharging" to isCharging
            )
        } catch (e: Exception) {
            Log.e(TAG, "Battery error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 🌐 Network info — type (WIFI/CELLULAR/NONE) & internet reachability (no WifiManager/carrier bloat)
     */
    @SuppressLint("MissingPermission")
    fun getNetworkInfo(): Map<String, Any?> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)

            var type = "NONE"
            if (caps != null) {
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    else -> "OTHER"
                }
            }

            mapOf(
                "type" to type,
                "hasInternet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Storage & Memory info have been deprecated to save CPU, battery, and Firebase bandwidth.
     * Retained as emptyMap to maintain MethodChannel backward compatibility.
     */
    fun getStorageInfo(): Map<String, Any?> = emptyMap()

    fun getMemoryInfo(): Map<String, Any?> = emptyMap()

    /**
     * Permission check for onboarding verification
     */
    fun checkAllPermissions(): Map<String, Boolean> {
        return mapOf(
            "location" to hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            "backgroundLocation" to hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            "microphone" to hasPermission(Manifest.permission.RECORD_AUDIO),
            "notifications" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            } else true,
            "phoneState" to hasPermission(Manifest.permission.READ_PHONE_STATE),
            "usageStats" to UsageStatsProvider(context).hasPermission(),
            "batteryOptimized" to isBatteryOptimized(),
            "overlay" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        )
    }

    // ============ Private helpers ============

    private fun hasPermission(perm: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }
}
