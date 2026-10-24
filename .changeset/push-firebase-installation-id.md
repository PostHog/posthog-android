---
"posthog": minor
"posthog-android": minor
---

Support Firebase Installation ID (FID) push registration. When the app enables `firebase_messaging_installation_id_enabled`, the SDK registers the FID instead of calling the deprecated `getToken()`. A new `registerPushNotificationToken(deviceToken, appId, tokenType)` overload takes `PostHogPushTokenType.FID` for installation IDs forwarded from `onRegistered`.
