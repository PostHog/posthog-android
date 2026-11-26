---
'posthog': patch
'posthog-server': patch
---

Local flag evaluation in `posthog-server` matches a filter with an empty `type` as a person filter, like an omitted or `null` type, instead of treating it as unsupported
