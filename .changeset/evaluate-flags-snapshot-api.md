---
"posthog-server": minor
---

Add `evaluateFlags()` API for single-call flag evaluation. Returns a `PostHogFeatureFlagEvaluations` snapshot with `isEnabled` / `getFlag` / `getFlagPayload` / `getFlagPayloadAs<T>` accessors plus `onlyAccessed()` and `only(keys)` filters. `capture()` accepts the snapshot via a new `flags` parameter to attach `$feature/<key>` properties without a second `/flags` request; user-supplied `$feature/<key>` properties win over snapshot-derived ones. `$feature_flag_called` events now include `$feature_flag_id`, `$feature_flag_version`, `$feature_flag_reason`, and propagate `$feature_flag_error` (response-level errors plus `flag_missing` for unknown keys). `flagKeys` and `disableGeoip` are forwarded to the `/flags` request body and contribute to the per-identity cache key.

Deprecates `isFeatureEnabled`, `getFeatureFlag`, `getFeatureFlagPayload`, `getFeatureFlagResult`, and `capture(appendFeatureFlags = true)` in favour of `evaluateFlags(...)`. The legacy methods keep working unchanged; Kotlin callers see a `@Deprecated` compile-time warning (silenceable with `@Suppress("DEPRECATION")`) and the `appendFeatureFlags = true` capture path emits a one-line deprecation log. Removal targets the next major.
