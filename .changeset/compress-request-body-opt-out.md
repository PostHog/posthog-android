---
'posthog': minor
'posthog-android': minor
---

Add the `compression` config (`PostHogCompression.GZIP` / `NONE`) so an app can send request bodies uncompressed, e.g. when a managed Android work profile alters the compressed body in transit.
