---
'posthog': minor
'posthog-android': minor
'posthog-server': minor
---

Add the `compressRequestBody` config so an app can send request bodies uncompressed. The SDK also stops compressing on its own, once, when the server reports that it cannot read a gzipped body. This unblocks networks that re-encode the body in transit, e.g. a managed Android work profile.
