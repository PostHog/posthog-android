---
"posthog": patch
"posthog-android": patch
---

Stop push notifications for a previous user reaching the device after an identity switch PostHog cannot merge, such as `identify()` with `reuseAnonymousId` enabled. The SDK now removes the device's push subscription from the previous person before registering it for the new one, and retries the removal if the device is offline. A normal anonymous-to-identified `identify()` keeps the subscription, since both ids end up on the same person.
