---
"posthog": minor
"posthog-server": minor
---

Add a `$feature_flag_has_experiment` boolean property to `$feature_flag_called` events, sourced from the `has_experiment` field the server reports in each flag's metadata (`/flags?v=2` and `/local_evaluation`). The property is only sent when the server explicitly reported `has_experiment`; it is omitted when the server did not report it (older deployments) or when flag details are unavailable.
