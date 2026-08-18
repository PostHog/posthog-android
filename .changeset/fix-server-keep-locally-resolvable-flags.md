---
"posthog-server": patch
---

fix(server): keep locally resolvable flags in `evaluateFlags` instead of falling back to `/flags` for the whole set

`evaluateFlags` now keeps every flag that resolves during local evaluation and, for scoped calls,
only fetches unresolved keys from `/flags`, merging the response without overwriting local results.
Requested keys missing from local definitions also fall back to `/flags`. `flagKeys` scopes local
evaluation before the loop, so unrequested flags can't trigger fallback. Merged snapshots are built
per call while the shared cache keeps only raw remote responses, preserving strict local-only calls
and the legacy all-or-nothing behavior.
