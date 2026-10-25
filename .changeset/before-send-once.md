---
'posthog': patch
'posthog-android': patch
---

Fix `beforeSend` running twice for each captured event, which discarded the hook's changes to `distinctId`, `timestamp` and `uuid` and compounded random sampling
