---
"posthog-android": minor
---

Add `PostHogReplayIntegration.captureSessionReplaySnapshot(...)`, an internal opt-in session-replay hook (`@PostHogInternalReplayApi`) that lets first-party PostHog wrapper SDKs (e.g. posthog-flutter) capture the current native window on their own cadence — used to record native screens that cover an out-of-engine UI. Not a public API: it requires an explicit opt-in and carries no stability guarantees.
