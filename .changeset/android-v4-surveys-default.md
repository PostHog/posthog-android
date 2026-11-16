---
'posthog': major
'posthog-android': major
---

- **Breaking:** enable `surveys` by default. Surveys display when the app depends on `posthog-android-surveys-compose` or sets `surveysConfig.surveysDelegate`; set `surveys = false` to keep them off
