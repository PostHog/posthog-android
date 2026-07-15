---
"posthog": patch
---

Feature Flags: stop holding `featureFlagsLock` across the cached-config disk read during a flags reload. `PostHog.reset()` (commonly called from a UI thread, e.g. a logout tap) calls `PostHogRemoteConfig.clear()`, which acquires `featureFlagsLock`; a concurrent flags reload held that same lock while reading the cached session replay / capture performance / error tracking config from disk, so `reset()` could block the calling thread long enough to ANR. The cached config is now read before the lock is taken; the in-memory re-evaluation (which reads the flag maps) still runs under the lock.
