---
"posthog-server": patch
"posthog": patch
---

Local evaluation of a group flag with a holdout above 0% and no usable group key is now inconclusive with `missing_context`, so it falls back to `/flags` (or stays unresolved with `onlyEvaluateLocally`). Previously it resolved to `false`, but `/flags` buckets the holdout on the missing key and can return the holdout variant. A group flag without a holdout and without a usable key stays `false`.
