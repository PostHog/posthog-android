---
'posthog': patch
'posthog-android': patch
'posthog-server': patch
---

Retry feature flag requests after transient network errors only. The feature flag request retry count defaults to 1 and can be set to 0 to disable retries.
