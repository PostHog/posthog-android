---
'posthog': minor
'posthog-android': minor
'posthog-server': patch
'posthog-android-surveys-compose': patch
---

Add `PostHogAndroid.capturePushNotificationOpened(intent)` to capture `$push_notification_opened` for a launch intent the SDK was installed too late to read.
