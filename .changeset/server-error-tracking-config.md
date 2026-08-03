---
'posthog-server': minor
---

Expose the error-tracking configuration surface on the server SDK:

- `PostHogConfig.inAppIncludes` / `inAppExcludes` control the `in_app` classification of captured stack-trace frames (prefix match on the class name; excludes always win). `inAppExcludes` defaults to the new `PostHogConfig.DEFAULT_IN_APP_EXCLUDES` — a list of common JVM/framework prefixes (JDK, Kotlin, Spring, Netty, servlet containers, HTTP clients, the PostHog SDK) — so zero-config users get a sensible your-code vs framework split. Assigning your own list replaces the defaults.
- Both are available on the config `Builder` (`inAppIncludes(...)`, `inAppExcludes(...)`).
- New `captureException(exception, distinctId, options)` / `captureException(exception, options)` overloads accept `PostHogCaptureOptions` with the same merging semantics as `capture(..., options)`: custom properties, `$groups`, user properties (`$set`/`$set_once`), timestamp, and feature-flag enrichment via a pre-evaluated `flags` snapshot or `appendFeatureFlags`. Reserved exception properties (e.g. `$exception_level`, `$exception_fingerprint`) can be overridden through options properties. Request-context distinct-id resolution and personless fallback behave exactly like the existing `captureException` overloads. Java callers that passed an explicit untyped `null` as the third argument of `captureException` need to cast it (`(Map<String, Object>) null`), since that call now matches both the properties and the options overload.
