---
'posthog': minor
'posthog-android': minor
'posthog-server': minor
---

Send error tracking stack frames in canonical bottom-up order: `frames[0]` is the outermost/entry point and the last frame is the crash site. Previously frames were emitted in Java's native innermost-first order. This aligns the wire format with the cross-SDK convention and affects both the `posthog-android` and `posthog-server` `$lib`s, which share the exception coercer.
