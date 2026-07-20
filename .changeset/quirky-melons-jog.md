---
"posthog-android": minor
"posthog": minor
---

Add push notification support for PostHog Workflows. Register device push tokens with `registerPushNotificationToken(...)` (auto-registered from Firebase Cloud Messaging when `firebase-messaging` is on the classpath and `capturePushNotificationSubscriptions` is enabled), and capture opens with `capturePushNotificationOpened(...)` (cold-start tray taps are auto-detected when `capturePushNotificationOpened` is enabled).
