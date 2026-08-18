---
'posthog': patch
'posthog-android': patch
---

`PostHogStateless` now builds every `$exception` event through a single internal route
(`captureExceptionEvent`), which `captureExceptionStateless` delegates to. The route owns the
`errorTrackingConfig.ignoredExceptionTypes` prefilter, the coerce-then-merge property order and the
personless distinct-id fallback, and it can carry the event fields `captureExceptionStateless`
cannot express (groups, `$set`/`$set_once`, an explicit timestamp), so SDK layers that need those
no longer have to re-implement the pre-capture steps and drift from the guarded path. Capture
behavior is unchanged; the addition is internal (`@PostHogInternal`) and visible only because of
the multi-module architecture.
