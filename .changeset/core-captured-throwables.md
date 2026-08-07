---
'posthog': patch
---

Add an internal process-wide `PostHogCapturedThrowables` guard (marked `@PostHogInternal`, visible only because of the multi-module architecture) that lets independent error-capture paths avoid double-reporting the same `Throwable` instance. The guard is directional: log-mirror paths (e.g. the `posthog-server-logback` appender) consult it and skip instances already reported, while the uncaught-exception handler only marks — a crash is always captured as the authoritative fatal/unhandled record even if the same instance was logged first, and marking it keeps post-crash log mirrors from reporting it again. Membership is keyed on instance identity and held weakly, so the guard never keeps a throwable or its stack alive.
