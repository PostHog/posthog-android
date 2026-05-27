---
"posthog": patch
---

Retry event uploads on HTTP 408 (Request Timeout). 408 is transient and retryable, and the logs endpoint already retries it; this aligns the events endpoint with the SDK compliance contract.
