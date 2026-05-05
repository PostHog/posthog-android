---
"posthog": patch
"posthog-android": patch
---

Stop mutating user-supplied `PostHogConfig.maxBatchSize` and `PostHogConfig.flushAt` when the events queue adapts to HTTP 413 responses. The adaptive cap is now kept in private queue state, halved from the actual batch size that triggered the 413, and `flushAt` is clamped to the cap so a partial-batch 413 can't leave the queue buffering more events than a single batch can drain.
