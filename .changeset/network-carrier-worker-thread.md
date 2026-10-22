---
'posthog-android': patch
---

Stop reading the network carrier synchronously while capturing events, which could cause ANRs at app start. Events captured before the first background read finishes, such as `Application Installed` and the first `Application Opened`, are sent without `$network_carrier`.
