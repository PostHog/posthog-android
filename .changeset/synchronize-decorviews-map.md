---
"posthog-android": patch
---

Session Replay: guard the internal decor-view snapshot map with a synchronized map. It is written on the main thread (decor view registration) while the capture executor reads it, and `WeakHashMap.get()` can structurally modify the map while expunging stale entries — unsafe when raced across threads.
