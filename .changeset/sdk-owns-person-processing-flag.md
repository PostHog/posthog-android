---
'posthog': patch
'posthog-android': patch
---

Stop event properties from overriding `$process_person_profile` and `$is_identified`, so a caller-supplied `true` can no longer create person profiles when `personProfiles` is `NEVER` or the user is anonymous under `IDENTIFIED_ONLY`, matching posthog-ios and posthog-js. Per-event values for these two properties are now ignored; use `beforeSend` if you need to rewrite them
