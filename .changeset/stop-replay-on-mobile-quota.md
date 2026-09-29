---
'posthog': patch
'posthog-android': patch
---

Stop session replay when the remote config reports the mobile recordings quota as exceeded (`quotaLimited` contains `mobile_recordings`). Replay resumes once a later remote config no longer reports it.
