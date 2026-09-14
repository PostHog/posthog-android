---
"posthog-android": patch
---

Move replay buffer initialization and leftover-file cleanup off the SDK setup thread onto the replay executor to reduce main-thread startup stalls, including when session replay is disabled.
