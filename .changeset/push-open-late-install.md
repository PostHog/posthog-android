---
'posthog': minor
'posthog-android': minor
'posthog-server': patch
'posthog-android-surveys-compose': patch
---

Add `PostHogAndroid.capturePushNotificationOpened(intent)` to capture `$push_notification_opened` for a launch intent the SDK was installed too late to read. In the published test fixtures, `PostHogFake.optOut()` and `optIn()` now change what `isOptOut()` returns, where they were previously no-ops.
