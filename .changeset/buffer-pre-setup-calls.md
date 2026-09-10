---
'posthog': minor
'posthog-android': minor
---

Buffer `capture`, `screen`, `identify`, `register`, `reset` and `unregister` calls made before `setup()` and replay them once the SDK is enabled, instead of dropping them. Move the PackageManager lookup and the release identifier asset read off the thread that calls `PostHogAndroid.setup()`, so setting the SDK up from a background thread is now safe.
