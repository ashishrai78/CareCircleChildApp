package com.example.background

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 📱 ContactsSyncHelper — syncs contacts to Firestore
 *
 * Called by:
 *  - NativeDataCollector.collectAndSyncAll() (every 10 min)
 *  - ContactsSyncWorker (every 6 hours fallback)
 *  - When parent requests sync (contacts_sync_request = true)
 *
 * Firestore structure:
 *  contacts/{childUid}/items/{contactId}
 *    - id, displayName, primaryPhone, phoneNumbers, emails, photoBase64, etc.
 */
class ContactsSyncHelper(private val context: Context) {

    companion object {
        private const val TAG = "ContactsSync"
    }

    private val contactsProvider = ContactsProvider(context)

    /**
     * Sync all contacts to Firestore
     * Returns: number of contacts synced
     */
    suspend fun syncContacts(): Int = withContext(Dispatchers.IO) {
        if (!contactsProvider.hasPermission()) {
            Log.w(TAG, "⚠️ READ_CONTACTS permission not granted — skipping")
            return@withContext 0
        }

        try {
            val uid = NativeDataCollector(context).let { collector ->
                // Use the same getUserId logic
                try {
                    val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    if (user != null) return@let user.uid
                } catch (_: Exception) {}
                context.getSharedPreferences("carecircle_prefs", Context.MODE_PRIVATE)
                    .getString("currentUserId", null)
            } ?: run {
                Log.w(TAG, "⚠️ No UID — skipping contacts sync")
                return@withContext 0
            }

            FirestoreClient.init(context)
            FirestoreClient.setUserId(uid)

            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()

            // Get all contacts (with photos)
            val contacts = contactsProvider.getAllContacts(includePhotos = true)

            if (contacts.isEmpty()) {
                Log.w(TAG, "⚠️ No contacts found")
                return@withContext 0
            }

            // Write each contact as a separate document (sub-collection)
            val batch = db.batch()
            val collectionRef = db.collection("contacts").document(uid).collection("items")

            for (contact in contacts) {
                val contactId = contact["id"] as? String ?: continue
                val docRef = collectionRef.document(contactId)

                @Suppress("UNCHECKED_CAST")
                val contactData = mutableMapOf<String, Any?>()
                contactData.putAll(contact)
                contactData["updatedAt"] = FieldValue.serverTimestamp()

                batch.set(docRef, contactData, com.google.firebase.firestore.SetOptions.merge())
            }

            // Commit batch
            batch.commit().await()

            // Update summary doc
            val summaryData = mapOf(
                "totalContacts" to contacts.size,
                "lastSync" to FieldValue.serverTimestamp(),
                "hasPermission" to true
            )
            db.collection("contacts").document(uid)
                .set(summaryData, com.google.firebase.firestore.SetOptions.merge())
                .await()

            Log.d(TAG, "✅ Synced ${contacts.size} contacts to Firestore")
            contacts.size

        } catch (e: Exception) {
            Log.e(TAG, "❌ Contacts sync failed: ${e.message}")
            0
        }
    }

    /**
     * Get contact count only (fast, no photos)
     */
    suspend fun getContactCount(): Int = withContext(Dispatchers.IO) {
        contactsProvider.getContactCount()
    }
}

// 🔥 Extension to await Task
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return kotlinx.coroutines.tasks.await(this)
}
