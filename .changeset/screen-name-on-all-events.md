---
"posthog": minor
"posthog-android": minor
---

Auto-attach `$screen_name` to every captured event after `PostHog.screen()` has been called (manually or via Activity-lifecycle auto-capture). Cached value is cleared by `reset()` and `close()`.

**To opt out of `$screen_name` stamping entirely**, set `PostHogAndroidConfig.captureScreenViews = false` **and** stop calling `PostHog.screen()` manually. Disabling `captureScreenViews` alone is not sufficient — a single manual `PostHog.screen("Home")` call will re-enable stamping.