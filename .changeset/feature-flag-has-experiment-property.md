---
"posthog": minor
"posthog-server": minor
---

Add a `$feature_flag_has_experiment` boolean property to every `$feature_flag_called` event, sourced from the `has_experiment` field the server reports in each flag's metadata (`/flags?v=2` and `/local_evaluation`). Defaults to `false` when the server does not report the field.
