---
"posthog": minor
"posthog-server": minor
---

Add `getEvaluationRuntime(key)` to the `evaluateFlags()` snapshot in `posthog-server`. It returns the flag's configured runtime (`"all"`, `"client"` or `"server"`) as reported by `/local_evaluation`, so you can choose which flags to forward to a client. A flag that falls back to `/flags` keeps the runtime of its local definition. It returns null for a flag without a local definition, because the `/flags` response does not report the runtime. Note that `/flags` treats this SDK as a server runtime and leaves `"client"` flags out of its response, so a `"client"` flag that does not resolve locally, for example because a person property is missing, is absent from the snapshot.

Add `only(PostHogFeatureFlagFilter)` to the snapshot. The filter combines flag keys and evaluation runtimes, so `snapshot.only(PostHogFeatureFlagFilter(evaluationRuntimes = setOf("client", "all")))` is the set of flags to forward to a browser. A flag with an unknown runtime never matches a runtime criterion.
