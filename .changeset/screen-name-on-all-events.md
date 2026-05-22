---
"posthog": minor
"posthog-android": minor
---

Auto-attach `$screen_name` to every captured event after `PostHog.screen()` has been called (manually or via Activity-lifecycle auto-capture). Cached value is cleared by `reset()` and `close()`. Closes #119.

**To opt out of `$screen_name` stamping entirely**, set `PostHogAndroidConfig.captureScreenViews = false` **and** stop calling `PostHog.screen()` manually. Disabling `captureScreenViews` alone is not sufficient — a single manual `PostHog.screen("Home")` call will re-enable stamping.

## Behavior changes

These all affect what your events carry on the wire. Review your dashboards/insights/HogQL queries:

- **Cross-event stamping.** `$exception`, `$identify`, `$autocapture`, `$create_alias`, `$groupidentify`, `$feature_flag_called`, custom events, etc. will start carrying `$screen_name` whenever a screen has been recorded in the session. Previously only `$screen` events carried it. `$snapshot` events are excluded.
- **Empty/whitespace `$screen_name` in `properties` falls back to the cache.** Passing `properties = mapOf("$screen_name" to "")` (or whitespace-only) on a `capture(...)` call no longer ships an empty `$screen_name` — the cached value wins. A meaningful caller-supplied value still wins.
- **No change for `screen()` override semantics.** `PostHog.screen("Home", properties = mapOf("$screen_name" to "Override"))` continues to ship `$screen_name = "Override"` on the `$screen` event itself (Kotlin's `putAll` already let the caller override).

## What did NOT change

- The `$screen` event payload is unchanged on Android — Activity class names are still recorded verbatim. (iOS has SwiftUI wrapper sanitization for the same release; Android has no equivalent need.)
- `PostHog.screen("")` and `PostHog.screen("AnyView")` continue to emit `$screen` events with whatever string was passed (the activity-lifecycle auto-capture path already gates on `!screenName.isNullOrEmpty()` before calling `screen()`, so the noise that motivated the iOS-side drop doesn't reach here).
- In-process uncaught exceptions captured via the default exception handler go through `captureException` → `capture()` → `buildProperties` and pick up `$screen_name` correctly. No separate crash-replay mechanism exists on Android, so there's no asymmetry to document.
