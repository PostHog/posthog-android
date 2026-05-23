---
"posthog": minor
"posthog-android": minor
---

Auto-attach `$screen_name` to every captured event after `PostHog.screen()` has been called (manually or via Activity-lifecycle auto-capture). Cached value is cleared by `reset()` and `close()`. Closes #119.

**To opt out of `$screen_name` stamping entirely**, set `PostHogAndroidConfig.captureScreenViews = false` **and** stop calling `PostHog.screen()` manually. Disabling `captureScreenViews` alone is not sufficient — a single manual `PostHog.screen("Home")` call will re-enable stamping.

## Behavior changes

These all affect what your events carry on the wire. Review your dashboards/insights/HogQL queries:

- **Cross-event stamping.** `$exception`, `$identify`, `$autocapture`, `$create_alias`, `$groupidentify`, `$feature_flag_called`, custom events, etc. will start carrying `$screen_name` whenever a screen has been recorded in the session. Previously only `$screen` events carried it. `$snapshot` events are excluded.
- **`PostHog.screen("")` (and whitespace-only titles) are silently dropped.** No `$screen` event is emitted and the cached value is untouched, so the last useful screen name survives. Previously these emitted a `$screen` event with an empty/whitespace `$screen_name`. Customers using empty-string as a sentinel in dashboards will see those rows disappear.
- **Empty/whitespace `$screen_name` in `properties` falls back to the cache.** Passing `properties = mapOf("$screen_name" to "")` (or whitespace-only) on a `capture(...)` call no longer ships an empty `$screen_name` — the cached value wins. A meaningful caller-supplied value still wins.
- **No change for `screen()` override semantics.** `PostHog.screen("Home", properties = mapOf("$screen_name" to "Override"))` continues to ship `$screen_name = "Override"` on the `$screen` event itself (Kotlin's `putAll` already let the caller override).
