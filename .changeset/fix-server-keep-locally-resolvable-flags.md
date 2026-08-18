---
"posthog-server": patch
---

fix(server): fetch only unresolved scoped flags during local evaluation fallback

For scoped `evaluateFlags` calls, `/flags` now receives only keys that local evaluation could not
resolve, while locally resolved results remain authoritative. Requested keys missing from the
currently loaded local definitions are included in the fallback so newly created or stale keys can
still resolve remotely. The shared cache continues to store only raw remote responses, preserving
strict local-only calls and the legacy all-or-nothing behavior.
