package com.example.background

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 📞 CallLogProvider — reads past call history from CallLog.Calls
 *
 * Works on ALL Android versions (Android 7.0 to Android 15+)
 * when android.permission.READ_CALL_LOG is granted.
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
            context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if direct CallLog access is available (permission is granted)
     */
    fun isDirectAccessAvailable(): Boolean {
        return hasPermission()
    }

    /**
     * Get recent call history (limited by maxResults)
     */
    suspend fun getCallHistory(maxResults: Int = 100): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(TAG, "READ_CALL_LOG permission not granted")
            return@withContext emptyList()
        }

        queryCallLogs(selection = null, selectionArgs = null, limit = maxResults)
    }

    /**
     * Get calls since a specific timestamp (e.g. past 7 days)
     */
    suspend fun getCallHistorySince(sinceTimeMs: Long, maxResults: Int = 500): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(TAG, "READ_CALL_LOG permission not granted")
            return@withContext emptyList()
        }

        val selection = "${CallLog.Calls.DATE} >= ?"
        val selectionArgs = arrayOf(sinceTimeMs.toString())
        queryCallLogs(selection, selectionArgs, limit = maxResults)
    }

    /**
     * Internal helper to query CallLog.Calls
     */
    private fun queryCallLogs(
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int
    ): List<Map<String, Any?>> {
        val calls = mutableListOf<Map<String, Any?>>()

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

            val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT $limit"

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rawNumber = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    var name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))

                    // If cached name is empty, attempt to resolve via PhoneLookup
                    if (name.isNullOrBlank() && rawNumber != "Unknown") {
                        name = resolveContactName(rawNumber)
                    }

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
                        "phoneNumber" to rawNumber,
                        "contactName" to name,
                        "timestamp" to date,
                        "duration" to duration,
                        "type" to typeLabel,
                        "source" to "call_log_provider"
                    ))
                }
            }

            Log.d(TAG, "✅ Loaded ${calls.size} call logs from system")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException reading call log: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to read call log: ${e.message}")
        }

        return calls
    }

    /**
     * Resolves contact name from ContactsContract.PhoneLookup
     */
    private fun resolveContactName(phoneNumber: String): String? {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (_: Exception) {
            null
        }
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

        if (!hasPermission()) {
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