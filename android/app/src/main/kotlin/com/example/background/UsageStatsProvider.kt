package com.example.background

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.content.pm.ApplicationInfo

/**
 * 📊 PRODUCTION UsageStatsProvider — uses UsageStatsManager.queryEvents()
 *
 * Why queryEvents instead of queryUsageStats:
 *  - queryUsageStats returns AGGREGATED buckets — inaccurate (Android caches them)
 *  - queryEvents returns RAW ACTIVITY_RESUMED / ACTIVITY_PAUSED events
 *  - We compute exact foreground time per app from event pairs
 *
 * Output:
 *  {
 *    "dateKey": "05-07-2026",
 *    "totalTime": 12345678,           // ms total foreground
 *    "unlockCount": 12,               // number of screen unlocks
 *    "apps": {
 *      "com.whatsapp": {
 *        "totalTime": 1200000,
 *        "sessions": 8,
 *        "firstUsed": 1234567890,
 *        "lastUsed": 1234567999
 *      },
 *      ...
 *    },
 *    "hourlyBreakdown": { "0": 0, "1": 0, ..., "23": 600000 }
 *  }
 */
class UsageStatsProvider(private val context: Context) {

    companion object {
        private const val TAG = "UsageStatsProvider"
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * Get app usage between [startMs, endMs] using event-based approach
     */
    fun getAppUsage(startMs: Long, endMs: Long): Map<String, Any> {
        if (!hasPermission()) {
            return mapOf("error" to "Usage stats permission not granted")
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val events = usageStatsManager.queryEvents(startMs, endMs)
        val event = UsageEvents.Event()

        // Map: packageName → list of (eventType, timestamp)
        // We track ACTIVITY_RESUMED (1) → ACTIVITY_PAUSED (2) pairs
        val appForegroundTime = mutableMapOf<String, Long>()    // total ms
        val appSessions = mutableMapOf<String, Int>()           // session count
        val appFirstUsed = mutableMapOf<String, Long>()
        val appLastUsed = mutableMapOf<String, Long>()

        // Hourly breakdown
        val hourlyBreakdown = LongArray(24) { 0L }

        // For unlock counting — KEYGUARD or SCREEN_ON events (not directly in UsageEvents,
        // but we can detect app switches as proxy for activity)

        var currentPackage: String? = null
        var currentResumeTime = 0L

        val totalEvents = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val packageName = event.packageName ?: continue
            val timeStamp = event.timeStamp
            val eventType = event.eventType

            when (eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // App came to foreground
                    currentPackage = packageName
                    currentResumeTime = timeStamp

                    // Track first/last used
                    appFirstUsed[packageName] = minOf(
                        appFirstUsed.getOrDefault(packageName, Long.MAX_VALUE),
                        timeStamp
                    )
                    appLastUsed[packageName] = maxOf(
                        appLastUsed.getOrDefault(packageName, 0L),
                        timeStamp
                    )
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    // App went to background — calculate session duration
                    if (currentPackage == packageName && currentResumeTime > 0) {
                        val sessionDuration = timeStamp - currentResumeTime
                        if (sessionDuration > 0 && sessionDuration < 24 * 60 * 60 * 1000L) {
                            appForegroundTime[packageName] =
                                appForegroundTime.getOrDefault(packageName, 0L) + sessionDuration
                            appSessions[packageName] =
                                appSessions.getOrDefault(packageName, 0) + 1

                            // Hourly breakdown
                            val cal = Calendar.getInstance().apply { timeInMillis = currentResumeTime }
                            val startHour = cal.get(Calendar.HOUR_OF_DAY)
                            cal.timeInMillis = timeStamp
                            val endHour = cal.get(Calendar.HOUR_OF_DAY)

                            if (startHour == endHour) {
                                hourlyBreakdown[startHour] += sessionDuration
                            } else {
                                // Spans multiple hours — split
                                val calStart = Calendar.getInstance().apply {
                                    timeInMillis = currentResumeTime
                                    set(Calendar.MINUTE, 59)
                                    set(Calendar.SECOND, 59)
                                    set(Calendar.MILLISECOND, 999)
                                }
                                hourlyBreakdown[startHour] += calStart.timeInMillis - currentResumeTime
                                // For simplicity, attribute remainder to endHour
                                hourlyBreakdown[endHour] += timeStamp - calStart.timeInMillis - 1000
                            }
                        }
                        currentPackage = null
                        currentResumeTime = 0
                    }
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    // Screen turned on
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Screen turned off
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    // Phone unlocked
                }
            }
        }

        // Handle ongoing session (still in foreground at endMs)
        if (currentPackage != null && currentResumeTime > 0) {
            val sessionDuration = endMs - currentResumeTime
            if (sessionDuration > 0) {
                appForegroundTime[currentPackage!!] =
                    appForegroundTime.getOrDefault(currentPackage!!, 0L) + sessionDuration
                appSessions[currentPackage!!] =
                    appSessions.getOrDefault(currentPackage!!, 0) + 1
            }
        }

        // Filter system apps & short sessions
        val filteredApps = mutableMapOf<String, Map<String, Any>>()
        var totalTime = 0L

        appForegroundTime.forEach { (pkg, time) ->
            if (time < 5000) return@forEach  // <5s = noise
            if (pkg.startsWith("com.android") ||
                pkg.startsWith("android") ||
                pkg.startsWith("com.google.android.gms") ||
                pkg.contains("launcher") ||
                pkg.contains("settings") ||
                pkg.contains("inputmethod")
            ) return@forEach

            filteredApps[pkg] = mapOf(
                "totalTime" to time,
                "sessions" to (appSessions[pkg] ?: 0),
                "firstUsed" to (appFirstUsed[pkg] ?: 0L),
                "lastUsed" to (appLastUsed[pkg] ?: 0L)
            )
            totalTime += time
        }

        val dateKey = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(startMs))

