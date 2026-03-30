package com.frontieraudio.app.data.remote

import android.util.Log
import com.frontieraudio.app.data.local.dao.SyncDao
import com.frontieraudio.app.data.local.entity.TranscriptEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class FirestoreSyncManager(
    private val syncDao: SyncDao,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    suspend fun syncTranscripts() {
        val transcripts = syncDao.getUnsyncedTranscripts()
        if (transcripts.isEmpty()) {
            Log.d(TAG, "No transcripts to sync to Firestore")
            return
        }

        Log.d(TAG, "Syncing ${transcripts.size} transcripts to Firestore")

        for (transcript in transcripts) {
            try {
                val chunk = syncDao.getChunkById(transcript.chunkId)
                syncOne(transcript, chunk?.sessionId, chunk?.locationAccuracy, chunk?.speakerConfidence)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync transcript ${transcript.transcriptId}", e)
            }
        }
    }

    private suspend fun syncOne(
        transcript: TranscriptEntity,
        sessionId: String?,
        accuracy: Float?,
        speakerConfidence: Float?,
    ) {
        val doc = hashMapOf<String, Any?>(
            "text" to transcript.text,
            "correctedText" to transcript.correctedText,
            "words" to transcript.wordsJson,
            "latitude" to transcript.latitude,
            "longitude" to transcript.longitude,
            "accuracy" to accuracy?.toDouble(),
            "speakerConfidence" to speakerConfidence?.toDouble(),
            "timestamp" to transcript.createdAt,
            "sessionId" to (sessionId ?: "unknown"),
        )

        firestore.collection(COLLECTION)
            .document(transcript.transcriptId)
            .set(doc)
            .await()

        syncDao.updateSyncedAt(transcript.transcriptId, System.currentTimeMillis())
        Log.d(TAG, "Synced transcript ${transcript.transcriptId}")
    }

    companion object {
        private const val TAG = "FirestoreSync"
        private const val COLLECTION = "transcripts"
    }
}
