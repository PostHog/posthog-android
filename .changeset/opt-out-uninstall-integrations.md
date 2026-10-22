---
"posthog": patch
"posthog-android": patch
---

Stop session replay, autocapture and other integrations while opted out, and restart them on `optIn()`. Replay restarts in a new session if the app wanted it on at opt-out: `sessionReplay` is enabled or the app called `startSessionReplay()`, and the app hasn't called `stopSessionReplay()` since.
