package com.frontieraudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SyncStatus {
    PENDING,
    UPLOADING,
    TRANSCRIBED,
    FAILED,
}

@Entity(
    tableName = "audio_chunks",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["sync_status"]),
    ],
)
data class AudioChunkEntity(
    @PrimaryKey
    @ColumnInfo(name = "chunk_id")
    val chunkId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "audio_data", typeAffinity = ColumnInfo.BLOB)
    val audioData: ByteArray,

    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Int,

    @ColumnInfo(name = "sample_rate")
    val sampleRate: Int,

    @ColumnInfo(name = "is_speaker_verified")
    val isSpeakerVerified: Boolean,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING,

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "location_accuracy")
    val locationAccuracy: Float? = null,

    @ColumnInfo(name = "speaker_confidence")
    val speakerConfidence: Float? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunkEntity) return false
        return chunkId == other.chunkId &&
            sessionId == other.sessionId &&
            audioData.contentEquals(other.audioData) &&
            startTimestamp == other.startTimestamp &&
            durationMs == other.durationMs &&
            sampleRate == other.sampleRate &&
            isSpeakerVerified == other.isSpeakerVerified &&
            syncStatus == other.syncStatus &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            locationAccuracy == other.locationAccuracy &&
            speakerConfidence == other.speakerConfidence
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + startTimestamp.hashCode()
        result = 31 * result + durationMs
        result = 31 * result + sampleRate
        result = 31 * result + isSpeakerVerified.hashCode()
        result = 31 * result + syncStatus.hashCode()
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + (locationAccuracy?.hashCode() ?: 0)
        result = 31 * result + (speakerConfidence?.hashCode() ?: 0)
        return result
    }
}
