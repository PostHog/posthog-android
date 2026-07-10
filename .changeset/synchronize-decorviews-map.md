---
"posthog-android": patch
---

Session Replay: fix a thread-safety race on the internal decor-view snapshot map between main-thread view registration and capture-executor reads.
