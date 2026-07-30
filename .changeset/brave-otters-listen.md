---
"posthog-android": patch
"posthog": patch
---

Make push subscription delivery more reliable: retries are driven passively by flush, identify, and app launch (no self-firing timer) with a backoff ladder that persists across triggers; unclassified send errors are treated as retryable; and a queued logout DELETE is dropped when the same identity re-registers, so it can no longer cancel the fresh subscription.
