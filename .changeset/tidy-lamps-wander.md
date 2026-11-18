---
"posthog-server": minor
"posthog": minor
---

Add `getRemoteConfigPayload(key)` to the server SDK. It returns the parsed payload of a remote config feature flag, with no distinct id. It needs `personalApiKey`, and returns `null` and logs a warning on a failure.
