---
"posthog": minor
"posthog-android": minor
---

Support Firebase Installation ID (FID) push registration. When the app enables `firebase_messaging_installation_id_enabled`, the SDK registers the FID instead of calling the deprecated `getToken()`. Forward `onRegistered` to `registerPushNotificationToken(installationId, appId)` to refresh the subscription even when the FID is unchanged. Automatic startup registration still skips previously delivered subscriptions, and pending registrations retain their retry backoff.
