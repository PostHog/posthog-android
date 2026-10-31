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
- **Breaking:** return `null` instead of the raw string for a malformed, empty, whitespace-only or lenient-only (unquoted or single-quoted) feature flag payload from `getFeatureFlagResult(key)?.payload`; payloads must be valid JSON (a JSON `null` payload now also reads as `null` instead of the string `"null"`)
- **Breaking:** make SDK-internal helpers in the `com.posthog.internal`, `com.posthog.android.internal` and `com.posthog.android.replay.internal` packages non-public
- **Breaking:** remove the deprecated `remoteConfig` config option; the SDK now always loads remote config at setup
- **Breaking:** replace `PostHogOnFeatureFlags` with `PostHogFeatureFlagsCallback` in `PostHogConfig.onFeatureFlags` and `reloadFeatureFlags()`; it receives a `PostHogFeatureFlagsLoaded` with the enabled flags, their values, and `errorsLoading`
- **Breaking:** add the abstract `onFeatureFlags()` method to `PostHogInterface`; classes that implement it must add the method
- **Breaking:** send events to capture v1 (`/i/v1/analytics/events`) with the project token in an `Authorization: Bearer` header; if you use a reverse proxy or self-host PostHog, make sure the path is served, because events stay queued and are retried on a 404, with a warning printed once even when `debug` is off, and authenticate your proxy with a header other than `Authorization`, which capture requests replace
- **Breaking:** stop retrying 3xx responses for events, retry 404 responses, and cap a `Retry-After` pause at 30 seconds for events, session replay and logs
- **Breaking:** add up to 25% random jitter to the wait after a failed send, which still doubles from 1 second to 30 seconds, and treat a `Retry-After` as a minimum wait instead of replacing the backoff, for events, session replay and logs; queued records are still kept until they're delivered
- **Breaking:** follow only a 307 or 308 redirect of a capture request to the same origin as `host`, at most 5 times; any other redirect drops the events, so point `host` at the final host
- **Breaking:** send the `$process_person_profile`, `$cookieless_mode`, `$ignore_sent_at` and `$product_tour_id` properties as capture options (`PostHogEvent.options`, a new trailing constructor parameter that Java callers of the full constructor and `copy` must pass), where PostHog drops an event whose option value it can't read
- **Breaking:** rename `PostHogConfig.maxRetries` to `pushNotificationSubscriptionMaxRetries`, because it only limits push-notification subscription retries
- **Breaking:** set `$lib` and `$lib_version` from the `PostHog-Sdk-Info` request header; a value set in properties, super properties or `beforeSend` is removed before sending
- **Breaking:** send `$session_id` and `$window_id` as top-level `session_id` and `window_id` fields; a non-string value is dropped, with a log when `debug` is on
- **Breaking:** disable the SDK at setup when `projectToken` is a personal API key (`phx_...`), as for an empty token
- **Breaking:** add an `options` argument to `capture`, `screen`, `identify`, `setPersonProperties`, `alias`, `group` and `captureException` for per-event capture options such as `cookieless_mode`, sent apart from the properties and editable in `beforeSend` through `PostHogEvent.options`; Java callers pass the trailing argument or `null`, and classes that implement `PostHogInterface` add the parameter
- **Breaking:** move `identify(distinctId, userProperties, userPropertiesSetOnce)` from `PostHogCoreInterface` to `PostHogStatelessInterface`; `PostHogInterface` declares `identify` with the trailing `options` parameter
- **Breaking:** registered super properties now override the properties the SDK sets itself, such as `$os_name`; super and event properties also override `$is_identified`, `$process_person_profile` and the session replay debug properties, which the SDK no longer re-applies after the caller's values
- **Breaking:** the SDK sets the `process_person_profile` option to `false` only when person processing is off, and no longer adds a `$process_person_profile` property to every event
- **Breaking:** keep the uuid of its own capture on the event `beforeSend` returns, so a uuid set in `beforeSend`, for example with `copy(uuid = …)`, is replaced
- **Breaking:** add `captureAi` to send AI events such as `$ai_generation` to PostHog's AI endpoint (`/i/v1/ai/events`), which accepts events whose properties are up to 8 MiB, on its own disk queue; it returns the event uuid, or `null` when the event isn't queued; past `captureAiMaxQueueSize` AI events or 50 MiB, including events still waiting to be written to disk, the oldest are dropped, and classes that implement `PostHogInterface` add the method
- use the current distinct ID when the `distinctId` argument to `capture` is empty or whitespace-only, instead of dropping the event
- **Breaking:** skip deep link query parameters whose name starts with `$` in the `Deep Link Opened` event, so a link can't set a PostHog property such as `$process_person_profile`
- stop storing or matching the person-properties deduplication hash for a `$set` sent with `process_person_profile: false`
- debug logs for capture requests name the event count and the response status instead of printing the request and response bodies, and name each event PostHog drops with the reason it gives
- add `captureAiCompression`, `captureAiMaxQueueSize`, `captureAiTimeout` and `captureAiMaxEventBytes` to `PostHogConfig` to tune AI requests separately from other events; the defaults are gzip, 100 queued events (and at most 50 MiB), 60 seconds and 8 MiB + 64 KiB per serialized event
- cap a request at about 5 MiB of events, for analytics and AI events, and send as soon as that much is queued; an event larger than that is sent on its own
- Add `onFeatureFlags()` to get notified after every feature flags load (on the main thread on Android), returning a `PostHogFeatureFlagsSubscription` you can `unsubscribe()`
- add internal APIs so wrapper SDKs can run their own `beforeSend` on the fully built event before it's sent
- See the [3.x to 4.0 migration guide](https://github.com/PostHog/posthog-android/blob/main/docs/migration-3.x-to-4.0.md) for upgrade steps
