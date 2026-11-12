---
"posthog": patch
"posthog-android": patch
"posthog-android-surveys-compose": patch
"posthog-server": patch
"posthog-server-openfeature": patch
---

Treat failed detailed feature flag records as unavailable values and payloads, independently of response-wide errors. Preserve successful cached values on errored client refreshes and same-context local decisions during server fallback. Report failed direct reads as evaluation failures without tracking caller defaults as evaluated responses.
