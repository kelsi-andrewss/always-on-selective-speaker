package com.frontieraudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.frontieraudio.app.data.local.entity.AudioChunkEntity
import com.frontieraudio.app.data.local.entity.SyncStatus
import com.frontieraudio.app.data.local.entity.TranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    @Query(
        """
        SELECT * FROM audio_chunks
        WHERE sync_status = 'PENDING'
        ORDER BY start_timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingChunks(limit: Int = 10): List<AudioChunkEntity>

    @Query("UPDATE audio_chunks SET sync_status = :status WHERE chunk_id = :chunkId")
    suspend fun updateSyncStatus(chunkId: String, status: SyncStatus)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscript(transcript: TranscriptEntity)

    @Query("SELECT COUNT(*) FROM audio_chunks WHERE sync_status = 'PENDING' OR sync_status = 'FAILED'")
    fun getPendingCount(): Flow<Int>
}
