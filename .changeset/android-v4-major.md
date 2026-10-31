---
'posthog': major
'posthog-android': major
'posthog-android-surveys-compose': minor
---

- **Breaking:** remove the deprecated `apiKey` property and the `PostHogConfig(apiKey = …)` and `PostHogAndroidConfig(apiKey = …)` factories; use `projectToken` instead
- **Breaking:** remove the deprecated `propertiesSanitizer` config option and the `PostHogPropertiesSanitizer` interface; use `addBeforeSend` instead
- **Breaking:** remove the deprecated `evaluationEnvironments` config option; use `evaluationContexts` instead
- **Breaking:** remove the deprecated `getFeatureFlagPayload()`; use `getFeatureFlagResult(key, sendFeatureFlagEvent = false)?.payload` instead to keep not sending `$feature_flag_called`
- **Breaking:** remove the deprecated `PostHogSessionReplayConfig.debouncerDelayMs`; use `throttleDelayMs` instead
- **Breaking:** remove wireframe session replay; replay always records masked screenshots, the mode `screenshot = true` already used. `PostHogSessionReplayConfig.screenshot` is removed, and so are `drawableConverter` and `PostHogDrawableConverter`, which only wireframe capture read
  - Apps that relied on the default now upload screenshots. Masking still covers text inputs, images, `WebView`s, Compose semantics and `ph-no-capture` views and `Modifier.postHogMask()`, but custom-drawn views and other content the SDK doesn't recognize (for example maps or video) can now appear; mask them, or turn replay off with `sessionReplay = false`
  - Jetpack Compose screens, which wireframe captured as empty boxes, are now recorded
  - Wrapper SDKs: the internal `RRWireframe` keeps only `id`, `x`, `y`, `width`, `height`, `type` and `base64`, and `RRStyle` is removed; screenshot frames no longer send an empty `style`
- **Breaking:** return `null` instead of the raw string for a malformed, empty, whitespace-only or lenient-only (unquoted or single-quoted) feature flag payload from `getFeatureFlagResult(key)?.payload`; payloads must be valid JSON (a JSON `null` payload now also reads as `null` instead of the string `"null"`)
- **Breaking:** make SDK-internal helpers in the `com.posthog.internal`, `com.posthog.android.internal` and `com.posthog.android.replay.internal` packages non-public
- **Breaking:** remove the deprecated `remoteConfig` config option; the SDK now always loads remote config at setup
- **Breaking:** replace `PostHogOnFeatureFlags` with `PostHogFeatureFlagsCallback` in `PostHogConfig.onFeatureFlags` and `reloadFeatureFlags()`; it receives a `PostHogFeatureFlagsLoaded` with the enabled flags, their values, and `errorsLoading`
- **Breaking:** add the abstract `onFeatureFlags()` method to `PostHogInterface`; classes that implement it must add the method
- Add `onFeatureFlags()` to get notified after every feature flags load (on the main thread on Android), returning a `PostHogFeatureFlagsSubscription` you can `unsubscribe()`
- See the [3.x to 4.0 migration guide](https://github.com/PostHog/posthog-android/blob/main/docs/migration-3.x-to-4.0.md) for upgrade steps
