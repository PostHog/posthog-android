---
'posthog': patch
'posthog-server': patch
---

Local flag evaluation in `posthog-server` checks condition groups in the order they are stored, like `/flags` and the other server SDKs, instead of moving groups with a variant override to the front, and applies `early_exit`: a group whose filters all match but whose rollout excludes the user returns `false` without evaluating later groups. For a flag with a variant override on a group other than the first, or with `early_exit` on, some users can now get a different (matching `/flags`) value
