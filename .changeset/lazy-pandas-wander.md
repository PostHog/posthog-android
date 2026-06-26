---
'posthog': patch
'posthog-android': patch
---

Session replay now respects the freshly-fetched session-recording remote config at cold start. Previously a returning user whose cached flag said "on" could record an opening window even when the server's config said "off". Snapshots captured before the first remote config response are buffered and, once the config resolves, flushed if recording is enabled or dropped if it isn't. A fresh "off" now also stops an in-progress recording instead of waiting for the next session rotation.
