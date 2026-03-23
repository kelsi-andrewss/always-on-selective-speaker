# Always-On Selective Speaker Android App

## Problem Statement
**What problem?** Frontline workers (managers, supervisors) have hundreds of technical conversations daily in extreme field environments. They cannot remember all details discussed. They need a frictionless, automatic record of everything *they* said — not others around them. Current solutions are either manual (requires turning on/off), non-selective (records everyone), or unreliable (killed by Android battery optimization).

**Why fix it?** Without automatic selective records, critical technical details are lost — creating liability risk, operational errors, safety violations, and incomplete chain-of-command documentation. In extreme environments, safety protocols are adjusted verbally; without a verified transcript, there is no record of what a supervisor actually instructed.

**Why integral?** This IS the core product for Frontier Audio. Always-on selective transcription is the entire value proposition — transforming a phone into a professional accountability tool rather than a battery-draining recorder.

**End goal:** Production-grade demo Android app submitted to Frontier Audio with working speaker verification, cloud transcription with timestamps and GPS, Bluetooth mic support, and a web dashboard for transcript review. The app never stops recording once started (only exception: device powered off).

## Overview
A Kotlin Android app that runs a foreground service (microphone|location type) continuously, performing on-device voice activity detection (Silero VAD) and speaker verification (sherpa-onnx ECAPA-TDNN) to filter only the enrolled user's speech. Verified audio segments are queued in Room DB and uploaded to AssemblyAI for transcription when connectivity is available. Every transcript segment is tagged with GPS coordinates. A simple web dashboard allows supervisors to review transcripts in a browser. The app survives Doze mode, sleep, charger idle, and OEM battery optimization through battery whitelist exemption and OEM-specific onboarding UX.

