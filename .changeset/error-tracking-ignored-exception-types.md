---
"posthog": minor
---

Add `errorTrackingConfig.ignoredExceptionTypes`: throwable classes to drop from `$exception` capture. Matched via `Class.isInstance` across the cause chain in `captureException` (autocapture and manual callers), and by class name in `$exception_list` for the generic capture path. Defaults to empty.
