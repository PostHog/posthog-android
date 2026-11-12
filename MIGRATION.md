# Migration guide

## posthog-android 3.x → 4.0 and posthog (core) 7.0

4.0 sends analytics events to capture v1 (`/i/v1/analytics/events`) instead of `/batch`, removes APIs deprecated in 3.x and changes the feature flags callback.
Most removals are compile errors with a direct replacement. [Behavior changes](#behavior-changes) won't show up at build time.
Read the checklist first, then the sections that apply to you.

Core `posthog` 7.0 is only supported through `posthog-android` 4.0 and `posthog-server`. Using it on its own through `PostHogStateless.with` isn't supported; see [`posthog-server`](#posthog-server).

For the changes every capture v1 SDK shares, such as event options, limits and per-event results, see [Upgrading to capture v1 SDKs](https://posthog.com/docs/api/capture-v1).

For the full list of 4.0 changes, see the [changelog](https://github.com/PostHog/posthog-android/blob/main/posthog-android/CHANGELOG.md).

### Checklist

You need to change code if your app does any of these:

- sends events to a self-hosted PostHog or a reverse proxy that doesn't serve `/i/v1/analytics/events`
- authenticates its proxy with an `Authorization` header in `requestHeaders`
- sends events through a reverse proxy set up from PostHog's proxy guides before 29 October 2026, which strips `Authorization`
- sends events through a proxy that redirects capture requests, other than with a 307 or 308 to the same origin as `host`
- captures events with an empty event name, or with an event name or distinct ID over 200 bytes
- sets an empty distinct ID in `beforeSend`
- assigns a new map to `event.options` in `beforeSend` (see [Event options](#event-options))
- sets `projectToken` to a personal API key (`phx_...`)
- reads `$session_id` or `$window_id` from the raw request body, for example in a proxy
- sets `$process_person_profile`, `$cookieless_mode`, `$ignore_sent_at` or `$product_tour_id` to a value PostHog can't read, such as `"maybe"`
- registers `$process_person_profile` as `false` and still calls `identify`, `alias`, `group` or `setPersonProperties` (see [Event options](#event-options))
- registers a super property, or passes an event property, with the same key as one the SDK sets, such as `$os_name` or `$is_identified`, and expects the SDK's value to win
- calls `capture`, `screen`, `identify`, `setPersonProperties`, `alias`, `group` or `captureException` from Java, implements `PostHogInterface`, or calls `identify` through a `PostHogCoreInterface` reference
- sets the event `uuid` in `beforeSend`
- relies on a `Retry-After` longer than 30 seconds
- sets `maxRetries` on `PostHogConfig` or `PostHogAndroidConfig`
- uses `apiKey`, `propertiesSanitizer`, `evaluationEnvironments`, `debouncerDelayMs`, `screenshot`, `drawableConverter` or `remoteConfig`
- passes `PostHogConfig` or `PostHogSessionReplayConfig` constructor arguments by position
- calls `getFeatureFlagPayload()`, or passes a default value to `getFeatureFlag()`
- filters insights, cohorts or actions on the `$feature_flag_response` of `$feature_flag_called`, such as `""` or `false` for a missing flag (see [`$feature_flag_response` for a missing flag](#feature_flag_response-for-a-missing-flag))
- sets `PostHogConfig.onFeatureFlags` or `PostHogOnFeatureFlags`, or passes a callback to `reloadFeatureFlags()`
- uses session replay (see [Session replay records screenshots](#session-replay-records-screenshots))
- reads a feature flag payload that isn't valid JSON, such as plain text saved as `hello`
- breaks down by `$locale` for the language your app shows (use `$app_language`, and call `setAppLanguage()` if you need it on Android 6–12)
- reads event content or request bodies from the SDK's debug log, or matches its log text
- depends only on `posthog-android-surveys-compose`, or uses `posthog-server` (2.25.0 and later need `posthog-android` 4.0 in the same project)

### Requirements and versions

| Artifact | Upgrade |
| --- | --- |
| `com.posthog:posthog-android` | 3.x to 4.0 |
| `com.posthog:posthog` (core) | 6.x to 7.0 |
| `com.posthog:posthog-android-surveys-compose` | keep your version, and declare `com.posthog:posthog-android` 4.0.0 or later in your build so Gradle resolves 4.x instead of the 3.x version it pulls in |
| `com.posthog:posthog-server` | 2.25.0, together with `posthog-android` 4.0; see [`posthog-server`](#posthog-server) |

These are unchanged, so you don't need to change your build for them:

- Android minimum API level: 23
- Java bytecode target: Java 8
- Kotlin language and API compatibility: 2.0
- `posthog-android-gradle-plugin`

Upgrade PostHog artifacts together. Core 7.0 is not binary compatible with SDK versions built against core 6.x, except `posthog-android-surveys-compose`, which works with both. A mismatch fails at runtime with errors such as `NoSuchMethodError` or `NoClassDefFoundError`.

### Removed APIs

All of these are compile errors. Calling them from an already-compiled library that you haven't updated fails at runtime.

| Removed | Use instead |
| --- | --- |
| `PostHogConfig.apiKey`, `PostHogConfig(apiKey = …)`, `PostHogAndroidConfig(apiKey = …)` | `projectToken` |
| `PostHogConfig.propertiesSanitizer`, `PostHogPropertiesSanitizer` | `addBeforeSend` |
| `PostHogConfig.evaluationEnvironments` | `evaluationContexts` |
| `getFeatureFlagPayload()`, on the instance and on `PostHog` | `getFeatureFlagResult()` |
| `defaultValue` on `getFeatureFlag()` | `getFeatureFlag(key) ?: default` |
| `PostHogSessionReplayConfig.debouncerDelayMs` | `throttleDelayMs` |
| `PostHogSessionReplayConfig.screenshot`, `drawableConverter`, `PostHogDrawableConverter` | nothing; replay always records screenshots |
| `PostHogConfig.remoteConfig` | nothing; remote config always loads |
| `PostHogConfig.onFeatureFlags` | `PostHog.onFeatureFlags()` before `setup()` |
| `PostHogOnFeatureFlags` | `PostHogFeatureFlagsCallback` |
| `PostHogConfig.maxRetries` | `pushNotificationSubscriptionMaxRetries` (see [Retries](#retries)) |

The constructor parameters of `PostHogConfig` and `PostHogSessionReplayConfig` move up where these were removed. If you pass arguments by position, check them, or switch to named arguments.

#### `apiKey`

```kotlin
// 3.x
val config = PostHogAndroidConfig(apiKey = "phc_your_project_token")
val token = config.apiKey

// 4.0
val config = PostHogAndroidConfig(projectToken = "phc_your_project_token")
val token = config.projectToken
```

`PostHogAndroidConfig("phc_your_project_token")`, with no parameter name, still works. From Java, replace `getApiKey()` with `getProjectToken()`.

#### `propertiesSanitizer`

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

#### `evaluationEnvironments`

```kotlin
// 3.x
config.evaluationEnvironments = listOf("production")

// 4.0
config.evaluationContexts = listOf("production")
```

#### `getFeatureFlagPayload()`

`getFeatureFlagPayload()` never sent `$feature_flag_called`. `getFeatureFlagResult()` sends it by default, following `sendFeatureFlagEvent`, so pass `sendFeatureFlagEvent = false` to keep your event volume and experiment exposures unchanged. If you drop that argument, nothing fails, but you'll send new events.

The new call has no `defaultValue` parameter, so apply your default with `?:`.

```kotlin
// 3.x
val payload = PostHog.getFeatureFlagPayload("checkout", defaultValue = "fallback")

// 4.0
val payload = PostHog.getFeatureFlagResult("checkout", sendFeatureFlagEvent = false)?.payload ?: "fallback"
```

#### `getFeatureFlag()` default value

```kotlin
// 3.x
val value = PostHog.getFeatureFlag("checkout", defaultValue = "fallback")

// 4.0
val value = PostHog.getFeatureFlag("checkout") ?: "fallback"
```

The second parameter is now `sendFeatureFlagEvent`, which affects code that still compiles:

- `getFeatureFlag("checkout", true)` compiles, but `true` now means `sendFeatureFlagEvent` rather than the default value.
- A disabled SDK now returns `null`, not your default.
- `$feature_flag_called` no longer reports a fallback as the flag's value. `isFeatureEnabled("missing", true)` still returns `true`, but its event leaves out `$feature_flag_response` (see [`$feature_flag_response` for a missing flag](#feature_flag_response-for-a-missing-flag)).

Classes that implement `PostHogInterface` update the signature.

#### `debouncerDelayMs`

```kotlin
// 3.x
PostHogSessionReplayConfig(debouncerDelayMs = 500)

// 4.0
PostHogSessionReplayConfig(throttleDelayMs = 500)
```

#### `remoteConfig`

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

### Feature flags callback

`PostHogConfig.onFeatureFlags` is removed. Call `PostHog.onFeatureFlags()` before `setup()` instead, which also covers the first load. An instance from `PostHogAndroid.with()` is set up as it's created, so call `onFeatureFlags()` on it right after `with()`; if flags have already loaded, the listener gets the current values shortly after it registers. `reloadFeatureFlags()` now takes a `PostHogFeatureFlagsCallback`. Both callbacks receive a `PostHogFeatureFlagsLoaded`:

- `flags`: the keys of the enabled flags
- `variants`: their values, either `true` or the variant string
- `errorsLoading`: whether the latest load failed. The values are then the last known ones, which can be empty

```kotlin
// 3.x
config.onFeatureFlags = PostHogOnFeatureFlags {
    refreshUi()
}

// 4.0, before PostHogAndroid.setup()
PostHog.onFeatureFlags { loaded ->
    if (!loaded.errorsLoading) {
        refreshUi()
    }
}
```

The listener differs from the removed option in three ways:

- It runs on the main thread on Android. The option ran on a background thread.
- Bootstrapped flags arrive just after `setup()` is called, not synchronously inside it.
- A reload on a disabled SDK doesn't call listeners. `reloadFeatureFlags(callback)` still gets `errorsLoading = true` in that case.

From Java, the `reloadFeatureFlags()` lambda now takes the `PostHogFeatureFlagsLoaded` argument:

```java
// 3.x
PostHog.Companion.reloadFeatureFlags(() -> refreshUi());

// 4.0
PostHog.Companion.reloadFeatureFlags(loaded -> refreshUi());
```

A Kotlin trailing lambda such as `PostHog.reloadFeatureFlags { refreshUi() }` compiles unchanged, so it won't show up as an error. Check whether it should now read `errorsLoading`: the callback has always also run after a failed load.

`errorsLoading` is `false` when PostHog answers that the project is over its feature flags quota, and the values are the cached flags. It's `true` when no request was made, such as when the device is offline or the distinct ID is blank, and when the SDK is disabled or isn't set up, with no values in that last case.

The `reloadFeatureFlags()` callback still runs on a background thread, or right away on the calling thread when no load is attempted, so switch to the main thread before touching UI.

### `PostHogInterface` implementations

`PostHogInterface` lost `getFeatureFlagPayload()`, gained the abstract `onFeatureFlags()` and `setAppLanguage()`, and changed the callback type of `reloadFeatureFlags()`. Classes that implement it, such as hand-written fakes for tests, no longer compile until they're updated. They also add the `options` parameter and `captureAi()` (see [Event options](#event-options) and [AI events](#ai-events)).

```kotlin
class FakePostHog : PostHogInterface {
    override fun reloadFeatureFlags(onFeatureFlags: PostHogFeatureFlagsCallback?) {}

    // mock() from mockito-kotlin, or your mocking library's equivalent
    override fun onFeatureFlags(callback: PostHogFeatureFlagsCallback): PostHogFeatureFlagsSubscription = mock()

    // ...
}
```

`PostHogFeatureFlagsSubscription` and `PostHogFeatureFlagsLoaded` have constructors marked `@PostHogInternal`, which aren't intended for app code, so create them with a mocking library.

### Internal APIs

These helpers were public only by accident and were marked `@PostHogInternal`. They're no longer visible outside the SDK and have no replacement. Apps that don't use `@PostHogInternal` APIs aren't affected.

- `com.posthog.internal`: `CustomHeadersInterceptor`, `PostHogPrintLogger`, `PropertyGroupDeserializer`, `PropertyValueDeserializer`, `personPropertiesContext()`, `PostHogPreferences.PUSH_SUBSCRIPTION_REJECTED`
- `com.posthog.internal.errortracking`: `ThrowableCoercer.fromThrowableToPostHogProperties()`
- `com.posthog.internal.replay`: `RRDocumentNode`, `RRDomContentLoadedEvent`, `RRLoadedEvent`, `RRStyle`; `RRWireframe` keeps only `id`, `x`, `y`, `width`, `height`, `type` and `base64`
- `com.posthog.internal.surveys`: `findBestTranslationMatch()`
- `com.posthog.android.internal`: `Bitmap.isValid()`, `Bitmap.webpBase64()`
- `com.posthog.android.replay.internal`: `LogLine`, `LogcatParser`

If you call `PostHogRemoteConfig.loadFeatureFlags()` or `loadRemoteConfig()`, their callback parameters changed too. The internal callback is now `(errorsLoading: Boolean) -> Unit`, and the other is a `PostHogFeatureFlagsCallback`.

### Behavior changes

#### Feature flag payloads must be valid JSON

A payload that isn't valid JSON now reads as `null` from `getFeatureFlagResult(key)?.payload` and `getAllFeatureFlags()`, instead of the raw string. With `debug` on, the SDK logs the flag key.

| Payload | 3.x | 4.0 |
| --- | --- | --- |
| Malformed (`{broken`, `[1, 2`), empty or whitespace-only | the raw string | `null` |
| Only valid in lenient mode (unquoted `hello`, single quotes, comments) | decoded leniently | `null` |
| JSON `null` | the string `"null"` | `null` |
| Valid JSON (`"hello"`, `""`, `false`, `0`, objects, arrays) | decoded | decoded, unchanged |
| Bootstrapped payloads | as passed | as passed, unchanged |

The flag's value, enabled state, variant and any other flag's payload aren't affected.

If you read a payload as plain text, such as a payload saved as `hello` rather than `"hello"`, update it in PostHog to valid JSON before you upgrade, or your code gets `null`. PostHog no longer saves a new payload that isn't valid JSON, so only payloads saved earlier are affected.

Apps upgrading from 3.13 or older didn't cache flag details, so until the app's first `/flags` response, a malformed payload cached by 3.x still reads as the raw string, as it did in 3.x.

#### Empty variants read as disabled

A flag whose variant is an empty string now reads as disabled everywhere, as it already did from `isFeatureEnabled()`, with or without `defaultValue`. `getFeatureFlagResult()` and `getAllFeatureFlags()` return `enabled = false` for it, where 3.x returned `true`, `PostHogFeatureFlagsLoaded` leaves it out of `flags` and `variants`, and captured events leave it out of `$active_feature_flags`. `variant`, `getFeatureFlag()` and the event's `$feature/<key>` property still return `""`.

PostHog no longer accepts empty variants for new flags, so only flags created earlier through the API, MCP or Terraform are affected. `/flags` still returns `enabled: true` for them, and other PostHog SDKs can still report them as enabled, so rename the variant before you upgrade.

#### `$feature_flag_response` for a missing flag

When a flag is missing, `$feature_flag_called` now leaves out `$feature_flag_response`. 3.x sent `""` from `getFeatureFlag()` and `false` from `isFeatureEnabled()`, or your default value when you passed one. In 4.0 an empty string means a flag with an empty variant, so a missing flag no longer reports one.

Insights, cohorts and actions that filter on `$feature_flag_response = ""` or `false` to find missing flags stop matching new events without any error. Use an `is not set` filter on `$feature_flag_response` instead. Events already stored keep their old values.

#### `$locale` and `$app_language`

`$locale` now means the first language in the device settings, as a full language tag such as `de-CH` or `zh-Hant-TW`. In 3.x it came from `Locale.getDefault()`, which could be a secondary language, a per-app language or a value your app set with `Locale.setDefault()`, and it dropped the script (`zh-TW`). It's left out when the device reports no language. On older Android versions, 3.x also sent the legacy codes `iw`, `in` and `ji`, so Hebrew, Indonesian and Yiddish values change, such as `in-ID` to `id-ID`. Update filters that match those. Other values mostly stay the same, so otherwise only check filters if your users change these settings.

The new `$app_language` property is the language your app shows. Android has no API that reports it exactly, so what you get depends on the version:

| Android | `$app_language` |
|---|---|
| 13+ with `android:localeConfig` declared (by hand or with AGP `generateLocaleConfig`) | The per-app language or, without one, the first device language, as you declare it. If you don't declare that language, your `android:defaultLocale` (Android 15+) or your language if you only declare one. Not sent if several of your declared languages could match, such as `pt` and `pt-PT` for a `pt-MZ` device, or if nothing matches and you declare several languages without a default. |
| 13+ without `localeConfig` | The per-app language, if one is set. Otherwise not sent. |
| 6–12 | Not sent. |

The SDK reads both languages in the background when an event is captured and uses them from the next event on, so the first event after a language change carries the previous value. Changing only the runtime override (`LocaleManager.setOverrideLocaleConfig`) isn't picked up until the device or per-app language changes; call `PostHog.setAppLanguage()` if your app changes it.

Strings you don't translate fall back per string, so the value is the app's language, not a guarantee for every string. Call `PostHog.setAppLanguage("de")` at startup and whenever the language changes to send it on every version, or if you translate some other way, such as with an in-app language setting. It's kept in memory only.

#### Session replay records screenshots

4.0 removes wireframe session replay. Replay always records masked screenshots, which is what `screenshot = true` already did.

```kotlin
// 3.x
config.sessionReplayConfig.screenshot = true

// 4.0: remove the line
```

Apps that relied on the default now upload screenshots:

- Masking still covers text inputs, images, Compose semantics, `ph-no-capture` views and `Modifier.postHogMask()`. `WebView`s are masked whole while `maskAllTextInputs` or `maskAllImages` is on.
- Custom-drawn views and other content the SDK doesn't recognize, such as maps or video, can now appear in recordings. Mask them, or turn replay off with `sessionReplay = false`.
- Jetpack Compose screens, which wireframe captured as empty boxes, are now recorded. That can count against your replay quota and may need a consent or privacy disclosure update.
- Each frame uses more bandwidth and CPU than a wireframe.

Compose masking also changed, including for apps that already set `screenshot = true`:

- `Modifier.postHogMask()` and `Modifier.postHogUnmask()` apply only to the element and its children, not to siblings merged into the same clickable row.
- Text that draws past its layout box is masked in full.
- Nodes using `Modifier.clearAndSetSemantics` are masked while text or image masking is on. Use `Modifier.postHogUnmask()` to show them.

A dialog's dim, and a dialog masked whole on Android 8.1 and lower, are sent as screenshot images instead of styled wireframe shapes.

The `$sdk_debug_replay_capture_mode` property is now always `screenshot`.

#### Deep link query parameters

The `Deep Link Opened` event leaves out query parameters whose name starts with `$`, such as `$process_person_profile`, so a link can't set PostHog's own properties. Other parameters and the `url` property are sent as in 3.x.

#### Other changes that don't fail the build

The code you end up with after these two migrations compiles, so they're easy to miss:

- [`getFeatureFlagResult()` sends `$feature_flag_called` by default](#getfeatureflagpayload), unlike `getFeatureFlagPayload()`.
- [Apps that set `remoteConfig = false` now load `/config`](#remoteconfig) and apply its settings.

### Endpoints

| Data | 3.x | 4.0 |
| --- | --- | --- |
| Analytics events | `/batch` | `/i/v1/analytics/events` |
| Session replay | `/s/` | `/s/`, unchanged |
| AI events (`captureAi`) | – | `/i/v1/ai/events` (see [AI events](#ai-events)) |
| Logs | `/i/v1/logs` | `/i/v1/logs`, unchanged |
| Feature flags | `/flags` | `/flags`, unchanged |

Capture v1 requests send the project token in an `Authorization: Bearer <projectToken>` header instead of an `api_key` body field.
This header replaces any `Authorization` header you set in `PostHogConfig.requestHeaders`, so authenticate your proxy with a different header, such as `X-Proxy-Token`.
Other requests still send your `Authorization` header.

Analytics requests are gzipped unless you set `compression` to `PostHogCompression.NONE`, as in 3.x.

A personal API key (`phx_...`) in `projectToken` now disables the SDK at setup, as an empty or whitespace-only token does. Both print why, even when `debug` is off. To turn the SDK off in tests, use an empty token.
In 3.x, the SDK started and PostHog rejected every event with a 401. Use your project token (`phc_...`).

#### Hosts without capture v1

If you self-host PostHog, make sure `/i/v1/analytics/events` is served before you upgrade, and `/i/v1/ai/events` if you use `captureAi`.
PostHog Cloud (`us.i.posthog.com`, `eu.i.posthog.com`) serves it.

If you send events through your own reverse proxy, update it before you upgrade:

- Forward `/i/v1/analytics/events` and `/i/v1/ai/events` to PostHog.
- Pass `Authorization` (the project token, sent as `Bearer <token>`), every `PostHog-*` header, `Content-Type`, `Content-Encoding` and `User-Agent` through unchanged. PostHog answers `401` without `Authorization` and `400` without the others, and the events are dropped. If your proxy strips other credentials, keep `Authorization` values that start with `Bearer phc_`, `Bearer phs_` or `Bearer phx_`, plus the exact `Bearer <key>` of an older key without a prefix.
- Accept request bodies up to 20 MB. AI requests are often larger than 1 MB, the default limit in nginx and ingress-nginx. A `413` from the proxy makes the SDK halve the batch until it fits, and drops an event that doesn't fit on its own.
- Pass PostHog's response body through unchanged. A 2xx response whose body isn't PostHog's `{"results": {...}}` drops the events instead of retrying them.

PostHog's managed reverse proxy already does this and needs no change. A proxy set up from PostHog's [proxy guides](https://posthog.com/docs/advanced/proxy) before 29 October 2026 strips `Authorization`, so PostHog answers `401` and the events are dropped. Update it from the current guide.

The SDK doesn't fall back to `/batch`. A 404 is retried, so the events stay queued until the path is served, and the SDK prints one warning, even with `debug` off. Any other error status that isn't retried, such as 403 or 405, drops the events.

#### Redirects

The SDK follows a 307 or 308 redirect only to the origin of `host` (same scheme, host and port), at most 5 times, and resends the same body and headers.
Any other redirect is not followed and the events are dropped.
In 3.x, OkHttp followed redirects to any host.
If a proxy redirects capture requests to another host, point `host` at the final host.
This also applies when you set your own `httpClient`: for capture requests, the SDK turns off its redirect following and OkHttp's connection retries, and keeps your other settings. Other requests, such as session replay and logs, behave as in 3.x.

### Event validation

Capture v1 checks each event on its own and drops only the invalid ones, so the rest of the batch is delivered.

The SDK drops an event whose name is empty before sending it, for example `capture("")` or one `beforeSend` renames to `""`.
With `debug` on, it logs this once.

There's no error callback, as in 3.x. The SDK reports dropped and failed events only in its debug log.

The SDK doesn't check the rest before sending. PostHog drops the event, and the SDK removes it from the queue.
With `debug` on, the SDK logs one line per batch with how many events PostHog dropped, how many duplicate copies it dropped and how many it asked to retry, with the reasons PostHog gave, never the event names or properties.
It no longer logs a line for each queued event.
Debug logs for capture requests name the event count and the response status, and no longer print the request and response bodies.
When a `beforeSend` hook throws, the debug log names the event and the exception type, not the exception message, which can hold the data the hook was removing. In 3.x it printed the exception.
When a property value can't be serialized, the debug log names the key and the value's type, not the value. In 3.x it printed the value.
Update any log-based checks that match the 3.x text.

- An event with an empty or whitespace-only distinct ID is dropped, for example one `beforeSend` sets to `""`.
- An event whose name is over 200 UTF-8 bytes is dropped. Legacy capture had no limit on event name length.
- An event whose distinct ID is over 200 UTF-8 bytes is dropped. Legacy capture shortened the ID to 200 characters and kept the event.

In 3.x, an empty event name or distinct ID made legacy capture reject the whole batch it was in.

A `NaN` or infinite number in the properties is dropped from the event, and the event is sent without it.

A map key in the properties that isn't a string is converted to one when it's a number, a boolean or `null`. Any other key is dropped with its value, and the rest of the event is sent. With `debug` on, the SDK logs the key's type. In 3.x, a nested map with a non-string key lost the whole event.

A `null` element in a list or array in the properties is now sent as JSON `null` at its position, and so is an element that can't be serialized, such as `NaN`. In 3.x, both were removed, which moved the later elements to lower indexes. A property or map entry whose value is `null` is still left out.

The distinct ID can come from `identify()`, the `distinctId` argument of `capture` or `beforeSend`.
If you identify users with a long ID, such as a JWT or a long email address, shorten or hash it before you upgrade. Otherwise every event for that user is dropped.
`identify()` with an empty or whitespace-only distinct ID is still ignored, as in 3.x.

### Property precedence

An event's properties are merged in this order, and later values win:

1. properties the SDK sets itself, such as `$os_name`, `$app_version`, `$screen_name`, `$is_identified`, `$session_id` and the session replay debug properties (`$recording_status`, `$sdk_debug_*`)
2. registered super properties (`register`)
3. the properties you pass to `capture` or another event-sending method; `userProperties`, `userPropertiesSetOnce` and `groups` are placed as `$set`, `$set_once` and `$groups` here, and when you also pass that key in properties they win key by key; a `$groups` map in properties adds to the groups set with `group()` instead of replacing them
4. `beforeSend`

In 3.x, the SDK's own values won over super properties, and `$is_identified`, `$process_person_profile` and the session replay debug properties also won over the properties you pass.
In 4.0 a value you register or pass wins, for example `register("\$os_name", "custom")` sends every event with that value.
A registered `$session_id` now overrides the SDK's session ID, which can detach events from their session replay.
A caller `$screen_name` or `$session_id` still replaces the SDK's value even when it's blank, as in 3.x, so a blank `$session_id` is sent as an empty `session_id`.
In 3.x, a `$set`, `$set_once` or `$groups` map in properties replaced the per-call values and the groups set with `group()`. A `$set` or `$set_once` you pass, or `userProperties`, still replaces a registered one whole.

`$lib` and `$lib_version` name the library that produced the event, and the SDK fills them last, as a pair, only where nothing above named the library.
When neither is set, or only `$lib_version` is, the SDK sends its own `$lib` and `$lib_version`.
When `$lib` is the SDK's own name without a version, the SDK adds its version.
When `$lib` names another library, the SDK leaves both as they are and never adds its own version, so set both keys together.
In 3.x, a registered `$lib` or `$lib_version` was replaced by the SDK's, and a `$lib` you passed was sent with the SDK's `$lib_version`.

### Session and window IDs

Capture v1 sends `$session_id` and `$window_id` as top-level `session_id` and `window_id` fields, not as properties.
The SDK moves them out of `properties` for you.
A string value, including an empty string, is sent unchanged.
Any other value is removed and not sent. With `debug` on, the SDK logs the key and the value's JSON type, never the value.
A null value counts as unset and is removed without a log.
In PostHog, the events still have `$session_id` and `$window_id`, so insights, filters and session replay work as before.

### Timestamps and IDs

The SDK sends every timestamp in UTC with 3 fractional digits, for example `2026-01-01T00:00:00.000Z`, as 3.x did.
It doesn't check for future timestamps. PostHog replaces a timestamp more than 23 hours ahead with the time it received the event.
Event uuids and anonymous distinct IDs are UUID v7, as in 3.x. An anonymous ID you change with `getAnonymousId` is sent as you return it.
Each capture request also sends a UUID v7 request ID in the `PostHog-Request-Id` header, which 3.x didn't send.

### Event options

Capture v1 reads processing settings from an `options` object sent next to `properties`.
`capture` takes a new `options` argument, and `beforeSend` can read and change `PostHogEvent.options`.
`screen`, `identify`, `setPersonProperties`, `alias`, `group` and `captureException` take the same argument, as their last parameter.
For `identify`, the options apply to the `$identify` or `$set` event the call sends.

```kotlin
// 3.x
PostHog.capture("signed_up", properties = mapOf("\$cookieless_mode" to true))

// 4.0
PostHog.capture("signed_up", options = mapOf("cookieless_mode" to true))
```

Kotlin callers that use named or positional arguments for the existing parameters keep compiling.
Java callers pass the new trailing `options` argument, or `null`.
Classes that implement `PostHogInterface` add the parameter.
`identify(distinctId, userProperties, userPropertiesSetOnce)` moved from `PostHogCoreInterface` to `PostHogStatelessInterface`; call it through `PostHogInterface` or `PostHogStatelessInterface`.

The legacy option properties, the `$`-prefixed properties legacy capture reads as settings, still work.
When the event is sent, the SDK removes each one from `properties` and uses it for its option:

| Legacy option property | Option |
| --- | --- |
| `$process_person_profile` | `process_person_profile` |
| `$cookieless_mode` | `cookieless_mode` |
| `$ignore_sent_at` | `disable_skew_correction` |
| `$product_tour_id` | `product_tour_id` |

- An option you pass to any of these methods or set in `beforeSend` wins over its legacy property. An option set to `null` counts as unset, so the legacy property fills it.
- To change person processing in `beforeSend`, edit `event.options`. A `$process_person_profile` property the hook adds only fills an option that is still unset, so it doesn't override the `false` the SDK set for an anonymous event.
- When `personProfiles` turns person processing off, the SDK sets the `process_person_profile` option to `false`. Otherwise it sends neither, because PostHog processes person profiles by default. In 3.x, every event carried a `$process_person_profile` property. To change person processing for one event, pass the `process_person_profile` option or the `$process_person_profile` property. A registered `$process_person_profile` changes it for every event. In 3.x, the SDK's value replaced a registered `$process_person_profile`, so a registered `false` had no effect. In 4.0 it applies, also after `identify` and with `PersonProfiles.ALWAYS`: those events don't change the person profile, and their `$set` is ignored. They are still linked to the person and billed as identified events.
- This also applies with `personProfiles = PersonProfiles.NEVER`: a `process_person_profile` option or `$process_person_profile` property set to `true`, passed or registered, turns person processing on for that event. In 3.x, `NEVER` always won. If you rely on `NEVER` to keep events personless, make sure neither is set to `true`, or remove it in `beforeSend`.
- Review your `beforeSend` hooks for the new event shape. In 3.x, the SDK marked person processing with the `$process_person_profile` property. In 4.0, it's the `process_person_profile` option, and the property isn't set. A hook that assigns a new map to `event.options` without keeping that key loses the `false` the SDK set. That turns person processing on for anonymous users, overriding `personProfiles`. Editing `event.options` in place keeps it.
- `beforeSend` gets its own copy of the event's nested maps, lists and arrays, in `properties` and `options`. Changes a hook makes no longer reach the objects you passed to `capture` or registered, and identity checks against them (`===`) no longer match. In 3.x, hooks edited the caller's objects.
- `options` is a property of `PostHogEvent`, not a constructor parameter, so the constructor and `copy` are unchanged from 3.x. When `beforeSend` returns a different event whose `options` is `null`, such as one made with `event.copy(…)` or the constructor, it gets the options of the event the hook was given. To send an event with no options, set `options` to an empty map.
- A `$set` sent with `process_person_profile: false` doesn't update the person, so it no longer suppresses a later `setPersonProperties` or `identify` call with the same properties as a duplicate.
- The SDK sends option values as given. PostHog reads common forms of a value, such as `"false"` or `0` for a boolean, and drops an event whose option value it can't read.
- Other option keys are sent unchanged, and PostHog ignores keys it doesn't know.
- There are no global or context options. To set an option on every event, register its legacy property, for example `register("\$cookieless_mode", true)`.
- There's no setting for GeoIP lookup or `historical_migration` on events, as in 3.x.
- With `personProfiles = PersonProfiles.IDENTIFIED_ONLY`, `identify`, `alias`, `group` and `setPersonProperties` still turn on person processing for the user's later events, as in 3.x.
- A registered `$process_person_profile` set to `false` also reaches the events `identify`, `alias`, `group` and `setPersonProperties` send. PostHog accepts `$identify`, `$create_alias` and `$groupidentify` and then drops them with an ingestion warning, and the SDK doesn't report it. A `$set` event is kept, but the person isn't updated. To keep identifying, pass the option on the call, or set it in `beforeSend`:

  ```kotlin
  PostHog.register("\$process_person_profile", false)

  PostHog.identify(
      distinctId = "user-123",
      userProperties = mapOf("plan" to "pro"),
      options = mapOf("process_person_profile" to true),
  )
  ```

  If you registered `false` to skip person profiles for anonymous users, remove it: `personProfiles = PersonProfiles.IDENTIFIED_ONLY`, the default, already does that and still lets `identify` create the person. To create no person profiles at all, set `personProfiles = PersonProfiles.NEVER` instead. The SDK then skips these calls.
- An empty or whitespace-only `distinctId` argument to `capture` now uses the current distinct ID, like `null`. In 3.x, the event was dropped.
- The event `beforeSend` returns keeps the uuid of the capture it came from. A uuid you set in `beforeSend`, for example with `copy(uuid = …)`, is replaced, so a hook that returns an event kept from an earlier call still sends one uuid per capture.
- Events still queued on disk by 3.x are sent to capture v1 after the update, with their legacy properties moved to options.

### AI events

4.0 adds `captureAi`, which sends AI events, such as `$ai_generation`, to PostHog's AI endpoint (`/i/v1/ai/events`) instead of the analytics endpoint.
It takes the same arguments as `capture`, and the events go through the same `beforeSend` hooks.

```kotlin
PostHog.captureAi(
    "\$ai_generation",
    properties = mapOf("\$ai_model" to "gpt-5", "\$ai_trace_id" to traceId),
)
```

- `captureAi` returns the event's uuid, or `null` when the event isn't queued: the SDK is disabled or opted out, the event name is empty, `beforeSend` dropped it, or it's over the size limit. Once 100 AI events (`captureAiMaxQueueSize`) or 50 MiB are queued, including events still waiting to be written to disk during a slow upload, the oldest are dropped.
- An AI event's properties can be up to 8 MiB. The SDK drops a serialized event over 8 MiB + 64 KiB, measured before `encryption`. With `debug` on, it logs the event name and the limit, never the content. Set `captureAiMaxEventBytes` to lower that limit; a larger value uses 8 MiB + 64 KiB, and with `debug` on the SDK logs that it did.
- PostHog drops an event sent with `captureAi` whose name doesn't start with `$ai_`. `capture` never sends events to the AI endpoint, even ones named `$ai_*`.
- AI requests time out after 60 seconds without network activity, or `captureAiTimeout`. Other requests keep OkHttp's 10-second defaults.
- On Android, AI events are queued on disk separately from other events (`posthog-disk-ai-queue` in the app's cache directory). Up to 100 events (`captureAiMaxQueueSize`) and 50 MiB are kept, and the oldest are dropped first. `maxQueueSize` doesn't apply.
- `flush()` and `close()` also send the AI queue. On Android, AI events left on disk are sent at the next launch.
- AI requests are gzipped unless you set `captureAiCompression` to `PostHogCompression.NONE`. `compression` doesn't apply.
- AI events use `flushAt`, `maxBatchSize` and `flushIntervalSeconds`, like other events. A request carries up to about 5 MiB, as for analytics events (see [Retries](#retries)).
- The SDK sends AI events unchanged. It doesn't redact or truncate prompts, outputs or media, so use `beforeSend` to remove anything you don't want to send.

### Retries

These apply to analytics and AI event requests, unless a bullet names session replay or logs. Session replay and logs keep their 3.x status handling: they still retry 3xx and don't retry 404.

- The SDK retries network errors and 404, 408, 429, 500, 502, 503 and 504 responses. Other statuses drop the events, including 3xx, and a 402 (billing limit) drops the whole batch. A product quota, such as for exceptions or AI events, drops only those events inside a 200 response. 3.x also retried 3xx responses, and didn't retry 404.
- When the response asks to retry only some events, the SDK keeps those events queued and removes the rest.
- A failed request is resent with the same events, request ID and `created_at`, and the next attempt number, before newer events are sent. The request ID and attempt number reset after an app restart. OkHttp doesn't retry capture requests on its own.
- A `Retry-After` header sets the minimum wait before the next send, up to 30 seconds. This also applies to session replay and logs. In 3.x there was no cap.
- `Retry-After` can be a number of seconds or an HTTP date. 3.x read only a number of seconds.
- After a failed send, the wait before the next one doubles from 1 second up to 30 seconds, with up to 25% random jitter at millisecond precision. In 3.x it doubled without jitter and a `Retry-After` replaced it. This applies to events, session replay and logs.
- Event, session replay and log retries have no limit, as in 3.x: queued records stay until they're delivered or a full queue evicts them (`maxQueueSize`, or `logs.maxBufferSize` for logs).
- `maxRetries` is renamed to `pushNotificationSubscriptionMaxRetries`, with no deprecated alias. It only limits push-notification subscription retries.
- A request carries at most `maxBatchSize` events and about 5 MiB, and the SDK sends as soon as about 5 MiB of events is queued, even before `flushAt` is reached. In 3.x, batches were limited by event count only.
- There's no size limit for one analytics event in the SDK, as in 3.x. An event larger than about 5 MiB is sent in a request of its own.
- A 413 still halves the batch until it fits, as in 3.x. An event that gets a 413 on its own is dropped.
- `beforeSend` still runs when you capture the event, before it's queued, not when the batch is sent.

### New: `onFeatureFlags()`

`onFeatureFlags()` is now the way to listen for flag loads, for example from a screen or a library. You can remove the listener later:

```kotlin
val subscription = PostHog.onFeatureFlags { loaded ->
    if (loaded.variants["new-checkout"] == "test") {
        showTestVariant()
    }
}

// later
subscription.unsubscribe()
```

On Android, listeners run on the main thread. If flags have already loaded, a new listener also gets the current values shortly after it registers. You can register before `setup()`, and listeners stay registered across `close()` and `setup()`.

### `posthog-server`

`posthog-server` isn't having a major release, and its API is unchanged: it keeps its own `apiKey`, `getFeatureFlagPayload` and `remoteConfig`, and `com.posthog.PostHogOnFeatureFlags` now ships in `posthog-server` itself. None of the API changes above apply to it. `PostHogEvent`, which `beforeSend` gets, keeps its 3.x constructor and `copy`, and gains an `options` property; `posthog-server` still sends to `/batch`, which doesn't use it, so a value set in `beforeSend` is sent but has no effect.

It still sends events to `/batch`, not to capture v1, so the endpoint, proxy, retry and event option changes above don't apply to it either.

Using core `posthog` on its own through `PostHogStateless.with` isn't a supported entry point in core 7.0. Without its own queue it gets Android's capture v1 behavior, including the persisted-queue retries, which isn't a server-side contract. Use `posthog-server` for server-side JVM apps. A class that extends `PostHogStateless` also needs updating: the protected `buildEvent()` gained `options` and `uuid` parameters, and `captureFeatureFlagCalledEvent()` is no longer protected.

A personal API key (`phx_...`) in `projectToken` now disables the client at setup, as an empty token does, and both print why even when `debug` is off.

Events with an empty name are dropped before sending. `userProperties`, `userPropertiesSetOnce` and `groups` now merge key by key over a `$set`, `$set_once` or `$groups` map in `properties`. A `null` element in a list or array in the properties is sent as JSON `null` at its position instead of being removed. `beforeSend` gets its own copy of the event's nested maps, lists and arrays, so its changes no longer reach the objects you passed to `capture`.

`posthog-server` 2.25.0 depends on core 7.0, so upgrade it and `posthog-android` together:

- If your project uses `posthog-android` 4.0 or core 7.0, use `posthog-server` 2.25.0 or later. Older `posthog-server` versions aren't compatible with core 7.0 at runtime.
- If your project still uses `posthog-android` 3.x, stay on `posthog-server` 2.24.x. Gradle resolves core 7.0 for both, and `posthog-android` 3.x fails at runtime against it with errors such as `NoSuchMethodError`.