        val hourlyMap = mutableMapOf<String, Long>()
        for (i in 0..23) {
            hourlyMap[i.toString()] = hourlyBreakdown[i]
        }

        return mapOf(
            "dateKey" to dateKey,
            "totalTime" to totalTime,
            "apps" to filteredApps,
            "hourlyBreakdown" to hourlyMap,
            "sessionCount" to filteredApps.values.sumOf { it["sessions"] as Int },
            "startMs" to startMs,
            "endMs" to endMs,
            "timestamp" to System.currentTimeMillis()
        )
    }

    /**
     * Convenience: today's usage from midnight to now
     */
    fun getTodayUsage(): Map<String, Any> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return getAppUsage(cal.timeInMillis, System.currentTimeMillis())
    }

    /**
     * Get screen on/off + unlock events (for screen time analytics)
     */
    fun getScreenEvents(startMs: Long, endMs: Long): Map<String, Any> {
        if (!hasPermission()) {
            return mapOf("error" to "Usage stats permission not granted")
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageStatsManager.queryEvents(startMs, endMs)
        val event = UsageEvents.Event()

        var screenOnCount = 0
        var screenOffCount = 0
        var unlockCount = 0
        val screenOnTimestamps = mutableListOf<Long>()
        val screenOffTimestamps = mutableListOf<Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenOnCount++
                    screenOnTimestamps.add(event.timeStamp)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOffCount++
                    screenOffTimestamps.add(event.timeStamp)
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    unlockCount++
                }
            }
        }

        // Compute screen-on duration
        var totalScreenOnMs = 0L
        var pendingOn: Long? = null
        val allEvents = (screenOnTimestamps.map { it to "ON" } +
                screenOffTimestamps.map { it to "OFF" }).sortedBy { it.first }

        for ((ts, type) in allEvents) {
            if (type == "ON") {
                pendingOn = ts
            } else if (type == "OFF" && pendingOn != null) {
                totalScreenOnMs += (ts - pendingOn)
                pendingOn = null
            }
        }
        // If still on at endMs
        if (pendingOn != null) {
            totalScreenOnMs += (endMs - pendingOn)
        }

        return mapOf(
            "screenOnCount" to screenOnCount,
            "screenOffCount" to screenOffCount,
            "unlockCount" to unlockCount,
            "totalScreenOnMs" to totalScreenOnMs,
            "startMs" to startMs,
            "endMs" to endMs
        )
    }

    /**
     * 🎯 Get current foreground/active app
     * Returns map with packageName, appName, lastUsed timestamp
     *
     * Uses queryEvents() with last 10 seconds window to find latest ACTIVITY_RESUMED event.
     * Falls back to 60 seconds window if nothing found.
     *
     * Requires USAGE_STATS permission.
     */
    fun getCurrentActiveApp(): Map<String, Any?> {
        if (!hasPermission()) {
            return mapOf("error" to "Usage stats permission not granted")
        }

        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            val now = System.currentTimeMillis()
            val startTime = now - 10_000  // Last 10 seconds

            val events = usageStatsManager.queryEvents(startTime, now)
            val event = UsageEvents.Event()

            var latestPackage: String? = null
            var latestTimestamp = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp > latestTimestamp) {
                        latestTimestamp = event.timeStamp
                        latestPackage = event.packageName
                    }
                }
            }

            // Fallback: try last 60 seconds
            if (latestPackage == null) {
                val events60 = usageStatsManager.queryEvents(now - 60_000, now)
                val event60 = UsageEvents.Event()
                while (events60.hasNextEvent()) {
                    events60.getNextEvent(event60)
                    if (event60.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        if (event60.timeStamp > latestTimestamp) {
                            latestTimestamp = event60.timeStamp
                            latestPackage = event60.packageName
                        }
                    }
                }
            }

            if (latestPackage == null) {
                return mapOf(
                    "packageName" to null,
                    "appName" to "Idle",
                    "isSystemApp" to false,
                    "timestamp" to 0L,
                    "secondsAgo" to 0L
                )
            }

            // Get friendly app name
            val appName = try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(latestPackage, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                latestPackage
            }

            // Check if it's a system app
            val isSystemApp = try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(latestPackage, 0)
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: Exception) {
                false
            }

            mapOf(
                "packageName" to latestPackage,
                "appName" to appName,
                "isSystemApp" to isSystemApp,
                "timestamp" to latestTimestamp,
                "secondsAgo" to ((now - latestTimestamp) / 1000)
            )
        } catch (e: Exception) {
            Log.e("UsageStatsProvider", "getCurrentActiveApp error: ${e.message}")
            mapOf("error" to e.message)
        }
    }
}
