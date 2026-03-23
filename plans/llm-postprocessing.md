# LLM Post-Processing

Story: story-1110
Agent: architect

## Context

Server-side transcript error correction using GPT-4o or Claude API. Triggered automatically after AssemblyAI returns a transcript. Corrects domain-specific terminology, fixes common STT errors, and stores corrected text. (see briefing ## Features > 8. LLM Post-Processing)

## What changes

| File | Change |
|---|---|
| app/src/main/java/com/frontieraudio/app/data/remote/LlmPostProcessor.kt | Retrofit client for OpenAI API: sends transcript + system prompt for error correction, returns corrected text |
| app/src/main/java/com/frontieraudio/app/domain/usecase/PostProcessTranscriptUseCase.kt | Use case: takes raw transcript, sends to LLM, updates TranscriptEntity.correctedText in Room |
| app/src/main/java/com/frontieraudio/app/service/sync/LlmPostProcessWorker.kt | WorkManager worker: queries TRANSCRIBED chunks without correctedText, runs LLM post-processing |

## Contract

- `LlmPostProcessor.correctTranscript(rawText: String, context: String?): Result<String>` — sends to GPT-4o with system prompt for STT error correction, returns corrected text
- `PostProcessTranscriptUseCase.execute(transcriptId: String): Result<Unit>` — loads transcript from Room, sends to LLM, updates correctedText field

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Environment: OPENAI_API_KEY required
- `app/src/main/java/com/frontieraudio/app/data/local/dao/SyncDao.kt` — for updating transcript (from story-1107)
- `app/src/main/java/com/frontieraudio/app/service/sync/TranscriptionSyncWorker.kt` — triggers post-processing after STT (from story-1108)

## Tasks

1. Implement LlmPostProcessor: Retrofit interface for OpenAI chat completions API (POST /v1/chat/completions), system prompt instructs model to correct STT errors in field worker speech (technical terminology, proper nouns, numbers), temperature=0.1 for deterministic output, model=gpt-4o
2. Implement PostProcessTranscriptUseCase: load TranscriptEntity by ID from Room, call LlmPostProcessor.correctTranscript with raw text, update correctedText field in Room via SyncDao, handle API errors gracefully (leave correctedText null on failure — raw text is still available)
3. Create LlmPostProcessWorker (CoroutineWorker): queries SyncDao for TranscriptEntities where correctedText IS NULL and syncStatus=TRANSCRIBED, runs PostProcessTranscriptUseCase on each. Enqueued as OneTimeWorkRequest chained after TranscriptionSyncWorker completes (WorkManager dependency). If LLM fails, don't block — transcript is usable without correction. Does NOT modify TranscriptionSyncWorker (owned by story-1108).
<!-- END_CODER_ONLY -->

## Acceptance criteria

- Given a raw transcript with STT errors, when PostProcessTranscriptUseCase executes, then correctedText is stored in Room with improved text
- Given OpenAI API is unavailable, when post-processing fails, then raw transcript remains accessible (correctedText stays null, no crash)
- Given transcript "the worker sed to use the rench on the bolt", when LLM processes it, then correctedText contains "the worker said to use the wrench on the bolt"
- LLM system prompt includes instruction to preserve meaning, only fix obvious STT errors

## Verification

- Unit test: mock OpenAI response, verify correctedText stored in Room
- Unit test: API failure results in null correctedText, no exception propagated
- Manual test: real API call with sample STT-error text

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
