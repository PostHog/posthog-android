---
"posthog-server": minor
"posthog": minor
---

Add `getRemoteConfigPayload(key)` to the server SDK. It returns the parsed payload of a remote config feature flag, without a distinct id. It needs `personalApiKey`. It returns `null`, and logs a warning, when the client is disabled, no personal API key is set, the flag key is invalid, the request fails, or the response body is not valid JSON. The core `PostHogApi` gets the matching `flagRemoteConfigPayload` request method.
