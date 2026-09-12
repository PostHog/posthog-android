---
'posthog': minor
'posthog-android': minor
---

Change `capturePushNotificationOpened` to skip a PostHog-sent notification open already captured in the last 5 minutes (same `invocation_id` and `action_id`), unless the payload's `google.message_id` differs.
