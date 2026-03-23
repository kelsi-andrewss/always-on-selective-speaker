# Foreground Service

Story: story-1106
Agent: architect

## Context

The central always-on service that orchestrates audio capture, VAD, speaker verification, GPS, and upload queue. Uses microphone|location foreground service type (timeout-exempt). Acquires PARTIAL_WAKE_LOCK for Doze survival. WorkManager watchdog restarts if killed. (see briefing ## Technical Research > ### Architecture and ## Gotchas)

## What changes

| File | Change |
|---|---|
| app/src/main/java/com/frontieraudio/app/service/RecordingForegroundService.kt | Foreground service: startForeground with MICROPHONE\|LOCATION type, PARTIAL_WAKE_LOCK, orchestrates pipeline components, handles lifecycle |
| app/src/main/java/com/frontieraudio/app/service/ServiceNotificationManager.kt | Creates persistent notification channel, builds notification with recording status, elapsed time |
| app/src/main/java/com/frontieraudio/app/service/WatchdogWorker.kt | PeriodicWorkRequest (15 min) checks service liveness, restarts via Activity intent if dead |

## Contract

- `RecordingForegroundService` — started via `startForegroundService()` from Activity only. Binds AudioCaptureManager, SileroVadProcessor, SherpaOnnxVerifier, GpsTracker. Pipeline: audio frames → VAD → verification → persist verified chunks.
- `RecordingForegroundService.isRunning: StateFlow<Boolean>` — observable service state
- `ServiceNotificationManager.createNotification(elapsed: Duration): Notification` — persistent foreground notification
- `WatchdogWorker.enqueue(context: Context)` — schedules periodic liveness check

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Gotchas: service cannot start from background, START_STICKY restart may fail, OEM killing behavior
- `app/src/main/AndroidManifest.xml` — service declaration (from story-1103)
- All service/* files from stories 1104, 1105, 1109 — components this service orchestrates

## Tasks

1. Implement RecordingForegroundService: onCreate acquires PARTIAL_WAKE_LOCK ("com.frontieraudio.app:recording"), onStartCommand returns START_STICKY, startForeground() with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION`, inject pipeline components via Hilt
2. Implement pipeline orchestration in service: launch coroutine scope, AudioCaptureManager.start() → SileroVadProcessor.collectSpeechSegment() → SherpaOnnxVerifier.verify() → if matched, write AudioChunk to Room via DAO, attach GPS from GpsTracker
3. Implement ServiceNotificationManager: create NotificationChannel (IMPORTANCE_LOW), build persistent notification showing "Recording — {elapsed}", update every minute
4. Implement WatchdogWorker: PeriodicWorkRequest every 15 min, checks RecordingForegroundService.isRunning, if false launches transparent Activity to restart service (microphone FGS requires foreground context)
5. Handle lifecycle: onDestroy releases wake lock and stops audio, onTaskRemoved restarts via alarm, service state exposed via companion object StateFlow
6. Implement pipeline error isolation: wrap each component (AudioCaptureManager, SileroVadProcessor, SherpaOnnxVerifier, GpsTracker) in individual try-catch within the coroutine pipeline. Log errors but don't kill the service — a GPS failure shouldn't stop audio recording, a verification failure shouldn't stop VAD
<!-- END_CODER_ONLY -->

## Acceptance criteria

- Given permissions granted and enrollment complete, when user taps "Start Recording", then foreground service starts with persistent notification showing "Recording"
- Given service is running, when screen turns off and device enters Doze, then audio capture continues (battery whitelist active)
- Given service is running, when user opens other apps, then recording is uninterrupted
- Given service is killed by system, when WatchdogWorker fires within 15 min, then it relaunches service via Activity context
- Notification channel shows "Always-On Monitoring" with elapsed recording time

## Verification

- Start service, lock screen, wait 5 min, verify audio chunks in Room DB
- Kill app from recents, verify WatchdogWorker fires and service restarts

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
