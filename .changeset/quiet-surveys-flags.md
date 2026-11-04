---
"posthog-android": patch
---

Survey eligibility checks no longer send `$feature_flag_called` events for keys starting with `survey-targeting-`, matching posthog-js. Other flag keys retain their configured tracking behavior, and linked-variant lookups remain silent.
