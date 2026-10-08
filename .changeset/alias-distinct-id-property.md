---
'posthog': patch
'posthog-android': patch
---

`alias()` now also sends the current `distinct_id` in the `$create_alias` event properties, alongside `alias`, matching posthog-js
