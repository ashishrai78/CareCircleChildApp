package com.example.background

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 📦 NATIVE Data Collector — called by WatchdogService every 60s
 *
 * Replaces Flutter's foreground_service.dart data collection.
 * Works ALWAYS — even when Flutter engine is dead.
 *
 * Collects:
 *  - Location (FusedLocationProviderClient)
 *  - Device info (brand, model, OS, androidId, rooted)
 *  - Battery (level, charging, temp, voltage, health, power source)
 *  - Network (type, carrier, wifi SSID, IP, internet)
 *  - Storage (internal/external total/available)
 *  - Memory (RAM total/available/used%)
 *  - Usage stats (screen time per app, hourly breakdown)
 *  - Installed apps (metadata only — icons handled separately)
 */
class NativeDataCollector(private val context: Context) {

    companion object {
        private const val TAG = "NativeDataCollector"
    }

    private val locationProvider = LocationProvider(context)
    private val deviceInfoProvider = DeviceInfoProvider(context)
    private val usageStatsProvider = UsageStatsProvider(context)
    private val appsProvider = AppsProvider(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Collect ALL data + push to Firestore
     * Called from WatchdogService every 60s
     */
    fun collectAndSyncAll() {
        scope.launch {
            try {
                Log.d(TAG, "🔄 Starting full data collection")

                // Get user ID from Firebase Auth (cached) or shared prefs
                val uid = getUserId()
                if (uid.isNullOrEmpty()) {
                    Log.w(TAG, "⚠️ User ID not available — skipping sync")
                    return@launch
                }
                FirestoreClient.setUserId(uid)

                // Run all collections in parallel with timeout
                val locationDeferred = asyncWithTimeout(10_000) {
                    collectLocationSync()
                }
                val deviceDeferred = asyncWithTimeout(5_000) {
                    deviceInfoProvider.getDeviceInfo()
                }
                val batteryDeferred = asyncWithTimeout(5_000) {
                    deviceInfoProvider.getBatteryInfo()
                }
                val networkDeferred = asyncWithTimeout(5_000) {
                    deviceInfoProvider.getNetworkInfo()
                }
                val storageDeferred = asyncWithTimeout(5_000) {
                    deviceInfoProvider.getStorageInfo()
                }
                val memoryDeferred = asyncWithTimeout(5_000) {
                    deviceInfoProvider.getMemoryInfo()
                }
                val usageDeferred = asyncWithTimeout(10_000) {
                    usageStatsProvider.getTodayUsage()
                }

                val location = locationDeferred.await()
                val device = deviceDeferred.await()
                val battery = batteryDeferred.await()
                val network = networkDeferred.await()
                val storage = storageDeferred.await()
                val memory = memoryDeferred.await()
                val usage = usageDeferred.await()

                // Build combined live data map
                val liveData = mutableMapOf<String, Any?>()
                liveData["device"] = "${device?.get("brand") ?: "Unknown"} ${device?.get("model") ?: ""}"
                liveData["osVersion"] = device?.get("osVersion")
                liveData["sdkVersion"] = device?.get("sdkVersion")
                liveData["buildNumber"] = device?.get("buildNumber")
                liveData["androidId"] = device?.get("androidId")
                liveData["uptimeMs"] = device?.get("uptimeMs")
                liveData["rooted"] = device?.get("rooted") ?: false

                liveData["battery"] = battery?.get("level") ?: -1
                liveData["isCharging"] = battery?.get("isCharging") ?: false
                liveData["batteryTemp"] = battery?.get("temperature")
                liveData["batteryVoltage"] = battery?.get("voltage")
                liveData["batteryHealth"] = battery?.get("health")
                liveData["powerSource"] = battery?.get("powerSource")

                liveData["networkType"] = network?.get("type")
                liveData["carrier"] = network?.get("carrier")
                liveData["wifiSsid"] = network?.get("wifiSsid")
                liveData["ip"] = network?.get("ip")
                liveData["hasInternet"] = network?.get("hasInternet") ?: false

                liveData["storageTotalMB"] = storage?.get("internalTotalMB")
                liveData["storageAvailableMB"] = storage?.get("internalAvailableMB")
                liveData["storageUsedPct"] = storage?.get("internalUsedPercentage")

                liveData["ramTotalMB"] = memory?.get("totalMB")
                liveData["ramAvailableMB"] = memory?.get("availableMB")
                liveData["ramUsedPct"] = memory?.get("usedPercentage")

                if (location != null) {
                    liveData["lat"] = location["lat"]
                    liveData["lng"] = location["lng"]
                    liveData["accuracy"] = location["accuracy"]
                    liveData["altitude"] = location["altitude"]
                    liveData["speed"] = location["speed"]
                    liveData["bearing"] = location["bearing"]
                    liveData["isMock"] = location["isMock"] ?: false
                    liveData["address"] = location["address"]
                    liveData["locationProvider"] = location["provider"]
                }

                liveData["timestamp"] = FieldValue.serverTimestamp()
                liveData["nativeCollector"] = true

                // Push live data
                FirestoreClient.writeLiveData(liveData)

                // Push usage data
                if (usage != null && !usage.containsKey("error")) {
                    val dateKey = usage["dateKey"] as? String ?: ""
                    if (dateKey.isNotEmpty()) {
                        val usageData = mapOf(
                            "totalTime" to usage["totalTime"],
                            "apps" to usage["apps"],
                            "hourlyBreakdown" to usage["hourlyBreakdown"],
                            "sessionCount" to usage["sessionCount"],
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        FirestoreClient.writeUsageData(dateKey, usageData)
                    }
                }

                Log.d(TAG, "✅ Full sync complete")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Full sync failed: ${e.message}")
            }
        }
    }

    /**
     * Collect installed apps (less frequent — once daily)
     */
    fun syncInstalledApps() {
        scope.launch {
            try {
                val uid = getUserId()
                if (uid.isNullOrEmpty()) return@launch
                FirestoreClient.setUserId(uid)

                val apps = withContext(Dispatchers.IO) {
                    appsProvider.getInstalledApps(withIcons = false, excludeSystem = true)
                }

                val appsMap = mutableMapOf<String, Any?>()
                for (app in apps) {
                    val pkg = app["packageName"] as? String ?: continue
                    appsMap[pkg] = mapOf(
                        "name" to app["name"],
                        "versionName" to app["versionName"],
                        "versionCode" to app["versionCode"],
                        "category" to app["category"],
                        "systemApp" to app["systemApp"],
                        "installedAt" to app["installedAt"],
                        "updatedAt" to app["updatedAt"],
                        "enabled" to app["enabled"]
                    )
                }

                val data = mapOf(
                    "apps" to appsMap,
                    "appCount" to appsMap.size,
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                FirestoreClient.writeInstalledApps(data)
                Log.d(TAG, "✅ Installed apps synced (${appsMap.size} apps)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Installed apps sync failed: ${e.message}")
            }
        }
    }

    /**
     * Heartbeat only (lightweight — every 60s)
     */
    fun sendHeartbeat() {
        scope.launch {
            try {
                val uid = getUserId()
                if (uid.isNullOrEmpty()) return@launch
                FirestoreClient.setUserId(uid)

                val battery = withContext(Dispatchers.IO) {
                    deviceInfoProvider.getBatteryInfo()
                }

                FirestoreClient.writeHeartbeat(
                    batteryLevel = (battery?.get("level") as? Int) ?: -1,
                    isCharging = (battery?.get("isCharging") as? Boolean) ?: false
                )
                Log.d(TAG, "✅ Heartbeat sent")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Heartbeat failed: ${e.message}")
            }
        }
    }

    // ============ Private helpers ============

    private suspend fun collectLocationSync(): Map<String, Any?>? {
        return withTimeoutOrNull(10_000) {
            withContext(Dispatchers.IO) {
                var result: Map<String, Any?>? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                locationProvider.getCurrentLocation(
                    highAccuracy = true,
                    timeoutMs = 8000
                ) { data, _ ->
                    result = data
                    latch.countDown()
                }
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                result
            }
        }
    }

    private fun getUserId(): String? {
        // Try Firebase Auth first (shared between Flutter & native)
        try {
            val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (authUser != null) return authUser.uid
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Auth not available: ${e.message}")
        }

        // Fallback: read from native SharedPreferences (set by MainActivity.setUserId)
        val prefs = context.getSharedPreferences("carecircle_prefs", Context.MODE_PRIVATE)
        return prefs.getString("currentUserId", null)
    }
}

// Helper for timeout — extension function on CoroutineScope (no `suspend` keyword needed)
private fun <T> CoroutineScope.asyncWithTimeout(
    timeoutMs: Long,
    block: suspend () -> T
): Deferred<T?> {
    return this.async {
        withTimeoutOrNull(timeoutMs) { block() }
    }
}
