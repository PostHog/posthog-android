---
"posthog-android": patch
---

Fix session replay started with `sessionReplay = false` staying stopped for the rest of the process after an internal stop, such as the session being cleared while the app is backgrounded.
