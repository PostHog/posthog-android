---
'posthog': minor
'posthog-android': minor
---

Change `capturePushNotificationOpened` to skip a repeat open of a PostHog-sent notification (same `invocation_id` and `action_id`) captured in the last 5 minutes, so an automatic capture plus a manual call for one tap counts once, while a resend of that notification (a new `google.message_id`) still counts.
