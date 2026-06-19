---
"posthog": minor
---

Add `errorTrackingConfig.ignoredExceptionTypes`, a `MutableList<Class<out Throwable>>` consulted by `PostHog.captureException` (both autocapture and direct callers). The throwable and every entry in its cause chain are tested with `Class.isInstance`; if any link matches, the SDK skips the `$exception` event. Matching by `Class` reference is safe under R8 / ProGuard renames. The downstream uncaught-exception handler still runs. Defaults to empty.
