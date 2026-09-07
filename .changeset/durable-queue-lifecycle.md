---
"posthog": minor
---

Retain bounded durable queue entries across retryable transport and HTTP failures, pause while offline, and acknowledge successful batches by unique queue-entry identity. `maxRetries` now controls push subscription registration retries, not durable queue flush attempts. Preserve existing queued records when a new record fails to persist, and enforce FIFO capacity when loading records from disk.
