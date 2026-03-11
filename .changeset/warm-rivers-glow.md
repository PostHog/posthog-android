---
'posthog': patch
---

Fix SDK compliance: retry on 5xx errors with exponential backoff, respect Retry-After header, add maxRetries config, and make GzipRequestInterceptor public for custom HTTP clients
