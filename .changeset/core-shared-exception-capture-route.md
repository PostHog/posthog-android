---
'posthog': patch
'posthog-android': patch
'posthog-server': patch
---

`PostHogStateless` now builds every `$exception` event through a single internal route
(`captureExceptionEvent`), which `captureExceptionStateless` delegates to. The route owns the
`errorTrackingConfig.ignoredExceptionTypes` prefilter, the coerce-then-merge property order and the
personless distinct-id fallback, and it can carry the event fields `captureExceptionStateless`
cannot express (groups, an explicit timestamp), so SDK layers that need those no longer have to
re-implement the pre-capture steps and drift from the guarded path. Caller properties are supplied
as a provider that runs only after the enabled/opt-out and ignore-list gates pass, so expensive
enrichment is never computed for an event that is about to be dropped. `$exception` events carry no
person properties: they are ingested by a separate error-tracking pipeline with no ordering
guarantee against the person pipeline, so `$set`/`$set_once` are dropped server-side. Capture
behavior is unchanged; the addition is internal (`@PostHogInternal`) and visible only because of
the multi-module architecture.
