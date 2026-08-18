---
"posthog": patch
"posthog-android": patch
---

Clarify that event timestamps are serialized in UTC, and make session replay log timestamp handling more robust by parsing timezone-independent logcat epoch timestamps instead of local wall-clock timestamps.
