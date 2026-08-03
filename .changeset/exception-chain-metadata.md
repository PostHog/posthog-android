---
'posthog': minor
---

Completes the exception-item model in the shared `ThrowableCoercer`:

- Each `$exception_list` item's mechanism now carries `exception_id` (0-based position); cause items get `parent_id` and mechanism `type: "chained"`, the primary item keeps its existing type. A single-item list carries no ids, matching the other SDKs.
- Suppressed exceptions (`Throwable.suppressed`) are serialized with mechanism `type: "suppressed"` and the holder's `parent_id`.
- Caps: at most 50 items per `$exception_list` (keeps the primary + nearest causes) and 64 frames per stacktrace (keeps the frames nearest the crash).
- JVM-synthesized frames (lambdas, Spring CGLIB proxies, reflection accessors, dynamic proxies) are flagged with `synthetic: true` rather than dropped.
- New `PostHogErrorTrackingConfig.inAppExcludes` to force frames out of `in_app` (excludes win over `inAppIncludes`).

All field/key names and `platform: "java"` are unchanged; the additions are backwards compatible on the wire.
