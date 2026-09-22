---
"posthog": minor
"posthog-server": minor
---

Add `getEvaluationRuntime(key)` to the `evaluateFlags()` snapshot in `posthog-server`. It returns the flag's configured runtime (`"all"`, `"client"` or `"server"`) as reported by `/local_evaluation`, so you can choose which flags to forward to a client. A flag that falls back to `/flags` keeps the runtime of its local definition. It returns null for a flag without a local definition, because the `/flags` response does not report the runtime.
