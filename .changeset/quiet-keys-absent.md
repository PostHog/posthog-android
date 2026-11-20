---
"posthog-server": minor
"posthog": minor
---

Add `personPropertyKeysKnownAbsent` and `groupPropertyKeysKnownAbsent` to `PostHogEvaluateFlagsOptions`, `PostHogFeatureFlagOptions` and `PostHogFeatureFlagResultOptions`. Local evaluation treats a listed key as absent, even when a value is supplied: `is_not_set` and the negated operators match, and other operators do not, with no `/flags` fallback. When evaluation falls back to `/flags`, the keys are sent as `person_property_keys_known_absent` and `group_property_keys_known_absent`. A list longer than 1000 keys logs a warning and returns the unavailable result without evaluating or sending a request.
