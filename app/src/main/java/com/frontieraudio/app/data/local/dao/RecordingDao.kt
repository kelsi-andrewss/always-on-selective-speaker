package com.frontieraudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.frontieraudio.app.data.local.entity.AudioChunkEntity
import com.frontieraudio.app.data.local.entity.LocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: AudioChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationEntity): Long

    @Query("SELECT * FROM audio_chunks WHERE session_id = :sessionId ORDER BY start_timestamp ASC")
    fun getChunksBySession(sessionId: String): Flow<List<AudioChunkEntity>>

    @Query("DELETE FROM audio_chunks WHERE session_id = :sessionId")
    suspend fun deleteChunksBySession(sessionId: String)

    @Query("DELETE FROM locations WHERE session_id = :sessionId")
    suspend fun deleteLocationsBySession(sessionId: String)

    @Query("DELETE FROM audio_chunks WHERE start_timestamp < :before")
    suspend fun deleteChunksOlderThan(before: Long)
}
