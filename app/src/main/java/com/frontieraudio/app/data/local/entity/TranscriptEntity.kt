package com.frontieraudio.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = AudioChunkEntity::class,
            parentColumns = ["chunk_id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chunk_id"]),
    ],
)
data class TranscriptEntity(
    @PrimaryKey
    @ColumnInfo(name = "transcript_id")
    val transcriptId: String,

    @ColumnInfo(name = "chunk_id")
    val chunkId: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "corrected_text")
    val correctedText: String? = null,

    @ColumnInfo(name = "words_json")
    val wordsJson: String? = null,

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null,
)
