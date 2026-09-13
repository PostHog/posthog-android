---
"posthog-android": patch
---

Resolve the cache directory and package information at most once per SDK setup, only when needed, and reuse the results across startup consumers.
