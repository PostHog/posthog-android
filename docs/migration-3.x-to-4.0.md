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
- relies on 429 responses being retried, or on a `Retry-After` longer than 30 seconds
- sets `maxRetries` on `PostHogConfig` or `PostHogAndroidConfig`

## Endpoints

| Data | 3.x | 4.0 |
| --- | --- | --- |
| Analytics events | `/batch` | `/i/v1/analytics/events` |
| Session replay | `/s/` | `/s/`, unchanged |
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

- Forward `/i/v1/analytics/events` to PostHog.
- Pass `Authorization` (the project token, sent as `Bearer <token>`), every `PostHog-*` header, `Content-Type`, `Content-Encoding` and `User-Agent` through unchanged. PostHog answers `401` without `Authorization` and `400` without the others, and the events are dropped.
- Accept request bodies up to 20 MB. nginx and ingress-nginx default to 1 MB. A `413` from the proxy makes the SDK halve the batch until it fits, and drops an event that doesn't fit on its own.

PostHog's managed reverse proxy already does this. See the [proxy guides](https://posthog.com/docs/advanced/proxy).

The SDK doesn't fall back to `/batch`. A 404, or any other error status that isn't retried, such as 403 or 405, drops the events.

### Redirects

The SDK follows a 307 or 308 redirect only to the origin of `host` (same scheme, host and port), at most 5 times, and resends the same body and headers.
Any other redirect is not followed and the events are dropped.
In 3.x, OkHttp followed redirects to any host.
If a proxy redirects capture requests to another host, point `host` at the final host.

## Event validation

Capture v1 checks each event on its own and drops only the invalid ones, so the rest of the batch is delivered.
The SDK doesn't check these before sending. PostHog drops the event, and the SDK removes it from the queue.
With `debug` on, the SDK logs how many events were dropped.

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

## Session and window IDs

Capture v1 sends `$session_id` and `$window_id` as top-level `session_id` and `window_id` fields, not as properties.
The SDK moves them out of `properties` for you.
A string value, including an empty string, is sent unchanged.
Any other value is removed and not sent, and the SDK logs a warning that names the key and the value's JSON type, never the value.
A null value counts as unset and is removed without a warning.
In PostHog, the events still have `$session_id` and `$window_id`, so insights, filters and session replay work as before.

## Event options

Capture v1 reads processing settings from an `options` object sent next to `properties`, which `beforeSend` can read and change through `PostHogEvent.options`.
The legacy option properties still work.
When the event is sent, the SDK removes each one from `properties` and uses it for its option, unless that option is already set:

| Legacy property | Option |
| --- | --- |
| `$process_person_profile` | `process_person_profile` |
| `$cookieless_mode` | `cookieless_mode` |
| `$ignore_sent_at` | `disable_skew_correction` |
| `$product_tour_id` | `product_tour_id` |

- The SDK sends option values as given. PostHog reads common forms of a value, such as `"false"` or `0` for a boolean, and drops an event whose option value it can't read.
- Other option keys are sent unchanged, and PostHog ignores keys it doesn't know.
- Events still queued on disk by 3.x are sent to capture v1 after the update, with their legacy properties moved to options.

## Retries

- The SDK retries network errors and 408, 500, 502, 503 and 504 responses. Other statuses drop the events, including 429. 3.x also retried 429 and 3xx responses. Capture itself doesn't return 429, but a proxy in front of it can.
- When the response asks to retry only some events, the SDK keeps those events queued and removes the rest.
- A failed request is resent with the same events, request ID and `created_at`, and the next attempt number, before newer events are sent. OkHttp doesn't retry capture requests on its own.
- A `Retry-After` header pauses sending for at most 30 seconds. This also applies to session replay and logs. In 3.x there was no cap.
- After a failed send, the wait before the next one doubles from 1 second up to 30 seconds, with up to 25% random jitter, rounded to whole seconds. In 3.x it doubled without jitter and a `Retry-After` replaced it. This applies to events, session replay and logs.
- Event, session replay and log retries have no limit, as in 3.x: queued records stay until they're delivered or a full queue evicts them (`maxQueueSize`, or `logs.maxBufferSize` for logs).
- `maxRetries` is renamed to `pushNotificationSubscriptionMaxRetries`, with no deprecated alias. It only limits push-notification subscription retries.
- A 413 still splits the batch into smaller requests.
