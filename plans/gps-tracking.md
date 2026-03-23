# GPS Tracking

Story: story-1109
Agent: architect

## Context

Battery-efficient GPS tracking using FusedLocationProviderClient with PRIORITY_HIGH_ACCURACY and batch delivery. Timestamps synchronized with audio segments via SystemClock.elapsedRealtimeNanos(). (see briefing ## Technical Research > GPS: BALANCED=100m, need HIGH_ACCURACY)

## What changes

| File | Change |
|---|---|
| app/src/main/java/com/frontieraudio/app/service/location/GpsTracker.kt | Wraps FusedLocationProviderClient: request HIGH_ACCURACY updates, emit LocationPoint via Flow, handle permission |
| app/src/main/java/com/frontieraudio/app/service/location/LocationBatchManager.kt | Buffers location updates, provides nearest-timestamp lookup for audio chunk association |

## Contract

- `GpsTracker.start(): Flow<LocationPoint>` — requests location updates with HIGH_ACCURACY, 30s interval, 5min batch delay
- `GpsTracker.stop()` — removes location updates
- `GpsTracker.lastKnownLocation: LocationPoint?` — cached last location
- `LocationBatchManager.addLocation(point: LocationPoint)` — buffers location point
- `LocationBatchManager.getNearestLocation(timestamp: Long): LocationPoint?` — returns location closest to given timestamp within 30s window
- `LocationBatchManager.clear()` — clears buffer

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Gotchas: PRIORITY_BALANCED=100m not 10m, must use HIGH_ACCURACY; see ## Features > 7 for batching strategy

## Tasks

1. Implement GpsTracker: create LocationRequest.Builder with PRIORITY_HIGH_ACCURACY, intervalMillis=30000 (30s), setMaxUpdateDelayMillis=300000 (5min batch), request via requestLocationUpdates with LocationCallback, emit LocationPoint via MutableSharedFlow, cache lastKnownLocation
2. Implement LocationBatchManager: circular buffer of LocationPoint (max 100 entries), addLocation inserts with timestamp, getNearestLocation performs binary search by timestamp returning closest point within 30s tolerance, clear() resets buffer
3. Handle permissions: verify ACCESS_FINE_LOCATION before starting, handle SecurityException gracefully
<!-- END_CODER_ONLY -->

## Acceptance criteria

- Given location permission granted, when GpsTracker.start() called, then LocationPoint emissions begin within 30s
- Given location updates flowing, when getNearestLocation called with audio chunk timestamp, then returns LocationPoint within 30s of that timestamp
- Given GPS updates arriving batched every 5 min, then battery impact is minimized vs continuous updates
- GpsTracker.stop() removes all location callbacks

## Verification

- Unit test: LocationBatchManager.getNearestLocation returns correct point from sorted buffer
- Unit test: getNearestLocation returns null when no point within 30s window
- Instrumented test: GpsTracker emits real GPS coordinates on physical device

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
