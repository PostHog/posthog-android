---
'posthog': patch
'posthog-android': patch
'posthog-server': patch
---

Retry feature flag requests that fail with a DNS or TLS transport error, instead of only timeouts, EOF, and connection resets
