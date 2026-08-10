---
"posthog": patch
"posthog-android": patch
---

Fix: opting back in now re-arms push notifications without an app restart. After a logout unregister clears the device token, `optIn()` refetches the FCM token and re-registers the device (when `capturePushNotificationSubscriptions` is enabled) instead of only restoring consent (#675).
