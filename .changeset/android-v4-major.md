---
'posthog': major
'posthog-android': major
---

- **Breaking:** remove the deprecated `apiKey` property and the `PostHogConfig(apiKey = …)` and `PostHogAndroidConfig(apiKey = …)` factories; use `projectToken` instead
- **Breaking:** remove the deprecated `propertiesSanitizer` config option and the `PostHogPropertiesSanitizer` interface; use `addBeforeSend` instead
- **Breaking:** remove the deprecated `evaluationEnvironments` config option; use `evaluationContexts` instead
- **Breaking:** remove the deprecated `getFeatureFlagPayload()`; use `getFeatureFlagResult(key)?.payload` instead
- **Breaking:** remove the deprecated `PostHogSessionReplayConfig.debouncerDelayMs`; use `throttleDelayMs` instead
- **Breaking:** return `null` instead of the raw string for a malformed, empty or whitespace-only feature flag payload from `getFeatureFlagResult(key)?.payload`; payloads must be valid JSON (a JSON `null` payload now also reads as `null` instead of the string `"null"`)
- **Breaking:** make SDK-internal helpers in the `com.posthog.internal` and `com.posthog.android.replay.internal` packages non-public
- **Breaking:** remove the deprecated `remoteConfig` config option; the SDK now always loads remote config at setup
- **Breaking:** replace `PostHogOnFeatureFlags` with `PostHogFeatureFlagsCallback` in `PostHogConfig.onFeatureFlags` and `reloadFeatureFlags()`; it receives a `PostHogFeatureFlagsLoaded` with the enabled flags, their values, and `errorsLoading`
