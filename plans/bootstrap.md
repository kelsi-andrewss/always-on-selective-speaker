# Bootstrap — Gradle KTS, Dependencies, Domain Models, Hilt Modules

Story: story-1102
Agent: architect

## Context

Initialize the Android project skeleton: Gradle KTS build files with version catalog, all dependencies declared, shared domain model data classes, Hilt DI module stubs, and .env.example. This is the foundation every other story depends on. Package: `com.frontieraudio.app`.

## What changes

| File | Change |
|---|---|
| build.gradle.kts | Root Gradle build file with Hilt and KSP plugin declarations |
| app/build.gradle.kts | App module with all dependencies, minSdk 31, targetSdk 35, Compose, Room, Hilt, ONNX Runtime |
| settings.gradle.kts | Project name, repository declarations, version catalog |
| gradle/libs.versions.toml | Version catalog: coroutines 1.8.1, compose 1.7.0, room 2.6.1, retrofit 2.11.0, hilt 2.51, location 21.3.0, onnxruntime 1.17.3, workmanager 2.9.1, junit5 5.10.2, mockk 1.13.10 |
| app/src/main/java/com/frontieraudio/app/domain/model/AudioChunk.kt | Data class: pcmData ByteArray, startTimestamp Long, durationMs Int, sampleRate Int, isSpeakerVerified Boolean |
| app/src/main/java/com/frontieraudio/app/domain/model/LocationPoint.kt | Data class: latitude Double, longitude Double, accuracy Float, timestamp Long |
| app/src/main/java/com/frontieraudio/app/domain/model/Transcript.kt | Data class: id String, text String, correctedText String?, words List<WordTimestamp>, locationPoint LocationPoint?, sessionId String, createdAt Long |
| app/src/main/java/com/frontieraudio/app/domain/model/SpeakerProfile.kt | Data class: userId String, embeddingVector FloatArray, enrolledAt Long, qualityScore Float |
| app/src/main/java/com/frontieraudio/app/di/AppModule.kt | Hilt @Module providing application-level singletons (Context, SharedPreferences, DataStore) |
| app/src/main/java/com/frontieraudio/app/di/DataModule.kt | Hilt @Module stub — TODO placeholder for Room/Network bindings (owned by story-1107 and story-1108) |
| .env.example | ASSEMBLY_AI_API_KEY=, OPENAI_API_KEY= |

## Contract

- `AudioChunk(pcmData: ByteArray, startTimestamp: Long, durationMs: Int, sampleRate: Int, isSpeakerVerified: Boolean)` — immutable audio segment
- `LocationPoint(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long)` — GPS coordinate
- `Transcript(id: String, text: String, correctedText: String?, words: List<WordTimestamp>, locationPoint: LocationPoint?, sessionId: String, createdAt: Long)` — transcription result
- `WordTimestamp(text: String, start: Long, end: Long, confidence: Float)` — word-level timing
- `SpeakerProfile(userId: String, embeddingVector: FloatArray, enrolledAt: Long, qualityScore: Float)` — enrolled voice print

<!-- CODER_ONLY -->
## Read-only context

These files inform the implementation but should not be modified:
- `presearch/android-selective-speaker.md` — full technical briefing (see ## Dependencies for exact artifact IDs, ## Project Structure for directory layout)

## Tasks

1. Create root `build.gradle.kts` with Hilt plugin (`com.google.dagger.hilt.android`), KSP plugin, and Android application plugin declarations
2. Create `settings.gradle.kts` with project name "selective-speaker", Google/Maven Central repositories, version catalog enabled
3. Create `gradle/libs.versions.toml` with ALL dependency versions and library aliases per briefing ## Dependencies
4. Create `app/build.gradle.kts`: android block (namespace `com.frontieraudio.app`, compileSdk 35, minSdk 31, targetSdk 35), Compose enabled, Hilt plugin, KSP for Room, all dependency references from version catalog
5. Create `AudioChunk.kt`, `LocationPoint.kt`, `Transcript.kt`, `WordTimestamp.kt`, `SpeakerProfile.kt` in domain/model/
6. Create `AppModule.kt` Hilt module providing application Context and DataStore<Preferences>
7. Create `DataModule.kt` stub with TODO comment (Room bindings provided by story-1107)
8. Create `.env.example` with commented signup URLs:
   ```
   # AssemblyAI — Sign up at https://www.assemblyai.com/dashboard/signup
   ASSEMBLY_AI_API_KEY=
   # OpenAI — Sign up at https://platform.openai.com/signup
   OPENAI_API_KEY=
   ```
9. Create `app/src/main/java/com/frontieraudio/app/SelectiveSpeakerApplication.kt` with @HiltAndroidApp annotation
<!-- END_CODER_ONLY -->

## Acceptance criteria

- Project compiles with `./gradlew assembleDebug` with zero errors
- All domain model classes are importable from `com.frontieraudio.app.domain.model`
- Hilt Application class is annotated and declared in a placeholder AndroidManifest.xml
- Version catalog contains all dependencies listed in briefing ## Dependencies

## Verification

- Run `./gradlew assembleDebug` — must succeed
- Verify package is `com.frontieraudio.app` (NOT jarvis)

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
