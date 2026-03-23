package com.frontieraudio.app.service.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.frontieraudio.app.data.local.dao.SyncDao
import com.frontieraudio.app.data.remote.LlmPostProcessor
import com.frontieraudio.app.domain.usecase.PostProcessTranscriptUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class LlmPostProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun syncDao(): SyncDao
    }

    override suspend fun doWork(): Result {
        val apiKey = inputData.getString(KEY_OPENAI_API_KEY)
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "No OpenAI API key provided, skipping post-processing")
            return Result.success()
        }

        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Dependencies::class.java,
        )
        val syncDao = deps.syncDao()
        val llmPostProcessor = LlmPostProcessor(apiKey)
        val useCase = PostProcessTranscriptUseCase(syncDao, llmPostProcessor)

        val transcripts = syncDao.getTranscriptsNeedingCorrection()
        Log.d(TAG, "Found ${transcripts.size} transcripts needing LLM correction")

        var successCount = 0
        for (transcript in transcripts) {
            useCase.execute(transcript.transcriptId)
                .onSuccess { successCount++ }
                .onFailure { e ->
                    Log.w(TAG, "Failed to correct transcript ${transcript.transcriptId}", e)
                }
        }

        Log.i(TAG, "LLM post-processing complete: $successCount/${transcripts.size} corrected")
        return Result.success()
    }

    companion object {
        private const val TAG = "LlmPostProcessWorker"
        const val KEY_OPENAI_API_KEY = "openai_api_key"

        fun enqueue(context: Context, apiKey: String) {
            val request = OneTimeWorkRequestBuilder<LlmPostProcessWorker>()
                .setInputData(workDataOf(KEY_OPENAI_API_KEY to apiKey))
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "LLM post-processing work enqueued")
        }
    }
}
