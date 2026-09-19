---
'posthog': patch
'posthog-android': patch
---

Keep session replay off after `stopSessionReplay()`: an event trigger, a session rotation, a remote config delivery or a feature flag reload no longer restarts recording the app turned off. Only `startSessionReplay()` records again.
