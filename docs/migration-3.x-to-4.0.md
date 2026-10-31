# Migrating from PostHog Android 3.x to 4.0

4.0 sends analytics events to capture v1 (`/i/v1/analytics/events`) instead of `/batch`, removes APIs deprecated in 3.x and changes the feature flags callback.
Most removals are compile errors with a direct replacement. [Behavior changes](#behavior-changes) won't show up at build time.
Read the checklist first, then the sections that apply to you.

<!-- TODO(capture-v1): when the public capture v1 SDK upgrade guide is published (PostHog/posthog.com#21386), link it here for details on options, limits and per-event results. -->

For the full list of 4.0 changes, see the [changelog](../posthog-android/CHANGELOG.md).

## Checklist

You need to change something if your app does any of these:

- sends events to a self-hosted PostHog or a reverse proxy that doesn't serve `/i/v1/analytics/events`
- authenticates its proxy with an `Authorization` header in `requestHeaders`
- sends events through a proxy that redirects capture requests, other than with a 307 or 308 to the same origin as `host`
- captures events with an empty event name, or with an event name or distinct ID over 200 bytes
- sets an empty distinct ID in `beforeSend`
- sets `projectToken` to a personal API key (`phx_...`)
- sets `$lib` or `$lib_version` and expects PostHog to store that value
- reads `$session_id` or `$window_id` from the raw request body, for example in a proxy
- sets `$process_person_profile`, `$cookieless_mode`, `$ignore_sent_at` or `$product_tour_id` to a value PostHog can't read, such as `"maybe"`
- registers a super property, or passes an event property, with the same key as one the SDK sets, such as `$os_name` or `$is_identified`, and expects the SDK's value to win
- calls `capture`, `screen`, `identify`, `setPersonProperties`, `alias`, `group` or `captureException` from Java, implements `PostHogInterface`, or calls `identify` through a `PostHogCoreInterface` reference
- sets the event `uuid` in `beforeSend`
- relies on a `Retry-After` longer than 30 seconds
- sets `maxRetries` on `PostHogConfig` or `PostHogAndroidConfig`
- uses `apiKey`, `propertiesSanitizer`, `evaluationEnvironments`, `debouncerDelayMs` or `remoteConfig`
- calls `getFeatureFlagPayload()`
- sets `PostHogOnFeatureFlags` or passes a callback to `reloadFeatureFlags()`
- reads a feature flag payload that isn't valid JSON, such as plain text saved as `hello`
- depends only on `posthog-android-surveys-compose`, or uses `posthog-server`

## Requirements and versions

| Artifact | Upgrade |
| --- | --- |
| `com.posthog:posthog-android` | 3.x to 4.0 |
| `com.posthog:posthog` (core) | 6.x to 7.0 |
| `com.posthog:posthog-android-surveys-compose` | keep your version, and declare `com.posthog:posthog-android:4.+` in your build so Gradle resolves 4.0 instead of the 3.x version it pulls in |
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
| `PostHogConfig.remoteConfig` | nothing; remote config always loads |
| `PostHogOnFeatureFlags` | `PostHogFeatureFlagsCallback` |
| `PostHogConfig.maxRetries` | `pushNotificationSubscriptionMaxRetries` (see [Retries](#retries)) |

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

`PostHogInterface` lost `getFeatureFlagPayload()`, gained the abstract `onFeatureFlags()` and changed the callback type of `reloadFeatureFlags()`. Classes that implement it, such as hand-written fakes for tests, no longer compile until they're updated. They also add the `options` parameter and `captureAi()` (see [Event options](#event-options) and [AI events](#ai-events)).

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

### Other changes that don't fail the build

The code you end up with after these two migrations compiles, so they're easy to miss:

- [`getFeatureFlagResult()` sends `$feature_flag_called` by default](#getfeatureflagpayload), unlike `getFeatureFlagPayload()`.
- [Apps that set `remoteConfig = false` now load `/config`](#remoteconfig) and apply its settings.

## Endpoints

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

A personal API key (`phx_...`) in `projectToken` now disables the SDK at setup, as an empty token does, and logs why when `debug` is on.
In 3.x, the SDK started and PostHog rejected every event with a 401. Use your project token (`phc_...`).

### Hosts without capture v1

If you self-host PostHog, make sure `/i/v1/analytics/events` is served before you upgrade.
PostHog Cloud (`us.i.posthog.com`, `eu.i.posthog.com`) serves it.

If you send events through your own reverse proxy, update it before you upgrade:

- Forward `/i/v1/analytics/events` and `/i/v1/ai/events` to PostHog.
- Pass `Authorization` (the project token, sent as `Bearer <token>`), every `PostHog-*` header, `Content-Type`, `Content-Encoding` and `User-Agent` through unchanged. PostHog answers `401` without `Authorization` and `400` without the others, and the events are dropped. If your proxy strips other credentials, keep `Authorization` values that start with `Bearer phc_`, or the exact `Bearer <token>` if your project token doesn't start with `phc_`.
- Accept request bodies up to 20 MB. AI requests are often larger than 1 MB, the default limit in nginx and ingress-nginx. A `413` from the proxy makes the SDK halve the batch until it fits, and drops an event that doesn't fit on its own.

PostHog's managed reverse proxy already does this. See the [proxy guides](https://posthog.com/docs/advanced/proxy).

The SDK doesn't fall back to `/batch`. A 404 is retried, so the events stay queued until the path is served, and the SDK prints one warning, even with `debug` off. Any other error status that isn't retried, such as 403 or 405, drops the events.

### Redirects

The SDK follows a 307 or 308 redirect only to the origin of `host` (same scheme, host and port), at most 5 times, and resends the same body and headers.
Any other redirect is not followed and the events are dropped.
In 3.x, OkHttp followed redirects to any host.
If a proxy redirects capture requests to another host, point `host` at the final host.

## Event validation

Capture v1 checks each event on its own and drops only the invalid ones, so the rest of the batch is delivered.
The SDK doesn't check these before sending. PostHog drops the event, and the SDK removes it from the queue.
With `debug` on, the SDK logs the name and uuid of each dropped event and the reason PostHog gives, such as `misrouted_event`, never its properties.
Debug logs for capture requests name the event count and the response status, and no longer print the request and response bodies.

- An event with an empty event name is dropped, for example `capture("")`.
- An event with an empty or whitespace-only distinct ID is dropped, for example one `beforeSend` sets to `""`.
- An event whose name is over 200 UTF-8 bytes is dropped. Legacy capture had no limit on event name length.
- An event whose distinct ID is over 200 UTF-8 bytes is dropped. Legacy capture shortened the ID to 200 characters and kept the event.

In 3.x, an empty event name or distinct ID made legacy capture reject the whole batch it was in.

The distinct ID can come from `identify()`, the `distinctId` argument of `capture` or `beforeSend`.
If you identify users with a long ID, such as a JWT or a long email address, shorten or hash it before you upgrade. Otherwise every event for that user is dropped.

## SDK identity

PostHog sets `$lib` and `$lib_version` from the `PostHog-Sdk-Info` request header, for example `posthog-android/4.0.0`.
The SDK removes `$lib` and `$lib_version` from the properties it sends to capture v1.
A value you set in properties, in a super property or in `beforeSend` doesn't reach PostHog.
The SDK removes them when it sends the event, so `beforeSend` still sees them.

## Property precedence

An event's properties are merged in this order, and later values win:

1. properties the SDK sets itself, such as `$os_name`, `$app_version`, `$screen_name`, `$is_identified`, `$session_id` and the session replay debug properties (`$recording_status`, `$sdk_debug_*`)
2. registered super properties (`register`)
3. the properties you pass to `capture` or another event-sending method; `userProperties`, `userPropertiesSetOnce` and `groups` are placed as `$set`, `$set_once` and `$groups` here
4. `beforeSend`

In 3.x, the SDK's own values won over super properties, and `$is_identified`, `$process_person_profile` and the session replay debug properties also won over the properties you pass.
In 4.0 a value you register or pass wins, for example `register("\$os_name", "custom")` sends every event with that value.
A registered `$session_id` now overrides the SDK's session ID, which can detach events from their session replay.
A caller `$screen_name` or `$session_id` still replaces the SDK's value even when it's blank, as in 3.x, so a blank `$session_id` is sent as an empty `session_id`.

## Session and window IDs

Capture v1 sends `$session_id` and `$window_id` as top-level `session_id` and `window_id` fields, not as properties.
The SDK moves them out of `properties` for you.
A string value, including an empty string, is sent unchanged.
Any other value is removed and not sent. With `debug` on, the SDK logs the key and the value's JSON type, never the value.
A null value counts as unset and is removed without a log.
In PostHog, the events still have `$session_id` and `$window_id`, so insights, filters and session replay work as before.

## Event options

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

The legacy option properties still work.
When the event is sent, the SDK removes each one from `properties` and uses it for its option:

| Legacy property | Option |
| --- | --- |
| `$process_person_profile` | `process_person_profile` |
| `$cookieless_mode` | `cookieless_mode` |
| `$ignore_sent_at` | `disable_skew_correction` |
| `$product_tour_id` | `product_tour_id` |

- An option you pass to any of these methods or set in `beforeSend` wins over its legacy property. An option set to `null` counts as unset, so the legacy property fills it.
- To change person processing in `beforeSend`, edit `event.options`. A `$process_person_profile` property the hook adds only fills an option that is still unset, so it doesn't override the `false` the SDK set for an anonymous event.
- When `personProfiles` turns person processing off, the SDK sets the `process_person_profile` option to `false`. Otherwise it sends neither, because PostHog processes person profiles by default. In 3.x, every event carried a `$process_person_profile` property. To change person processing for one event, pass the `process_person_profile` option or the `$process_person_profile` property. A registered `$process_person_profile` changes it for every event.
- A `$set` sent with `process_person_profile: false` doesn't update the person, so it no longer suppresses a later `setPersonProperties` or `identify` call with the same properties as a duplicate.
- The SDK sends option values as given. PostHog reads common forms of a value, such as `"false"` or `0` for a boolean, and drops an event whose option value it can't read.
- Other option keys are sent unchanged, and PostHog ignores keys it doesn't know.
- There are no global or context options. To set an option on every event, register its legacy property, for example `register("\$process_person_profile", false)`.
- An empty or whitespace-only `distinctId` argument to `capture` now uses the current distinct ID, like `null`. In 3.x, the event was dropped.
- The event `beforeSend` returns keeps the uuid of the capture it came from. A uuid you set in `beforeSend`, for example with `copy(uuid = …)`, is replaced, so a hook that returns an event kept from an earlier call still sends one uuid per capture.
- Events still queued on disk by 3.x are sent to capture v1 after the update, with their legacy properties moved to options.

## AI events

4.0 adds `captureAi`, which sends AI events, such as `$ai_generation`, to PostHog's AI endpoint (`/i/v1/ai/events`) instead of the analytics endpoint.
It takes the same arguments as `capture`, and the events go through the same `beforeSend` hooks.

```kotlin
PostHog.captureAi(
    "\$ai_generation",
    properties = mapOf("\$ai_model" to "gpt-5", "\$ai_trace_id" to traceId),
)
```

- `captureAi` returns the event's uuid, or `null` when the event isn't queued: the SDK is disabled or opted out, `beforeSend` dropped it, or it's over the size limit. Once 100 AI events (`captureAiMaxQueueSize`) or 50 MiB are queued, including events still waiting to be written to disk during a slow upload, the oldest are dropped.
- An AI event's properties can be up to 8 MiB. The SDK drops a serialized event over 8 MiB + 64 KiB, measured before `encryption`. With `debug` on, it logs the event name and the limit, never the content. Set `captureAiMaxEventBytes` to lower that limit; a larger value uses 8 MiB + 64 KiB, and with `debug` on the SDK logs that it did.
- PostHog drops an event sent with `captureAi` whose name doesn't start with `$ai_`. `capture` never sends events to the AI endpoint, even ones named `$ai_*`.
- AI requests time out after 60 seconds without network activity, or `captureAiTimeout`. Other requests keep OkHttp's 10-second defaults.
- AI events are queued on disk separately from other events (`posthog-disk-ai-queue` in the app's cache directory). Up to 100 events (`captureAiMaxQueueSize`) and 50 MiB are kept, and the oldest are dropped first. `maxQueueSize` doesn't apply.
- `flush()` and `close()` also send the AI queue, and AI events left on disk are sent at the next launch.
- AI requests are gzipped unless you set `captureAiCompression` to `PostHogCompression.NONE`. `compression` doesn't apply.
- AI events use `flushAt`, `maxBatchSize` and `flushIntervalSeconds`, like other events. A request carries up to about 5 MiB, as for analytics events (see [Retries](#retries)).
- The SDK sends AI events unchanged. It doesn't redact or truncate prompts, outputs or media, so use `beforeSend` to remove anything you don't want to send.

## Retries

- The SDK retries network errors and 404, 408, 429, 500, 502, 503 and 504 responses. Other statuses drop the events, including 3xx, and a 402 (billing limit) drops the whole batch. 3.x also retried 3xx responses, and didn't retry 404.
- When the response asks to retry only some events, the SDK keeps those events queued and removes the rest.
- A failed request is resent with the same events, request ID and `created_at`, and the next attempt number, before newer events are sent. The request ID and attempt number reset after an app restart. OkHttp doesn't retry capture requests on its own.
- A `Retry-After` header pauses sending for at most 30 seconds. This also applies to session replay and logs. In 3.x there was no cap.
- After a failed send, the wait before the next one doubles from 1 second up to 30 seconds, with up to 25% random jitter at millisecond precision. In 3.x it doubled without jitter and a `Retry-After` replaced it. This applies to events, session replay and logs.
- Event, session replay and log retries have no limit, as in 3.x: queued records stay until they're delivered or a full queue evicts them (`maxQueueSize`, or `logs.maxBufferSize` for logs).
- `maxRetries` is renamed to `pushNotificationSubscriptionMaxRetries`, with no deprecated alias. It only limits push-notification subscription retries.
- A request carries at most `maxBatchSize` events and about 5 MiB, and the SDK sends as soon as about 5 MiB of events is queued, even before `flushAt` is reached. In 3.x, batches were limited by event count only.
- A 413 still splits the batch into smaller requests.

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

`posthog-server` isn't having a major release, and its API is unchanged: it keeps its own `apiKey`, `getFeatureFlagPayload` and `remoteConfig`, and `com.posthog.PostHogOnFeatureFlags` now ships in `posthog-server` itself. None of the API changes above apply to it.

A personal API key (`phx_...`) in `projectToken` now disables the client at setup, as an empty token does.

Its next release depends on core 7.0, so update it if your project also uses core 7.0 or `posthog-android` 4.0. Older `posthog-server` versions aren't compatible with core 7.0 at runtime.
<<<<<<< HEAD
=======

## Checklist

- [ ] Update `posthog-android` to 4.0 and declare it in your build if you only depend on `posthog-android-surveys-compose`, and update `posthog-server` if you use it.
- [ ] Replace `apiKey` with `projectToken`.
- [ ] Replace `propertiesSanitizer` with `addBeforeSend`.
- [ ] Replace `evaluationEnvironments` with `evaluationContexts`.
- [ ] Replace `getFeatureFlagPayload()` with `getFeatureFlagResult(key, sendFeatureFlagEvent = false)?.payload`.
- [ ] Replace `debouncerDelayMs` with `throttleDelayMs`.
- [ ] Remove `remoteConfig`. If it was `false`, expect `/config` requests and its settings to apply.
- [ ] Replace `PostHogOnFeatureFlags` with `PostHogFeatureFlagsCallback`, and check existing callbacks for `errorsLoading`.
- [ ] Update any class that implements `PostHogInterface`.
- [ ] Make sure every feature flag payload you read is valid JSON.
>>>>>>> origin/chore/v4-remove-deprecated
