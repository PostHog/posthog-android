---
"posthog-server": patch
---

Local evaluation now evaluates a flag dependency like a standalone flag, with its own aggregation, properties, known-absent keys and bucketing identifier. A group-aggregated dependency reads its group's key and properties and is `false` when that group isn't passed, matching `/flags`. This also fixes local results for flag dependencies across aggregations without known-absent keys.
