---
"posthog": patch
---

Send `$groupidentify` through `capture()` so it carries the session id, identity flags and shared properties like every other event, matching posthog-js
