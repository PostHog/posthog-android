---
"posthog-server": minor
---

Add `unresolvedFlags` to the `evaluateFlags()` snapshot. It lists the requested flags that have a local definition but no value, because local evaluation could not resolve them and no `/flags` fallback filled them, for example with `onlyEvaluateLocally` or when the fallback fails. Each entry has a `PostHogUnresolvedFlagReason`: `EXPERIENCE_CONTINUITY`, `UNSUPPORTED_DEFINITION`, `UNRESOLVED_DEPENDENCY` or `MISSING_CONTEXT`. When several causes apply, the first reason in that order is reported. Snapshots from `only()` and `onlyAccessed()` have no unresolved flags.

Behavior changes:

- A condition with a filter that local evaluation cannot check and a filter that does not match now resolves locally as no match. Before, the flag fell back to a `/flags` request, or stayed absent with `onlyEvaluateLocally`.
- `$feature_flag_called` events from snapshot reads of an unresolved flag report `$feature_flag_error: local_evaluation_inconclusive` instead of `flag_missing`, so insights that filter on `flag_missing` count fewer events.
- When definitions load, the SDK logs a warning, also with `debug` off, that names the flags local evaluation can never resolve: flags with experience continuity, and flags that use a static cohort, an unknown operator, a malformed value, or a missing or circular dependency. Each flag is warned about once until its definition changes.
