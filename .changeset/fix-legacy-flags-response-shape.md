---
"posthog-server": patch
---

Fix `isFeatureEnabled` / `getFeatureFlag` returning the caller-supplied default value for flags that are enabled, when the `/flags` server response only populates the legacy `featureFlags` map and leaves `flags` empty. `PostHogFeatureFlags.getFeatureFlagsFromRemote` now adapts the V1 response into the rich `FeatureFlag` map the rest of the SDK consumes, so legacy method reads and snapshot-derived `$feature_flag_called` events see a consistent shape.
