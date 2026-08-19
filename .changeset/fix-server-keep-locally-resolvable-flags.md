---
"posthog-server": patch
---

fix(server): remotely evaluate requested flags missing from local definitions

Scoped `evaluateFlags` calls now fall back to `/flags` when a requested key is absent from the
currently loaded local definitions, allowing newly created or stale keys to resolve remotely. The
fallback forwards the caller's original `flagKeys` scope, matching the Python and Rust SDKs, while
locally resolved values remain authoritative when the response is merged.
