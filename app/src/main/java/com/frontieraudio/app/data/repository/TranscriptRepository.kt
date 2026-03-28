package com.frontieraudio.app.data.repository

import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class FirestoreTranscript(
    val transcriptId: String = "",
    val chunkId: String = "",
    val userId: String = "",
    val text: String = "",
    val status: String = "pending",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: Long = 0L,
)

@Singleton
class TranscriptRepository @Inject constructor() {

    private val firestore = Firebase.firestore

    val transcripts: Flow<List<FirestoreTranscript>> = callbackFlow {
        val userId = Firebase.auth.currentUser?.uid
        if (userId == null) {
            trySend(emptyList())
            awaitClose()
            return@callbackFlow
        }

        val registration = firestore.collection("transcripts")
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Firestore listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val list = snapshot.documents.mapNotNull { doc ->
                    val status = doc.getString("status") ?: "pending"
                    val text = doc.getString("text") ?: ""
                    val timestamp = doc.getTimestamp("createdAt")

                    FirestoreTranscript(
                        transcriptId = doc.getString("transcriptId") ?: doc.id,
                        chunkId = doc.getString("chunkId") ?: doc.id,
                        userId = doc.getString("userId") ?: "",
                        text = text,
                        status = status,
                        latitude = doc.getDouble("latitude"),
                        longitude = doc.getDouble("longitude"),
                        createdAt = timestamp?.toDate()?.time ?: 0L,
                    )
                }

                trySend(list)
            }

        awaitClose { registration.remove() }
    }

    val pendingCount: Flow<Int> = callbackFlow {
        val userId = Firebase.auth.currentUser?.uid
        if (userId == null) {
            trySend(0)
            awaitClose()
            return@callbackFlow
        }

        val registration = firestore.collection("transcripts")
            .whereEqualTo("userId", userId)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Firestore pending count listener error", error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.size() ?: 0)
            }

        awaitClose { registration.remove() }
    }

    companion object {
        private const val TAG = "TranscriptRepository"
    }
}
