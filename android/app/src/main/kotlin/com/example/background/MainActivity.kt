package com.example.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 🛡️ PRODUCTION MainActivity
 *
 * Method Channels registered:
 *  1. watchdog_channel    — start/stop watchdog, check service status
 *  2. location_channel    — get fused location + accuracy + speed + altitude
 *  3. usage_channel       — get screen time via UsageStatsManager.queryEvents
 *  4. apps_channel        — get installed apps + icons (base64)
 *  5. device_channel      — get device info, battery, network, RAM, storage
 *  6. permissions_channel — request battery optimization, usage stats, overlay
 *  7. mic_channel         — start/stop native audio recording (WebRTC fallback)
 */
class MainActivity : FlutterActivity() {

    private val TAG = "MAIN_ACTIVITY"

    // Channels
    private lateinit var watchdogChannel: MethodChannel
    private lateinit var locationChannel: MethodChannel
    private lateinit var usageChannel: MethodChannel
    private lateinit var appsChannel: MethodChannel
    private lateinit var deviceChannel: MethodChannel
    private lateinit var permissionsChannel: MethodChannel

    // Providers (lazy init)
    private val locationProvider by lazy { LocationProvider(this) }
    private val usageStatsProvider by lazy { UsageStatsProvider(this) }
    private val appsProvider by lazy { AppsProvider(this) }
    private val deviceInfoProvider by lazy { DeviceInfoProvider(this) }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        setupWatchdogChannel(flutterEngine)
        setupLocationChannel(flutterEngine)
        setupUsageChannel(flutterEngine)
        setupAppsChannel(flutterEngine)
        setupDeviceChannel(flutterEngine)
        setupPermissionsChannel(flutterEngine)
    }

    // ============ WATCHDOG CHANNEL ============
    private fun setupWatchdogChannel(flutterEngine: FlutterEngine) {
        watchdogChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "watchdog_channel"
        )

        watchdogChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startWatchdog" -> {
                    WatchdogService.start(this)
                    result.success(true)
                }
                "stopWatchdog" -> {
                    stopService(Intent(this, WatchdogService::class.java))
                    result.success(true)
                }
                "setUserId" -> {
                    // 🔥 CRITICAL: Flutter passes UID after login — native needs this for Firestore
                    val uid = call.argument<String>("uid")
                    FirestoreClient.setUserId(uid)
                    // Persist in SharedPreferences for service restarts
                    getSharedPreferences("carecircle_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("currentUserId", uid)
                        .apply()
                    Log.d(TAG, "✅ User ID set: $uid")
                    result.success(true)
                }
                "isAccessibilityEnabled" -> {
                    result.success(AccessibilityWatchdogService.isEnabled(this))
                }
                "openAccessibilitySettings" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    result.success(true)
                }
                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }
                "requestIgnoreBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }
                // ============ OEM AUTOSTART METHODS ============
                "openAutoStartSettings" -> {
                    val success = AutoStartHelper.openAutoStartSettings(this)
                    result.success(success)
                }
                "openBatteryOptimizationSettings" -> {
                    val success = AutoStartHelper.openBatteryOptimizationSettings(this)
                    result.success(success)
                }
                "requestIgnoreBatteryOptimizationDirect" -> {
                    val success = AutoStartHelper.requestIgnoreBatteryOptimization(this)
                    result.success(success)
                }
                "needsAutoStartPermission" -> {
                    result.success(AutoStartHelper.needsAutoStartPermission())
                }
                "getOEMFamily" -> {
                    result.success(AutoStartHelper.getOEMFamily().name)
                }
                "getOEMDisplayName" -> {
                    result.success(AutoStartHelper.getOEMDisplayName())
                }
                "getManufacturer" -> {
                    result.success(AutoStartHelper.getManufacturer())
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ LOCATION CHANNEL ============
    private fun setupLocationChannel(flutterEngine: FlutterEngine) {
        locationChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "location_channel"
        )

        locationChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getLocation" -> {
                    locationProvider.getCurrentLocation(
                        highAccuracy = call.argument<Boolean>("highAccuracy") ?: true,
                        timeoutMs = call.argument<Int>("timeoutMs") ?: 10000
                    ) { locationData, error ->
                        if (error != null) {
                            result.error("LOCATION_ERROR", error, null)
                        } else {
                            result.success(locationData)
                        }
                    }
                }
                "getLastKnownLocation" -> {
                    locationProvider.getLastKnownLocation { locationData, error ->
                        if (error != null) {
                            result.error("LOCATION_ERROR", error, null)
                        } else {
                            result.success(locationData)
                        }
                    }
                }
                "startLocationUpdates" -> {
                    locationProvider.startLocationUpdates(
                        intervalMs = call.argument<Long>("intervalMs") ?: 60_000L
                    ) { locationData ->
                        locationChannel.invokeMethod("onLocationUpdate", locationData)
                    }
                    result.success(true)
                }
                "stopLocationUpdates" -> {
                    locationProvider.stopLocationUpdates()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ USAGE STATS CHANNEL ============
    private fun setupUsageChannel(flutterEngine: FlutterEngine) {
        usageChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "usage_channel"
        )

        usageChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "isUsageStatsEnabled" -> {
                    result.success(usageStatsProvider.hasPermission())
                }
                "openUsageStatsSettings" -> {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    result.success(true)
                }
                "getAppUsage" -> {
                    val startMs = call.argument<Long>("startMs") ?: 0L
                    val endMs = call.argument<Long>("endMs") ?: System.currentTimeMillis()
                    result.success(usageStatsProvider.getAppUsage(startMs, endMs))
                }
                "getTodayUsage" -> {
                    result.success(usageStatsProvider.getTodayUsage())
                }
                "getScreenEvents" -> {
                    val startMs = call.argument<Long>("startMs") ?: 0L
                    val endMs = call.argument<Long>("endMs") ?: System.currentTimeMillis()
                    result.success(usageStatsProvider.getScreenEvents(startMs, endMs))
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ APPS CHANNEL ============
    private fun setupAppsChannel(flutterEngine: FlutterEngine) {
        appsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "apps_channel"
        )

        appsChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getInstalledApps" -> {
                    val withIcons = call.argument<Boolean>("withIcons") ?: false
                    val excludeSystem = call.argument<Boolean>("excludeSystem") ?: true
                    result.success(appsProvider.getInstalledApps(withIcons, excludeSystem))
                }
                "getAppIcon" -> {
                    val pkg = call.argument<String>("packageName")
                    if (pkg.isNullOrEmpty()) {
                        result.error("INVALID", "packageName required", null)
                    } else {
                        result.success(appsProvider.getAppIconBase64(pkg))
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ DEVICE INFO CHANNEL ============
    private fun setupDeviceChannel(flutterEngine: FlutterEngine) {
        deviceChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "device_channel"
        )

        deviceChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getDeviceInfo" -> {
                    result.success(deviceInfoProvider.getDeviceInfo())
                }
                "getBatteryInfo" -> {
                    result.success(deviceInfoProvider.getBatteryInfo())
                }
                "getNetworkInfo" -> {
                    result.success(deviceInfoProvider.getNetworkInfo())
                }
                "getStorageInfo" -> {
                    result.success(deviceInfoProvider.getStorageInfo())
                }
                "getMemoryInfo" -> {
                    result.success(deviceInfoProvider.getMemoryInfo())
                }
                "getAll" -> {
                    result.success(deviceInfoProvider.getAll())
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ PERMISSIONS CHANNEL ============
    private fun setupPermissionsChannel(flutterEngine: FlutterEngine) {
        permissionsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "permissions_channel"
        )

        permissionsChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "isAllPermissionsGranted" -> {
                    result.success(deviceInfoProvider.checkAllPermissions())
                }
                "openAppSettings" -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    result.success(true)
                }
                "openOverlaySettings" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                    result.success(true)
                }
                "hasOverlayPermission" -> {
                    result.success(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Settings.canDrawOverlays(this)
                        } else true
                    )
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ Battery Optimization Helpers ============
    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Battery opt request failed: ${e.message}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Auto-start watchdog on app launch
        WatchdogService.start(this)
    }
}
