package com.example.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * 📱 PRODUCTION DeviceInfoProvider — collects everything in one shot
 *
 * Returns:
 *  - Device: brand, model, manufacturer, os version, build number, androidId, rooted
 *  - Battery: level, charging, temp, voltage, power source
 *  - Network: type (WIFI/CELLULAR/NONE), carrier, wifi SSID, IP
 *  - Storage: total/available internal + external
 *  - Memory: total/available RAM
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
            "storage" to getStorageInfo(),
            "memory" to getMemoryInfo(),
            "timestamp" to System.currentTimeMillis()
        )
    }

    fun getDeviceInfo(): Map<String, Any?> {
        return try {
            mapOf(
                "brand" to Build.BRAND,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "product" to Build.PRODUCT,
                "osVersion" to Build.VERSION.RELEASE,
                "sdkVersion" to Build.VERSION.SDK_INT,
                "buildNumber" to Build.DISPLAY,
                "fingerprint" to Build.FINGERPRINT,
                "androidId" to Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ),
                "uptimeMs" to SystemClock.elapsedRealtime(),
                "bootCount" to getBootCount(),
                "rooted" to isRooted()
            )
        } catch (e: Exception) {
            Log.e(TAG, "DeviceInfo error: ${e.message}")
            emptyMap()
        }
    }

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

            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val powerSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
                else -> "NONE"
            }

            val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
            val voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) / 1000.0
            val health = when (battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
                else -> "UNKNOWN"
            }

            mapOf(
                "level" to percent,
                "isCharging" to isCharging,
                "powerSource" to powerSource,
                "temperature" to temp,
                "voltage" to voltage,
                "health" to health
            )
        } catch (e: Exception) {
            Log.e(TAG, "Battery error: ${e.message}")
            emptyMap()
        }
    }

    @SuppressLint("MissingPermission")
    fun getNetworkInfo(): Map<String, Any?> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)

            var type = "NONE"
            var carrier = ""
            var wifiSsid = ""
            var ip = ""

            if (caps != null) {
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    else -> "OTHER"
                }
            }

            if (type == "WIFI") {
                try {
                    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wm.connectionInfo
                    wifiSsid = info.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
                    ip = intToIp(info.ipAddress)
                } catch (_: Exception) {}
            }

            if (type == "CELLULAR") {
                try {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_PHONE_STATE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        carrier = tm.networkOperatorName ?: ""
                    }
                } catch (_: Exception) {}
            }

            mapOf(
                "type" to type,
                "carrier" to carrier,
                "wifiSsid" to wifiSsid,
                "ip" to ip,
                "hasInternet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            emptyMap()
        }
    }

    fun getStorageInfo(): Map<String, Any?> {
        return try {
            val internal = StatFs(Environment.getDataDirectory().path)
            val external = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                StatFs(Environment.getExternalStorageDirectory().path)
            } else null

            val internalTotal = internal.totalBytes / (1024 * 1024)
            val internalAvail = internal.availableBytes / (1024 * 1024)

            val result = mutableMapOf<String, Any?>(
                "internalTotalMB" to internalTotal,
                "internalAvailableMB" to internalAvail,
                "internalUsedPercentage" to ((internalTotal - internalAvail) * 100 / internalTotal.coerceAtLeast(1))
            )

            if (external != null) {
                result["externalTotalMB"] = external.totalBytes / (1024 * 1024)
                result["externalAvailableMB"] = external.availableBytes / (1024 * 1024)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Storage error: ${e.message}")
            emptyMap()
        }
    }

    fun getMemoryInfo(): Map<String, Any?> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)

            mapOf(
                "totalMB" to (memInfo.totalMem / (1024 * 1024)),
                "availableMB" to (memInfo.availMem / (1024 * 1024)),
                "lowMemory" to memInfo.lowMemory,
                "thresholdMB" to (memInfo.threshold / (1024 * 1024)),
                "usedPercentage" to ((memInfo.totalMem - memInfo.availMem) * 100 / memInfo.totalMem.coerceAtLeast(1))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Memory error: ${e.message}")
            emptyMap()
        }
    }

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
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    private fun getBootCount(): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, "boot_count", 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun isRooted(): Boolean {
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        return paths.any { java.io.File(it).exists() }
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}
