# Migrate the Android SDK from 3.x.x to 4.0.0

The Android SDK 4.x.x sends every event to the [capture v1](https://posthog.com/docs/api/capture-v1) endpoints, `/i/v1/analytics/events` and `/i/v1/ai/events`.

Use the top right copy page button to work through this with your agents.

You don't need to make every change on the day you upgrade your SDK. Required changes fall in these categories:

- **Before you upgrade**: you should make these changes before you upgrade your SDK to 4.x.x.
- **During your upgrade**: These are changes that you need to make in the same PR that upgrades your SDK version.
- **Follow ups**: These are changes that can come in follow up PRs because 4.x.x has limited backward compatibility for methods that are to be deprecated.

## Checklist

Read the checklist first, then the sections that apply to you.

**Before you upgrade**

- [ ] You send events through a reverse proxy that doesn't serve `/i/v1/analytics/events` → [Prepare your proxy](#prepare-your-proxy)
- [ ] You authenticate your proxy with an `Authorization` header in `requestHeaders` → [Prepare your proxy](#prepare-your-proxy)
- [ ] You send events through a reverse proxy set up from PostHog's proxy guides before 29 October 2026, which strips `Authorization` → [Prepare your proxy](#prepare-your-proxy)
- [ ] You send events through a proxy that redirects capture requests, other than with a 307 or 308 to the same origin as `host` → [Prepare your proxy](#prepare-your-proxy)
- [ ] You set `projectToken` to a personal API key (`phx_...`) → [Use a project token](#use-a-project-token)
- [ ] You depend only on `posthog-android-surveys-compose` → [Upgrade your dependencies](#upgrade-your-dependencies)
- [ ] You use `posthog-server` (2.25.0 and later need `posthog-android` 4.0.0 in the same project) → [Upgrade posthog-server](#upgrade-posthog-server)
- [ ] You read a feature flag payload that isn't valid JSON, such as plain text saved as `hello` → [Fix flag payloads](#fix-flag-payloads)
- [ ] A feature flag has an empty string as a variant → [Rename empty variants](#rename-empty-variants)
- [ ] You capture events with a distinct ID over 200 bytes → [Shorten long IDs](#shorten-long-ids)

**During your upgrade**

- [ ] You use `apiKey`, `propertiesSanitizer`, `evaluationEnvironments`, `debouncerDelayMs`, `screenshot`, or `drawableConverter` → [Fix removed APIs](#fix-removed-apis)
- [ ] You pass `PostHogConfig` or `PostHogSessionReplayConfig` constructor arguments by position → [Fix removed APIs](#fix-removed-apis)
- [ ] You call `getFeatureFlagPayload()`, or pass a default value to `getFeatureFlag()` → [Fix flag calls](#fix-flag-calls)
- [ ] You use `remoteConfig` → [Remove remoteConfig](#remove-remoteconfig)
- [ ] You set `PostHogConfig.onFeatureFlags` or `PostHogOnFeatureFlags`, or pass a callback to `reloadFeatureFlags()` → [Move the flags callback](#move-the-flags-callback)
- [ ] You call `capture`, `screen`, `identify`, `setPersonProperties`, `alias`, `group`, or `captureException` from Java, or call `identify` through a `PostHogCoreInterface` reference → [Add the options argument](#add-the-options-argument)
- [ ] You implement `PostHogInterface` → [Update interface implementations](#update-interface-implementations)
- [ ] You use `@PostHogInternal` APIs → [Drop internal APIs](#drop-internal-apis)
- [ ] You filter insights, cohorts, or actions on the `$feature_flag_response` of `$feature_flag_called`, such as `""` or `false` for a missing flag → [Check flag events](#check-flag-events)
- [ ] You break down by `$locale` for the language your app shows (use `$app_language`, and call `setAppLanguage()` if you need it on Android 6 to 12) → [Check locale properties](#check-locale-properties)
- [ ] You use session replay → [Check session replay](#check-session-replay)
- [ ] You read `$`-prefixed query parameters from `Deep Link Opened` events → [Check deep links](#check-deep-links)
- [ ] You capture events with an empty event name, or with an event name over 200 bytes → [Check dropped events](#check-dropped-events)
- [ ] You set an empty distinct ID in `beforeSend` → [Check dropped events](#check-dropped-events)
- [ ] You set `$process_person_profile`, `$cookieless_mode`, `$ignore_sent_at`, or `$product_tour_id` to a value PostHog can't read, such as `"maybe"` → [Check dropped events](#check-dropped-events)
- [ ] You read event content or request bodies from the SDK's debug log, or match its log text → [Update log checks](#update-log-checks)
- [ ] You register a super property, or pass an event property, with the same key as one the SDK sets, such as `$os_name` or `$is_identified`, and expect the SDK's value to win → [Check property precedence](#check-property-precedence)
- [ ] You read `$session_id` or `$window_id` from the raw request body, for example in a proxy → [Check session IDs](#check-session-ids)
- [ ] You set event timestamps, or change the anonymous ID with `getAnonymousId` → [Check timestamps and IDs](#check-timestamps-and-ids)
- [ ] You assign a new map to `event.options` in `beforeSend` → [Review beforeSend hooks](#review-beforesend-hooks)
- [ ] You set the event `uuid` in `beforeSend` → [Review beforeSend hooks](#review-beforesend-hooks)
- [ ] You register `$process_person_profile` as `false` and still call `identify`, `alias`, `group`, or `setPersonProperties` → [Check person processing](#check-person-processing)
- [ ] You rely on a `Retry-After` longer than 30 seconds → [Check retries](#check-retries)
- [ ] You set `maxRetries` on `PostHogConfig` or `PostHogAndroidConfig` → [Check retries](#check-retries)

**Follow ups**

- [ ] You set legacy option properties like `$process_person_profile` → [Move to event options](#move-to-event-options)
- [ ] You capture `$ai_` events with `capture()` → [Send AI events with captureAi](#send-ai-events-with-captureai)

## Before you upgrade

### Prepare your proxy

posthog-android 4.x.x sends data to these endpoints:

| Data | 3.x.x | 4.x.x |
| --- | --- | --- |
| Analytics events | `/batch` | `/i/v1/analytics/events` |
| Session replay | `/s/` | `/s/`, unchanged |
| AI events (`captureAi`) | None | `/i/v1/ai/events` (see [Send AI events with captureAi](#send-ai-events-with-captureai)) |
| Logs | `/i/v1/logs` | `/i/v1/logs`, unchanged |
| Feature flags | `/flags` | `/flags`, unchanged |

Capture v1 requests send the project token in an `Authorization: Bearer <projectToken>` header instead of an `api_key` body field.

This header replaces any `Authorization` header you set in `PostHogConfig.requestHeaders`, so authenticate your proxy with a different header, such as `X-Proxy-Token`. Other requests still send your `Authorization` header.

Analytics requests are gzipped unless you set `compression` to `PostHogCompression.NONE`, as in 3.x.x.

If you send events through your own reverse proxy, update it before you upgrade:

- **Forward both routes.** Forward `/i/v1/analytics/events` and `/i/v1/ai/events` to PostHog.
- **Pass the headers through unchanged.** Pass `Authorization`, every `PostHog-*` header, `Content-Type`, `Content-Encoding`, and `User-Agent`. `Authorization` holds the project token, sent as `Bearer <token>`. PostHog answers `401` without `Authorization` and `400` without the others, and the events are dropped.
- **Keep PostHog's credentials.** If your proxy strips other credentials, keep `Authorization` values that start with `Bearer phc_`, `Bearer phs_`, or `Bearer phx_`. Keep the exact `Bearer <key>` of an older key without a prefix too.
- **Accept request bodies up to 20 MB.** AI requests are often larger than 1 MB, the default limit in nginx and ingress-nginx. A `413` from the proxy makes the SDK halve the batch until it fits. The SDK drops an event that doesn't fit on its own.
- **Pass PostHog's response body through unchanged.** A 2xx response whose body isn't PostHog's `{"results": {...}}` drops the events instead of retrying them.

PostHog's managed reverse proxy already does this and needs no change.

A proxy set up from PostHog's [proxy guides](https://posthog.com/docs/advanced/proxy) before 29 October 2026 strips `Authorization`, so PostHog answers `401` and the events are dropped. Update it from the current guide.

The SDK doesn't fall back to `/batch`. It retries a 404, so the events stay queued until the path is served. The SDK prints one warning, even with `debug` off.

Any other error status that isn't retried, such as 403 or 405, drops the events.

The SDK follows a 307 or 308 redirect only to the origin of `host`, at most 5 times. The origin is the same scheme, host, and port. The SDK resends the same body and headers.

The SDK doesn't follow any other redirect, and the events are dropped. In 3.x.x, OkHttp followed redirects to any host.

If a proxy redirects capture requests to another host, point `host` at the final host.

This also applies when you set your own `httpClient`. For capture requests, the SDK turns off its redirect following and OkHttp's connection retries, and keeps your other settings. Other requests, such as session replay and logs, behave as in 3.x.x.

### Use a project token

A personal API key (`phx_...`) in `projectToken` disables the SDK at setup, as an empty or whitespace-only token does. Both print why, even when `debug` is off.

In 3.x.x, the SDK started and PostHog rejected every event with a 401. Use your project token (`phc_...`).

To turn the SDK off in tests, use an empty token.

### Upgrade your dependencies

Upgrade PostHog artifacts together:

| Artifact | Upgrade |
| --- | --- |
| `com.posthog:posthog-android` | 3.x.x to 4.0.0 |
| `com.posthog:posthog` (core) | 6.x.x to 7.0.0 |
| `com.posthog:posthog-android-surveys-compose` | Keep your version |
| `com.posthog:posthog-server` | 2.25.0, together with `posthog-android` 4.0.0 (see [Upgrade posthog-server](#upgrade-posthog-server)) |

With `posthog-android-surveys-compose`, declare `com.posthog:posthog-android` 4.0.0 or later in your build, so Gradle resolves 4.x.x instead of the 3.x.x version it pulls in.

Core `posthog` 7.x.x isn't binary compatible with SDK versions built against core 6.x.x. The exception is `posthog-android-surveys-compose`, which works with both.

When core 7.x.x is in a Gradle build, for example through `posthog-server` 2.25.0, Gradle upgrades `posthog-android` to 4.x.x, even over a range such as `[3.75.0,4.0.0)`. Code that still uses removed APIs then fails to compile.

Other mismatches fail at runtime with errors such as `NoSuchMethodError` or `NoClassDefFoundError`.

The Android minimum API level (23), the Java bytecode target (Java 8), the Kotlin language and API compatibility (2.0), and `posthog-android-gradle-plugin` are unchanged. You don't need to change your build for them.

The [changelog](https://github.com/PostHog/posthog-android/blob/main/posthog-android/CHANGELOG.md) lists every 4.0.0 change.

### Upgrade posthog-server

`posthog-server` has no major release, and its API is unchanged. It keeps its own `apiKey`, `getFeatureFlagPayload`, and `remoteConfig`, and `com.posthog.PostHogOnFeatureFlags` ships in `posthog-server` itself.

It still sends events to `/batch`, not to capture v1. The API, endpoint, proxy, retry, and event option changes in this guide don't apply to it.

`posthog-server` 2.25.0 depends on core `posthog` 7.0.0, so upgrade it and `posthog-android` together:

- **With `posthog-android` 4.x.x or core 7.x.x.** Use `posthog-server` 2.25.0 or later. Older `posthog-server` versions aren't compatible with core 7.x.x at runtime.
- **With `posthog-android` 3.x.x.** Stay on `posthog-server` 2.24.x. `posthog-server` 2.25.0 brings in core 7.0.0, and Gradle then upgrades `posthog-android` to 4.x.x.

These changes apply to `posthog-server` too:

- **Personal API keys.** A personal API key (`phx_...`) in `projectToken` disables the client at setup, as an empty token does. Both print why, even when `debug` is off.
- **Empty names.** The client drops events with an empty name before sending.
- **Merged maps.** `userProperties`, `userPropertiesSetOnce`, and `groups` merge key by key over a `$set`, `$set_once`, or `$groups` map in `properties`.
- **`null` elements.** A `null` element in a list or array in the properties is sent as JSON `null` at its position instead of being removed.
- **`beforeSend` copies.** `beforeSend` gets its own copy of the event's nested maps, lists, and arrays, so its changes no longer reach the objects you passed to `capture`.
- **Event options.** `PostHogEvent`, which `beforeSend` gets, keeps its 3.x.x constructor and `copy`, and gains an `options` property. `/batch` doesn't use it, so a value set in `beforeSend` is sent but has no effect.

Core `posthog` 7.x.x is only supported through `posthog-android` 4.x.x and `posthog-server`. `PostHogStateless.with` isn't a supported entry point in core 7.x.x.

Without its own queue, it gets Android's capture v1 behavior, including the persisted-queue retries, which isn't a server-side contract. Use `posthog-server` for server-side JVM apps.

A class that extends `PostHogStateless` also needs an update. The protected `buildEvent()` gained `options` and `uuid` parameters, and `captureFeatureFlagCalledEvent()` is no longer protected.

### Fix flag payloads

In 4.x.x, a payload that isn't valid JSON reads as `null` from `getFeatureFlagResult(key)?.payload` and `getAllFeatureFlags()`. 3.x.x returned the raw string. With `debug` on, the SDK logs the flag key.

| Payload | 3.x.x | 4.x.x |
| --- | --- | --- |
| Malformed (`{broken`, `[1, 2`), empty or whitespace-only | the raw string | `null` |
| Only valid in lenient mode (unquoted `hello`, single quotes, comments) | decoded leniently | `null` |
| JSON `null` | the string `"null"` | `null` |
| Valid JSON (`"hello"`, `""`, `false`, `0`, objects, arrays) | decoded | decoded, unchanged |
| Bootstrapped payloads | as passed | as passed, unchanged |

The flag's value, enabled state, variant, and any other flag's payload aren't affected.

You might read a payload as plain text, such as a payload saved as `hello` rather than `"hello"`. Update it in PostHog to valid JSON before you upgrade, or your code gets `null`.

PostHog no longer saves a new payload that isn't valid JSON, so only payloads saved earlier are affected.

Apps that upgrade from 3.13.x or older didn't cache flag details. Until the app's first `/flags` response, a malformed payload cached by 3.x.x still reads as the raw string, as it did in 3.x.x.

### Rename empty variants

In 4.x.x, a flag whose variant is an empty string reads as disabled everywhere. `isFeatureEnabled()` already read it as disabled in 3.x.x, with or without `defaultValue`.

- **Flag results.** `getFeatureFlagResult()` and `getAllFeatureFlags()` return `enabled = false` for it. 3.x.x returned `true`.
- **Loaded flags.** `PostHogFeatureFlagsLoaded` leaves it out of `flags` and `variants`.
- **Captured events.** Captured events leave it out of `$active_feature_flags`.
- **Variant values.** `variant`, `getFeatureFlag()`, and the event's `$feature/<key>` property still return `""`.

PostHog no longer accepts empty variants for new flags, so only flags created earlier through the API, MCP, or Terraform are affected.

`/flags` still returns `enabled: true` for them, and other PostHog SDKs can still report them as enabled, so rename the variant before you upgrade.

### Shorten long IDs

PostHog drops an event whose distinct ID is over 200 UTF-8 bytes. Legacy capture shortened the ID to 200 characters and kept the event.

The distinct ID can come from `identify()`, the `distinctId` argument of `capture`, or `beforeSend`.

If you identify users with a long ID, such as a JWT or a long email address, shorten or hash it before you upgrade. Otherwise every event for that user is dropped.

## During your upgrade

### Fix removed APIs

posthog-android 4.x.x removes these APIs. Each one is a compile error. A call from an already-compiled library that you haven't updated fails at runtime.

| Removed | Use instead |
| --- | --- |
| `PostHogConfig.apiKey`, `PostHogConfig(apiKey = …)`, `PostHogAndroidConfig(apiKey = …)` | `projectToken` |
| `PostHogConfig.propertiesSanitizer`, `PostHogPropertiesSanitizer` | `addBeforeSend` |
| `PostHogConfig.evaluationEnvironments` | `evaluationContexts` |
| `getFeatureFlagPayload()`, on the instance and on `PostHog` | `getFeatureFlagResult()` (see [Fix flag calls](#fix-flag-calls)) |
| `defaultValue` on `getFeatureFlag()` | `getFeatureFlag(key) ?: default` (see [Fix flag calls](#fix-flag-calls)) |
| `PostHogSessionReplayConfig.debouncerDelayMs` | `throttleDelayMs` |
| `PostHogSessionReplayConfig.screenshot`, `drawableConverter`, `PostHogDrawableConverter` | Nothing |
| `PostHogConfig.remoteConfig` | Nothing |
| `PostHogConfig.onFeatureFlags` | `PostHog.onFeatureFlags()` before `setup()` (see [Move the flags callback](#move-the-flags-callback)) |
| `PostHogOnFeatureFlags` | `PostHogFeatureFlagsCallback` |
| `PostHogConfig.maxRetries` | `pushNotificationSubscriptionMaxRetries` (see [Check retries](#check-retries)) |

Replay always records screenshots (see [Check session replay](#check-session-replay)). Remote config always loads (see [Remove remoteConfig](#remove-remoteconfig)).

The constructor parameters of `PostHogConfig` and `PostHogSessionReplayConfig` move up where these were removed. If you pass arguments by position, check them, or switch to named arguments.

Replace `apiKey` with `projectToken`:

```kotlin
// 3.x.x
val config = PostHogAndroidConfig(apiKey = "phc_your_project_token")
val token = config.apiKey

// 4.x.x
val config = PostHogAndroidConfig(projectToken = "phc_your_project_token")
val token = config.projectToken
```

`PostHogAndroidConfig("phc_your_project_token")`, with no parameter name, still works. From Java, replace `getApiKey()` with `getProjectToken()`.

Replace `propertiesSanitizer` with `addBeforeSend`:

```kotlin
// 3.x.x
config.propertiesSanitizer = PostHogPropertiesSanitizer { properties ->
    properties.apply { remove("email") }
}

// 4.x.x
config.addBeforeSend { event ->
    event.properties?.remove("email")
    event
}
```

`addBeforeSend` gets the whole `PostHogEvent`. Return `null` to drop the event. If the hook throws, the event is dropped.

Replace `evaluationEnvironments` with `evaluationContexts`:

```kotlin
// 3.x.x
config.evaluationEnvironments = listOf("production")

// 4.x.x
config.evaluationContexts = listOf("production")
```

Replace `debouncerDelayMs` with `throttleDelayMs`:

```kotlin
// 3.x.x
PostHogSessionReplayConfig(debouncerDelayMs = 500)

// 4.x.x
PostHogSessionReplayConfig(throttleDelayMs = 500)
```

### Fix flag calls

`getFeatureFlagPayload()` never sent `$feature_flag_called`. `getFeatureFlagResult()` sends it by default, following `sendFeatureFlagEvent`.

Pass `sendFeatureFlagEvent = false` to keep your event volume and experiment exposures unchanged. If you drop that argument, nothing fails, but you'll send new events.

`getFeatureFlagResult()` has no `defaultValue` parameter, so apply your default with `?:`:

```kotlin
// 3.x.x
val payload = PostHog.getFeatureFlagPayload("checkout", defaultValue = "fallback")

// 4.x.x
val payload = PostHog.getFeatureFlagResult("checkout", sendFeatureFlagEvent = false)?.payload ?: "fallback"
```

`getFeatureFlag()` has no `defaultValue` parameter either:

```kotlin
// 3.x.x
val value = PostHog.getFeatureFlag("checkout", defaultValue = "fallback")

// 4.x.x
val value = PostHog.getFeatureFlag("checkout") ?: "fallback"
```

The second parameter of `getFeatureFlag()` is `sendFeatureFlagEvent` in 4.x.x, which affects code that still compiles:

- **Boolean arguments.** `getFeatureFlag("checkout", true)` compiles, but `true` means `sendFeatureFlagEvent` rather than the default value.
- **A disabled SDK.** A disabled SDK returns `null`, not your default.
- **Fallback values.** `$feature_flag_called` no longer reports a fallback as the flag's value. `isFeatureEnabled("missing", true)` still returns `true`, but its event leaves out `$feature_flag_response` (see [Check flag events](#check-flag-events)).

Classes that implement `PostHogInterface` update the signature.

### Remove remoteConfig

Apps that left `remoteConfig` at its default of `true` see no behavior change. Delete the option:

```kotlin
// 3.x.x
config.remoteConfig = false

// 4.x.x: remove the line
```

If you set it to `false`, your app's behavior changes:

- **Requests at setup.** The SDK requests `/config` at setup, even if `preloadFeatureFlags` is also `false`. 3.x.x sent no request at all.
- **Project settings.** The settings in `/config` apply, including the session replay, surveys, error tracking, and push settings from your project.
- **Flag requests.** With `preloadFeatureFlags = true`, the SDK requests `/flags` only if `/config` reports that the project has feature flags. 3.x.x always requested `/flags`. If the project has none, the cached flags are cleared.

### Move the flags callback

`PostHogConfig.onFeatureFlags` is removed. Call `PostHog.onFeatureFlags()` before `setup()` instead, which also covers the first load.

An instance from `PostHogAndroid.with()` is set up as it's created, so call `onFeatureFlags()` on it right after `with()`. If flags have already loaded, the listener gets the current values shortly after it registers.

`reloadFeatureFlags()` takes a `PostHogFeatureFlagsCallback`. Both callbacks receive a `PostHogFeatureFlagsLoaded`:

| Field | Holds |
| --- | --- |
| `flags` | The keys of the enabled flags |
| `variants` | Their values, either `true` or the variant string |
| `errorsLoading` | Whether the latest load failed |

When the latest load failed, the values are the last known ones, which can be empty.

Register the listener before `setup()`:

```kotlin
// 3.x.x
config.onFeatureFlags = PostHogOnFeatureFlags {
    refreshUi()
}

// 4.x.x, before PostHogAndroid.setup()
PostHog.onFeatureFlags { loaded ->
    if (!loaded.errorsLoading) {
        refreshUi()
    }
}
```

The listener differs from the removed option in three ways:

- **Thread.** It runs on the main thread on Android. The option ran on a background thread.
- **Bootstrapped flags.** They arrive just after `setup()` is called, not synchronously inside it.
- **Disabled SDK.** A reload on a disabled SDK doesn't call listeners. `reloadFeatureFlags(callback)` still gets `errorsLoading = true` in that case.

`onFeatureFlags()` is the way to listen for flag loads, for example from a screen or a library. You can remove the listener later:

```kotlin
val subscription = PostHog.onFeatureFlags { loaded ->
    if (loaded.variants["new-checkout"] == "test") {
        showTestVariant()
    }
}

// later
subscription.unsubscribe()
```

You can register before `setup()`, and listeners stay registered across `close()` and `setup()`.

From Java, the `reloadFeatureFlags()` lambda takes the `PostHogFeatureFlagsLoaded` argument:

```java
// 3.x.x
PostHog.Companion.reloadFeatureFlags(() -> refreshUi());

// 4.x.x
PostHog.Companion.reloadFeatureFlags(loaded -> refreshUi());
```

A Kotlin trailing lambda such as `PostHog.reloadFeatureFlags { refreshUi() }` compiles unchanged, so it won't show up as an error. Check whether it should read `errorsLoading`. The callback has always also run after a failed load.

`errorsLoading` depends on why the load ended:

- **`false`.** PostHog answers that the project is over its feature flags quota. The values are the cached flags.
- **`true`.** No request was made, such as when the device is offline or the distinct ID is blank. It's also `true` when the SDK is disabled or isn't set up, with no values in that last case.

The `reloadFeatureFlags()` callback still runs on a background thread, or right away on the calling thread when no load is attempted. Switch to the main thread before you touch UI.

### Add the options argument

`capture` takes a new `options` argument. `screen`, `identify`, `setPersonProperties`, `alias`, `group`, and `captureException` take the same argument, as their last parameter (see [Move to event options](#move-to-event-options)).

- **Kotlin callers.** Callers that use named or positional arguments for the existing parameters keep compiling.
- **Java callers.** Pass the new trailing `options` argument, or `null`.
- **`PostHogInterface` implementations.** Add the parameter.
- **`identify` on `PostHogCoreInterface`.** `identify(distinctId, userProperties, userPropertiesSetOnce)` moved from `PostHogCoreInterface` to `PostHogStatelessInterface`. Call it through `PostHogInterface` or `PostHogStatelessInterface`.

### Update interface implementations

`PostHogInterface` lost `getFeatureFlagPayload()`, gained the abstract `onFeatureFlags()` and `setAppLanguage()`, and changed the callback type of `reloadFeatureFlags()`.

Implementations also add the `options` parameter and `captureAi()` (see [Add the options argument](#add-the-options-argument) and [Send AI events with captureAi](#send-ai-events-with-captureai)).

Classes that implement it, such as hand-written fakes for tests, don't compile until you update them:

```kotlin
class FakePostHog : PostHogInterface {
    override fun reloadFeatureFlags(onFeatureFlags: PostHogFeatureFlagsCallback?) {}

    // mock() from mockito-kotlin, or your mocking library's equivalent
    override fun onFeatureFlags(callback: PostHogFeatureFlagsCallback): PostHogFeatureFlagsSubscription = mock()

    // ...
}
```

`PostHogFeatureFlagsSubscription` and `PostHogFeatureFlagsLoaded` have constructors marked `@PostHogInternal`, which aren't intended for app code, so create them with a mocking library.

### Drop internal APIs

These helpers were public only by accident and were marked `@PostHogInternal`. They're no longer visible outside the SDK and have no replacement. Apps that don't use `@PostHogInternal` APIs aren't affected.

| No longer visible | Package |
| --- | --- |
| `CustomHeadersInterceptor` | `com.posthog.internal` |
| `PostHogPrintLogger` | `com.posthog.internal` |
| `PropertyGroupDeserializer` | `com.posthog.internal` |
| `PropertyValueDeserializer` | `com.posthog.internal` |
| `personPropertiesContext()` | `com.posthog.internal` |
| `PostHogPreferences.PUSH_SUBSCRIPTION_REJECTED` | `com.posthog.internal` |
| `ThrowableCoercer.fromThrowableToPostHogProperties()` | `com.posthog.internal.errortracking` |
| `RRDocumentNode` | `com.posthog.internal.replay` |
| `RRDomContentLoadedEvent` | `com.posthog.internal.replay` |
| `RRLoadedEvent` | `com.posthog.internal.replay` |
| `RRStyle` | `com.posthog.internal.replay` |
| `findBestTranslationMatch()` | `com.posthog.internal.surveys` |
| `Bitmap.isValid()` | `com.posthog.android.internal` |
| `Bitmap.webpBase64()` | `com.posthog.android.internal` |
| `LogLine` | `com.posthog.android.replay.internal` |
| `LogcatParser` | `com.posthog.android.replay.internal` |

In `com.posthog.internal.replay`, `RRWireframe` keeps only `id`, `x`, `y`, `width`, `height`, `type`, and `base64`.

If you call `PostHogRemoteConfig.loadFeatureFlags()` or `loadRemoteConfig()`, their callback parameters changed too. The internal callback is `(errorsLoading: Boolean) -> Unit`, and the other is a `PostHogFeatureFlagsCallback`.

### Check flag events

When a flag is missing, `$feature_flag_called` leaves out `$feature_flag_response`.

3.x.x sent `""` from `getFeatureFlag()` and `false` from `isFeatureEnabled()`, or your default value when you passed one. In 4.x.x an empty string means a flag with an empty variant, so a missing flag no longer reports one.

Insights, cohorts, and actions that filter on `$feature_flag_response = ""` or `false` to find missing flags stop matching new events without any error. Use an `is not set` filter on `$feature_flag_response` instead.

Events already stored keep their old values.

### Check locale properties

In 4.x.x, `$locale` means the first language in the device settings, as a full language tag such as `de-CH` or `zh-Hant-TW`. It's left out when the device reports no language.

In 3.x.x it came from `Locale.getDefault()`. That could be a secondary language, a per-app language, or a value your app set with `Locale.setDefault()`, and it dropped the script (`zh-TW`).

On older Android versions, 3.x.x also sent the legacy codes `iw`, `in`, and `ji`, so Hebrew, Indonesian, and Yiddish values change, such as `in-ID` to `id-ID`. Update filters that match those.

Other values mostly stay the same, so otherwise only check filters if your users change these settings.

The new `$app_language` property is the language your app shows. Android has no API that reports it exactly, so what you get depends on the version:

| Android | `$app_language` |
|---|---|
| 13+ with `android:localeConfig` declared | The per-app language or the first device language |
| 13+ without `localeConfig` | The per-app language, if one is set |
| 6 to 12 | Not sent |

You can declare `android:localeConfig` by hand or with AGP `generateLocaleConfig`. With it, the value is the per-app language or, without one, the first device language, as you declare it.

If you don't declare that language, the value is your `android:defaultLocale` (Android 15+) or your language if you only declare one.

It's not sent if several of your declared languages could match, such as `pt` and `pt-PT` for a `pt-MZ` device, or if nothing matches and you declare several languages without a default.

On Android 13+ without `localeConfig`, it's not sent when no per-app language is set.

The SDK reads both languages in the background when an event is captured and uses them from the next event on. The first event after a language change carries the previous value.

A change to only the runtime override (`LocaleManager.setOverrideLocaleConfig`) isn't picked up until the device or per-app language changes. Call `PostHog.setAppLanguage()` if your app changes it.

Strings you don't translate fall back per string, so the value is the app's language, not a guarantee for every string.

Call `PostHog.setAppLanguage("de")` at startup and whenever the language changes to send it on every version, or if you translate some other way, such as with an in-app language setting. It's kept in memory only.

### Check session replay

posthog-android 4.x.x removes wireframe session replay. Replay always records masked screenshots, which is what `screenshot = true` already did. Remove the `screenshot` option:

```kotlin
// 3.x.x
config.sessionReplayConfig.screenshot = true

// 4.x.x: remove the line
```

Apps that relied on the default now upload screenshots:

- **Masking.** Masking still covers text inputs, images, Compose semantics, `ph-no-capture` views, and `Modifier.postHogMask()`. `WebView`s are masked whole while `maskAllTextInputs` or `maskAllImages` is on.
- **Unrecognized content.** Custom-drawn views and other content the SDK doesn't recognize, such as maps or video, can now appear in recordings. Mask them, or turn replay off with `sessionReplay = false`.
- **Jetpack Compose screens.** Wireframe captured them as empty boxes. 4.x.x records them. That can count against your replay quota and may need a consent or privacy disclosure update.
- **Frame cost.** Each frame uses more bandwidth and CPU than a wireframe.

Compose masking also changed, including for apps that already set `screenshot = true`:

- **Mask scope.** `Modifier.postHogMask()` and `Modifier.postHogUnmask()` apply only to the element and its children, not to siblings merged into the same clickable row.
- **Overflowing text.** Text that draws past its layout box is masked in full.
- **Cleared semantics.** Nodes that use `Modifier.clearAndSetSemantics` are masked while text or image masking is on. Use `Modifier.postHogUnmask()` to show them.

A dialog's dim, and a dialog masked whole on Android 8.1 and lower, are sent as screenshot images instead of styled wireframe shapes.

The `$sdk_debug_replay_capture_mode` property is always `screenshot`.

### Check deep links

The `Deep Link Opened` event leaves out query parameters whose name starts with `$`, such as `$process_person_profile`, so a link can't set PostHog's own properties.

Other parameters and the `url` property are sent as in 3.x.x.

### Check dropped events

Capture v1 checks each event on its own and drops only the invalid ones, so the rest of the batch is delivered. In 3.x.x, an empty event name or distinct ID made legacy capture reject the whole batch it was in.

- **Empty names.** The SDK drops an event whose name is empty before sending it, for example `capture("")` or one `beforeSend` renames to `""`. With `debug` on, it logs this once.
- **Empty distinct IDs.** PostHog drops an event with an empty or whitespace-only distinct ID, for example one `beforeSend` sets to `""`.
- **Long names.** PostHog drops an event whose name is over 200 UTF-8 bytes. Legacy capture had no limit on event name length.
- **Long distinct IDs.** PostHog drops an event whose distinct ID is over 200 UTF-8 bytes (see [Shorten long IDs](#shorten-long-ids)).
- **Empty IDs in `identify()`.** `identify()` with an empty or whitespace-only distinct ID is still ignored, as in 3.x.x.
- **Option values.** The SDK sends option values as given. PostHog reads common forms of a value, such as `"false"` or `0` for a boolean, and drops an event whose option value it can't read.

The SDK checks only the event name before sending. For the other checks, PostHog drops the event, and the SDK removes it from the queue.

There's no error callback, as in 3.x.x. The SDK reports dropped and failed events only in its debug log (see [Update log checks](#update-log-checks)).

The SDK sends these events and changes the value:

- **`NaN` and infinite numbers.** A `NaN` or infinite number in the properties is dropped from the event, and the event is sent without it.
- **Map keys.** A map key in the properties that isn't a string is converted to one when it's a number, a boolean, or `null`. Any other key is dropped with its value, and the rest of the event is sent. With `debug` on, the SDK logs the key's type. In 3.x.x, a nested map with a non-string key lost the whole event.
- **`null` elements.** A `null` element in a list or array in the properties is sent as JSON `null` at its position. So is an element that can't be serialized, such as `NaN`. In 3.x.x, both were removed, which moved the later elements to lower indexes. A property or map entry whose value is `null` is still left out.
- **Empty `distinctId` arguments.** An empty or whitespace-only `distinctId` argument to `capture` uses the current distinct ID, like `null`. In 3.x.x, the event was dropped.

### Update log checks

Update any log-based checks that match the 3.x.x text.

- **Batch results.** With `debug` on, the SDK logs one line per batch. The line says how many events PostHog dropped, how many duplicate copies it dropped, and how many it asked to retry. It has the reasons PostHog gave, never the event names or properties.
- **Queued events.** The SDK no longer logs a line for each queued event.
- **Capture requests.** Debug logs for capture requests name the event count and the response status. They no longer print the request and response bodies.
- **Hook errors.** When a `beforeSend` hook throws, the debug log names the event and the exception type, not the exception message. The message can hold the data the hook was removing. In 3.x.x it printed the exception.
- **Values that can't be serialized.** When a property value can't be serialized, the debug log names the key and the value's type, not the value. In 3.x.x it printed the value.

### Check property precedence

An event's properties are merged in this order, and later values win:

1. Properties the SDK sets itself, such as `$os_name`, `$app_version`, `$screen_name`, `$is_identified`, `$session_id`, and the session replay debug properties (`$recording_status`, `$sdk_debug_*`).
2. Registered super properties (`register`).
3. The properties you pass to `capture` or another event-sending method. `userProperties`, `userPropertiesSetOnce`, and `groups` are placed as `$set`, `$set_once`, and `$groups` here. When you also pass that key in properties, the arguments win key by key. A `$groups` map in properties adds to the groups set with `group()` instead of replacing them.
4. `beforeSend`.

These changes follow from this order:

- **SDK values.** In 3.x.x, the SDK's own values won over super properties. `$is_identified`, `$process_person_profile`, and the session replay debug properties also won over the properties you pass. In 4.x.x a value you register or pass wins. For example, `register("\$os_name", "custom")` sends every event with that value.
- **Session IDs.** A registered `$session_id` overrides the SDK's session ID, which can detach events from their session replay.
- **Blank values.** A caller `$screen_name` or `$session_id` still replaces the SDK's value even when it's blank, as in 3.x.x, so a blank `$session_id` is sent as an empty `session_id`.
- **Merged maps.** In 3.x.x, a `$set`, `$set_once`, or `$groups` map in properties replaced the per-call values and the groups set with `group()`. A `$set` or `$set_once` you pass, or `userProperties`, still replaces a registered one whole.

`$lib` and `$lib_version` name the library that produced the event. The SDK fills them last, as a pair, only where nothing above named the library:

| You set | The SDK sends |
| --- | --- |
| Neither, or only `$lib_version` | Its own `$lib` and `$lib_version` |
| `$lib` as the SDK's own name, without a version | Your `$lib`, and it adds its version |
| `$lib` as another library's name | Both as they are |

When `$lib` names another library, the SDK never adds its own version, so set both keys together.

In 3.x.x, a registered `$lib` or `$lib_version` was replaced by the SDK's, and a `$lib` you passed was sent with the SDK's `$lib_version`.

### Check session IDs

Capture v1 sends `$session_id` and `$window_id` as top-level `session_id` and `window_id` fields, not as properties. The SDK moves them out of `properties` for you, by the value's type:

- **A string.** The SDK sends it unchanged, including an empty string.
- **`null`.** It counts as unset, and the SDK removes it without a log.
- **Any other value.** The SDK removes it and doesn't send it. With `debug` on, the SDK logs the key and the value's JSON type, never the value.

In PostHog, the events still have `$session_id` and `$window_id`, so insights, filters, and session replay work as before.

### Check timestamps and IDs

- **Timestamps.** The SDK sends every timestamp in UTC with 3 fractional digits, for example `2026-01-01T00:00:00.000Z`, as 3.x.x did.
- **Future timestamps.** The SDK doesn't check for future timestamps. PostHog replaces a timestamp more than 23 hours ahead with the time it received the event.
- **Generated IDs.** Event uuids and anonymous distinct IDs are UUID v7, as in 3.x.x. An anonymous ID you change with `getAnonymousId` is sent as you return it.
- **Request IDs.** Each capture request also sends a UUID v7 request ID in the `PostHog-Request-Id` header, which 3.x.x didn't send.

### Review beforeSend hooks

Review your `beforeSend` hooks for the new event shape. Capture v1 reads processing settings from an `options` object sent next to `properties`, and `beforeSend` can read and change `PostHogEvent.options`.

- **Person processing.** In 3.x.x, the SDK marked person processing with the `$process_person_profile` property. In 4.x.x, it's the `process_person_profile` option, and the property isn't set. To change person processing in `beforeSend`, edit `event.options`.
- **Properties a hook adds.** A `$process_person_profile` property the hook adds only fills an option that is still unset. It doesn't override the `false` the SDK set for an anonymous event.
- **New option maps.** A hook that assigns a new map to `event.options` without keeping that key loses the `false` the SDK set. That turns person processing on for anonymous users, overriding `personProfiles`. Editing `event.options` in place keeps it.
- **Copies.** `beforeSend` gets its own copy of the event's nested maps, lists, and arrays, in `properties` and `options`. Changes a hook makes no longer reach the objects you passed to `capture` or registered, and identity checks against them (`===`) no longer match. In 3.x.x, hooks edited the caller's objects.
- **Returned events.** `options` is a property of `PostHogEvent`, not a constructor parameter, so the constructor and `copy` are unchanged from 3.x.x. When `beforeSend` returns a different event whose `options` is `null`, such as one made with `event.copy(…)` or the constructor, it gets the options of the event the hook was given. To send an event with no options, set `options` to an empty map.
- **Event uuids.** The event `beforeSend` returns keeps the uuid of the capture it came from. A uuid you set in `beforeSend`, for example with `copy(uuid = …)`, is replaced. A hook that returns an event kept from an earlier call still sends one uuid per capture.
- **Timing.** `beforeSend` still runs when you capture the event, before it's queued, not when the batch is sent.

### Check person processing

- **The SDK's value.** When `personProfiles` turns person processing off, the SDK sets the `process_person_profile` option to `false`. Otherwise it sends neither the option nor the property, since PostHog processes person profiles by default. In 3.x.x, every event carried a `$process_person_profile` property.
- **One event.** To change person processing for one event, pass the `process_person_profile` option or the `$process_person_profile` property.
- **Every event.** A registered `$process_person_profile` changes it for every event. In 3.x.x, the SDK's value replaced a registered `$process_person_profile`, so a registered `false` had no effect.
- **A registered `false`.** In 4.x.x it applies, also after `identify` and with `PersonProfiles.ALWAYS`. Those events don't change the person profile, and their `$set` is ignored. They are still linked to the person and billed as identified events.
- **`PersonProfiles.NEVER`.** A `process_person_profile` option or `$process_person_profile` property set to `true`, passed or registered, turns person processing on for that event. In 3.x.x, `NEVER` always won. If you rely on `NEVER` to keep events personless, make sure neither is set to `true`, or remove it in `beforeSend`.
- **`PersonProfiles.IDENTIFIED_ONLY`.** `identify`, `alias`, `group`, and `setPersonProperties` still turn on person processing for the user's later events, as in 3.x.x.
- **Duplicate checks.** A `$set` sent with `process_person_profile: false` doesn't update the person, so it no longer suppresses a later `setPersonProperties` or `identify` call with the same properties as a duplicate.

A registered `$process_person_profile` set to `false` also reaches the events `identify`, `alias`, `group`, and `setPersonProperties` send.

PostHog accepts `$identify`, `$create_alias`, and `$groupidentify` and then drops them with an ingestion warning, and the SDK doesn't report it. A `$set` event is kept, but the person isn't updated.

To keep identifying, pass the option on the call, or set it in `beforeSend`:

```kotlin
PostHog.register("\$process_person_profile", false)

PostHog.identify(
    distinctId = "user-123",
    userProperties = mapOf("plan" to "pro"),
    options = mapOf("process_person_profile" to true),
)
```

If you registered `false` to skip person profiles for anonymous users, remove it. `personProfiles = PersonProfiles.IDENTIFIED_ONLY`, the default, already does that and still lets `identify` create the person.

To create no person profiles at all, set `personProfiles = PersonProfiles.NEVER` instead. The SDK then skips these calls.

### Check retries

These apply to analytics and AI event requests, unless a bullet names session replay or logs. Session replay and logs keep their 3.x.x status handling: they still retry 3xx and don't retry 404.

- **Statuses retried.** The SDK retries network errors and 404, 408, 429, 500, 502, 503, and 504 responses. Other statuses drop the events, including 3xx. 3.x.x also retried 3xx responses, and didn't retry 404.
- **Billing limits and quotas.** A 402 (billing limit) drops the whole batch. A product quota, such as for exceptions or AI events, drops only those events inside a 200 response.
- **Partial retries.** When the response asks to retry only some events, the SDK keeps those events queued and removes the rest.
- **Resent requests.** A failed request is resent with the same events, request ID, and `created_at`, and the next attempt number, before newer events are sent. The request ID and attempt number reset after an app restart. OkHttp doesn't retry capture requests on its own.
- **The `Retry-After` cap.** A `Retry-After` header sets the minimum wait before the next send, up to 30 seconds. This also applies to session replay and logs. In 3.x.x there was no cap.
- **`Retry-After` formats.** `Retry-After` can be a number of seconds or an HTTP date. 3.x.x read only a number of seconds.
- **Backoff.** After a failed send, the wait before the next one doubles from 1 second up to 30 seconds, with up to 25% random jitter at millisecond precision. In 3.x.x it doubled without jitter and a `Retry-After` replaced it. This applies to events, session replay, and logs.
- **Retry limit.** Event, session replay, and log retries have no limit, as in 3.x.x. Queued records stay until they're delivered or a full queue evicts them (`maxQueueSize`, or `logs.maxBufferSize` for logs).
- **`maxRetries`.** `maxRetries` is renamed to `pushNotificationSubscriptionMaxRetries`, with no deprecated alias. It only limits push-notification subscription retries.
- **Request size.** A request carries at most `maxBatchSize` events and about 5 MiB. The SDK sends as soon as about 5 MiB of events is queued, even before `flushAt` is reached. In 3.x.x, batches were limited by event count only.
- **Event size.** There's no size limit for one analytics event in the SDK, as in 3.x.x. An event larger than about 5 MiB is sent in a request of its own.
- **413 responses.** A 413 still halves the batch until it fits, as in 3.x.x. An event that gets a 413 on its own is dropped.

## Follow ups

### Move to event options

Capture v1 reads processing settings from an `options` object sent next to `properties`. `capture`, `screen`, `identify`, `setPersonProperties`, `alias`, `group`, and `captureException` take an `options` argument. For `identify`, the options apply to the `$identify` or `$set` event the call sends.

Pass an option in `options` instead of its `$`-prefixed property:

```kotlin
// 3.x.x
PostHog.capture("signed_up", properties = mapOf("\$cookieless_mode" to true))

// 4.x.x
PostHog.capture("signed_up", options = mapOf("cookieless_mode" to true))
```

The legacy option properties still work. They're the `$`-prefixed properties legacy capture reads as settings. When the event is sent, the SDK removes each one from `properties` and uses it for its option:

| Legacy option property | Option |
| --- | --- |
| `$process_person_profile` | `process_person_profile` |
| `$cookieless_mode` | `cookieless_mode` |
| `$ignore_sent_at` | `disable_skew_correction` |
| `$product_tour_id` | `product_tour_id` |

An option you pass to any of these methods or set in `beforeSend` wins over its legacy property. An option set to `null` counts as unset, so the legacy property fills it.

- **Other keys.** Other option keys are sent unchanged, and PostHog ignores keys it doesn't know.
- **Every event.** There are no global or context options. To set an option on every event, register its legacy property, for example `register("\$cookieless_mode", true)`.
- **GeoIP and migrations.** There's no setting for GeoIP lookup or `historical_migration` on events, as in 3.x.x.
- **Queued events.** Events still queued on disk by 3.x.x are sent to capture v1 after the update, with their legacy properties moved to options.

### Send AI events with captureAi

`captureAi` sends AI events, such as `$ai_generation`, to PostHog's AI endpoint (`/i/v1/ai/events`) instead of the analytics endpoint. `capture` never sends events to the AI endpoint, even ones named `$ai_*`.

`captureAi` takes the same arguments as `capture`, and the events go through the same `beforeSend` hooks:

```kotlin
PostHog.captureAi(
    "\$ai_generation",
    properties = mapOf("\$ai_model" to "gpt-5", "\$ai_trace_id" to traceId),
)
```

- **Return value.** `captureAi` returns the event's uuid, or `null` when the event isn't queued. That happens when the SDK is disabled or opted out, the event name is empty, `beforeSend` dropped it, or it's over the size limit.
- **Queue limits.** Once 100 AI events (`captureAiMaxQueueSize`) or 50 MiB are queued, the oldest are dropped. The count includes events still waiting to be written to disk during a slow upload.
- **Event size.** An AI event's properties can be up to 8 MiB. The SDK drops a serialized event over 8 MiB + 64 KiB, measured before `encryption`. With `debug` on, it logs the event name and the limit, never the content.
- **A lower size limit.** Set `captureAiMaxEventBytes` to lower that limit. A larger value uses 8 MiB + 64 KiB, and with `debug` on the SDK logs that it did.
- **Event names.** PostHog drops an event sent with `captureAi` whose name doesn't start with `$ai_`.
- **Timeouts.** AI requests time out after 60 seconds without network activity, or `captureAiTimeout`. Other requests keep OkHttp's 10-second defaults.
- **Disk queue.** On Android, AI events are queued on disk separately from other events (`posthog-disk-ai-queue` in the app's cache directory). Up to 100 events (`captureAiMaxQueueSize`) and 50 MiB are kept, and the oldest are dropped first. `maxQueueSize` doesn't apply.
- **Flushing.** `flush()` and `close()` also send the AI queue. On Android, AI events left on disk are sent at the next launch.
- **Compression.** AI requests are gzipped unless you set `captureAiCompression` to `PostHogCompression.NONE`. `compression` doesn't apply.
- **Batching.** AI events use `flushAt`, `maxBatchSize`, and `flushIntervalSeconds`, like other events. A request carries up to about 5 MiB, as for analytics events (see [Check retries](#check-retries)).
- **Content.** The SDK sends AI events unchanged. It doesn't redact or truncate prompts, outputs, or media, so use `beforeSend` to remove anything you don't want to send.
