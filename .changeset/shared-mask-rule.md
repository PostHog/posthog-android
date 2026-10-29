---
"posthog-android": patch
---

Refactor session replay masking so the mask walk and the transient-view discard check share one classifier, without changing SDK behavior.
