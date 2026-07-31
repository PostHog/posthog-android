---
"posthog-android": minor
"posthog": minor
---

Add push notification support for PostHog Workflows. Register device push tokens with `registerPushNotificationToken(...)` (auto-registered from Firebase Cloud Messaging when `firebase-messaging` is on the classpath), and capture opens with `capturePushNotificationOpened(...)` (cold-start tray taps are auto-detected). Both are on by default and can be turned off with the new `capturePushNotificationSubscriptions` and `capturePushNotificationOpened` config flags. On `reset()` the token is unregistered for the logged-out user and re-registered under the new anonymous id, and `unregisterPushNotificationToken()` lets you unregister manually.
