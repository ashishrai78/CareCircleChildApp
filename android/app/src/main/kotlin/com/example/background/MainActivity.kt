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
 * 🛡️ PRODUCTION MainActivity (v3 — Option B architecture)
 *
 * Changes vs v2:
 *  1. Calls CareCircleForegroundService.start() (was WatchdogService)
 *  2. Calls CareCircleWorkScheduler.scheduleAll() on app launch
 *  3. onResume() checks CareCircleForegroundService.isRunning()
 */
class MainActivity : FlutterActivity() {

    private val TAG = "MAIN_ACTIVITY"

    private lateinit var watchdogChannel: MethodChannel
    private lateinit var locationChannel: MethodChannel
    private lateinit var usageChannel: MethodChannel
    private lateinit var appsChannel: MethodChannel
    private lateinit var deviceChannel: MethodChannel
    private lateinit var permissionsChannel: MethodChannel
    private lateinit var accessibilityEventChannel: EventChannel
    private lateinit var deviceAdminChannel: MethodChannel
    private lateinit var appBlockerChannel: MethodChannel
    private lateinit var contactsChannel: MethodChannel
    private lateinit var callLogChannel: MethodChannel

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val locationProvider by lazy { LocationProvider(this) }
    private val usageStatsProvider by lazy { UsageStatsProvider(this) }
    private val appsProvider by lazy { AppsProvider(this) }
    private val deviceInfoProvider by lazy { DeviceInfoProvider(this) }

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
        setupDeviceAdminChannel(flutterEngine)
        setupAppBlockerChannel(flutterEngine)
        setupContactsChannel(flutterEngine)
        setupCallLogChannel(flutterEngine)

        registerAccessibilityRevokedReceiver()

