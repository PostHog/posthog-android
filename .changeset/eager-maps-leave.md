---
"posthog-android": patch
---

Fix `identify()` leaving a user anonymous when the supplied ID already matches the persisted distinct ID (for example after a non-identified bootstrap seeded the same ID). The SDK now marks the user identified and captures a person-processed `$set` event.
