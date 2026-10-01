---
'posthog': patch
'posthog-android': patch
'posthog-server': patch
---

Retry feature flag requests that fail with a DNS, TLS, or connection-refused transport error, instead of only timeouts, EOF, and connection resets
