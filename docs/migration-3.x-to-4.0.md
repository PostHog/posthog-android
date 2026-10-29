# Migrating from PostHog Android 3.x to 4.0

4.0 sends analytics events to capture v1 (`/i/v1/analytics/events`) instead of `/batch`.
Most apps upgrade without code changes.
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
- Pass `Authorization` (the project token, sent as `Bearer <token>`), every `PostHog-*` header, `Content-Type`, `Content-Encoding` and `User-Agent` through unchanged. PostHog answers `401` without `Authorization` and `400` without the others, and the events are dropped.
- Accept request bodies up to 20 MB. AI requests are often larger than 1 MB, the default limit in nginx and ingress-nginx. A `413` from the proxy makes the SDK halve the batch until it fits, and drops an event that doesn't fit on its own.
- Pass PostHog's response body through unchanged. A 2xx response whose body isn't PostHog's `{"results": {...}}` drops the events instead of retrying them.

PostHog's managed reverse proxy already does this. See the [proxy guides](https://posthog.com/docs/advanced/proxy).

The SDK doesn't fall back to `/batch`. A 404 is retried, so the events stay queued until the path is served. Any other error status that isn't retried, such as 403 or 405, drops the events.

### Redirects

The SDK follows a 307 or 308 redirect only to the origin of `host` (same scheme, host and port), at most 5 times, and resends the same body and headers.
Any other redirect is not followed and the events are dropped.
In 3.x, OkHttp followed redirects to any host.
If a proxy redirects capture requests to another host, point `host` at the final host.

## Event validation

Capture v1 checks each event on its own and drops only the invalid ones, so the rest of the batch is delivered.

The SDK drops an event whose name is empty before sending it, for example `capture("")` or one `beforeSend` renames to `""`.
With `debug` on, it logs this once.

The SDK doesn't check the rest before sending. PostHog drops the event, and the SDK removes it from the queue.
With `debug` on, the SDK logs one line per batch with how many events PostHog dropped, how many duplicate copies it dropped and how many it asked to retry, never the event names or properties.

- An event with an empty or whitespace-only distinct ID is dropped, for example one `beforeSend` sets to `""`.
- An event whose name is over 200 UTF-8 bytes is dropped. Legacy capture had no limit on event name length.
- An event whose distinct ID is over 200 UTF-8 bytes is dropped. Legacy capture shortened the ID to 200 characters and kept the event.

In 3.x, an empty event name or distinct ID made legacy capture reject the whole batch it was in.

A `NaN` or infinite number in the properties is dropped from the event, and the event is sent without it.

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
3. the properties you pass to `capture` or another event-sending method; `userProperties`, `userPropertiesSetOnce` and `groups` are placed as `$set`, `$set_once` and `$groups` here, and when you also pass that key in properties they win key by key; a `$groups` map in properties adds to the groups set with `group()` instead of replacing them
4. `beforeSend`

In 3.x, the SDK's own values won over super properties, and `$is_identified`, `$process_person_profile` and the session replay debug properties also won over the properties you pass.
In 4.0 a value you register or pass wins, for example `register("\$os_name", "custom")` sends every event with that value.
A registered `$session_id` now overrides the SDK's session ID, which can detach events from their session replay.
A caller `$screen_name` or `$session_id` still replaces the SDK's value even when it's blank, as in 3.x, so a blank `$session_id` is sent as an empty `session_id`.

## Session and window IDs

Capture v1 sends `$session_id` and `$window_id` as top-level `session_id` and `window_id` fields, not as properties.
The SDK moves them out of `properties` for you.
A string value, including an empty string, is sent unchanged.
Any other value is removed and not sent, and the SDK logs a warning that names the key and the value's JSON type, never the value.
A null value counts as unset and is removed without a warning.
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

- `captureAi` returns the event's uuid, or `null` when the event isn't queued: the SDK is disabled or opted out, `beforeSend` dropped it, or it's over the size limit.
- An AI event's properties can be up to 8 MiB. The SDK drops a serialized event over 8 MiB + 64 KiB, measured before `encryption`, and logs the event name and size, never its content. Set `captureAiMaxEventBytes` to lower that limit; a larger value uses 8 MiB + 64 KiB and logs a warning.
- PostHog drops an event sent with `captureAi` whose name doesn't start with `$ai_`. `capture` never sends events to the AI endpoint, even ones named `$ai_*`.
- AI requests time out after 60 seconds without network activity, or `captureAiTimeout`. Other requests keep OkHttp's 10-second defaults.
- AI events are queued on disk separately from other events (`posthog-disk-ai-queue` in the app's cache directory). Up to 100 events (`captureAiMaxQueueSize`) and 50 MiB are kept, and the oldest are dropped first. `maxQueueSize` doesn't apply.
- AI requests are gzipped unless you set `captureAiCompression` to `PostHogCompression.NONE`. `compression` doesn't apply.
- AI events use `flushAt`, `maxBatchSize` and `flushIntervalSeconds`, like other events. A request carries up to about 5 MiB, as for analytics events (see [Retries](#retries)).
- The SDK sends AI events unchanged. It doesn't redact or truncate prompts, outputs or media, so use `beforeSend` to remove anything you don't want to send.

## Retries

- The SDK retries network errors and 404, 408, 429, 500, 502, 503 and 504 responses. Other statuses drop the events, including 3xx. 3.x also retried 3xx responses, and didn't retry 404.
- When the response asks to retry only some events, the SDK keeps those events queued and removes the rest.
- A failed request is resent with the same events, request ID and `created_at`, and the next attempt number, before newer events are sent. OkHttp doesn't retry capture requests on its own.
- A `Retry-After` header pauses sending for at most 30 seconds. This also applies to session replay and logs. In 3.x there was no cap.
- After a failed send, the wait before the next one doubles from 1 second up to 30 seconds, with up to 25% random jitter at millisecond precision. In 3.x it doubled without jitter and a `Retry-After` replaced it. This applies to events, session replay and logs.
- Event, session replay and log retries have no limit, as in 3.x: queued records stay until they're delivered or a full queue evicts them (`maxQueueSize`, or `logs.maxBufferSize` for logs).
- `maxRetries` is renamed to `pushNotificationSubscriptionMaxRetries`, with no deprecated alias. It only limits push-notification subscription retries.
- A request carries at most `maxBatchSize` events and about 5 MiB, and the SDK sends as soon as about 5 MiB of events is queued, even before `flushAt` is reached. In 3.x, batches were limited by event count only.
- A 413 still splits the batch into smaller requests.
