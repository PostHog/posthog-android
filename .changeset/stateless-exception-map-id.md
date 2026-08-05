---
"posthog": patch
"posthog-server": minor
---

Attach `map_id` (from `releaseIdentifier`) to stack frames of exceptions captured via `captureExceptionStateless`, matching the stateful `captureException` path. Previously exceptions captured through the stateless API (including `posthog-server`'s `captureException`) were missing `map_id` and could not be symbolicated against uploaded ProGuard mappings. `posthog-server`'s `PostHogConfig` now exposes `releaseIdentifier` (property and builder method) so server captures can opt into symbolication.
