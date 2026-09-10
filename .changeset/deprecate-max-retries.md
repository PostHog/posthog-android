---
"posthog": minor
---

Deprecate `maxRetries` with a warning. Ingestion retries are not count-limited. Use `maxQueueSize` for events and replay, and `logs.maxBufferSize` for logs. This option still controls push subscription registration retries.
