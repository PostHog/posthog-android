---
"posthog-server": patch
---

Fix numeric-string ordering in server-side local feature flag evaluation. `gt`, `gte`, `lt`, and `lte` now compare finite numbers rather than strings, which can change flag membership for numeric-string properties. Invalid property values no longer match through lexicographic comparison, and invalid numeric conditions remain eligible for remote fallback.
