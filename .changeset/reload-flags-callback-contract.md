---
"posthog": patch
---

`reloadFeatureFlags` now always invokes its completion callback, including when the SDK is disabled/opted-out or the distinct ID is blank. Previously these early-returns skipped the callback, which could leave callers that await it (e.g. the Flutter SDK's `reloadFeatureFlags`) hanging indefinitely.
