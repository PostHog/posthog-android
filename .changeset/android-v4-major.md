---
'posthog': major
'posthog-android': major
---

- **Breaking:** remove the deprecated `apiKey` property and the `PostHogConfig(apiKey = …)` and `PostHogAndroidConfig(apiKey = …)` factories; use `projectToken` instead
- **Breaking:** remove the deprecated `propertiesSanitizer` config option and the `PostHogPropertiesSanitizer` interface; use `addBeforeSend` instead
- **Breaking:** remove the deprecated `evaluationEnvironments` config option; use `evaluationContexts` instead
- **Breaking:** remove the deprecated `getFeatureFlagPayload()`; use `getFeatureFlagResult(key)?.payload` instead
- **Breaking:** remove the deprecated `PostHogSessionReplayConfig.debouncerDelayMs`; use `throttleDelayMs` instead
- **Breaking:** send events to capture v1 (`/i/v1/analytics/events`) with the project token in an `Authorization: Bearer` header; if you use a reverse proxy or self-host PostHog, make sure the path is served, because a 404 or other non-retried response drops the events, and authenticate your proxy with a header other than `Authorization`, which capture requests replace
- **Breaking:** stop retrying 429 and 3xx responses for events, and cap a `Retry-After` pause at 30 seconds for events, session replay and logs
- **Breaking:** add up to 25% random jitter to the wait after a failed send, which still doubles from 1 second to 30 seconds, and treat a `Retry-After` as a minimum wait instead of replacing the backoff, for events, session replay and logs; queued records are still kept until they're delivered
- **Breaking:** follow only a 307 or 308 redirect of a capture request to the same origin as `host`, at most 5 times; any other redirect drops the events, so point `host` at the final host
- **Breaking:** send the `$process_person_profile`, `$cookieless_mode`, `$ignore_sent_at` and `$product_tour_id` properties as capture options (`PostHogEvent.options`, a new trailing constructor parameter that Java callers of the full constructor and `copy` must pass), where PostHog drops an event whose option value it can't read
- **Breaking:** rename `PostHogConfig.maxRetries` to `pushNotificationSubscriptionMaxRetries`, because it only limits push-notification subscription retries
- **Breaking:** set `$lib` and `$lib_version` from the `PostHog-Sdk-Info` request header; a value set in properties, super properties or `beforeSend` is removed before sending
- **Breaking:** send `$session_id` and `$window_id` as top-level `session_id` and `window_id` fields; a non-string value is dropped with a warning
- **Breaking:** disable the SDK at setup when `projectToken` is a personal API key (`phx_...`), as for an empty token
