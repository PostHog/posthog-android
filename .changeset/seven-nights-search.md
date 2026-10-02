---
'posthog': patch
'posthog-android': patch
'posthog-server': patch
---

Require Okio 3.11.0 or later to prevent corrupt gzip request bodies when Android's JNI layer copies compression buffers. This includes the upstream DeflaterSink fix and retains the Kotlin 2.0 compatibility target. Applications overriding transitive dependencies should allow the updated Okio version.

