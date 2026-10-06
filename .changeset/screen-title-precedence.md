---
'posthog': patch
'posthog-android': patch
---

`screen(screenTitle, properties)` now always records `screenTitle` as `$screen_name` on the `$screen` event, ignoring a `$screen_name` key in `properties`. Pass the name you want recorded as `screenTitle`.
