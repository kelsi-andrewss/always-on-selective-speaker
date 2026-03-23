# Cloud STT Integration

Story: story-1108
Agent: architect

## Context

AssemblyAI Universal-3 Pro client for audio upload and transcript retrieval. SyncWorker handles background upload of queued audio chunks from Room DB. Parses word-level timestamps. (see briefing ## Technical Research > ### APIs & Services > AssemblyAI)

## What changes

| File | Change |
|---|---|
| app/src/main/java/com/frontieraudio/app/data/remote/AssemblyAiClient.kt | Retrofit interface: upload audio (POST /v2/upload), create transcript job (POST /v2/transcript), poll result (GET /v2/transcript/{id}) |
| app/src/main/java/com/frontieraudio/app/service/sync/TranscriptionSyncWorker.kt | WorkManager CoroutineWorker: queries PENDING chunks from SyncDao, uploads to AssemblyAI, polls for result, stores transcript in Room |
| app/src/main/java/com/frontieraudio/app/data/repository/TranscriptRepository.kt | Repository bridging Room and remote: getTranscripts Flow, triggerSync, sync status |

## Contract

- `AssemblyAiClient.uploadAudio(audioData: ByteArray): Result<String>` — returns upload_url
- `AssemblyAiClient.createTranscript(audioUrl: String): Result<String>` — returns transcript_id
- `AssemblyAiClient.getTranscript(transcriptId: String): Result<TranscriptResponse>` — polls until completed/error
- `TranscriptResponse(id: String, status: String, text: String?, words: List<WordResponse>?)` — API response model
- `TranscriptionSyncWorker` — CoroutineWorker, enqueued as OneTimeWorkRequest with network constraint
- `TranscriptRepository.transcripts: Flow<List<Transcript>>` — observable transcript list from Room
- `TranscriptRepository.pendingCount: Flow<Int>` — observable queue size

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Technical Research > ### APIs & Services > AssemblyAI for exact API shape
- `app/src/main/java/com/frontieraudio/app/data/local/dao/SyncDao.kt` — queue management (from story-1107)

## Tasks

1. Implement AssemblyAiClient Retrofit interface: POST /v2/upload with raw audio body (Content-Type: application/octet-stream), POST /v2/transcript with JSON body {audio_url, word_boost: []}, GET /v2/transcript/{id}. Auth via "Authorization: {apiKey}" header interceptor. Response models for upload, transcript creation, and transcript result.
2. Implement TranscriptionSyncWorker: doWork() queries SyncDao.getPendingChunks(5), for each chunk: update status to UPLOADING, convert PCM to WAV format, upload via AssemblyAiClient, create transcript job, poll every 5s until status is "completed" or "error", parse word-level timestamps into WordTimestamp list, insert TranscriptEntity via SyncDao, update chunk status to TRANSCRIBED (or FAILED on error). Use exponential backoff on network errors. Return Result.retry() on transient failures.
3. Implement TranscriptRepository: expose Flow<List<Transcript>> from Room, expose pendingCount, triggerSync() enqueues TranscriptionSyncWorker with Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
4. Wire SyncWorker into Hilt via @HiltWorker annotation and custom WorkerFactory
<!-- END_CODER_ONLY -->

## Acceptance criteria

- Given a PENDING audio chunk in Room and network available, when SyncWorker runs, then chunk is uploaded to AssemblyAI, transcript is retrieved with word-level timestamps, TranscriptEntity is stored in Room, chunk status becomes TRANSCRIBED
- Given no network, when SyncWorker is enqueued, then it waits for connectivity (WorkManager constraint)
- Given AssemblyAI returns error, when SyncWorker processes chunk, then status becomes FAILED and worker returns retry
- TranscriptRepository.transcripts emits updated list after new transcript stored

## Verification

- Unit test: mock AssemblyAI responses, verify SyncWorker state transitions (PENDING → UPLOADING → TRANSCRIBED)
- Unit test: verify PCM to WAV conversion produces valid WAV header
- Integration test: end-to-end with real AssemblyAI API key on sample audio

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
