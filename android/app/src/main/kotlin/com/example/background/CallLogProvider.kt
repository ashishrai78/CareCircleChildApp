package com.example.background

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 📞 CallLogProvider — reads past call history
 *
 * ANDROID VERSION BEHAVIOR:
 *  - Android 9 and below: ✅ Direct access (with READ_CALL_LOG permission)
 *  - Android 10+: ❌ BLOCKED for non-default dialer apps
 *                  (returns empty list, no error)
 *
 * Realme X2 (Android 10+): Will return empty list — use CallDetectorService instead
 * Older devices: Will return full history
 */
class CallLogProvider(private val context: Context) {

    companion object {
        private const val TAG = "CallLogProvider"
    }

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            // Older Android — READ_PHONE_STATE covers it
            context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if device allows direct CallLog access
     * Android 10+ blocks this for non-default dialer apps
     */
    fun isDirectAccessAvailable(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    /**
     * Get call history (limited by maxResults)
     * Returns empty list on Android 10+
     */
    suspend fun getCallHistory(maxResults: Int = 100): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val calls = mutableListOf<Map<String, Any?>>()

        if (!hasPermission()) {
            Log.w(TAG, "READ_CALL_LOG permission not granted")
            return@withContext emptyList()
        }

        if (!isDirectAccessAvailable()) {
            Log.w(TAG, "⚠️ Direct CallLog access blocked on Android 10+ — using CallDetectorService instead")
            return@withContext emptyList()
        }

        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.CACHED_NUMBER_TYPE
            )

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $maxResults"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))

                    val typeLabel = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        else -> "unknown"
                    }

                    calls.add(mapOf(
                        "id" to cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)).toString(),
                        "phoneNumber" to number,
                        "contactName" to name,
                        "timestamp" to date,
                        "duration" to duration,
                        "type" to typeLabel,
                        "source" to "call_log_provider"
                    ))
                }
            }

            Log.d(TAG, "✅ Loaded ${calls.size} call logs (direct access)")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException reading call log: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to read call log: ${e.message}")
        }

        calls
    }

    /**
     * Get call count by type (today)
     */
    suspend fun getTodayCallStats(): Map<String, Int> = withContext(Dispatchers.IO) {
        val stats = mutableMapOf(
            "incoming" to 0,
            "outgoing" to 0,
            "missed" to 0,
            "total" to 0
        )

        if (!hasPermission() || !isDirectAccessAvailable()) {
            return@withContext stats
        }

        try {
            val startOfDay = getStartOfDayMillis()
            val selection = "${CallLog.Calls.DATE} >= ?"
            val selectionArgs = arrayOf(startOfDay.toString())

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.TYPE),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val typeInt = cursor.getInt(0)
                    val typeLabel = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> null
                    }
                    typeLabel?.let {
                        stats[it] = (stats[it] ?: 0) + 1
                    }
                    stats["total"] = (stats["total"] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTodayCallStats failed: ${e.message}")
        }

        stats
    }

    private fun getStartOfDayMillis(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}