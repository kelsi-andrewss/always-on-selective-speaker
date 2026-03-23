package com.frontieraudio.app.domain.usecase

import android.util.Log
import com.frontieraudio.app.data.local.dao.SyncDao
import com.frontieraudio.app.data.remote.LlmPostProcessor

class PostProcessTranscriptUseCase(
    private val syncDao: SyncDao,
    private val llmPostProcessor: LlmPostProcessor,
) {

    suspend fun execute(transcriptId: String): Result<Unit> = runCatching {
        val transcript = syncDao.getTranscriptById(transcriptId)
        if (transcript == null) {
            Log.w(TAG, "Transcript $transcriptId not found")
            return Result.success(Unit)
        }

        if (transcript.correctedText != null) {
            Log.d(TAG, "Transcript $transcriptId already corrected, skipping")
            return Result.success(Unit)
        }

        val corrected = llmPostProcessor.correctTranscript(transcript.text).getOrElse { e ->
            Log.e(TAG, "LLM correction failed for $transcriptId, leaving correctedText null", e)
            return Result.success(Unit)
        }

        syncDao.updateCorrectedText(transcriptId, corrected)
        Log.d(TAG, "Stored corrected text for transcript $transcriptId")
        Unit
    }.onFailure { e ->
        Log.e(TAG, "PostProcessTranscriptUseCase failed for $transcriptId", e)
    }

    companion object {
        private const val TAG = "PostProcessTranscript"
    }
}
