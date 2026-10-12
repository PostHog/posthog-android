---
"posthog": patch
"posthog-android": patch
---

Stop session replay, autocapture and other integrations while opted out, and restart them on `optIn()` (replay starts a new session, and only if it was recording at opt-out).
