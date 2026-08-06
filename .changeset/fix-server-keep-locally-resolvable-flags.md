---
"posthog-server": patch
---

fix(server): keep locally resolvable flags in `evaluateFlags` instead of falling back to `/flags` for the whole set

`evaluateFlags` now keeps every flag that resolves during local evaluation and only fetches the
unresolved keys from `/flags`, merging the response in rather than letting it overwrite local
results. `flagKeys` scopes local evaluation before the loop, so a flag the caller never asked
about can't trigger a remote fallback, and `onlyEvaluateLocally` is now part of the flag cache key
so a local-only pass and a fallback pass no longer share a cached entry.
