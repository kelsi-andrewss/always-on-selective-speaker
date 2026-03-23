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

    @Query("SELECT * FROM transcripts ORDER BY created_at DESC")
    fun getTranscripts(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE transcript_id = :transcriptId")
    suspend fun getTranscriptById(transcriptId: String): TranscriptEntity?

    @Query(
        """
        SELECT t.* FROM transcripts t
        INNER JOIN audio_chunks a ON t.chunk_id = a.chunk_id
        WHERE t.corrected_text IS NULL AND a.sync_status = 'TRANSCRIBED'
        """
    )
    suspend fun getTranscriptsNeedingCorrection(): List<TranscriptEntity>

    @Query("UPDATE transcripts SET corrected_text = :correctedText WHERE transcript_id = :transcriptId")
    suspend fun updateCorrectedText(transcriptId: String, correctedText: String)
}
