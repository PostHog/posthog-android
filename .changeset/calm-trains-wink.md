---
"posthog": minor
"posthog-server": minor
"posthog-android": minor
---

Attach `$recording_status` and `$sdk_debug_*` replay properties to captured events.

`PostHogQueueInterface` gains `size` and `PostHogSessionReplayHandler` gains `debugProperties()`; custom implementations of these internal interfaces must add them.
