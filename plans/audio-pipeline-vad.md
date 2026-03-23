# Audio Pipeline & VAD

Story: story-1104
Agent: architect

## Context

Core audio capture engine using AudioRecord at 16kHz mono PCM_16BIT with Silero VAD for speech/silence segmentation. Provides audio buffers to downstream speaker verification. (see briefing ## Technical Research > ### Architecture for pipeline flow)

## What changes

| File | Change |
|---|---|
| app/src/main/java/com/frontieraudio/app/service/audio/AudioConfig.kt | Audio configuration constants: SAMPLE_RATE=16000, CHANNEL=MONO, ENCODING=PCM_16BIT, BUFFER_SIZE, VAD_FRAME_SIZE_MS=30 |
| app/src/main/java/com/frontieraudio/app/service/audio/AudioCaptureManager.kt | Wraps AudioRecord lifecycle: init, start, read buffers on coroutine, stop, release. Emits ShortArray frames via Flow. |
| app/src/main/java/com/frontieraudio/app/service/audio/SileroVadProcessor.kt | Loads silero_vad.onnx via ONNX Runtime, processes 30ms frames, emits speech/silence events, accumulates speech segments into AudioChunk |

## Contract

- `AudioConfig` — object with constants: SAMPLE_RATE (16000), CHANNEL_CONFIG (CHANNEL_IN_MONO), AUDIO_FORMAT (ENCODING_PCM_16BIT), FRAME_SIZE_MS (30)
- `AudioCaptureManager.start(): Flow<ShortArray>` — starts AudioRecord, emits PCM frames on Dispatchers.Default
- `AudioCaptureManager.stop()` — stops and releases AudioRecord
- `AudioCaptureManager.currentSource: AudioSource` — VOICE_COMMUNICATION or VOICE_RECOGNITION
- `SileroVadProcessor.process(frame: ShortArray): VadResult` — returns VadResult(isSpeech: Boolean, probability: Float)
- `SileroVadProcessor.collectSpeechSegment(frames: Flow<ShortArray>): Flow<AudioChunk>` — accumulates speech frames, emits AudioChunk when silence detected

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Gotchas: NO noise preprocessing, VOICE_COMMUNICATION primary source with VOICE_RECOGNITION fallback
- `app/src/main/java/com/frontieraudio/app/domain/model/AudioChunk.kt` — domain model consumed downstream

## Tasks

1. Create AudioConfig object with compile-time constants for 16kHz mono PCM_16BIT, 30ms VAD frame size, and calculated buffer size (AudioRecord.getMinBufferSize * 2)
2. Implement AudioCaptureManager: initialize AudioRecord with VOICE_COMMUNICATION source, read loop on Dispatchers.Default emitting ShortArray frames via SharedFlow, handle AudioRecord initialization errors gracefully, expose currentSource property
3. Integrate Silero VAD: load silero_vad.onnx from assets via OrtEnvironment/OrtSession, implement process() that feeds 30ms frames and returns speech probability, implement collectSpeechSegment() that accumulates consecutive speech frames into AudioChunk with ByteArray PCM data
4. Handle edge cases: AudioRecord initialization failure (retry with VOICE_RECOGNITION source), buffer overrun detection, graceful cleanup on stop()
<!-- END_CODER_ONLY -->

## Acceptance criteria

- Given AudioRecord is initialized, when start() is called, then PCM frames flow at ~33 frames/second (30ms each)
- Given a speech segment followed by silence, when audio flows through SileroVadProcessor, then exactly one AudioChunk is emitted containing only the speech frames
- Given silence-only audio, when processed by VAD, then no AudioChunk is emitted
- Given AudioRecord fails with VOICE_COMMUNICATION, when initialization retries, then it falls back to VOICE_RECOGNITION source

## Verification

- Unit test: feed known speech+silence WAV through SileroVadProcessor, verify segment boundaries
- Unit test: verify AudioConfig.SAMPLE_RATE == 16000, FRAME_SIZE_MS == 30

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
