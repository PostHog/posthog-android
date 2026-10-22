---
'posthog-android': patch
---

Stop reading the network carrier synchronously while capturing events, which could cause ANRs at app start.
