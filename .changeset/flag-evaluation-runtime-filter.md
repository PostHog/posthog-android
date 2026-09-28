---
"posthog": minor
"posthog-server": minor
---

Add `only(PostHogFeatureFlagFilter)` to the `evaluateFlags()` snapshot in `posthog-server`. The filter combines flag keys and evaluation runtimes (`"all"`, `"client"` or `"server"`, as reported by `/local_evaluation`), so `snapshot.only(PostHogFeatureFlagFilter(evaluationRuntimes = setOf("client", "all")))` is the set of flags to forward to a browser. A flag that falls back to `/flags` keeps the runtime of its local definition. A flag with an unknown runtime (no local definition, or a definition that does not report the field) never matches a runtime criterion, because `/flags` does not report the runtime. Note that `/flags` treats this SDK as a server runtime and leaves `"client"` flags out of its response, so a `"client"` flag that does not resolve locally, for example because a person property is missing, is absent from the snapshot.
