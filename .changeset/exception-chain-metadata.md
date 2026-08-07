---
'posthog': minor
---

Completes the exception-item model in the shared `ThrowableCoercer`:

- Each `$exception_list` item's mechanism now carries `exception_id` (0-based position); cause items get `parent_id` and mechanism `type: "chained"`, the primary item keeps its existing type. A single-item list carries no ids, matching the other SDKs. The chain metadata is emitted on the wire; persisting the relationships requires the PostHog-side mechanism-schema change (in flight), which today drops the ids on ingestion.
- Suppressed exceptions (`Throwable.suppressed`) are serialized with mechanism `type: "suppressed"` and the holder's `parent_id`.
- Caps: at most 50 items per `$exception_list` and 64 frames per stacktrace (keeps the frames nearest the crash). The 50-item cap bounds the traversal itself, so a pathological cause chain cannot be walked without limit.
- Compiler-generated frames (JVM and Kotlin lambdas, Android D8/R8 desugared lambdas and outlines, Spring CGLIB proxies, reflection accessors, dynamic proxies) are flagged with `method_synthetic: true` rather than dropped.
- New `PostHogErrorTrackingConfig.inAppExcludes` to force frames out of `in_app` (excludes win over `inAppIncludes`). Matching happens against runtime class names before symbolication, so on minified (ProGuard/R8) builds excludes generally will not match and server-side deobfuscation may reclassify frames afterwards; a deobfuscation-aware in-app contract is a follow-up.

All field/key names and `platform: "java"` are unchanged; the additions are backwards compatible on the wire.
