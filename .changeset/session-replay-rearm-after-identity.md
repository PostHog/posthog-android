---
"posthog": patch
"posthog-android": patch
---

Keep session replay, error tracking, and network performance capture active after an in-session `identify()`/`reset()` instead of disabling them until the next app restart.
