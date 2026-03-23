package com.frontieraudio.app.data.repository

import android.content.Context
import com.frontieraudio.app.data.local.dao.SyncDao
import com.frontieraudio.app.data.local.entity.TranscriptEntity
import com.frontieraudio.app.service.sync.TranscriptionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptRepository @Inject constructor(
    private val syncDao: SyncDao,
    @ApplicationContext private val context: Context,
) {

    val transcripts: Flow<List<TranscriptEntity>>
        get() = syncDao.getTranscripts()

    val pendingCount: Flow<Int>
        get() = syncDao.getPendingCount()

    fun triggerSync() {
        TranscriptionSyncWorker.enqueue(context)
    }
}
