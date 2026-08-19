---
"posthog": patch
"posthog-android": patch
"posthog-server": patch
---

Fix: `close()` now flushes pending events before tearing down the queue, so events captured shortly before shutdown are no longer left stranded until the SDK is re-initialized. Also fixes `PostHogMemoryQueue.flush()` (used by `posthog-server`) to dispatch on the queue's executor instead of running synchronously on the caller's thread, so it can no longer race ahead of an in-flight `add()` and see an empty queue.
