---
"posthog": patch
"posthog-server": patch
---

Retain `property_matching_version` in local-evaluation definitions and shared caches. Server-side local evaluation now uses explicit boolean matching for version 2, preserves legacy matching for missing/1, and keeps one definition snapshot through group, cohort, and dependency evaluation and version-only refreshes. In-flight remote evaluations retain their response errors and request metadata when definitions refresh, without repopulating the new result cache. Unchanged provider polls and HTTP 200 definition responses retain cached remote results; changes to definitions or matching version still invalidate them.
