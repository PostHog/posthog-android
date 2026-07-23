---
"posthog": minor
---

Send minimal `$feature_flag_called` events when the server opts the team in. When the v2 `/flags` response carries a top-level `minimalFlagCalledEvents: true` AND the evaluated flag's `has_experiment` metadata is `false`, the event's properties are reduced to a strict allowlist (`$feature_flag`, `$feature_flag_response`, `$feature_flag_has_experiment`, the `$feature_flag_*` evaluation-debug properties, `$groups`, `$process_person_profile`, `$session_id`, `$window_id`, `$lib`, `$lib_version`), stripping registered super properties, the static/dynamic context envelope, the `$feature/<key>` enumeration, and `$active_feature_flags`. Any missing signal (field absent from the response or cache, legacy response shape, `has_experiment` unknown) keeps the full legacy event shape, and experiment-linked flags always send the full envelope. The gate is cached alongside the flags so it survives restarts.
