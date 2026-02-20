---
"posthog-android": patch
---

Support session recording `sampleRate` from remote config. The API returns a decimal between 0 and 1 as a string. A deterministic hash-based sampling decision (matching the JS SDK) is made before starting session replay. Resuming an existing session bypasses the sampling check.
