---
'posthog': minor
'posthog-android': minor
---

Session replay now respects the freshly-fetched session-recording remote config at cold start. Previously a returning user whose cached flag said "on" could record an opening window even when the server's config said "off". Snapshots captured before the first remote config response are buffered; once the config resolves they are migrated to the send pipeline if the session is recordable (kept buffering until the minimum-duration window is met), or dropped if the flag is off or the session is sampled out. A fresh "off" now also stops an in-progress recording instead of waiting for the next session rotation, and a mid-session resume re-emits a full-snapshot keyframe. When the first remote config can't be fetched (e.g. the device is offline), recording falls back to the disk-cached flag rather than discarding the session.

API: `PostHogIntegration.onRemoteConfig()` now takes a `loaded: Boolean = true` parameter (true when a live remote config was applied, false on a terminal fetch failure), replacing the separate `onRemoteConfigFailed()` callback. Integrations that implement `PostHogIntegration` and override these methods should migrate to the single `onRemoteConfig(loaded)`.
