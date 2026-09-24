---
'posthog-server': patch
---

fix: keep the local-evaluation ETag in step with the shared flag definition cache

A `PostHogFlagDefinitionCacheProvider` that could not store definitions left the SDK instance on an
advanced ETag, so every later poll returned 304 with nothing to publish and each follower instance
kept the old definitions until a flag changed. The ETag now moves forward only when the definitions
reached the cache, an instance that fell back to the API on an empty cache no longer keeps the ETag,
and an instance that fetches definitions rewrites them when the cache reports the key is gone.
