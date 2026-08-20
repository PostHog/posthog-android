---
"posthog-server": minor
---

fix(server): remotely evaluate requested flags missing from local definitions

Scoped `evaluateFlags` calls now fall back to `/flags` when a requested key is absent from the
currently loaded local definitions, allowing newly created or stale keys to resolve remotely. The
fallback forwards the caller's original `flagKeys` scope while locally resolved values remain
authoritative when the response is merged. This reverses the 2.12.0 behavior where such a key was
absent from the snapshot and forced no request.
