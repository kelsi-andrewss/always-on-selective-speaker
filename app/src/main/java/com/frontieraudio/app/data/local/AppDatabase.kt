package com.frontieraudio.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.frontieraudio.app.data.local.dao.RecordingDao
import com.frontieraudio.app.data.local.dao.SyncDao
import com.frontieraudio.app.data.local.entity.AudioChunkEntity
import com.frontieraudio.app.data.local.entity.LocationEntity
import com.frontieraudio.app.data.local.entity.SyncStatus
import com.frontieraudio.app.data.local.entity.TranscriptEntity

class Converters {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}

@Database(
    entities = [
        AudioChunkEntity::class,
        TranscriptEntity::class,
        LocationEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun syncDao(): SyncDao
}
