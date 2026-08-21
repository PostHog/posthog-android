---
"posthog-server": minor
---

fix(server): remotely evaluate requested flags missing from local definitions

Scoped `evaluateFlags` calls now fall back to `/flags` when a requested key is absent from the
currently loaded local definitions, allowing newly created or stale keys to resolve remotely. The
fallback forwards the caller's original `flagKeys` scope while locally resolved values remain
authoritative when the response is merged. When a clean remote response also omits the key, later
calls suppress that key's fallback until the next successful definitions refresh, bounding deleted
keys and typos to one clean probe per refresh interval. Failed, quota-limited, or computation-error
responses do not establish suppression. This reverses the 2.12.0 behavior where such a key was absent
from the snapshot and forced no request.
