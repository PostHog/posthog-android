---
'posthog': patch
'posthog-android': patch
---

Session replay now respects the freshly-fetched session-recording remote config at cold start. Previously a returning user whose cached flag said "on" could record an opening window even when the server's config said "off". Snapshots captured before the first remote config response are buffered; once the config resolves they are migrated to the send pipeline if the session is recordable (kept buffering until the minimum-duration window is met), or dropped if the flag is off or the session is sampled out. A fresh "off" now also stops an in-progress recording instead of waiting for the next session rotation. When the first remote config can't be fetched (e.g. the device is offline), recording falls back to the disk-cached flag rather than discarding the session.
