---
"posthog": minor
"posthog-android": minor
---

Auto-attach `$screen_name` to every captured event after `PostHog.screen()` has been called, or whenever screen auto-capture is on (default). Cached value is cleared by `reset()` and `close()`. To opt out, set `PostHogAndroidConfig.captureScreenViews = false` and avoid calling `PostHog.screen()` manually. Behavior change: existing events from `captureException`, `captureFeatureView`, `identify`, etc. will start carrying `$screen_name` for users who hadn't disabled screen capture. Closes #119.
