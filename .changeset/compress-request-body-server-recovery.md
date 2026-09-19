---
'posthog-server': minor
---

The SDK stops compressing request bodies on its own, once, when the server reports that it cannot read a gzipped body. This unblocks networks that re-encode the body in transit.
