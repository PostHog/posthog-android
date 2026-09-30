---
"posthog-server": minor
---

Add `unresolvedFlags` to the `evaluateFlags()` snapshot. It lists the flags that have a local definition but no value, because local evaluation could not resolve them and no `/flags` fallback filled them, for example with `onlyEvaluateLocally`. Each entry has a `PostHogUnresolvedFlagReason`: `EXPERIENCE_CONTINUITY`, `UNSUPPORTED_DEFINITION`, `MISSING_CONTEXT` or `UNRESOLVED_DEPENDENCY`. Reading such a flag now reports `$feature_flag_error: local_evaluation_inconclusive` instead of `flag_missing`, so insights that filter on `flag_missing` count fewer events. The SDK also logs one warning per flag version when loaded definitions include an active flag with experience continuity.
