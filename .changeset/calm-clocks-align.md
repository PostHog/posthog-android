---
"posthog-android": patch
---

Make session replay log timestamp handling more robust by parsing timezone-independent logcat epoch timestamps instead of local wall-clock timestamps.
