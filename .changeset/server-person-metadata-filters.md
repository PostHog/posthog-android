---
'posthog': patch
'posthog-server': patch
---

Local flag evaluation in `posthog-server` treats a filter type it cannot evaluate, such as a cohort's `person_metadata` filter on first seen, as unsupported and falls back to `/flags`, instead of matching it as a person property with the same key
