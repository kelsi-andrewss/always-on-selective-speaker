package com.frontieraudio.app.service.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class LlmPostProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // TODO: Implement via Cloud Function proxy — OpenAI key must not be on-device
        Log.d(TAG, "LLM post-processing skipped — awaiting Cloud Function implementation")
        return Result.success()
    }

    companion object {
        private const val TAG = "LlmPostProcessWorker"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<LlmPostProcessWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "LLM post-processing work enqueued")
        }
    }
}
