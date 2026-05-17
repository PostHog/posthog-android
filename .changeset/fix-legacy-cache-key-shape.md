---
"posthog-server": patch
---

Fix the legacy `isFeatureEnabled` / `getFeatureFlag` flow so it stops emitting `$feature_flag_called` events with a spurious `$feature_flag_error: "unknown_error"` after a successful evaluation. `FeatureFlagCacheKey` is now built with the same shape (`flagKeys` and `disableGeoip` included) on every read path, matching what `getFeatureFlagsFromRemote` writes when called from the legacy flow. Affects `getFeatureFlagsFromCache`, `getFeatureFlagError`, `getFeatureFlagDetails`, `getRequestId`, and `getEvaluatedAt`.
