---
'posthog': patch
'posthog-android': patch
---

Enforce 24-hour maximum session duration and 30-minute inactivity rotation with automatic session rotation, mirroring iOS. Note: `PostHogSessionManager.isAppInBackground` now defaults to `true` until the first lifecycle `onStart` flips it; downstream wrappers (Flutter, RN) that exercise the manager directly in tests may need to call `setAppInBackground(false)` to simulate a foregrounded process.
