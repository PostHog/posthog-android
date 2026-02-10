
# Implement Remote Config for errorTracking, logs, and capturePerformance

## Goals
1. Parse `errorTracking`, `logs`, and `capturePerformance` from remote config API response
2. Cache these values to disk (like sessionRecording)
3. Preload cached values on start (like preloadSessionReplayFlag)
4. Apply remote config with AND logic: both remote AND local must be enabled
5. Apply on `onRemoteConfigLoaded`

## Checklist
- [ ] Add `errorTracking`, `logs`, `capturePerformance` fields to `PostHogRemoteConfigResponse`
- [ ] Add cache keys to `PostHogPreferences.Companion` (ERROR_TRACKING, LOGS, CAPTURE_PERFORMANCE)
- [ ] Add `PostHogRemoteConfig`: `processErrorTrackingConfig()` method
- [ ] Add `PostHogRemoteConfig`: `processLogsConfig()` method
- [ ] Add `PostHogRemoteConfig`: `processCapturePerformanceConfig()` method
- [ ] Add `PostHogRemoteConfig`: `preloadErrorTrackingConfig()` in init
- [ ] Add `PostHogRemoteConfig`: `preloadLogsConfig()` in init
- [ ] Add `PostHogRemoteConfig`: `preloadCapturePerformanceConfig()` in init
- [ ] Call process methods in `loadRemoteConfig` (like processSessionRecordingConfig)
- [ ] Clear cached values in `clear()`
- [ ] Add public getter methods for resolved config values
- [ ] Verify the AND logic: remote enabled AND local enabled = enabled
- [ ] Update test JSON fixtures
- [ ] Build and verify compilation
