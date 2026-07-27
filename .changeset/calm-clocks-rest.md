---
"posthog-android": patch
---

Avoid main-thread IPC candidates in screen autocapture and device type detection. Screen autocapture now uses the activity's current title, which may produce a different `$screen_name` for `$screen` events when apps set titles dynamically.