## Summary
Production-grade Android app for frontline worker speech transcription. Always-on foreground service (microphone|location type, timeout-exempt) with battery optimization whitelist for Doze survival. Audio pipeline: Silero VAD detects speech -> sherpa-onnx ECAPA-TDNN verifies enrolled speaker on-device (only user's audio leaves device) -> verified segments queued in Room DB -> uploaded to AssemblyAI Universal-3 Pro for transcription ($3.50/1K min, 9.97% noisy WER) -> GPS-tagged with HIGH_ACCURACY batched location. Bluetooth mic auto-switching via setCommunicationDevice() (API 31+) with defense-in-depth for OEM audio bugs. Kotlin, minSdk 31, targetSdk 35, Jetpack Compose UI. Includes OEM-specific onboarding (Samsung/Xiaomi battery settings) and simple web dashboard for transcript review.

## Features

### MVP
0. **Bootstrap** — Create Android project with Gradle KTS, configure all dependencies from ## Dependencies, create shared types from ## Shared Interfaces, set up Room DB schema, create `.env.example`, configure JUnit 5 + MockK, set up Hilt DI modules. Target files: `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `AppModule.kt`, `AppDatabase.kt`, domain model files, `.env.example`

1. **Permissions & OEM Onboarding** — Runtime permission flow (RECORD_AUDIO, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS), battery optimization whitelist request (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with manual settings fallback), OEM-specific guidance screens (Samsung Never Sleeping Apps, Xiaomi Background Autostart). Target files: `AndroidManifest.xml`, `ui/screens/OnboardingScreen.kt`, `service/BatteryOptimizationHelper.kt`, `service/OemDetector.kt`

2. **Audio Pipeline & VAD** — AudioRecord configuration (16kHz, PCM_16BIT, mono), Silero VAD integration for speech/silence detection, audio buffer management, sample rate handling. This is the audio foundation all other features build on. Target files: `service/audio/AudioCaptureManager.kt`, `service/audio/SileroVadProcessor.kt`, `service/audio/AudioConfig.kt`

3. **Speaker Enrollment & Verification** — One-time voice enrollment UI with guided prompts, sherpa-onnx ECAPA-TDNN embedding extraction (192-dim), cosine similarity verification against enrolled profile, enrollment data persistence. Target files: `ui/screens/EnrollmentScreen.kt`, `service/speaker/SherpaOnnxVerifier.kt`, `service/speaker/EnrollmentManager.kt`, `service/speaker/EmbeddingStore.kt`

4. **Foreground Service** — Always-on service with microphone|location type, PARTIAL_WAKE_LOCK, START_STICKY, persistent notification, orchestrates audio pipeline + VAD + verification + GPS + upload queue. WorkManager 15-min heartbeat watchdog. Target files: `service/RecordingForegroundService.kt`, `service/ServiceNotificationManager.kt`, `service/WatchdogWorker.kt`

5. **Local Persistence & Offline Queue** — Room DB entities for audio chunks, GPS points, transcripts. DAO layer for queue management. Sync status tracking. Target files: `data/local/AppDatabase.kt`, `data/local/entity/AudioChunkEntity.kt`, `data/local/entity/TranscriptEntity.kt`, `data/local/entity/LocationEntity.kt`, `data/local/dao/AudioChunkDao.kt`, `data/local/dao/TranscriptDao.kt`

6. **Cloud STT Integration** — AssemblyAI client for audio upload and transcript polling, SyncWorker for background upload when connectivity available, word-level timestamp parsing, transcript storage. Target files: `data/remote/AssemblyAiClient.kt`, `service/sync/TranscriptionSyncWorker.kt`, `data/repository/TranscriptRepository.kt`

7. **GPS Tracking** — FusedLocationProviderClient with PRIORITY_HIGH_ACCURACY, batch delivery via setMaxUpdateDelayMillis, timestamp synchronization with audio segments using SystemClock.elapsedRealtimeNanos(). Target files: `service/location/GpsTracker.kt`, `service/location/LocationBatchManager.kt`

8. **LLM Post-Processing** — Server-side error correction on transcripts using Claude/GPT-4o API. Triggered after AssemblyAI returns transcript, corrects domain-specific terminology and common STT errors. Target files: `data/remote/LlmPostProcessor.kt`, `domain/usecase/PostProcessTranscriptUseCase.kt`

### Phase 2
9. **Bluetooth Audio Routing** — setCommunicationDevice() (API 31+) with MODE_IN_COMMUNICATION, AudioDeviceCallback for connect/disconnect detection, 500ms+ stabilization delay, AudioRecord recreation on sample rate change, VOICE_COMMUNICATION -> VOICE_RECOGNITION fallback on silence, LE Audio (TYPE_BLE_HEADSET) preferred over SCO. Target files: `service/audio/BluetoothAudioRouter.kt`, `service/audio/AudioDeviceMonitor.kt`
   *Reasoning: Can demo with built-in mic first. BT routing is the most fragile component with OEM-specific bugs — better to nail the core pipeline first.*

10. **Web Dashboard** — Simple web UI for supervisors to review GPS-tagged transcripts. Firebase backend for data sync, basic auth. Target files: `web-dashboard/` directory
    *Reasoning: Makes demo more compelling but Android app is the core deliverable.*

## Technical Research

### APIs & Services
- **AssemblyAI Universal-3 Pro**: Cloud STT. POST `/v2/upload` for audio, POST `/v2/transcript` for transcription job, GET `/v2/transcript/{id}` for polling. Auth: `Authorization: {api_key}` header. Returns word-level timestamps, confidence scores. $3.50/1K min.
- **sherpa-onnx**: On-device speaker verification. `SpeakerEmbeddingExtractor` for 192-dim ECAPA-TDNN embeddings, `SpeakerEmbeddingManager` for enrollment storage, cosine similarity for verification. ONNX Runtime backend.
- **Silero VAD**: On-device voice activity detection. 16kHz input, outputs speech probability per frame. Lightweight, <1ms per frame.
- **FusedLocationProviderClient**: Android location API. `requestLocationUpdates()` with `LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, intervalMs)`.
- **Firebase**: App Distribution for APK delivery, Firestore for dashboard data sync, Firebase Auth for dashboard access.

### Architecture
- **Clean Architecture + MVVM**: Domain layer (use cases, models) isolated from data layer (Room, Retrofit, sherpa-onnx) and UI layer (Compose). Service layer bridges Android lifecycle with domain logic.
- **Single Foreground Service**: `RecordingForegroundService` with `foregroundServiceType="microphone|location"`. Orchestrates all audio/location/verification components. Never killed once started.
- **Audio Pipeline Flow**: `AudioRecord` -> `SileroVadProcessor` (speech detection) -> `SherpaOnnxVerifier` (speaker match) -> `ChunkQueue` (Room DB) -> `TranscriptionSyncWorker` (AssemblyAI upload)
- **Offline-first**: All data persisted to Room before any network call. Sync when connectivity returns.

### Patterns
- **HTTP client**: Retrofit 2.11 with OkHttp interceptors for auth headers and request logging
- **Error handling**: Kotlin sealed `Result<T>` classes — `Result.Success(data)` / `Result.Error(exception)`. Never swallow exceptions. Service errors logged + user notification.
- **Validation**: Domain-level validation in use cases before DB insert. No external validation library needed.
- **State management**: Jetpack Compose + StateFlow for UI state. Service state communicated via `StateFlow` exposed through bound service connection.
- **Naming**: PascalCase classes, camelCase functions/variables, SCREAMING_SNAKE_CASE constants. Package structure follows Clean Architecture layers.
- **Coroutines**: All audio processing on `Dispatchers.Default`, I/O on `Dispatchers.IO`, UI on `Dispatchers.Main`. No blocking calls on main thread.
- **DI**: Hilt for dependency injection. One module per layer (`ServiceModule`, `DataModule`, `DomainModule`).

### Project Structure
```
selective-speaker/
  app/
    src/main/
      AndroidManifest.xml
      java/com/frontieraudio/app/
        di/                          — Hilt DI modules
          AppModule.kt
          ServiceModule.kt
          DataModule.kt
        data/
          local/                     — Room DB
            AppDatabase.kt
            entity/
              AudioChunkEntity.kt
              TranscriptEntity.kt
              LocationEntity.kt
            dao/
              AudioChunkDao.kt
              TranscriptDao.kt
          remote/                    — Network clients
            AssemblyAiClient.kt
            LlmPostProcessor.kt
          repository/
            TranscriptRepository.kt
            AudioRepository.kt
        domain/
          model/                     — Business entities
            AudioChunk.kt
            LocationPoint.kt
            Transcript.kt
            SpeakerProfile.kt
          repository/                — Repository interfaces
            ITranscriptRepository.kt
            IAudioRepository.kt
          usecase/
            VerifySpeakerUseCase.kt
            EnrollSpeakerUseCase.kt
            SyncTranscriptsUseCase.kt
        service/                     — Android services
          RecordingForegroundService.kt
          ServiceNotificationManager.kt
          WatchdogWorker.kt
          BatteryOptimizationHelper.kt
          OemDetector.kt
          audio/
            AudioCaptureManager.kt
            AudioConfig.kt
            SileroVadProcessor.kt
            BluetoothAudioRouter.kt
            AudioDeviceMonitor.kt
          speaker/
            SherpaOnnxVerifier.kt
            EnrollmentManager.kt
            EmbeddingStore.kt
          location/
            GpsTracker.kt
            LocationBatchManager.kt
          sync/
            TranscriptionSyncWorker.kt
        ui/
          theme/
            Theme.kt
            Color.kt
          screens/
            OnboardingScreen.kt
            EnrollmentScreen.kt
            DashboardScreen.kt
            TranscriptDetailScreen.kt
          navigation/
            AppNavGraph.kt
      res/
        values/strings.xml
        raw/                         — ML model files
          silero_vad.onnx
          ecapa_tdnn.onnx
    build.gradle.kts
  build.gradle.kts
  settings.gradle.kts
  gradle/libs.versions.toml
  web-dashboard/                     — Phase 2
  .env.example
```

### Shared Interfaces
- `domain/model/AudioChunk.kt`: Audio segment with PCM data, timestamp, duration, sample rate, speaker-verified flag. Used by: features 2, 3, 4, 5, 6
- `domain/model/LocationPoint.kt`: GPS coordinate with lat, lng, accuracy, timestamp. Used by: features 5, 7
- `domain/model/Transcript.kt`: Transcribed text with word-level timestamps, GPS reference, session ID. Used by: features 5, 6, 9
- `domain/model/SpeakerProfile.kt`: Enrolled user's embedding vector, enrollment timestamp, quality score. Used by: features 3, 4
- `domain/repository/ITranscriptRepository.kt`: Interface for transcript CRUD + sync status. Used by: features 5, 6, 9
- `domain/repository/IAudioRepository.kt`: Interface for audio chunk queue management. Used by: features 2, 4, 5, 6

### Data Model
- **SpeakerProfile**: userId (PK), embeddingVector (FloatArray), enrolledAt (Long), qualityScore (Float)
- **RecordingSession**: sessionId (PK), startTime (Long), endTime (Long?), deviceId (String), isActive (Boolean)
- **AudioChunkEntity**: chunkId (PK), sessionId (FK->RecordingSession), audioData (ByteArray), startTimestamp (Long), durationMs (Int), sampleRate (Int), isSpeakerVerified (Boolean), syncStatus (PENDING|UPLOADING|TRANSCRIBED|FAILED)
- **TranscriptEntity**: transcriptId (PK), chunkId (FK->AudioChunk), text (String), words (JSON — word-level timestamps), latitude (Double), longitude (Double), locationAccuracy (Float), createdAt (Long), syncedAt (Long?)
- **LocationEntity**: locationId (PK), latitude (Double), longitude (Double), accuracy (Float), timestamp (Long), sessionId (FK->RecordingSession)

### Dependencies
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1` — async processing
- `androidx.compose.ui:ui:1.7.0` + `material3:1.3.0` — UI framework
- `androidx.compose.material:material-icons-extended` — icons
- `androidx.navigation:navigation-compose:2.8.0` — screen navigation
- `androidx.room:room-runtime:2.6.1` + `room-ktx` + `room-compiler` (KSP) — local DB
- `androidx.datastore:datastore-preferences:1.1.1` — enrollment state, settings
- `androidx.work:work-runtime-ktx:2.9.1` — background sync + watchdog
- `com.google.dagger:hilt-android:2.51` + `hilt-compiler` (KSP) — DI
- `com.squareup.retrofit2:retrofit:2.11.0` + `converter-gson:2.11.0` — HTTP client
- `com.squareup.okhttp3:okhttp:4.12.0` + `logging-interceptor` — network layer
- `com.google.android.gms:play-services-location:21.3.0` — GPS
- `com.microsoft.onnxruntime:onnxruntime-android:1.17.3` — ML inference runtime
- `com.k2-fsa:sherpa-onnx:1.10.x` — speaker verification (may need AAR from GitHub releases)
- `org.junit.jupiter:junit-jupiter-api:5.10.2` — unit tests
- `io.mockk:mockk:1.13.10` — mocking
- `androidx.test.ext:junit:1.2.1` — instrumented tests

### Gotchas
- **Bluetooth stabilization delay**: setCommunicationDevice() returns "accepted" not "complete". Must wait 500-1500ms after callback, discard first ~500ms of audio frames
- **AudioRecord recreation on BT disconnect**: Sample rate changes from 8-16kHz (SCO) to 48kHz (built-in mic) require destroying and recreating AudioRecord — not just rerouting
- **setCommunicationDevice requires MODE_IN_COMMUNICATION**: Must set audio mode BEFORE routing request, or system silently ignores
- **Samsung phantom delay**: S23-S26 have 200-500ms delay after BT "Connected" state before audio path opens — recording during this window yields null bytes
- **Pixel VOICE_COMMUNICATION theft**: Hey Google can steal VOICE_COMMUNICATION source on Pixel 7-10, producing silent PCM. Fallback to VOICE_RECOGNITION
- **NoiseSuppressor.isAvailable() lies**: Many mid-range devices report true but produce silent recordings. Don't trust it.
- **OEM killing**: Samsung One UI 7 pre-kills services after 4hr predicted inactivity. Xiaomi requires Background Autostart permission. WorkManager heartbeat is safety net, not primary defense.
- **NO noise preprocessing**: Drives WER UP 15%. Modern STT models handle noise. VAD trim only.
- **PRIORITY_BALANCED_POWER_ACCURACY = 100m, not 10m**: Must use HIGH_ACCURACY for field use
- **Microphone FGS cannot start from background**: Must be user-initiated from foreground Activity. START_STICKY restart may fail with SecurityException if app is background.
- **Google Play FGS declaration**: Microphone FGS requires formal Play Store declaration with feature description and video demo
- **sherpa-onnx AAR distribution**: May not be on Maven Central — check GitHub releases for latest AAR

### Risks
| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| OEM battery kill (Samsung/Xiaomi) | High — service dies, no recording | High | OEM-specific onboarding UX + WorkManager watchdog + battery whitelist |
| Bluetooth silent audio bugs | High — records silence | Medium | 500ms delay + frame discard + VOICE_RECOGNITION fallback + LE Audio preference |
| Speaker verification accuracy at 8kHz SCO | Medium — false rejections | Medium | Adaptive cosine threshold by audio source + prefer LE Audio (32kHz) |
| sherpa-onnx not on Maven Central | Low — build complexity | Medium | Download AAR from GitHub releases, host in local libs/ or internal Maven |
| AssemblyAI API changes/outage | High — no transcription | Low | Offline queue absorbs outage + fallback to Deepgram Nova-3 |
| Google Play FGS policy rejection | High — can't publish | Low | Proper declaration, video demo, legitimate voice recorder use case |
| Battery drain >20%/day | Medium — user uninstalls | Medium | VAD gates heavy processing, batch GPS, efficient audio pipeline |

### Cost Estimate
**Development complexity:**
| Feature | Size | Notes |
|---------|------|-------|
| 0. Bootstrap | S | Scaffold, deps, shared types, DB schema |
| 1. Permissions & Onboarding | M | OEM detection adds complexity |
| 2. Audio Pipeline & VAD | L | Core audio processing, buffer management |
| 3. Speaker Verification | L | ML model integration, enrollment UX |
| 4. Foreground Service | M | Orchestration, wake locks, lifecycle |
| 5. Local Persistence | S | Standard Room DB CRUD |
| 6. Cloud STT Integration | M | AssemblyAI API, sync worker, retry logic |
| 7. GPS Tracking | S | FusedLocation API, batching |
| 8. Bluetooth Routing | XL | Most fragile, OEM bugs, defense-in-depth |
| 9. Web Dashboard | M | New stack (web), auth, data sync |
| 8. LLM Post-Processing | S | API call + prompt engineering |

**Monthly operational costs (AssemblyAI STT is primary driver):**
Assumes ~60 min/day active speech per user (VAD-filtered), 22 working days/month = ~1,320 min/user/month.

| Component | 1K users | 10K users | 100K users |
|-----------|----------|-----------|------------|
| AssemblyAI STT | $4,600/mo | $46,000/mo | $460,000/mo |
| Firebase (Firestore + Auth) | $25-50/mo | $100-300/mo | $1,000-3,000/mo |
| Firebase Storage (audio) | $50-100/mo | $500-1,000/mo | $5,000-10,000/mo |
| **Total** | **$4,700-4,750/mo** | **$46,600-47,300/mo** | **$466,000-473,000/mo** |

Primary cost driver: AssemblyAI at $3.50/1K min dominates. At scale, negotiated volume pricing would reduce this significantly. Deepgram Nova-2 at $5.80/1K min is a fallback; Google Chirp batch at $4/1K min with 24hr delay is cheapest.

### Deployment
- **Platform**: Firebase App Distribution for demo APK delivery + Firestore for dashboard backend
- **Build**: `./gradlew assembleRelease` (signed APK)
- **Deploy**: Upload APK to Firebase App Distribution via `firebase appdistribution:distribute`
- **Dashboard**: Deploy web dashboard to Firebase Hosting (`firebase deploy --only hosting`)
- **Secrets**: API keys in `.env` file loaded via BuildConfig. Never committed to git. Firebase config via `google-services.json` (gitignored).

## Test Strategy

### Critical paths
- Foreground service starts from Activity and survives screen off + 30 min idle
- AudioRecord captures audio at correct sample rate (16kHz built-in, 8-32kHz BT)
- Silero VAD correctly gates speech vs silence (>90% precision on speech detection)
- Speaker verification accepts enrolled user and rejects non-enrolled speakers
- Verified audio chunks persist to Room DB and survive app process kill
- SyncWorker uploads queued chunks to AssemblyAI when connectivity available
- GPS coordinates attached to transcript segments within 5-second accuracy
- Service survives Doze mode entry with battery whitelist enabled

### Edge cases
- BT headset connects/disconnects mid-sentence — audio continuity preserved
- Network drops during AssemblyAI upload — chunk re-queued with PENDING status
- Device reboots — service cannot auto-start (by design), user must re-launch
- Low battery mode — service continues (battery whitelist exemption)
- Multiple BT devices paired — correct mic selected
- Enrollment with background noise — embedding quality score below threshold triggers re-enrollment prompt
- 8kHz SCO audio fed to speaker verifier trained on 16kHz — threshold adaptation

### Integration boundaries
- AssemblyAI API: audio format requirements (WAV/PCM), max upload size, polling timeout
- sherpa-onnx: model file loading from assets, ONNX Runtime version compatibility
- Room DB: schema migration strategy for future versions
- FusedLocationProvider: permission grant timing, accuracy in buildings

### What NOT to test
- Compose UI rendering — visual, fails obviously
- Hilt module wiring — DI, fails at compile/startup
- Room DAO SQL syntax — Room compiler catches at build time
- Android permission dialogs — system UI, not controllable

## Blast Radius
- **RecordingForegroundService**: Central orchestrator — touches audio, GPS, verification, sync. Changes here affect everything. Must be integration-tested end-to-end.
- **AudioCaptureManager**: Feeds all downstream processing. Sample rate or buffer changes break VAD, verification, and STT.
- **Room DB schema**: All features depend on persistence. Schema changes require migration strategy.
- **AssemblyAI client**: Single cloud dependency. API changes break transcription pipeline. Mitigation: adapter pattern for swappable STT providers.
- Confidence: **exhaustive** (greenfield, no existing dependents)

## Success Criteria
- User enrolls voice in <60 seconds on first launch
- App records continuously for 8+ hours without service interruption
- Only enrolled user's speech appears in transcripts (zero other-speaker segments in demo)
- Transcripts appear in cloud within 5 minutes of speech (when connected)
- GPS coordinates accurate to <20m on each transcript segment
- App works with screen off, on charger, with other apps open
- Supervisor can view GPS-tagged, timestamped transcripts on web dashboard
- Battery drain <15% over 8-hour workday

## Environment
- `ASSEMBLY_AI_API_KEY` — AssemblyAI transcription API authentication (required)
- `FIREBASE_PROJECT_ID` — Firebase project for Firestore + Auth + Hosting (required for dashboard)
- `OPENAI_API_KEY` — LLM post-processing for transcript error correction (required)

## Decisions
- **Cloud STT provider**: AssemblyAI Universal-3 Pro — best noisy WER (9.97%) at competitive pricing ($3.50/1K min). Word-level timestamps included. (research recommendation, confirmed by benchmarks)
- **Speaker verification**: sherpa-onnx with ECAPA-TDNN — production-ready on-device ONNX inference, 192-dim embeddings, ~0.80% EER. (research finding)
- **VAD**: Silero VAD — lightweight, 16kHz, <1ms per frame. Gates speaker verification to save battery. (research finding)
- **Audio preprocessing**: NONE — research proves noise preprocessing drives WER up 15%. Send raw audio, VAD trim only. Overrides clarify decision. (research override)
- **GPS accuracy**: PRIORITY_HIGH_ACCURACY with batching — BALANCED only gives 100m, insufficient for field use. (research correction)
- **Upload strategy**: Speech-activity-driven chunks with Room DB offline queue — no fixed time windows, no wasted empty data. (user decision)
- **Backend**: Firebase (Firestore + Auth + Hosting) for web dashboard — minimal backend overhead for demo. (recommended)
- **Bluetooth routing**: setCommunicationDevice() (API 31+) with defense-in-depth — replaces deprecated startBluetoothSco(). (research finding)
- **Min SDK**: 31 (Android 12) — required for setCommunicationDevice() and modern BT APIs. (research constraint)
- **DI framework**: Hilt — standard Android DI, well-integrated with Jetpack components. (recommended)

## Constraints
- Kotlin for Android (requirement)
- Android 15 preferred, minSdk 31 for modern BT APIs (requirement + research constraint)
- Always-on: survives sleep, Doze, charger idle, low battery — only stops on power-down (requirement)
- One-time voice enrollment at first startup (requirement)
- GPS tagging on all segments, location always on (requirement)
- Bluetooth mic auto-switching with seamless fallback (requirement)
- LLM post-processing for error correction (requirement, MVP)
- Production-grade demo for Frontier Audio submission (scope)
- No noise preprocessing before STT (research override)

## Reference
- [Android FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [LE Audio Recording Guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording)
- [AssemblyAI API Docs](https://www.assemblyai.com/docs)
- [sherpa-onnx GitHub](https://github.com/k2-fsa/sherpa-onnx)
- [Silero VAD GitHub](https://github.com/snakers4/silero-vad)
- [Don't Kill My App](https://dontkillmyapp.com/) — OEM battery optimization database
- [Battery Technical Quality Enforcement](https://android-developers.googleblog.com/2026/03/battery-technical-quality-enforcement.html)
- [AssemblyAI Benchmarks](https://www.assemblyai.com/benchmarks)
- [Artificial Analysis STT Index](https://artificialanalysis.ai/speech-to-text)