        // 🔥 Schedule all WorkManager workers
        CareCircleWorkScheduler.scheduleAll(this)
    }

    // ============ WATCHDOG CHANNEL (now controls ForegroundService) ============
    private fun setupWatchdogChannel(flutterEngine: FlutterEngine) {
        watchdogChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "watchdog_channel"
        )

        watchdogChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startWatchdog" -> {
                    // 🔥 Now starts CareCircleForegroundService instead of WatchdogService
                    CareCircleForegroundService.start(this)
                    result.success(true)
                }
                "stopWatchdog" -> {
                    stopService(Intent(this, CareCircleForegroundService::class.java))
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
                    result.success(!isIgnoringBatteryOptimizations())
                }
                "requestIgnoreBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }
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

    // ============ DEVICE ADMIN CHANNEL ============
    private fun setupDeviceAdminChannel(flutterEngine: FlutterEngine) {
        deviceAdminChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "device_admin_channel"
        )

        deviceAdminChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "isDeviceAdminEnabled" -> {
                    result.success(CareCircleDeviceAdminReceiver.isEnabled(this))
                }
                "openDeviceAdminSettings" -> {
                    val success = CareCircleDeviceAdminReceiver.openEnableScreen(this)
                    result.success(success)
                }
                "disableDeviceAdmin" -> {
                    val success = CareCircleDeviceAdminReceiver.disable(this)
                    result.success(success)
                }
                "lockDeviceNow" -> {
                    val success = try {
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                                as android.app.admin.DevicePolicyManager
                        if (CareCircleDeviceAdminReceiver.isEnabled(this)) {
                            dpm.lockNow()
                            true
                        } else false
                    } catch (e: Exception) {
                        Log.e(TAG, "lockDeviceNow failed: ${e.message}")
                        false
                    }
                    result.success(success)
                }
                "disableCamera" -> {
                    val disable = call.argument<Boolean>("disable") ?: true
                    val success = try {
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                                as android.app.admin.DevicePolicyManager
                        if (CareCircleDeviceAdminReceiver.isEnabled(this)) {
                            dpm.setCameraDisabled(
                                CareCircleDeviceAdminReceiver.getComponentName(this),
                                disable
                            )
                            true
                        } else false
                    } catch (e: Exception) {
                        Log.e(TAG, "disableCamera failed: ${e.message}")
                        false
                    }
                    result.success(success)
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ 🔥 NEW: APP BLOCKER CHANNEL ============
    private fun setupAppBlockerChannel(flutterEngine: FlutterEngine) {
        appBlockerChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "app_blocker_channel"
        )

        appBlockerChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getBlockedApps" -> {
                    val apps = AccessibilityWatchdogService.getBlockedApps(this)
                    result.success(apps.toList())
                }
                "updateBlockedApps" -> {
                    val apps = call.argument<List<String>>("apps") ?: emptyList()
                    AccessibilityWatchdogService.updateBlockedApps(this, apps.toSet())
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
                else -> result.notImplemented()
            }
        }
    }

    // ============ Accessibility Revoked Receiver ============
    private fun registerAccessibilityRevokedReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.w(TAG, "⚠️ Accessibility revoked broadcast received")
                accessibilitySink?.success("ACCESSIBILITY_REVOKED")
            }
        }

        val filter = android.content.IntentFilter("com.example.background.ACCESSIBILITY_REVOKED")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    // ============ CONTACTS CHANNEL ============
    private fun setupContactsChannel(flutterEngine: FlutterEngine) {
        contactsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "contacts_channel"
        )

        val contactsProvider = ContactsProvider(this)

        contactsChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "hasContactsPermission" -> {
                    result.success(contactsProvider.hasPermission())
                }
                "openContactsSettings" -> {
                    val success = tryStartActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    result.success(success)
                }
                "getAllContacts" -> {
                    val includePhotos = call.argument<Boolean>("includePhotos") ?: true
                    ioScope.launch {
                        val contacts = contactsProvider.getAllContacts(includePhotos)
                        withContext(Dispatchers.Main) { result.success(contacts) }
                    }
                }
                "getContactCount" -> {
                    ioScope.launch {
                        val count = contactsProvider.getContactCount()
                        withContext(Dispatchers.Main) { result.success(count) }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    // ============ CALL LOG CHANNEL ============
    private fun setupCallLogChannel(flutterEngine: FlutterEngine) {
        callLogChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "call_log_channel"
        )

        val callLogProvider = CallLogProvider(this)

        callLogChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "hasCallLogPermission" -> {
                    result.success(callLogProvider.hasPermission())
                }
                "isDirectAccessAvailable" -> {
                    result.success(callLogProvider.isDirectAccessAvailable())
                }
                "openCallLogSettings" -> {
                    val success = tryStartActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    result.success(success)
                }
                "getCallHistory" -> {
                    val max = call.argument<Int>("maxResults") ?: 100
                    ioScope.launch {
                        val history = callLogProvider.getCallHistory(max)
                        withContext(Dispatchers.Main) { result.success(history) }
                    }
                }
                "getTodayCallStats" -> {
                    ioScope.launch {
                        val stats = callLogProvider.getTodayCallStats()
                        withContext(Dispatchers.Main) { result.success(stats) }
                    }
                }
                "startCallDetection" -> {
                    CallDetectorService.start(this)
                    result.success(true)
                }
                "stopCallDetection" -> {
                    CallDetectorService.stop(this)
                    result.success(true)
                }
                "setCallMonitoringEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    getSharedPreferences("carecircle_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("call_monitoring_enabled", enabled)
                        .apply()

                    if (enabled) {
                        CallDetectorService.start(this)
                    } else {
                        CallDetectorService.stop(this)
                    }

                    Log.d(TAG, "📞 Call monitoring ${if (enabled) "enabled" else "disabled"}")
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }




    // ============ Helpers ============
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
        // 🔥 Start master foreground service
        CareCircleForegroundService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // 🔥 Re-verify service alive on every app resume
        if (!CareCircleForegroundService.isRunning(this)) {
            Log.d(TAG, "🔄 ForegroundService not running — restarting on resume")
            CareCircleForegroundService.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }
}