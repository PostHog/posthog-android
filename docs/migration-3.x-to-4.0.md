# Migrating from PostHog Android 3.x to 4.0

4.0 removes APIs deprecated in 3.x and changes the feature flags callback.
Most changes are compile errors with a direct replacement. Read [Behavior changes](#behavior-changes) carefully: those won't show up at build time.

For the full list of changes, see the [changelog](../posthog-android/CHANGELOG.md).

## Requirements and versions

| Artifact | Upgrade |
| --- | --- |
| `com.posthog:posthog-android` | 3.x to 4.0 |
| `com.posthog:posthog` (core) | 6.x to 7.0 |
| `com.posthog:posthog-android-surveys-compose` | the release that depends on `posthog-android` 4.0 |
| `com.posthog:posthog-server` | see [`posthog-server`](#posthog-server) |

These are unchanged, so you don't need to change your build for them:

- Android minimum API level: 23
- Java bytecode target: Java 8
- Kotlin language and API compatibility: 2.0
- `posthog-android-gradle-plugin`

Upgrade PostHog artifacts together. Core 7.0 is not binary compatible with SDK versions built against core 6.x, and a mismatch fails at runtime with errors such as `NoSuchMethodError` or `NoClassDefFoundError`.

## Removed APIs

All of these are compile errors. Calling them from an already-compiled library that you haven't updated fails at runtime.

| Removed | Use instead |
| --- | --- |
| `PostHogConfig.apiKey`, `PostHogConfig(apiKey = …)`, `PostHogAndroidConfig(apiKey = …)` | `projectToken` |
| `PostHogConfig.propertiesSanitizer`, `PostHogPropertiesSanitizer` | `addBeforeSend` |
| `PostHogConfig.evaluationEnvironments` | `evaluationContexts` |
| `getFeatureFlagPayload()`, on the instance and on `PostHog` | `getFeatureFlagResult()` |
| `PostHogSessionReplayConfig.debouncerDelayMs` | `throttleDelayMs` |
| `PostHogSessionReplayConfig.screenshot`, `drawableConverter`, `PostHogDrawableConverter` | nothing; replay always records screenshots |
| `PostHogConfig.remoteConfig` | nothing; remote config always loads |
| `PostHogOnFeatureFlags` | `PostHogFeatureFlagsCallback` |

The constructor parameters of `PostHogConfig` and `PostHogSessionReplayConfig` move up where these were removed. If you pass arguments by position, check them, or switch to named arguments.

### `apiKey`

```kotlin
// 3.x
val config = PostHogAndroidConfig(apiKey = "phc_your_project_token")
val token = config.apiKey

// 4.0
val config = PostHogAndroidConfig(projectToken = "phc_your_project_token")
val token = config.projectToken
```

`PostHogAndroidConfig("phc_your_project_token")`, with no parameter name, still works. From Java, replace `getApiKey()` with `getProjectToken()`.

### `propertiesSanitizer`

```kotlin
// 3.x
config.propertiesSanitizer = PostHogPropertiesSanitizer { properties ->
    properties.apply { remove("email") }
}

// 4.0
config.addBeforeSend { event ->
    event.properties?.remove("email")
    event
}
```

`addBeforeSend` gets the whole `PostHogEvent`. Return `null` to drop the event. If the hook throws, the event is dropped.

### `evaluationEnvironments`

```kotlin
// 3.x
config.evaluationEnvironments = listOf("production")

// 4.0
config.evaluationContexts = listOf("production")
```

### `getFeatureFlagPayload()`

`getFeatureFlagPayload()` never sent `$feature_flag_called`. `getFeatureFlagResult()` sends it by default, following `sendFeatureFlagEvent`, so pass `sendFeatureFlagEvent = false` to keep your event volume and experiment exposures unchanged. If you drop that argument, nothing fails, but you'll send new events.

The new call has no `defaultValue` parameter, so apply your default with `?:`.

```kotlin
// 3.x
val payload = PostHog.getFeatureFlagPayload("checkout", defaultValue = "fallback")

// 4.0
val payload = PostHog.getFeatureFlagResult("checkout", sendFeatureFlagEvent = false)?.payload ?: "fallback"
```

### `debouncerDelayMs`

```kotlin
// 3.x
PostHogSessionReplayConfig(debouncerDelayMs = 500)

// 4.0
PostHogSessionReplayConfig(throttleDelayMs = 500)
```

### `remoteConfig`

Delete the option. Apps that left it at its default of `true` see no behavior change.

```kotlin
// 3.x
config.remoteConfig = false

// 4.0: remove the line
```

If you set it to `false`, your app's behavior changes:

- The SDK now requests `/config` at setup, even if `preloadFeatureFlags` is also `false`, when 3.x sent no request at all.
- The settings in `/config` now apply, including the session replay, surveys, error tracking and push settings from your project.
- With `preloadFeatureFlags = true`, the SDK requests `/flags` only if `/config` reports that the project has feature flags. In 3.x it always requested `/flags`. If the project has none, the cached flags are cleared.

## Feature flags callback

`PostHogConfig.onFeatureFlags` and `reloadFeatureFlags()` now take a `PostHogFeatureFlagsCallback`, which receives a `PostHogFeatureFlagsLoaded`:

- `flags`: the keys of the enabled flags
- `variants`: their values, either `true` or the variant string
- `errorsLoading`: whether the latest load failed. The values are then the last known ones, which can be empty

```kotlin
// 3.x
config.onFeatureFlags = PostHogOnFeatureFlags {
    refreshUi()
}

// 4.0
config.onFeatureFlags = PostHogFeatureFlagsCallback { loaded ->
    if (!loaded.errorsLoading) {
        refreshUi()
    }
}
```

```java
// 3.x
PostHog.Companion.reloadFeatureFlags(() -> refreshUi());

// 4.0
PostHog.Companion.reloadFeatureFlags(loaded -> refreshUi());
```

A Kotlin trailing lambda such as `PostHog.reloadFeatureFlags { refreshUi() }` compiles unchanged, so it won't show up as an error. Check whether it should now read `errorsLoading`: the callback has always also run after a failed load.

`errorsLoading` is `false` when PostHog answers that the project is over its feature flags quota, and the values are the cached flags. It's `true` when no request was made, such as when the device is offline or the distinct ID is blank, and when the SDK is disabled or isn't set up, with no values in that last case.

These callbacks still run on a background thread, or right away on the calling thread when no load is attempted, so switch to the main thread before touching UI. With bootstrapped flags, `PostHogConfig.onFeatureFlags` also runs once on the thread calling `setup()`.

## `PostHogInterface` implementations

`PostHogInterface` lost `getFeatureFlagPayload()`, gained the abstract `onFeatureFlags()` and changed the callback type of `reloadFeatureFlags()`. Classes that implement it, such as hand-written fakes for tests, no longer compile until they're updated.

```kotlin
class FakePostHog : PostHogInterface {
    override fun reloadFeatureFlags(onFeatureFlags: PostHogFeatureFlagsCallback?) {}

    override fun onFeatureFlags(callback: PostHogFeatureFlagsCallback): PostHogFeatureFlagsSubscription =
        TODO("Return a subscription from your own fake")

    // ...
}
```

`PostHogFeatureFlagsSubscription` and `PostHogFeatureFlagsLoaded` have constructors marked `@PostHogInternal`, which aren't intended for app code, so prefer a mocking library for these types if you can.

## Internal APIs

These helpers were public only by accident and were marked `@PostHogInternal`. They're no longer visible outside the SDK and have no replacement. Apps that don't use `@PostHogInternal` APIs aren't affected.

- `com.posthog.internal`: `CustomHeadersInterceptor`, `PostHogPrintLogger`, `PropertyGroupDeserializer`, `PropertyValueDeserializer`, `personPropertiesContext()`, `PostHogPreferences.PUSH_SUBSCRIPTION_REJECTED`
- `com.posthog.internal.errortracking`: `ThrowableCoercer.fromThrowableToPostHogProperties()`
- `com.posthog.internal.replay`: `RRDocumentNode`, `RRDomContentLoadedEvent`, `RRLoadedEvent`
- `com.posthog.internal.surveys`: `findBestTranslationMatch()`
- `com.posthog.android.internal`: `Bitmap.isValid()`, `Bitmap.webpBase64()`
- `com.posthog.android.replay.internal`: `LogLine`, `LogcatParser`

If you call `PostHogRemoteConfig.loadFeatureFlags()` or `loadRemoteConfig()`, their callback parameters changed too. The internal callback is now `(errorsLoading: Boolean) -> Unit`, and the other is a `PostHogFeatureFlagsCallback`.

## Behavior changes

### Feature flag payloads must be valid JSON

A payload that isn't valid JSON now reads as `null` from `getFeatureFlagResult(key)?.payload` and `getAllFeatureFlags()`, instead of the raw string. With `debug` on, the SDK logs the flag key.

| Payload | 3.x | 4.0 |
| --- | --- | --- |
| Malformed (`{broken`, `[1, 2`), empty or whitespace-only | the raw string | `null` |
| Only valid in lenient mode (unquoted `hello`, single quotes, comments) | decoded leniently | `null` |
| JSON `null` | the string `"null"` | `null` |
| Valid JSON (`"hello"`, `""`, `false`, `0`, objects, arrays) | decoded | decoded, unchanged |
| Bootstrapped payloads | as passed | as passed, unchanged |

The flag's value, enabled state, variant and any other flag's payload aren't affected.

If you read a payload as plain text, such as a payload saved as `hello` rather than `"hello"`, update it in PostHog to valid JSON before you upgrade, or your code gets `null`.

A malformed payload cached by 3.x can still read as the raw string after the upgrade, until the app's first `/flags` response replaces it.

### Session replay records screenshots

4.0 removes wireframe session replay. Replay always records masked screenshots, which is what `screenshot = true` already did.

```kotlin
// 3.x
config.sessionReplayConfig.screenshot = true

// 4.0: remove the line
```

Apps that relied on the default now upload screenshots:

- Masking still covers text inputs, images, `WebView`s, Compose semantics, `ph-no-capture` views and `Modifier.postHogMask()`.
- Custom-drawn views and other content the SDK doesn't recognize, such as maps or video, can now appear in recordings. Mask them, or turn replay off with `sessionReplay = false`.
- Jetpack Compose screens, which wireframe captured as empty boxes, are now recorded.

### Other changes that don't fail the build

The code you end up with after these two migrations compiles, so they're easy to miss:

- [`getFeatureFlagResult()` sends `$feature_flag_called` by default](#getfeatureflagpayload), unlike `getFeatureFlagPayload()`.
- [Apps that set `remoteConfig = false` now load `/config`](#remoteconfig) and apply its settings.

## New: `onFeatureFlags()`

If you need flag updates outside of `PostHogConfig`, for example from a screen or a library, register a listener you can remove:

```kotlin
val subscription = PostHog.onFeatureFlags { loaded ->
    if (loaded.variants["new-checkout"] == "test") {
        showTestVariant()
    }
}

// later
subscription.unsubscribe()
```

On Android, listeners run on the main thread. On the JVM, they run on a background thread. If flags have already loaded, a new listener also gets the current values shortly after it registers. You can register before `setup()`, and listeners stay registered across `close()` and `setup()`.

## `posthog-server`

`posthog-server` isn't having a major release, and its API is unchanged: it keeps its own `apiKey`, `getFeatureFlagPayload` and `remoteConfig`, and `com.posthog.PostHogOnFeatureFlags` now ships in `posthog-server` itself. None of the changes above apply to its API.

Its next release depends on core 7.0, so update it if your project also uses core 7.0 or `posthog-android` 4.0. Older `posthog-server` versions aren't compatible with core 7.0 at runtime.

## Checklist

- [ ] Update `posthog-android` to 4.0, and `posthog-android-surveys-compose` and `posthog-server` if you use them.
- [ ] Replace `apiKey` with `projectToken`.
- [ ] Replace `propertiesSanitizer` with `addBeforeSend`.
- [ ] Replace `evaluationEnvironments` with `evaluationContexts`.
- [ ] Replace `getFeatureFlagPayload()` with `getFeatureFlagResult(key, sendFeatureFlagEvent = false)?.payload`.
- [ ] Replace `debouncerDelayMs` with `throttleDelayMs`.
- [ ] Remove `remoteConfig`. If it was `false`, expect `/config` requests and its settings to apply.
- [ ] Replace `PostHogOnFeatureFlags` with `PostHogFeatureFlagsCallback`, and check existing callbacks for `errorsLoading`.
- [ ] Update any class that implements `PostHogInterface`.
- [ ] Make sure every feature flag payload you read is valid JSON.
