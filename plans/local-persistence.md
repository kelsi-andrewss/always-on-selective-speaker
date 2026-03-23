# Local Persistence & Offline Queue

Story: story-1107
Agent: architect

## Context

Room database with entities for audio chunks, GPS locations, and transcripts. DAO layer manages the offline upload queue with sync status tracking. Hilt DatabaseModule provides the database instance. (see briefing ## Technical Research > ### Data Model)

## What changes

| File | Change |
|---|---|
| app/src/main/java/com/frontieraudio/app/data/local/AppDatabase.kt | Room database: entities, version 1, type converters for FloatArray and List<WordTimestamp> |
| app/src/main/java/com/frontieraudio/app/data/local/entity/AudioChunkEntity.kt | Room entity: chunkId PK, sessionId, audioData BLOB, startTimestamp, durationMs, sampleRate, isSpeakerVerified, syncStatus (PENDING\|UPLOADING\|TRANSCRIBED\|FAILED), latitude, longitude, locationAccuracy |
| app/src/main/java/com/frontieraudio/app/data/local/entity/TranscriptEntity.kt | Room entity: transcriptId PK, chunkId FK, text, correctedText, wordsJson (JSON string), latitude, longitude, createdAt, syncedAt |
| app/src/main/java/com/frontieraudio/app/data/local/entity/LocationEntity.kt | Room entity: locationId PK, latitude, longitude, accuracy, timestamp, sessionId |
| app/src/main/java/com/frontieraudio/app/data/local/dao/RecordingDao.kt | Insert audio chunks + locations, query by session, delete old data |
| app/src/main/java/com/frontieraudio/app/data/local/dao/SyncDao.kt | Query PENDING chunks for upload, update sync status atomically, count pending/failed |
| app/src/main/java/com/frontieraudio/app/di/DatabaseModule.kt | Hilt @Module: provides AppDatabase singleton via Room.databaseBuilder, provides DAOs |

## Contract

- `RecordingDao.insertChunk(chunk: AudioChunkEntity): Long` — returns row ID
- `RecordingDao.insertLocation(location: LocationEntity): Long`
- `RecordingDao.getChunksBySession(sessionId: String): Flow<List<AudioChunkEntity>>`
- `SyncDao.getPendingChunks(limit: Int = 10): List<AudioChunkEntity>` — oldest first, PENDING status
- `SyncDao.updateSyncStatus(chunkId: String, status: SyncStatus)` — atomic status update
- `SyncDao.insertTranscript(transcript: TranscriptEntity)`
- `SyncDao.getPendingCount(): Flow<Int>` — observable pending queue size
- `SyncStatus` enum: PENDING, UPLOADING, TRANSCRIBED, FAILED

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Technical Research > ### Data Model for entity relationships
- `app/src/main/java/com/frontieraudio/app/domain/model/*.kt` — domain models this layer maps to/from

## Tasks

1. Create AudioChunkEntity with Room annotations: @Entity, @PrimaryKey (UUID string), @ColumnInfo for BLOB audioData, SyncStatus enum with @TypeConverter
2. Create TranscriptEntity and LocationEntity with proper FK relationships and indices (index on syncStatus for queue queries, index on sessionId)
3. Implement RecordingDao with @Insert, @Query for session-based retrieval, @Delete for cleanup
4. Implement SyncDao: getPendingChunks ordered by startTimestamp ASC with limit, updateSyncStatus with @Query UPDATE, insertTranscript, getPendingCount as Flow
5. Create AppDatabase @Database class with all entities, TypeConverters for FloatArray/List<WordTimestamp>/SyncStatus, version=1
6. Create DatabaseModule Hilt @Module: @Provides AppDatabase via Room.databaseBuilder, @Provides each DAO from database instance
<!-- END_CODER_ONLY -->

## Acceptance criteria

- Given a verified AudioChunk, when insertChunk called, then chunk persists in Room with syncStatus=PENDING
- Given 5 PENDING chunks in DB, when getPendingChunks(limit=3) called, then returns 3 oldest by startTimestamp
- Given a chunk with status PENDING, when updateSyncStatus(id, UPLOADING) called, then status updates atomically
- Given transcript data from STT, when insertTranscript called, then transcript links to correct chunkId
- DatabaseModule provides singleton AppDatabase and all DAOs via Hilt injection

## Verification

- Instrumented test: insert chunk, query by session, verify round-trip
- Instrumented test: insert 5 chunks, getPendingChunks(3) returns correct 3
- Instrumented test: updateSyncStatus changes status without affecting other fields

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
