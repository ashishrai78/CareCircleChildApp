package com.example.background

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 📱 ContactsProvider — reads all contacts with photos
 *
 * Works on ALL Android versions (Android 7.0+ — your minSdk = 24)
 * Requires: android.permission.READ_CONTACTS
 *
 * Returns: List of contacts with:
 *  - id, displayName, phoneNumber (all numbers), email, photo (base64)
 */
class ContactsProvider(private val context: Context) {

    companion object {
        private const val TAG = "ContactsProvider"
        private const val PHOTO_MAX_SIZE_BYTES = 50_000  // 50KB max per photo
    }

    fun hasPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get all contacts (with photos, multiple numbers, emails)
     * Returns List<Map> with contact data
     */
    suspend fun getAllContacts(includePhotos: Boolean = true): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return@withContext emptyList()
        }

        val contacts = mutableListOf<Map<String, Any?>>()
        val contactMap = mutableMapOf<Long, MutableMap<String, Any?>>()

        try {
            // Step 1: Get all contacts (basic info)
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_ID,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ContactsContract.Contacts.STARRED
            )

            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                    val hasPhone = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    val starred = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)) == 1

                    contactMap[id] = mutableMapOf(
                        "id" to id.toString(),
                        "displayName" to name,
                        "phoneNumbers" to mutableListOf<String>(),
                        "emails" to mutableListOf<String>(),
                        "photoBase64" to null as Any?,
                        "isStarred" to starred,
                        "hasPhone" to hasPhone
                    )
                }
            }

            // Step 2: Get all phone numbers
            val phoneProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID))
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))

                    val typeLabel = when (typeInt) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "Fax Work"
                        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Fax Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "Pager"
                        else -> "Other"
                    }

                    contactMap[contactId]?.let { contact ->
                        (contact["phoneNumbers"] as MutableList<String>).add(number ?: "")
                        if (!contact.containsKey("primaryPhoneType")) {
                            contact["primaryPhoneType"] = typeLabel
                        }
                    }
                }
            }

            // Step 3: Get all emails
            val emailProjection = arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE
            )

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                emailProjection,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID))
                    val email = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))

                    contactMap[contactId]?.let { contact ->
                        (contact["emails"] as MutableList<String>).add(email ?: "")
                    }
                }
            }

            // Step 4: Get photos (if requested)
            if (includePhotos) {
                contactMap.keys.toList().forEach { id ->
                    val photoBase64 = getContactPhotoBase64(id)
                    if (photoBase64 != null) {
                        contactMap[id]?.set("photoBase64", photoBase64)
                    }
                }
            }

            // Convert to list (only contacts with phone numbers)
            contacts.addAll(
                contactMap.values
                    .filter { it["hasPhone"] == true }  // Only contacts with at least 1 phone
                    .map { contact ->
                        val phones = (contact["phoneNumbers"] as MutableList<String>)
                        contact["primaryPhone"] = phones.firstOrNull() ?: ""
                        contact["phoneCount"] = phones.size
                        contact["emailCount"] = (contact["emails"] as MutableList<String>).size
                        contact
                    }
            )

            Log.d(TAG, "✅ Loaded ${contacts.size} contacts (with ${if (includePhotos) "photos" else "no photos"})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load contacts: ${e.message}")
        }

        contacts
    }

    /**
     * Get contact count only (fast)
     */
    suspend fun getContactCount(): Int = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext 0

        try {
            val projection = arrayOf(ContactsContract.Contacts._ID)
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = ?",
                arrayOf("1"),
                null
            )?.use { cursor ->
                return@withContext cursor.count
            }
        } catch (e: Exception) {
            Log.e(TAG, "getContactCount failed: ${e.message}")
        }
        0
    }

    /**
     * Get contact photo as base64 (compressed, max 50KB)
     */
    private fun getContactPhotoBase64(contactId: Long): String? {
        return try {
            // 🔥 FIX: Manually construct photo URI
            val contactUri = android.net.Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_URI,
                contactId.toString()
            )
            val photoUri = android.net.Uri.withAppendedPath(contactUri, "photo")

            context.contentResolver.openInputStream(photoUri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
                val compressed = compressBitmap(bitmap, PHOTO_MAX_SIZE_BYTES)
                val byteArrayOutputStream = ByteArrayOutputStream()
                compressed.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
                Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null  // Many contacts have no photo — silent fail
        }
    }

    private fun compressBitmap(bitmap: Bitmap, maxBytes: Int): Bitmap {
        var quality = 90
        var stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)

        while (stream.toByteArray().size > maxBytes && quality > 20) {
            quality -= 10
            stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }

        return BitmapFactory.decodeStream(java.io.ByteArrayInputStream(stream.toByteArray()))
            ?: bitmap
    }
}