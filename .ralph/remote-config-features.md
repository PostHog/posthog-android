
# Implement Remote Config for errorTracking, logs, and capturePerformance

## Goals
1. Parse `errorTracking`, `logs`, and `capturePerformance` from remote config API response
2. Cache these values to disk (like sessionRecording)
3. Preload cached values on start (like preloadSessionReplayFlag)
4. Apply remote config with AND logic: both remote AND local must be enabled
5. Apply on `onRemoteConfigLoaded`

## Checklist
- [x] Add `errorTracking`, `logs`, `capturePerformance` fields to `PostHogRemoteConfigResponse`
- [x] Add cache keys to `PostHogPreferences.Companion` (ERROR_TRACKING, LOGS, CAPTURE_PERFORMANCE)
- [x] Add `PostHogRemoteConfig`: `processErrorTrackingConfig()` method
- [x] Add `PostHogRemoteConfig`: `processLogsConfig()` method
- [x] Add `PostHogRemoteConfig`: `processCapturePerformanceConfig()` method
- [x] Add `PostHogRemoteConfig`: `preloadErrorTrackingConfig()` in init
- [x] Add `PostHogRemoteConfig`: `preloadLogsConfig()` in init
- [x] Add `PostHogRemoteConfig`: `preloadCapturePerformanceConfig()` in init
- [x] Call process methods in `loadRemoteConfig` (like processSessionRecordingConfig)
- [x] Clear cached values in `clear()`
- [x] Add public getter methods for resolved config values
- [x] Verify the AND logic: remote enabled AND local enabled = enabled
- [x] Update test JSON fixtures
- [x] Build and verify compilation
