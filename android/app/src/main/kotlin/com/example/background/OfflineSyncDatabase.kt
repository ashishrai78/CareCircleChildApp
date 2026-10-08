package com.example.background

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 💾 OfflineSyncDatabase — Local SQLite database for caching notifications and call logs
 * when the device has NO internet connectivity.
 *
 * Data is preserved across app kills, reboots, and network outages, and will be synced
 * to Firebase Firestore as soon as internet connectivity is restored.
 */
class OfflineSyncDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "OfflineSyncDb"
        private const val DATABASE_NAME = "carecircle_offline_sync.db"
        private const val DATABASE_VERSION = 1

        // Table: offline_notifications
        private const val TABLE_NOTIFICATIONS = "offline_notifications"
        private const val COL_NOTIF_ID = "id"
        private const val COL_NOTIF_UID = "uid"
        private const val COL_NOTIF_PACKAGE = "package_name"
        private const val COL_NOTIF_APP_NAME = "app_name"
        private const val COL_NOTIF_TITLE = "title"
        private const val COL_NOTIF_TEXT = "text"
        private const val COL_NOTIF_SUB_TEXT = "sub_text"
        private const val COL_NOTIF_INFO_TEXT = "info_text"
        private const val COL_NOTIF_CATEGORY = "category"
        private const val COL_NOTIF_PRIORITY = "priority"
        private const val COL_NOTIF_POSTED_AT = "posted_at"
        private const val COL_NOTIF_DATE_KEY = "date_key"
        private const val COL_NOTIF_CREATED_AT = "created_at"

        // Table: offline_call_logs
        private const val TABLE_CALL_LOGS = "offline_call_logs"
        private const val COL_CALL_ID = "id"
        private const val COL_CALL_UID = "uid"
        private const val COL_CALL_DOC_ID = "doc_id"
        private const val COL_CALL_TYPE = "type"
        private const val COL_CALL_NUMBER = "phone_number"
        private const val COL_CALL_NAME = "contact_name"
        private const val COL_CALL_DURATION = "duration"
        private const val COL_CALL_DEVICE_TIME = "device_time"
        private const val COL_CALL_DETECTED_BY = "detected_by"
        private const val COL_CALL_SOURCE = "source"
        private const val COL_CALL_CREATED_AT = "created_at"

        @Volatile
        private var instance: OfflineSyncDatabase? = null

        fun getInstance(context: Context): OfflineSyncDatabase {
            return instance ?: synchronized(this) {
                instance ?: OfflineSyncDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    data class OfflineNotification(
        val id: Long,
        val uid: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val subText: String?,
        val infoText: String?,
        val category: String,
        val priority: String,
        val postedAt: Long,
        val dateKey: String,
        val createdAt: Long
    )

    data class OfflineCallLog(
        val id: Long,
        val uid: String,
        val docId: String,
        val type: String,
        val phoneNumber: String,
        val contactName: String?,
        val duration: Long,
        val deviceTime: Long,
        val detectedBy: String,
        val source: String,
        val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        val createNotifTable = """
            CREATE TABLE $TABLE_NOTIFICATIONS (
                $COL_NOTIF_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NOTIF_UID TEXT NOT NULL,
                $COL_NOTIF_PACKAGE TEXT NOT NULL,
                $COL_NOTIF_APP_NAME TEXT,
                $COL_NOTIF_TITLE TEXT,
                $COL_NOTIF_TEXT TEXT,
                $COL_NOTIF_SUB_TEXT TEXT,
                $COL_NOTIF_INFO_TEXT TEXT,
                $COL_NOTIF_CATEGORY TEXT,
                $COL_NOTIF_PRIORITY TEXT,
                $COL_NOTIF_POSTED_AT INTEGER NOT NULL,
                $COL_NOTIF_DATE_KEY TEXT NOT NULL,
                $COL_NOTIF_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        val createCallTable = """
            CREATE TABLE $TABLE_CALL_LOGS (
                $COL_CALL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CALL_UID TEXT NOT NULL,
                $COL_CALL_DOC_ID TEXT UNIQUE NOT NULL,
                $COL_CALL_TYPE TEXT NOT NULL,
                $COL_CALL_NUMBER TEXT NOT NULL,
                $COL_CALL_NAME TEXT,
                $COL_CALL_DURATION INTEGER NOT NULL,
                $COL_CALL_DEVICE_TIME INTEGER NOT NULL,
                $COL_CALL_DETECTED_BY TEXT,
                $COL_CALL_SOURCE TEXT,
                $COL_CALL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        db.execSQL(createNotifTable)
        db.execSQL(createCallTable)
        Log.d(TAG, "Offline sync database tables created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Simple upgrade path: preserve if needed
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTIFICATIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CALL_LOGS")
        onCreate(db)
    }

    // ==========================================
    // NOTIFICATIONS
    // ==========================================

    fun saveNotification(uid: String, data: Map<String, Any?>): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_NOTIF_UID, uid)
                put(COL_NOTIF_PACKAGE, data["packageName"] as? String ?: "")
                put(COL_NOTIF_APP_NAME, data["appName"] as? String ?: "")
                put(COL_NOTIF_TITLE, data["title"] as? String ?: "")
                put(COL_NOTIF_TEXT, data["text"] as? String ?: "")
                put(COL_NOTIF_SUB_TEXT, data["subText"] as? String)
                put(COL_NOTIF_INFO_TEXT, data["infoText"] as? String)
                put(COL_NOTIF_CATEGORY, data["category"] as? String ?: "unknown")
                put(COL_NOTIF_PRIORITY, data["priority"] as? String ?: "default")
                put(COL_NOTIF_POSTED_AT, (data["postedAt"] as? Long) ?: System.currentTimeMillis())
                put(COL_NOTIF_DATE_KEY, data["dateKey"] as? String ?: "")
                put(COL_NOTIF_CREATED_AT, System.currentTimeMillis())
            }
            val id = db.insert(TABLE_NOTIFICATIONS, null, values)
            Log.d(TAG, "💾 Saved offline notification id=$id (${data["appName"]}: ${data["title"]})")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save offline notification: ${e.message}")
            -1L
        }
    }

    fun getPendingNotifications(uid: String, limit: Int = 100): List<OfflineNotification> {
        val list = mutableListOf<OfflineNotification>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NOTIFICATIONS,
            null,
            "$COL_NOTIF_UID = ?",
            arrayOf(uid),
            null,
            null,
            "$COL_NOTIF_POSTED_AT ASC",
            limit.toString()
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    OfflineNotification(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_NOTIF_ID)),
                        uid = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_UID)),
                        packageName = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_PACKAGE)),
                        appName = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_APP_NAME)),
                        title = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_TITLE)),
                        text = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_TEXT)),
                        subText = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_SUB_TEXT)),
                        infoText = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_INFO_TEXT)),
                        category = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_CATEGORY)),
                        priority = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_PRIORITY)),
                        postedAt = c.getLong(c.getColumnIndexOrThrow(COL_NOTIF_POSTED_AT)),
                        dateKey = c.getString(c.getColumnIndexOrThrow(COL_NOTIF_DATE_KEY)),
                        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_NOTIF_CREATED_AT))
                    )
                )
            }
        }
        return list
    }

    fun deleteNotifications(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val db = writableDatabase
            val inClause = ids.joinToString(",") { it.toString() }
            db.delete(TABLE_NOTIFICATIONS, "$COL_NOTIF_ID IN ($inClause)", null)
            Log.d(TAG, "🗑️ Deleted ${ids.size} synced notifications from offline cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete offline notifications: ${e.message}")
        }
    }

    // ==========================================
    // CALL LOGS
    // ==========================================

    fun saveCallLog(
        uid: String,
        docId: String,
        type: String,
        phoneNumber: String,
        contactName: String?,
        duration: Long,
        deviceTime: Long,
        detectedBy: String,
        source: String
    ): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_CALL_UID, uid)
                put(COL_CALL_DOC_ID, docId)
                put(COL_CALL_TYPE, type)
                put(COL_CALL_NUMBER, phoneNumber)
                put(COL_CALL_NAME, contactName)
                put(COL_CALL_DURATION, duration)
                put(COL_CALL_DEVICE_TIME, deviceTime)
                put(COL_CALL_DETECTED_BY, detectedBy)
                put(COL_CALL_SOURCE, source)
                put(COL_CALL_CREATED_AT, System.currentTimeMillis())
            }
            val id = db.insertWithOnConflict(
                TABLE_CALL_LOGS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            Log.d(TAG, "💾 Saved offline call log id=$id ($type, $phoneNumber, dur: ${duration}s)")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save offline call log: ${e.message}")
            -1L
        }
    }

    fun getPendingCallLogs(uid: String, limit: Int = 100): List<OfflineCallLog> {
        val list = mutableListOf<OfflineCallLog>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_CALL_LOGS,
            null,
            "$COL_CALL_UID = ?",
            arrayOf(uid),
            null,
            null,
            "$COL_CALL_DEVICE_TIME ASC",
            limit.toString()
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    OfflineCallLog(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_CALL_ID)),
                        uid = c.getString(c.getColumnIndexOrThrow(COL_CALL_UID)),
                        docId = c.getString(c.getColumnIndexOrThrow(COL_CALL_DOC_ID)),
                        type = c.getString(c.getColumnIndexOrThrow(COL_CALL_TYPE)),
                        phoneNumber = c.getString(c.getColumnIndexOrThrow(COL_CALL_NUMBER)),
                        contactName = c.getString(c.getColumnIndexOrThrow(COL_CALL_NAME)),
                        duration = c.getLong(c.getColumnIndexOrThrow(COL_CALL_DURATION)),
                        deviceTime = c.getLong(c.getColumnIndexOrThrow(COL_CALL_DEVICE_TIME)),
                        detectedBy = c.getString(c.getColumnIndexOrThrow(COL_CALL_DETECTED_BY)),
                        source = c.getString(c.getColumnIndexOrThrow(COL_CALL_SOURCE)),
                        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CALL_CREATED_AT))
                    )
                )
            }
        }
        return list
    }

    fun deleteCallLogs(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val db = writableDatabase
            val inClause = ids.joinToString(",") { it.toString() }
            db.delete(TABLE_CALL_LOGS, "$COL_CALL_ID IN ($inClause)", null)
            Log.d(TAG, "🗑️ Deleted ${ids.size} synced call logs from offline cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete offline call logs: ${e.message}")
        }
    }

    fun getPendingCount(): Int {
        return try {
            val db = readableDatabase
            var count = 0
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_NOTIFICATIONS", null).use {
                if (it.moveToFirst()) count += it.getInt(0)
            }
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_CALL_LOGS", null).use {
                if (it.moveToFirst()) count += it.getInt(0)
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Clean up items older than [cutoffMs]
     */
    fun cleanOldData(cutoffMs: Long) {
        try {
            val db = writableDatabase
            db.delete(TABLE_NOTIFICATIONS, "$COL_NOTIF_CREATED_AT < ?", arrayOf(cutoffMs.toString()))
            db.delete(TABLE_CALL_LOGS, "$COL_CALL_CREATED_AT < ?", arrayOf(cutoffMs.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning old offline sync data: ${e.message}")
        }
    }
}
