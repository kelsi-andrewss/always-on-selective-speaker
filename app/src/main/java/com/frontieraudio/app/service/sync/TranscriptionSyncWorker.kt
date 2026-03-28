package com.frontieraudio.app.service.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.Room
import com.frontieraudio.app.data.local.AppDatabase
import com.frontieraudio.app.data.local.entity.AudioChunkEntity
import com.frontieraudio.app.data.local.entity.SyncStatus
import com.frontieraudio.app.data.local.entity.TranscriptEntity
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import kotlinx.coroutines.tasks.await

class TranscriptionSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val dao by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "frontier_audio.db",
        ).build().syncDao()
    }
    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val gson = Gson()

    override suspend fun doWork(): Result {
        val chunks = dao.getPendingChunks(5)
        if (chunks.isEmpty()) {
            Log.d(TAG, "No pending chunks")
            return Result.success()
        }

        Log.d(TAG, "Processing ${chunks.size} pending chunks")

        var hasTransientFailure = false

        for (chunk in chunks) {
            try {
                processChunk(chunk)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process chunk ${chunk.chunkId}", e)
                dao.updateSyncStatus(chunk.chunkId, SyncStatus.FAILED)
                hasTransientFailure = true
            }
        }

        return if (hasTransientFailure) Result.retry() else Result.success()
    }

    private suspend fun processChunk(chunk: AudioChunkEntity) {
        dao.updateSyncStatus(chunk.chunkId, SyncStatus.UPLOADING)

        val wavData = WavConverter.pcmToWav(
            pcmData = chunk.audioData,
            sampleRate = chunk.sampleRate,
            channels = 1,
            bitsPerSample = 16,
        )

        val audioBase64 = Base64.encodeToString(wavData, Base64.NO_WRAP)
        Log.d(TAG, "Calling Cloud Function for chunk ${chunk.chunkId} (${wavData.size} bytes)")

        val result = functions
            .getHttpsCallable("transcribe")
            .call(hashMapOf("audioData" to audioBase64))
            .await()

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>

        val status = data["status"] as? String
        if (status == "error" || status != "completed") {
            Log.e(TAG, "Transcript error for ${chunk.chunkId}: status=$status")
            dao.updateSyncStatus(chunk.chunkId, SyncStatus.FAILED)
            return
        }

        val transcriptId = data["transcriptId"] as? String ?: ""
        val text = data["text"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val words = data["words"] as? List<Map<String, Any>>
        val wordsJson = words?.let { gson.toJson(it) }

        val entity = TranscriptEntity(
            transcriptId = transcriptId,
            chunkId = chunk.chunkId,
            text = text,
            wordsJson = wordsJson,
            latitude = chunk.latitude,
            longitude = chunk.longitude,
            createdAt = System.currentTimeMillis(),
        )

        dao.insertTranscript(entity)
        dao.updateSyncStatus(chunk.chunkId, SyncStatus.TRANSCRIBED)
        Log.d(TAG, "Stored transcript $transcriptId for chunk ${chunk.chunkId}")
    }

    companion object {
        private const val TAG = "TranscriptionSync"
        private const val WORK_NAME = "transcription_sync"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<TranscriptionSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "Sync work enqueued")
        }
    }
}

object WavConverter {
    fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val headerSize = 44
        val totalSize = headerSize + dataSize

        val wav = ByteArray(totalSize)

        // RIFF header
        wav[0] = 'R'.code.toByte()
        wav[1] = 'I'.code.toByte()
        wav[2] = 'F'.code.toByte()
        wav[3] = 'F'.code.toByte()
        writeInt32LE(wav, 4, totalSize - 8)
        wav[8] = 'W'.code.toByte()
        wav[9] = 'A'.code.toByte()
        wav[10] = 'V'.code.toByte()
        wav[11] = 'E'.code.toByte()

        // fmt sub-chunk
        wav[12] = 'f'.code.toByte()
        wav[13] = 'm'.code.toByte()
        wav[14] = 't'.code.toByte()
        wav[15] = ' '.code.toByte()
        writeInt32LE(wav, 16, 16) // PCM format chunk size
        writeInt16LE(wav, 20, 1)  // Audio format: PCM
        writeInt16LE(wav, 22, channels)
        writeInt32LE(wav, 24, sampleRate)
        writeInt32LE(wav, 28, byteRate)
        writeInt16LE(wav, 32, blockAlign)
        writeInt16LE(wav, 34, bitsPerSample)

        // data sub-chunk
        wav[36] = 'd'.code.toByte()
        wav[37] = 'a'.code.toByte()
        wav[38] = 't'.code.toByte()
        wav[39] = 'a'.code.toByte()
        writeInt32LE(wav, 40, dataSize)

        System.arraycopy(pcmData, 0, wav, headerSize, dataSize)

        return wav
    }

    private fun writeInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
        buf[offset + 2] = (value shr 16 and 0xFF).toByte()
        buf[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
    }
}
