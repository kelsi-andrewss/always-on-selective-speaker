# Permissions & OEM Onboarding

Story: story-1103
Agent: quick-fixer

## Context

Runtime permission flow for RECORD_AUDIO, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS plus battery optimization whitelist and OEM-specific guidance. This is the gate before the foreground service can start. (see briefing ## Features > 1. Permissions & OEM Onboarding)

## What changes

| File | Change |
|---|---|
| app/src/main/AndroidManifest.xml | Declare permissions: RECORD_AUDIO, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_LOCATION, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. Declare foreground service type. |
| app/src/main/java/com/frontieraudio/app/ui/screens/OnboardingScreen.kt | Compose screen: sequential permission requests, battery optimization prompt, OEM-specific guidance cards |
| app/src/main/java/com/frontieraudio/app/service/BatteryOptimizationHelper.kt | Request ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, check isIgnoringBatteryOptimizations(), fallback to ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS |
| app/src/main/java/com/frontieraudio/app/service/OemDetector.kt | Detect Samsung (One UI), Xiaomi (MIUI/HyperOS) via Build.MANUFACTURER. Return OEM-specific instructions. |

## Contract

- `BatteryOptimizationHelper.requestWhitelist(activity: Activity): Boolean` — launches whitelist intent, returns true if already whitelisted
- `BatteryOptimizationHelper.isWhitelisted(context: Context): Boolean` — checks PowerManager.isIgnoringBatteryOptimizations
- `OemDetector.detect(): OemType` — returns SAMSUNG, XIAOMI, or GENERIC
- `OemDetector.getInstructions(oem: OemType): OemInstructions` — OEM-specific battery setting navigation text

<!-- CODER_ONLY -->
## Read-only context

- `presearch/android-selective-speaker.md` — see ## Gotchas for OEM-specific killing behavior, ## Features > 1 for full requirements

## Tasks

1. Write AndroidManifest.xml with all required permissions, foreground service declaration with `android:foregroundServiceType="microphone|location"`, and Hilt application reference
2. Implement BatteryOptimizationHelper: check whitelist status via PowerManager, request via ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent, fallback to manual settings intent
3. Implement OemDetector: detect manufacturer from Build.MANUFACTURER, return enum (SAMSUNG, XIAOMI, GENERIC), provide per-OEM instructions (Samsung: "Settings > Battery > Background usage limits > Never sleeping apps", Xiaomi: "Settings > Apps > Manage apps > [app] > Autostart")
4. Build OnboardingScreen Compose UI: step-by-step permission flow using rememberLauncherForActivityResult, battery optimization step, OEM guidance card if applicable, "Continue" button enabled only when all permissions granted and whitelist set
<!-- END_CODER_ONLY -->

## Acceptance criteria

- User opens app for first time → sees onboarding screen with permission requests
- User grants RECORD_AUDIO → next permission step appears (ACCESS_FINE_LOCATION)
- User grants all permissions → battery optimization prompt appears
- User allows battery whitelist → OEM guidance shows if Samsung/Xiaomi detected
- User completes onboarding → navigates to enrollment screen
- AndroidManifest contains FOREGROUND_SERVICE_MICROPHONE and FOREGROUND_SERVICE_LOCATION permissions
- isIgnoringBatteryOptimizations returns true after whitelist granted

## Verification

- Install on device, verify permission dialogs appear in sequence
- Check `adb shell dumpsys deviceidle whitelist` includes app package

<!-- TESTER_ONLY -->
<!-- END_TESTER_ONLY -->
