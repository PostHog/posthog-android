---
"posthog-server": patch
---

Local evaluation now treats an empty group key like a missing group, as `/flags` does: a condition aggregated on that group type does not match, and a flag whose conditions all depend on it is `false` with no `/flags` fallback, whether evaluated directly or as a dependency. Previously an empty key was used as the bucketing identifier.
