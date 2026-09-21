---
"posthog": minor
"posthog-server": minor
---

Add `getEvaluationRuntime(key)` to the `evaluateFlags()` snapshot in `posthog-server`. It returns the flag's configured runtime (`"all"`, `"client"` or `"server"`) as reported by `/local_evaluation`, so you can choose which flags to forward to a client. It returns null for flags evaluated remotely, because the `/flags` response does not report the runtime.
