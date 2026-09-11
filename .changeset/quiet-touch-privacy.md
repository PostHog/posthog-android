---
"posthog-android": minor
---

Add `PostHogSessionReplayConfig.captureTouches` (default `true`) to disable touch coordinate recording independently of screenshots and view capture. The setting can be changed at runtime, and queued touch capture is skipped while disabled.
