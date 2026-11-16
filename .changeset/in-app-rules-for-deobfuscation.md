---
"posthog": minor
"posthog-android": minor
---

Send `inAppIncludes` and `inAppExcludes` with captured exceptions as `$exception_in_app_includes` and `$exception_in_app_excludes` when frames carry a mapping id, so PostHog can apply them to deobfuscated class names on minified builds.
