---
'posthog': major
'posthog-android': major
---

- **Breaking:** remove the deprecated `apiKey` property and the `PostHogConfig(apiKey = …)` and `PostHogAndroidConfig(apiKey = …)` factories; use `projectToken` instead
- **Breaking:** remove the deprecated `propertiesSanitizer` config option and the `PostHogPropertiesSanitizer` interface; use `addBeforeSend` instead
- **Breaking:** remove the deprecated `evaluationEnvironments` config option; use `evaluationContexts` instead
- **Breaking:** remove the deprecated `getFeatureFlagPayload()`; use `getFeatureFlagResult(key, sendFeatureFlagEvent = false)?.payload` instead to keep not sending `$feature_flag_called`
- **Breaking:** remove the `defaultValue` parameter from `getFeatureFlag()`, which returns `null` for a flag with no value or a disabled SDK, so use `getFeatureFlag(key) ?: default` instead; a positional `Boolean` second argument now sets `sendFeatureFlagEvent`, and `$feature_flag_called` no longer reports a fallback from `getFeatureFlag()` or `isFeatureEnabled()` as the flag's value
- **Breaking:** remove the deprecated `PostHogSessionReplayConfig.debouncerDelayMs`; use `throttleDelayMs` instead
- **Breaking:** return `null` instead of the raw string for a malformed, empty, whitespace-only or lenient-only (unquoted or single-quoted) feature flag payload from `getFeatureFlagResult(key)?.payload`; payloads must be valid JSON (a JSON `null` payload now also reads as `null` instead of the string `"null"`)
- **Breaking:** make SDK-internal helpers in the `com.posthog.internal`, `com.posthog.android.internal` and `com.posthog.android.replay.internal` packages non-public
- **Breaking:** remove the deprecated `remoteConfig` config option; the SDK now always loads remote config at setup
- **Breaking:** remove `PostHogConfig.onFeatureFlags`; call `PostHog.onFeatureFlags(callback)` before `setup()` instead, which also covers the first load
- **Breaking:** replace `PostHogOnFeatureFlags` with `PostHogFeatureFlagsCallback` in `reloadFeatureFlags()`; it receives a `PostHogFeatureFlagsLoaded` with the enabled flags, their values, and `errorsLoading`
- **Breaking:** add the abstract `onFeatureFlags()` method to `PostHogInterface`; classes that implement it must add the method
- Add `onFeatureFlags()` to get notified after every feature flags load (on the main thread on Android), returning a `PostHogFeatureFlagsSubscription` you can `unsubscribe()`
- See the [3.x to 4.0 migration guide](https://github.com/PostHog/posthog-android/blob/main/docs/migration-3.x-to-4.0.md) for upgrade steps
