---
"posthog-android": patch
---

Discard replay snapshots that cross a recording stop or session change, preserving the next session's initial keyframe and the captured frame's session identity.
