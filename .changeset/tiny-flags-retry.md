---
'posthog': patch
'posthog-android': patch
'posthog-server': patch
---

Retry `/flags` requests once by default when the flags endpoint returns HTTP 502 or 504, respecting `featureFlagRequestMaxRetries`.
