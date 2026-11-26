---
"posthog-server": patch
"posthog": patch
---

Local evaluation now treats a flag with a holdout above 0% as inconclusive with `unsupported_definition`, for person and group flags alike, so it falls back to `/flags` (or stays unresolved with `onlyEvaluateLocally`) and a flag that depends on it is inconclusive too. The SDK doesn't evaluate holdouts, so it could resolve such a flag locally while `/flags` returned the holdout variant. These flags are also listed in the load-time `unsupported_definition` warning. A group flag with a holdout and no usable group key keeps `missing_context`, and a 0% or malformed holdout counts as no holdout.
