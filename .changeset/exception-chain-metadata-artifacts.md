---
'posthog-android': minor
'posthog-server': minor
---

Release the shared `ThrowableCoercer` error-tracking improvements (shipped in core 6.32.0, PostHog/posthog-android#669) in the Android and server artifacts:

- Each `$exception_list` item's mechanism now carries `exception_id` (0-based position); cause items get `parent_id` and mechanism `type: "chained"`, suppressed exceptions (`Throwable.suppressed`) are serialized with mechanism `type: "suppressed"` and the holder's `parent_id`. A single-item list carries no ids, matching the other SDKs. The ids are emitted on the wire for cross-SDK parity; PostHog ingestion does not persist them yet.
- Caps: at most 50 items per `$exception_list` and 64 frames per stacktrace (keeps the frames nearest the crash); the cap bounds the traversal itself.
- Compiler-generated frames (JVM and Kotlin lambdas, Android D8/R8 desugared lambdas and outlines, Spring CGLIB proxies, reflection accessors, dynamic proxies) are flagged with `method_synthetic: true` rather than dropped.
- New `PostHogErrorTrackingConfig.inAppExcludes` to force frames out of `in_app` (excludes win over `inAppIncludes`); matching happens against runtime class names before symbolication.

All field/key names and `platform: "java"` are unchanged; the additions are backwards compatible on the wire.
