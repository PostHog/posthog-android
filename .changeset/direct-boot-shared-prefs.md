---
"posthog-android": patch
---

Fix a crash when `PostHogAndroid.setup()` runs in Direct Boot mode (after a reboot, before the user first unlocks the device): accessing SharedPreferences in credential encrypted storage threw `IllegalStateException` and took the host app down. The SDK now resolves SharedPreferences lazily, buffers writes in memory while storage is locked, and flushes them on the first access after unlock.
