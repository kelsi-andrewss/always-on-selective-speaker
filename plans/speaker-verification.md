# Speaker Enrollment & Verification

Story: story-1105
Agent: architect

## Context

On-device speaker verification using sherpa-onnx ECAPA-TDNN. One-time enrollment captures 3 guided speech prompts, extracts 192-dim embeddings, stores reference profile. Runtime verification compares live speech embeddings via cosine similarity. (see briefing ## Decisions: sherpa-onnx with ECAPA-TDNN)

## What changes

| File | Change |
|---|---|
| app/src/main/java/com/frontieraudio/app/ui/screens/EnrollmentScreen.kt | Compose UI: 3-step guided enrollment with real-time audio level feedback, progress indicator, success/retry states |
| app/src/main/java/com/frontieraudio/app/service/speaker/SherpaOnnxVerifier.kt | Loads ECAPA-TDNN model via sherpa-onnx, extracts 192-dim embeddings, computes cosine similarity |
| app/src/main/java/com/frontieraudio/app/service/speaker/EnrollmentManager.kt | Orchestrates enrollment: captures 3 utterances, extracts embeddings, averages, validates quality, persists |
| app/src/main/java/com/frontieraudio/app/service/speaker/EmbeddingStore.kt | Persists SpeakerProfile to DataStore (serialized FloatArray embedding + metadata) |

## Contract

- `SherpaOnnxVerifier.extractEmbedding(audioChunk: AudioChunk): FloatArray` — returns 192-dim speaker embedding
- `SherpaOnnxVerifier.verify(audioChunk: AudioChunk, profile: SpeakerProfile, threshold: Float = 0.65f): VerificationResult` — returns VerificationResult(isMatch: Boolean, similarity: Float)
- `EnrollmentManager.enroll(utterances: List<AudioChunk>): Result<SpeakerProfile>` — averages embeddings, validates quality (similarity between utterances > 0.7), persists
- `EmbeddingStore.save(profile: SpeakerProfile)` / `EmbeddingStore.load(): SpeakerProfile?` — DataStore persistence
- `EmbeddingStore.isEnrolled(): Boolean` — quick check

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Technical Research > Speaker Verification: ECAPA-TDNN 192-dim, ~0.80% EER
- `app/src/main/java/com/frontieraudio/app/domain/model/SpeakerProfile.kt` — domain model
- `app/src/main/java/com/frontieraudio/app/domain/model/AudioChunk.kt` — input from audio pipeline
- `app/src/main/java/com/frontieraudio/app/service/audio/AudioCaptureManager.kt` — audio source (from story-1104)

## Tasks

1. Implement SherpaOnnxVerifier: load ECAPA-TDNN ONNX model from assets via sherpa-onnx Android API, extractEmbedding() processes 16kHz PCM to 192-dim FloatArray, verify() computes cosine similarity against stored profile
2. Implement EnrollmentManager: orchestrates 3-utterance enrollment flow, calls extractEmbedding() on each, validates cross-utterance consistency (cosine > 0.7), averages embeddings, wraps into SpeakerProfile
3. Implement EmbeddingStore: serialize SpeakerProfile (embedding FloatArray + metadata) to Jetpack DataStore, load/save/isEnrolled methods
4. Build EnrollmentScreen: Compose UI with 3-step progress (prompt text for each step), real-time audio level indicator (RMS of current buffer), enrollment button per step, success screen with "Start Monitoring" CTA, retry on quality failure
5. Handle edge cases: noisy enrollment (quality score < threshold triggers re-enrollment prompt), model loading failure, empty audio chunks
6. sherpa-onnx AAR integration: check Maven Central first (com.k2-fsa:sherpa-onnx). If not available, download AAR from GitHub releases (https://github.com/k2-fsa/sherpa-onnx/releases), place in app/libs/, add flatDir repository to build.gradle.kts
<!-- END_CODER_ONLY -->

## Acceptance criteria

- User sees first enrollment prompt → speaks → audio level indicator responds → progress advances to step 2
- User completes 3 prompts → embeddings extracted → cross-utterance similarity > 0.7 → enrollment succeeds → "Start Monitoring" button appears
- User speaks with too much background noise → quality check fails → retry prompt shown
- Given enrolled profile and matching speaker audio, when verify() called, then isMatch=true with similarity > 0.65
- Given enrolled profile and different speaker audio, when verify() called, then isMatch=false
- EmbeddingStore.isEnrolled() returns true after successful enrollment, false before

## Verification

- Unit test: cosine similarity of identical vectors = 1.0
- Unit test: cosine similarity of orthogonal vectors = 0.0
- Integration test: enrollment flow with 3 sample WAV files produces valid SpeakerProfile

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
